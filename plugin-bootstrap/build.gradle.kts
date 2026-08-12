plugins {
    id("itemdrop.kotlin-jvm8")
}

dependencies {
    implementation(project(":core"))
    implementation(project(":capability:virtual-stacking-api"))
    implementation(project(":platform:bukkit-common"))
    implementation(libs.bstats.bukkit)
    compileOnly(libs.spigot.api)
    testImplementation(libs.spigot.api)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.processResources {
    val pluginVersion = project.version.toString()
    val gitCommitShort = rootProject.extra["itemdropGitCommitShort"].toString()
    val gitDirty = rootProject.extra["itemdropGitDirty"].toString()
    inputs.property("pluginVersion", pluginVersion)
    inputs.property("gitCommitShort", gitCommitShort)
    inputs.property("gitDirty", gitDirty)
    filesMatching("plugin.yml") {
        expand(
            "pluginVersion" to pluginVersion,
            "gitCommitShort" to gitCommitShort,
            "gitDirty" to gitDirty,
        )
    }
}
