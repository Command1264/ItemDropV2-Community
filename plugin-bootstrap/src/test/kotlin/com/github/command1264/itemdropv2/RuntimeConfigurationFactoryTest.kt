package com.github.command1264.itemdropv2

import com.github.command1264.itemdropv2.core.ItemDisplaySettingsUpdateResult
import com.github.command1264.itemdropv2.core.PluginMessageLanguage
import com.github.command1264.itemdropv2.platform.bukkit.BukkitMessageCatalogReloadResult
import com.github.command1264.itemdropv2.platform.bukkit.ConfigKeyMigrationReport
import com.github.command1264.itemdropv2.platform.bukkit.LegacyConfigMigrationReport
import com.github.command1264.itemdropv2.platform.bukkit.ManagementMessageKey
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

class RuntimeConfigurationFactoryTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `Pro defaults create client translation switch with comments`() {
        val fragmentPath = "config/test-pro-translation.yml.fragment"
        val fragment =
            "  display:\n" +
                "    # Paper client translation test comment.\n" +
                "    paper-client-side-translation: true\n"
        val loader = OverrideResourceClassLoader(fragmentPath, fragment.toByteArray(StandardCharsets.UTF_8))
        val runtime =
            RuntimeConfigurationFactory(
                loader,
                directory.toFile(),
                paperClientSideTranslationSettingSupported = true,
                configurationFragmentResource = fragmentPath,
            ).create()

        val applied = assertInstanceOf(ItemDisplaySettingsUpdateResult.Applied::class.java, runtime.settingsManager.reload())
        val generated = Files.readAllBytes(directory.resolve("config.yml")).toString(StandardCharsets.UTF_8)

