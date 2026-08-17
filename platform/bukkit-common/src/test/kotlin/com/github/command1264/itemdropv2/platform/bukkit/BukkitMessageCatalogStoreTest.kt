package com.github.command1264.itemdropv2.platform.bukkit

import com.github.command1264.itemdropv2.core.PluginMessageLanguage
import org.bukkit.configuration.file.YamlConfiguration
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class BukkitMessageCatalogStoreTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `creates built-in files and a selected custom locale from embedded defaults`() {
        val store = store()
        val japanese = requireNotNull(PluginMessageLanguage.parse("ja_jp"))

        assertInstanceOf(BukkitMessageCatalogReloadResult.Applied::class.java, store.reload(japanese))

        assertEquals(true, directory.resolve("zh_tw.yml").toFile().isFile)
        assertEquals(true, directory.resolve("en_us.yml").toFile().isFile)
        assertEquals(true, directory.resolve("ja_jp.yml").toFile().isFile)
        assertEquals(japanese, store.consumeGeneratedFallbackLanguage())
        assertEquals(null, store.consumeGeneratedFallbackLanguage())
        assertEquals(
            "ItemDropV2 management commands:",
            store.render(japanese, ManagementMessageKey.HELP_HEADER.path).removePrefix("§6"),
        )
        assertEquals("en_us permanent", store.displayPlaceholders(japanese).lifetimePermanent)
    }

    @Test
    fun `repairs missing keys while preserving translations and unknown values`() {
        val store = store()
        val language = PluginMessageLanguage.ZH_TW
        assertInstanceOf(BukkitMessageCatalogReloadResult.Applied::class.java, store.reload(language))
        val file = directory.resolve("zh_tw.yml").toFile()
        YamlConfiguration.loadConfiguration(file).apply {
            set(ManagementMessageKey.NO_PERMISSION.path, null)
            set(ManagementMessageKey.FAILURE_REASON.path, null)
            set(ManagementMessageKey.HELP_HEADER.path, "&a自訂標題")
            set(ManagementMessageKey.RELOAD_FAILED.path, "&d自訂失敗摘要")
            set("custom-value", "keep-me")
            save(file)
        }

        assertInstanceOf(BukkitMessageCatalogReloadResult.Applied::class.java, store.reload(language))

        assertEquals("§a自訂標題", store.render(language, ManagementMessageKey.HELP_HEADER.path))
        assertEquals("§d自訂失敗摘要", store.render(language, ManagementMessageKey.RELOAD_FAILED.path))
        assertEquals(
            "§fzh_tw:${ManagementMessageKey.FAILURE_REASON.path}",
            store.render(language, ManagementMessageKey.FAILURE_REASON.path, mapOf("reason" to "structure")),
        )
        assertEquals(
            "keep-me",
            YamlConfiguration.loadConfiguration(file).getString("custom-value"),
        )
        assertEquals(true, file.readText().contains("no-permission:"))
    }

    @Test
    fun `repairs an invalid known message and publishes the recovered catalog`() {
        val store = store()
        val language = PluginMessageLanguage.EN_US
        assertInstanceOf(BukkitMessageCatalogReloadResult.Applied::class.java, store.reload(language))
        val file = directory.resolve("en_us.yml").toFile()
        YamlConfiguration.loadConfiguration(file).apply {
            set(ManagementMessageKey.INFO_HEADER.path, 42)
            save(file)
        }

        assertEquals(BukkitMessageCatalogReloadResult.Applied, store.reload(language))
        assertEquals(
            "§fen_us:${ManagementMessageKey.INFO_HEADER.path}",
            store.render(language, ManagementMessageKey.INFO_HEADER.path),
        )
        assertEquals(1, Files.list(directory).use { files -> files.filter { it.fileName.toString().contains("parse-recovery") }.count() })
    }

    @Test
    fun `repairs an existing custom locale with English keys without replacing custom values`() {
        val store = store()
        val language = requireNotNull(PluginMessageLanguage.parse("ja_jp"))
        assertInstanceOf(BukkitMessageCatalogReloadResult.Applied::class.java, store.reload(language))
        val file = directory.resolve("ja_jp.yml").toFile()
        YamlConfiguration.loadConfiguration(file).apply {
            set(ManagementMessageKey.HELP_HEADER.path, "&aカスタム")
            set(ManagementMessageKey.NO_PERMISSION.path, null)
            set(ManagementMessageKey.FAILURE_REASON.path, null)
            set("translator-note", "preserve")
            save(file)
        }

        assertInstanceOf(BukkitMessageCatalogReloadResult.Applied::class.java, store.reload(language))

        assertEquals("§aカスタム", store.render(language, ManagementMessageKey.HELP_HEADER.path))
        assertEquals(
            "§fen_us:${ManagementMessageKey.NO_PERMISSION.path}",
            store.render(language, ManagementMessageKey.NO_PERMISSION.path),
        )
        assertEquals(
            "§fen_us:${ManagementMessageKey.FAILURE_REASON.path}",
            store.render(language, ManagementMessageKey.FAILURE_REASON.path, mapOf("reason" to "structure")),
        )
        assertEquals(
            "preserve",
            YamlConfiguration.loadConfiguration(file).getString("translator-note"),
        )
    }

    @Test
    fun `backs up malformed language yaml and publishes recreated defaults`() {
        val store = store()
        val language = PluginMessageLanguage.ZH_TW
        assertInstanceOf(BukkitMessageCatalogReloadResult.Applied::class.java, store.reload(language))
        directory.resolve("zh_tw.yml").toFile().writeText("command: [invalid")

        assertEquals(BukkitMessageCatalogReloadResult.Applied, store.reload(language))
        assertEquals("§6ItemDropV2 管理指令：", store.render(language, ManagementMessageKey.HELP_HEADER.path))
        assertEquals(1, Files.list(directory).use { files -> files.filter { it.fileName.toString().contains("parse-recovery") }.count() })
    }

    @Test
    fun `migrates legacy messages filename without losing custom translations`() {
        val legacy = directory.resolve("messages_zh_tw.yml").toFile()
        legacy.writeText(
            defaults(PluginMessageLanguage.ZH_TW)
                .apply { set("display.lifetime-permanent", "永不消失") }
                .saveToString(),
        )
        val store = store()

        assertEquals(BukkitMessageCatalogReloadResult.Applied, store.reload(PluginMessageLanguage.ZH_TW))

        assertEquals(false, legacy.exists())
        assertEquals(true, directory.resolve("zh_tw.yml").toFile().isFile)
        assertEquals("永不消失", store.displayPlaceholders(PluginMessageLanguage.ZH_TW).lifetimePermanent)
        assertEquals(null, store.consumeGeneratedFallbackLanguage())
        assertEquals(1, Files.list(directory).use { files -> files.filter { it.fileName.toString().contains("migration-") }.count() })
    }

    @Test
    fun `repairs missing and invalid messages from raw template with comments spacing and backup`() {
        val language = PluginMessageLanguage.EN_US
        val store = storeWithRawDefaults(timestamp = "2026-08-11-18-30-45-UTC+08-00")
        assertEquals(BukkitMessageCatalogReloadResult.Applied, store.reload(language))
        val file = directory.resolve("en_us.yml").toFile()
        val original =
            file
                .readText()
                .replace("  no-permission:", "  translator-note: keep\n  no-permission:")
                .replace(Regex("(?m)^  no-permission:.*(?:\\R)?"), "")
                .replace(Regex("(?m)^  info-header:.*$"), "  info-header: 42 # invalid but explained")
        file.writeText(original)

        assertEquals(BukkitMessageCatalogReloadResult.Applied, store.reload(language))

        val repaired = file.readText()
        assertTrue(repaired.contains("# permission comment\n  no-permission:"))
        assertTrue(repaired.contains("info-header: '&fen_us:command.info-header' # invalid but explained"))
        assertTrue(repaired.contains("translator-note: keep"))
        assertEquals(1, Files.list(directory).use { files -> files.filter { it.fileName.toString().contains("parse-recovery") }.count() })
        assertTrue(directory.resolve("en_us.yml.parse-recovery-2026-08-11-18-30-45-UTC+08-00.bak").toFile().isFile)
        assertEquals(listOf("command.info-header"), store.consumeRecoveryReports().single().repairedPaths)
    }

    @Test
    fun `backs up malformed language yaml and recreates the raw template`() {
        val store = storeWithRawDefaults()
        val file = directory.resolve("zh_tw.yml").toFile().apply { writeText("command: [broken") }

        assertEquals(BukkitMessageCatalogReloadResult.Applied, store.reload(PluginMessageLanguage.ZH_TW))

        assertTrue(file.readText().contains("# zh_tw catalog"))
        assertEquals(1, Files.list(directory).use { files -> files.filter { it.fileName.toString().contains("parse-recovery") }.count() })
        assertEquals(true, store.consumeRecoveryReports().single { it.fileName == "zh_tw.yml" }.documentRecreated)
    }

    @Test
    fun `recovery reporter failure restores language files and does not publish`() {
        val setup = storeWithRawDefaults()
        assertEquals(BukkitMessageCatalogReloadResult.Applied, setup.reload(PluginMessageLanguage.EN_US))
        val file = directory.resolve("en_us.yml").toFile()
        val invalid =
            file
                .readText()
                .replace(Regex("(?m)^  info-header:.*$"), "  info-header: 42 # retain on rollback")
        file.writeText(invalid)
        val store = storeWithRawDefaults { throw IllegalStateException("injected reporter failure") }

        assertInstanceOf(BukkitMessageCatalogReloadResult.Failed::class.java, store.reload(PluginMessageLanguage.EN_US))

        assertEquals(invalid, file.readText())
        assertEquals(emptyList<ConfigParseRecoveryReport>(), store.consumeRecoveryReports())
        assertEquals(1, Files.list(directory).use { files -> files.filter { it.fileName.toString().contains("parse-recovery") }.count() })
    }

    @Test
    fun `recovery rollback preserves a newer external language edit`() {
        val setup = storeWithRawDefaults()
        assertEquals(BukkitMessageCatalogReloadResult.Applied, setup.reload(PluginMessageLanguage.EN_US))
        val file = directory.resolve("en_us.yml").toFile()
        file.writeText(file.readText().replace(Regex("(?m)^  info-header:.*$"), "  info-header: 42"))
        val external = "external: edit\n".toByteArray()
        val store =
            storeWithRawDefaults {
                file.writeBytes(external)
                throw IllegalStateException("injected reporter failure after external edit")
            }

        assertInstanceOf(BukkitMessageCatalogReloadResult.Failed::class.java, store.reload(PluginMessageLanguage.EN_US))

        assertArrayEquals(external, file.readBytes())
        assertEquals(emptyList<ConfigParseRecoveryReport>(), store.consumeRecoveryReports())
    }

    private fun store(): BukkitMessageCatalogStore =
        BukkitMessageCatalogStore(
            directory.toFile(),
            PluginMessageLanguage.BUILT_IN.associateWith(::defaults),
        )

    private fun storeWithRawDefaults(
        timestamp: String = "2026-08-11-18-30-45-UTC+08-00",
        reporter: (ConfigParseRecoveryReport) -> Unit = {},
    ): BukkitMessageCatalogStore {
        val semantic = PluginMessageLanguage.BUILT_IN.associateWith(::defaults)
        val raw =
            semantic.mapValues { (language, configuration) ->
                val text =
                    ("# ${language.code} catalog\n" + configuration.saveToString())
                        .replace("  no-permission:", "  # permission comment\n  no-permission:")
                text.toByteArray()
            }
        return BukkitMessageCatalogStore(directory.toFile(), semantic, raw, reporter) { timestamp }
    }

    private fun defaults(language: PluginMessageLanguage): YamlConfiguration =
        YamlConfiguration().apply {
            ManagementMessageKey.entries.forEach { key ->
                set(
                    key.path,
                    if (key == ManagementMessageKey.HELP_HEADER) {
                        if (language == PluginMessageLanguage.ZH_TW) "&6ItemDropV2 管理指令：" else "&6ItemDropV2 management commands:"
                    } else {
                        "&f${language.code}:${key.path}"
                    },
                )
            }
            PickupMessageKey.entries.forEach { key -> set(key.path, "&f${language.code}:${key.path}") }
            set("display.no-owner", if (language == PluginMessageLanguage.ZH_TW) "無" else "None")
            set(
                "display.lifetime-permanent",
                if (language == PluginMessageLanguage.ZH_TW) "永久" else "${language.code} permanent",
            )
            set(
                "display.lifetime-unknown",
                if (language == PluginMessageLanguage.ZH_TW) "未知" else "${language.code} unknown",
            )
        }
}
