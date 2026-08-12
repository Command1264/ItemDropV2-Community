import com.diffplug.gradle.spotless.SpotlessExtension
import dev.detekt.gradle.extensions.DetektExtension
import org.gradle.api.tasks.bundling.AbstractArchiveTask
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.zip.ZipFile

plugins {
    base
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.shadow) apply false
    alias(libs.plugins.detekt) apply false
    alias(libs.plugins.spotless)
}

group = "com.github.command1264.itemdropv2"
version = "1.0.0-SNAPSHOT"

val isGitCheckout = file(".git").exists()

fun gitOutput(vararg arguments: String): String =
    if (!isGitCheckout) {
        ""
    } else {
        runCatching {
            providers
                .exec {
                    commandLine("git", *arguments)
                    isIgnoreExitValue = true
                }.standardOutput
                .asText
                .get()
                .trim()
        }.getOrDefault("")
    }

val gitCommitFull =
    gitOutput("rev-parse", "HEAD")
        .lowercase()
        .takeIf { it.matches(Regex("[0-9a-f]{40}")) }
        ?: "unknown"
val gitCommitShort = if (gitCommitFull == "unknown") "unknown" else gitCommitFull.take(8)
val gitDirty =
    if (gitCommitFull == "unknown") {
        "unknown"
    } else if (gitOutput("status", "--porcelain").isEmpty()) {
        "false"
    } else {
        "true"
    }
extra["itemdropGitCommitFull"] = gitCommitFull
extra["itemdropGitCommitShort"] = gitCommitShort
extra["itemdropGitDirty"] = gitDirty

val communityDistributionPath = ":distribution:community"
val verificationBuildRoot =
    providers.gradleProperty("community.verification-build-root").orNull?.let { configuredPath ->
        file(configuredPath)
    }
val communityProjectPaths =
    listOf(
        ":core",
        ":capability:virtual-stacking-api",
        ":capability:virtual-stacking-community",
        ":platform:bukkit-common",
        ":platform:view-bukkit",
        ":platform:view-community",
        ":plugin-bootstrap",
        communityDistributionPath,
    )

allprojects {
    group = rootProject.group
    version = rootProject.version

    if (verificationBuildRoot != null) {
        val projectDirectoryName = if (path == ":") "root" else path.removePrefix(":").replace(':', '/')
        layout.buildDirectory.set(verificationBuildRoot.resolve(projectDirectoryName))
    }

    tasks.withType<AbstractArchiveTask>().configureEach {
        isPreserveFileTimestamps = false
        isReproducibleFileOrder = true
    }
}

spotless {
    kotlinGradle {
        target(
            "*.gradle.kts",
            "build-logic/*.gradle.kts",
            "build-logic/src/**/*.gradle.kts",
            "capability/*/*.gradle.kts",
            "core/*.gradle.kts",
            "platform/*/*.gradle.kts",
            "plugin-bootstrap/*.gradle.kts",
            "distribution/*/*.gradle.kts",
        )
        ktlint(libs.versions.ktlint.get())
    }
    format("toml") {
        target("gradle/*.toml")
        trimTrailingWhitespace()
        endWithNewline()
    }
}

subprojects {
    pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
        pluginManager.apply("dev.detekt")
        pluginManager.apply("com.diffplug.spotless")

        extensions.configure<DetektExtension> {
            buildUponDefaultConfig.set(true)
            config.setFrom(rootProject.files("gradle/detekt.yml"))
            parallel.set(false)
        }
        extensions.configure<SpotlessExtension> {
            kotlin {
                target("src/**/*.kt")
                ktlint(libs.versions.ktlint.get())
            }
        }
    }
}

val validateCommunityDistributionRequest =
    tasks.register("validateCommunityDistributionRequest") {
        group = "verification"
        description = "拒絕私有 mono-repo 的舊 variant 參數。"
        doLast {
            require(!providers.gradleProperty("itemdrop.variant").isPresent) {
                "itemdrop.variant is not supported; build Community with assembleItemDropCommunity."
            }
        }
    }

tasks.register<Copy>("assembleItemDropCommunity") {
    group = "build"
    description = "組裝只含 Bukkit View 與 Legacy Drain 相容層的 ItemDropV2 Community 成品。"
    dependsOn(validateCommunityDistributionRequest)
    dependsOn("$communityDistributionPath:stageDistribution")
    from(project(communityDistributionPath).layout.buildDirectory.dir("staged-distribution"))
    into(layout.buildDirectory.dir("distributions"))
}