        assertEquals(true, applied.settings.paperClientSideTranslationEnabled)
        assertTrue(generated.contains("# Paper client translation test comment."))
        assertTrue(generated.contains("paper-client-side-translation: true"))
    }

    @Test
    fun `Pro reload rejects wrong typed client translation switch without rewriting it`() {
        val fragmentPath = "config/test-pro-translation.yml.fragment"
        val fragment = "  display:\n    paper-client-side-translation: true\n"
        val loader = OverrideResourceClassLoader(fragmentPath, fragment.toByteArray(StandardCharsets.UTF_8))
        val runtime =
            RuntimeConfigurationFactory(
                loader,
                directory.toFile(),
                paperClientSideTranslationSettingSupported = true,
                configurationFragmentResource = fragmentPath,
            ).create()
        assertInstanceOf(ItemDisplaySettingsUpdateResult.Applied::class.java, runtime.settingsManager.reload())
        val invalid =
            Files
                .readAllBytes(directory.resolve("config.yml"))
                .toString(StandardCharsets.UTF_8)
                .replace("paper-client-side-translation: true", "paper-client-side-translation: sometimes")
                .toByteArray(StandardCharsets.UTF_8)
        Files.write(directory.resolve("config.yml"), invalid)

        val failed = assertInstanceOf(ItemDisplaySettingsUpdateResult.Failed::class.java, runtime.settingsManager.reload())

        assertTrue(failed.reason.contains("items.display.paper-client-side-translation: expected boolean"))
        assertArrayEquals(invalid, Files.readAllBytes(directory.resolve("config.yml")))
        assertEquals(true, runtime.settingsManager.settings().paperClientSideTranslationEnabled)
    }

    @Test
    fun `Pro construction rejects malformed UTF-8 config fragment`() {
        val fragmentPath = "config/test-pro-translation.yml.fragment"
        val loader = OverrideResourceClassLoader(fragmentPath, byteArrayOf(0xC3.toByte(), 0x28))

        val error =
            assertThrows(IllegalArgumentException::class.java) {
                RuntimeConfigurationFactory(
                    loader,
                    directory.toFile(),
                    paperClientSideTranslationSettingSupported = true,
                    configurationFragmentResource = fragmentPath,
                ).create()
            }

        assertEquals("embedded $fragmentPath is not strict UTF-8", error.message)
    }

    @Test
    fun `Community reload preserves and ignores a wrong typed Pro-only switch`() {
        val base = embeddedConfigBytes().toString(StandardCharsets.UTF_8)
        val newline = if (base.contains("\r\n")) "\r\n" else "\n"
        val marker = "items:$newline"
        val original =
            base
                .replaceFirst(
                    marker,
                    marker +
                        "  display:$newline" +
                        "    paper-client-side-translation: retained-value$newline$newline",
                ).toByteArray(StandardCharsets.UTF_8)
        Files.write(directory.resolve("config.yml"), original)
        val runtime = RuntimeConfigurationFactory(javaClass.classLoader, directory.toFile()).create()

        val applied = assertInstanceOf(ItemDisplaySettingsUpdateResult.Applied::class.java, runtime.settingsManager.reload())
        val persisted = Files.readAllBytes(directory.resolve("config.yml"))
        val persistedText = persisted.toString(StandardCharsets.UTF_8)

        assertEquals(null, applied.settings.paperClientSideTranslationEnabled)
        assertTrue(persistedText.contains("paper-client-side-translation: retained-value"), persistedText)
    }

    @Test
    fun `embedded config is read once and raw bytes also provide semantic defaults`() {
        val raw = embeddedConfigBytes()
        val loader = OverrideResourceClassLoader(CONFIG_RESOURCE, raw)
        val factory = RuntimeConfigurationFactory(loader, directory.toFile())

        val embedded = factory.loadYamlResource(CONFIG_RESOURCE)

        assertEquals(1, loader.readCount)
        assertArrayEquals(raw, embedded.bytes)
        assertEquals(true, embedded.configuration.getBoolean("general.enabled"))
    }

    @Test
    fun `reload restores missing ownership strategy with its embedded comments`() {
        val runtime = RuntimeConfigurationFactory(javaClass.classLoader, directory.toFile()).create()
        val embedded = embeddedConfigBytes().toString(StandardCharsets.UTF_8)
        val ownershipStrategySpan =
            "\n    # 合併擁有權保護剩餘秒數：average（平均）、maximum（取較久）、minimum（取較短）或\n" +
                "    # reset（重設為目前 ownership.protection-seconds；若為 0，則清除合併結果的擁有權）。\n" +
                "    ownership-strategy: average\n"
        assertTrue(embedded.contains(ownershipStrategySpan))
        Files.write(
            directory.resolve("config.yml"),
            embedded.replace(ownershipStrategySpan, "").toByteArray(StandardCharsets.UTF_8),
        )

        assertInstanceOf(ItemDisplaySettingsUpdateResult.Applied::class.java, runtime.settingsManager.reload())

        val repaired = Files.readAllBytes(directory.resolve("config.yml")).toString(StandardCharsets.UTF_8)
        assertTrue(repaired.contains(ownershipStrategySpan))
        assertEquals(
            "average",
            runtime.settingsManager
                .settings()
                .merge.ownershipStrategy.configValue,
        )
    }

    @Test
    fun `runtime construction rejects a missing embedded config`() {
        val loader = OverrideResourceClassLoader(CONFIG_RESOURCE, null)

        val error =
            assertThrows(IllegalArgumentException::class.java) {
                RuntimeConfigurationFactory(loader, directory.toFile()).create()
            }

        assertEquals("embedded config/config.yml is missing", error.message)
        assertEquals(1, loader.readCount)
    }

    @Test
    fun `runtime construction rejects malformed UTF-8 before YAML parsing`() {
        val loader = OverrideResourceClassLoader(CONFIG_RESOURCE, byteArrayOf(0xC3.toByte(), 0x28))

        val error =
            assertThrows(IllegalArgumentException::class.java) {
                RuntimeConfigurationFactory(loader, directory.toFile()).create()
            }

        assertEquals("embedded config/config.yml is not strict UTF-8", error.message)
        assertEquals(1, loader.readCount)
    }

    @Test
    fun `runtime construction rejects Bukkit YAML parse failure`() {
        val loader = OverrideResourceClassLoader(CONFIG_RESOURCE, "general: [broken".toByteArray(StandardCharsets.UTF_8))

        val error =
            assertThrows(IllegalArgumentException::class.java) {
                RuntimeConfigurationFactory(loader, directory.toFile()).create()
            }

        assertEquals("embedded config/config.yml is invalid YAML", error.message)
        assertEquals(1, loader.readCount)
    }

    @Test
    fun `runtime construction rejects scanner-unsafe embedded config`() {
        val mixedNewlines = embeddedConfigBytes().toString(StandardCharsets.UTF_8).replaceFirst("\n", "\r\n")
        val loader = OverrideResourceClassLoader(CONFIG_RESOURCE, mixedNewlines.toByteArray(StandardCharsets.UTF_8))

        assertThrows(IllegalArgumentException::class.java) {
            RuntimeConfigurationFactory(loader, directory.toFile()).create()
        }

        assertEquals(1, loader.readCount)
    }

    @Test
    fun `runtime construction rejects embedded display domain failure`() {
        val invalid =
            embeddedConfigBytes()
                .toString(StandardCharsets.UTF_8)
                .replace("schema-version: 1", "schema-version: 2")
                .toByteArray(StandardCharsets.UTF_8)
        val loader = OverrideResourceClassLoader(CONFIG_RESOURCE, invalid)

        val error =
            assertThrows(IllegalStateException::class.java) {
                RuntimeConfigurationFactory(loader, directory.toFile()).create()
            }

        assertEquals(true, error.message.orEmpty().contains("schema-version"))
        assertEquals(1, loader.readCount)
    }

    @Test
    fun `transition rejection does not reload the production language catalog`() {
        var reloads = 0
        val runtime =
            RuntimeConfigurationFactory(
                javaClass.classLoader,
                directory.toFile(),
                messageCatalogReloader = { store, language ->
                    reloads += 1
                    store.reload(language)
                },
            ).create()
        val original = writeRepairCandidate("ja_jp")
        runtime.settingsManager.installTransitionValidator { _, _ -> "transition rejected" }

        val result = runtime.settingsManager.reload()

        assertTrue(
            assertInstanceOf(ItemDisplaySettingsUpdateResult.Failed::class.java, result).reason.contains("transition rejected"),
        )
        assertEquals(0, reloads)
        assertProductionStateRestored(runtime, original)
    }

    @Test
    fun `config replacement failure does not reload the production language catalog`() {
        var reloads = 0
        val runtime =
            RuntimeConfigurationFactory(
                javaClass.classLoader,
                directory.toFile(),
                configFileReplacer = { _, _ -> throw java.io.IOException("injected replacement failure") },
                messageCatalogReloader = { store, language ->
                    reloads += 1
                    store.reload(language)
                },
            ).create()
        val original = writeRepairCandidate("ja_jp")

        val result = runtime.settingsManager.reload()

        assertInstanceOf(ItemDisplaySettingsUpdateResult.Failed::class.java, result)
        assertEquals(0, reloads)
        assertProductionStateRestored(runtime, original)
    }

    @Test
    fun `unowned persisted corruption is preserved without reloading the production language catalog`() {
        val corrupted = "schema-version: broken".toByteArray(StandardCharsets.UTF_8)
        var reloads = 0
        val runtime =
            RuntimeConfigurationFactory(
                javaClass.classLoader,
                directory.toFile(),
                configFileReplacer = { source, target ->
                    Files.write(target, corrupted)
                    Files.deleteIfExists(source)
                },
                messageCatalogReloader = { store, language ->
                    reloads += 1
                    store.reload(language)
                },
            ).create()
        writeRepairCandidate("ja_jp")

        val result = runtime.settingsManager.reload()

        val failed = assertInstanceOf(ItemDisplaySettingsUpdateResult.Failed::class.java, result)
        assertTrue(failed.reason.contains("config rollback failed"))
        assertEquals(0, reloads)
        assertArrayEquals(corrupted, Files.readAllBytes(directory.resolve("config.yml")))
        assertProductionRuntimeAndCatalogRestored(runtime)
    }

    @Test
    fun `catalog reload failure restores config runtime settings and production catalog`() {
        var commitObservedPersistedCandidate = false
        val runtime =
            RuntimeConfigurationFactory(
                javaClass.classLoader,
                directory.toFile(),
                messageCatalogReloader = { store, language ->
                    val persisted = Files.readAllBytes(directory.resolve("config.yml")).toString(StandardCharsets.UTF_8)
                    commitObservedPersistedCandidate =
                        persisted.contains("language: ja_jp") && persisted.contains("minecraft-language: en_us")
                    if (language.code == "ja_jp") {
                        BukkitMessageCatalogReloadResult.Failed("injected catalog reload failure")
                    } else {
                        store.reload(language)
                    }
                },
            ).create()
        val original = writeRepairCandidate("ja_jp")

        val result = runtime.settingsManager.reload()

        assertEquals(
            "injected catalog reload failure",
            assertInstanceOf(ItemDisplaySettingsUpdateResult.Failed::class.java, result).reason,
        )
        assertTrue(commitObservedPersistedCandidate)
        assertProductionStateRestored(runtime, original)
    }

    @Test
    fun `catalog reload failure restores current key migration without publishing its report`() {
        val reports = mutableListOf<ConfigKeyMigrationReport>()
        val runtime =
            RuntimeConfigurationFactory(
                javaClass.classLoader,
                directory.toFile(),
                configKeyMigrationReporter = reports::add,
                messageCatalogReloader = { store, language ->
                    if (language.code == "ja_jp") {
                        BukkitMessageCatalogReloadResult.Failed("injected catalog reload failure")
                    } else {
                        store.reload(language)
                    }
                },
            ).create()
        val original = writeCurrentKeyMigrationCandidate()

        val result = runtime.settingsManager.reload()

        assertEquals(
            "injected catalog reload failure",
            assertInstanceOf(ItemDisplaySettingsUpdateResult.Failed::class.java, result).reason,
        )
        assertProductionStateRestored(runtime, original)
        assertTrue(reports.isEmpty())
        assertEquals(1, backupFiles().size)
        assertArrayEquals(original, Files.readAllBytes(backupFiles().single()))
    }

    @Test
    fun `catalog reload failure restores legacy migration without publishing its report`() {
        val reports = mutableListOf<LegacyConfigMigrationReport>()
        val runtime =
            RuntimeConfigurationFactory(
                javaClass.classLoader,
                directory.toFile(),
                migrationReporter = reports::add,
                messageCatalogReloader = { store, language ->
                    if (language.code == "ja_jp") {
                        BukkitMessageCatalogReloadResult.Failed("injected catalog reload failure")
                    } else {
                        store.reload(language)
                    }
                },
            ).create()
        val original =
            """
            general:
              enabled: true
              language: ja_jp
              mc-language: en_us
            items:
              async: true
            """.trimIndent().toByteArray(StandardCharsets.UTF_8)
        Files.write(directory.resolve("config.yml"), original)

        val result = runtime.settingsManager.reload()

        assertEquals(
            "injected catalog reload failure",
            assertInstanceOf(ItemDisplaySettingsUpdateResult.Failed::class.java, result).reason,
        )
        assertProductionStateRestored(runtime, original)
        assertTrue(reports.isEmpty())
        assertEquals(1, backupFiles().size)
        assertArrayEquals(original, Files.readAllBytes(backupFiles().single()))
    }

    @Test
    fun `direct toggle reporter failure restores config runtime settings and production catalog`() {
        val runtime =
            RuntimeConfigurationFactory(
                javaClass.classLoader,
                directory.toFile(),
                configKeyMigrationReporter = { throw IllegalStateException("injected reporter failure") },
            ).create()
        val original = writeCurrentKeyMigrationCandidate()

        val result = runtime.settingsManager.setEnabled(false)

        assertEquals(
            "migration report failed (IllegalStateException)",
            assertInstanceOf(ItemDisplaySettingsUpdateResult.Failed::class.java, result).reason,
        )
        assertProductionStateRestored(runtime, original)
        assertEquals(1, backupFiles().size)
        assertArrayEquals(original, Files.readAllBytes(backupFiles().single()))
    }

    private fun writeRepairCandidate(language: String): ByteArray {
        val candidate =
            embeddedConfigBytes()
                .toString(StandardCharsets.UTF_8)
                .replace("language: zh_tw", "language: $language")
                .replace("  minecraft-language: en_us\n", "")
                .toByteArray(StandardCharsets.UTF_8)
        Files.write(directory.resolve("config.yml"), candidate)
        return candidate
    }

    private fun writeCurrentKeyMigrationCandidate(): ByteArray {
        val candidate =
            embeddedConfigBytes()
                .toString(StandardCharsets.UTF_8)
                .replace("language: zh_tw", "language: ja_jp")
                .replace("creative-no-capacity-pickup: destroy", "creative-full-inventory-pickup: deny")
                .toByteArray(StandardCharsets.UTF_8)
        Files.write(directory.resolve("config.yml"), candidate)
        return candidate
    }

    private fun backupFiles(): List<Path> =
        Files.list(directory).use { paths ->
            paths
                .iterator()
                .asSequence()
                .filter { it.fileName.toString().endsWith(".bak") }
                .toList()
        }

    private fun assertProductionStateRestored(
        runtime: RuntimeConfiguration,
        originalConfig: ByteArray,
    ) {
        assertArrayEquals(originalConfig, Files.readAllBytes(directory.resolve("config.yml")))
        assertProductionRuntimeAndCatalogRestored(runtime)
    }

    private fun assertProductionRuntimeAndCatalogRestored(runtime: RuntimeConfiguration) {
        assertEquals(PluginMessageLanguage.ZH_TW, runtime.settingsManager.settings().messageLanguage)
        assertTrue(
            runtime.messageCatalog
                .render(PluginMessageLanguage.ZH_TW, ManagementMessageKey.HELP_HEADER.path)
                .contains("管理指令"),
        )
        val japanese = requireNotNull(PluginMessageLanguage.parse("ja_jp"))
        assertThrows(IllegalArgumentException::class.java) {
            runtime.messageCatalog.render(japanese, ManagementMessageKey.HELP_HEADER.path)
        }
    }

    private fun embeddedConfigBytes(): ByteArray =
        requireNotNull(javaClass.classLoader.getResourceAsStream(CONFIG_RESOURCE))
            .use(InputStream::readBytes)

    private class OverrideResourceClassLoader(
        private val overriddenPath: String,
        bytes: ByteArray?,
    ) : ClassLoader(RuntimeConfigurationFactoryTest::class.java.classLoader) {
        private val overriddenBytes = bytes?.copyOf()
        var readCount: Int = 0
            private set

        override fun getResourceAsStream(name: String): InputStream? {
            if (name != overriddenPath) return super.getResourceAsStream(name)
            readCount++
            return overriddenBytes?.let(::ByteArrayInputStream)
        }
    }

    private companion object {
        const val CONFIG_RESOURCE = "config/config.yml"
    }
}