val verifyCommunitySourceIsolation =
    tasks.register("verifyCommunitySourceIsolation") {
        group = "verification"
        description = "確認公開來源只有 Community allowlist，且不含私有 edition implementation。"

        inputs.files(
            fileTree(rootDir) {
                include("**/*.kt", "**/*.kts", "**/*.yml", "**/*.yaml", "**/*.properties")
                exclude(".gradle/**", "**/build/**")
            },
            "LICENSE",
            "settings.gradle.kts",
        )

        doLast {
            val requiredPaths =
                listOf(
                    "core",
                    "capability/virtual-stacking-api",
                    "capability/virtual-stacking-community",
                    "platform/bukkit-common",
                    "platform/view-bukkit",
                    "platform/view-community",
                    "plugin-bootstrap",
                    "distribution/community",
                )
            val missingPaths = requiredPaths.filterNot { file(it).isDirectory }
            require(missingPaths.isEmpty()) {
                "Community source export is missing required modules:\n${missingPaths.joinToString("\n")}"
            }

            val forbiddenPaths =
                listOf(
                    "capability/virtual-stacking-pro",
                    "platform/view-paper",
                    "platform/view-adaptive",
                    "platform/view-pro",
                    "platform/view-runtime",
                    "distribution/pro",
                    "distribution/runtime",
                    "testing/smoke-fixtures",
                )
            val leakedPaths = forbiddenPaths.filter { file(it).exists() }
            require(leakedPaths.isEmpty()) {
                "Private edition paths leaked into Community source:\n${leakedPaths.joinToString("\n")}"
            }

            val javaSources =
                fileTree(rootDir) {
                    include("**/*.java")
                    exclude(".gradle/**", "**/build/**")
                }.files.map { it.relativeTo(rootDir).invariantSeparatorsPath }
            require(javaSources.isEmpty()) {
                "Community public source is Kotlin-only; found Java sources:\n${javaSources.joinToString("\n")}"
            }

            val productionSources =
                fileTree(rootDir) {
                    include("**/src/main/**/*.kt", "**/src/main/**/*.properties", "**/src/main/**/*.yml")
                    exclude("**/build/**")
                }.files
            val forbiddenImplementationTokens =
                listOf(
                    "capability.virtualstacking.pro",
                    "platform.view.paper.PaperPresentationBackend",
                    "platform.view.pro.ProPresentationBackendProvider",
                    "platform.view.runtime.RuntimePresentationBackendProvider",
                    "ProVirtualStackingCapabilityProvider",
                    "BukkitVirtualItemMergeScheduler",
                )
            val leakedImplementations =
                productionSources.flatMap { source ->
                    val text = source.readText()
                    forbiddenImplementationTokens
                        .filter(text::contains)
                        .map { token -> "${source.relativeTo(rootDir).invariantSeparatorsPath}: $token" }
                }
            require(leakedImplementations.isEmpty()) {
                "Private edition implementation tokens leaked into Community source:\n" +
                    leakedImplementations.joinToString("\n")
            }

            val quotedStringLiteral = Regex("\"(?:\\\\.|[^\"\\\\])*\"")
            val coreSources = fileTree("core/src") { include("**/*.kt") }.files
            val bukkitSources =
                fileTree(rootDir) {
                    include(
                        "capability/**/src/**/*.kt",
                        "platform/**/src/**/*.kt",
                        "plugin-bootstrap/src/**/*.kt",
                    )
                    exclude("**/build/**")
                }.files
            val forbiddenCoreReference =
                Regex("(?:net\\.minecraft|org\\.bukkit|org\\.spigotmc|io\\.papermc|com\\.destroystokyo\\.paper)(?:\\.|$)")
            val forbiddenBukkitReference =
                Regex("(?:net\\.minecraft|io\\.papermc|com\\.destroystokyo\\.paper|org\\.bukkit\\.craftbukkit)(?:\\.|$)")
            val architectureViolations =
                (coreSources.map { it to forbiddenCoreReference } + bukkitSources.map { it to forbiddenBukkitReference })
                    .flatMap { (source, pattern) ->
                        source.readLines().mapIndexedNotNull { index, line ->
                            val code = line.replace(quotedStringLiteral, "").substringBefore("//")
                            if (pattern.containsMatchIn(code)) {
                                "${source.relativeTo(rootDir).invariantSeparatorsPath}:${index + 1}: ${line.trim()}"
                            } else {
                                null
                            }
                        }
                    }
            require(architectureViolations.isEmpty()) {
                "Forbidden platform references crossed Community architecture seams:\n" +
                    architectureViolations.joinToString("\n")
            }

            val licenseText = file("LICENSE").readText()
            require(licenseText.startsWith("# PolyForm Perimeter License 1.0.1")) {
                "Community source LICENSE must contain the unmodified PolyForm Perimeter 1.0.1 heading."
            }
            val licenseHash =
                MessageDigest
                    .getInstance("SHA-256")
                    .digest(licenseText.replace("\r\n", "\n").toByteArray(StandardCharsets.UTF_8))
                    .joinToString("") { byte -> "%02x".format(byte) }
            require(licenseHash == "5c7a5ccd847fcc285dda039e511ba013693fe979dfc5faee47f6fb59c7add337") {
                "Community source LICENSE must exactly match the official PolyForm Perimeter 1.0.1 text."
            }
            require(file("NOTICE").readText().startsWith("Required Notice: Copyright 2026 Command1264.")) {
                "Community source NOTICE must retain the project Required Notice."
            }
            require(!file("gradle/libs.versions.toml").readText().contains("paper-api")) {
                "Community source version catalog must not expose the unused private Paper View dependency."
            }
        }
    }

val verifyCommunityArtifact =
    tasks.register("verifyCommunityArtifact") {
        group = "verification"
        description = "驗證 Community JAR 的 provider、Java 8 與私有 implementation 隔離。"
        dependsOn("assembleItemDropCommunity")

        doLast {
            val artifactDirectory =
                layout.buildDirectory
                    .dir("distributions")
                    .get()
                    .asFile
            val artifact =
                artifactDirectory
                    .listFiles()
                    ?.singleOrNull { it.isFile && it.name == "ItemDropV2-Community-${project.version}.jar" }
                    ?: error("Expected exactly one Community artifact in ${artifactDirectory.absolutePath}")

            ZipFile(artifact).use { zip ->
                val entries = zip.entries().asSequence().toList()
                val names = entries.map { it.name }.toSet()
                val backendService = "META-INF/services/com.github.command1264.itemdropv2.core.BackendProvider"
                val capabilityService =
                    "META-INF/services/com.github.command1264.itemdropv2.capability.virtualstacking.VirtualStackingCapabilityProvider"
                require(backendService in names && capabilityService in names) {
                    "Community artifact must contain both provider service descriptors."
                }

                fun serviceLines(path: String): List<String> =
                    zip.getInputStream(zip.getEntry(path)).bufferedReader().useLines { lines ->
                        lines.map(String::trim).filter { it.isNotEmpty() && !it.startsWith("#") }.toList()
                    }

                require(
                    serviceLines(backendService) ==
                        listOf("com.github.command1264.itemdropv2.platform.view.community.CommunityPresentationBackendProvider"),
                ) { "Community artifact must contain exactly one Community presentation provider." }
                require(
                    serviceLines(capabilityService) ==
                        listOf(
                            "com.github.command1264.itemdropv2.capability.virtualstacking.community." +
                                "CommunityVirtualStackingCapabilityProvider",
                        ),
                ) { "Community artifact must contain exactly one Community capability provider." }

                val requiredEntries =
                    listOf(
                        "plugin.yml",
                        "com/github/command1264/itemdropv2/platform/view/bukkit/BukkitPresentationBackend.class",
                        "com/github/command1264/itemdropv2/platform/view/community/CommunityPresentationBackendProvider.class",
                        "com/github/command1264/itemdropv2/capability/virtualstacking/community/CommunityVirtualStackingCapabilityProvider.class",
                    )
                require(requiredEntries.all(names::contains)) {
                    "Community artifact is missing required entries: ${requiredEntries.filterNot(names::contains)}"
                }

                val forbiddenPrefixes =
                    listOf(
                        "com/github/command1264/itemdropv2/platform/view/paper/",
                        "com/github/command1264/itemdropv2/platform/view/adaptive/",
                        "com/github/command1264/itemdropv2/platform/view/pro/",
                        "com/github/command1264/itemdropv2/platform/view/runtime/",
                        "com/github/command1264/itemdropv2/capability/virtualstacking/pro/",
                    )
                val forbiddenEntries = names.filter { name -> forbiddenPrefixes.any(name::startsWith) }
                require(forbiddenEntries.isEmpty()) {
                    "Private edition classes leaked into Community artifact:\n${forbiddenEntries.sorted().joinToString("\n")}"
                }
                require("config/pro-paper-translation.yml.fragment" !in names) {
                    "Pro-only config fragment leaked into Community artifact."
                }

                val forbiddenReferences =
                    listOf("net/kyori/adventure", "io/papermc", "com/destroystokyo/paper")
                val referenceViolations = mutableListOf<String>()
                entries.filter { !it.isDirectory && it.name.endsWith(".class") }.forEach { entry ->
                    val bytes = zip.getInputStream(entry).readBytes()
                    require(bytes.size >= 8) { "Invalid class entry: ${entry.name}" }
                    val major = ((bytes[6].toInt() and 0xff) shl 8) or (bytes[7].toInt() and 0xff)
                    require(major <= 52) { "${entry.name} uses class major $major; Community must remain Java 8." }
                    val constantPoolText = String(bytes, StandardCharsets.ISO_8859_1)
                    forbiddenReferences.filter(constantPoolText::contains).forEach { token ->
                        referenceViolations += "${entry.name}: $token"
                    }
                }
                require(referenceViolations.isEmpty()) {
                    "Paper-only references leaked into Community artifact:\n${referenceViolations.joinToString("\n")}"
                }
            }
        }
    }

tasks.named("check") {
    dependsOn(communityProjectPaths.map { "$it:check" })
    dependsOn("spotlessCheck")
    dependsOn(verifyCommunitySourceIsolation)
    dependsOn(verifyCommunityArtifact)
}
