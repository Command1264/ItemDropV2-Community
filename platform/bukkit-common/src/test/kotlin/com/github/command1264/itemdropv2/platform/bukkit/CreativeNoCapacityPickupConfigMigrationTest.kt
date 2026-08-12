package com.github.command1264.itemdropv2.platform.bukkit

import com.github.command1264.itemdropv2.core.ItemDisplaySettingsUpdateResult
import com.github.command1264.itemdropv2.platform.bukkit.yaml.MappingTreeSpacingPolicy
import com.github.command1264.itemdropv2.platform.bukkit.yaml.YamlDocumentEditResult
import com.github.command1264.itemdropv2.platform.bukkit.yaml.ownedYamlDocumentReplacementFailure
import org.bukkit.configuration.file.YamlConfiguration
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

class CreativeNoCapacityPickupConfigMigrationTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `reload does not leave a duplicate blank line after moving the obsolete creative key`() {
        val defaults = validConfig()
        val original =
            defaults.replace(
                CANONICAL_LINE,
                "# MANUAL OLD KEY COMMENT\n" +
                    "    creative-full-inventory-pickup: destroy # test",
            )
        val expected =
            defaults.replace(
                CANONICAL_LINE,
                "# MANUAL OLD KEY COMMENT\n" +
                    "    creative-no-capacity-pickup: destroy # test",
            )
        val configFile = directory.resolve("config.yml").toFile().apply { writeText(original) }

        assertInstanceOf(ItemDisplaySettingsUpdateResult.Applied::class.java, manager(defaults).reload())

        val normalized =
            assertInstanceOf(
                YamlDocumentEditResult.Candidate::class.java,
                MappingTreeSpacingPolicy().apply(expected.toByteArray()),
            ).bytes.toString(Charsets.UTF_8)
        assertEquals(normalized, configFile.readText())
    }

    @Test
    fun `reload moves all current keys with one creative backup and report`() {
        val configFile = directory.resolve("config.yml").toFile()
        val defaults = validConfig()
        val legacyKeyConfig = allCurrentKeysDocument(defaults)
        configFile.writeBytes(legacyKeyConfig)
        val reports = mutableListOf<ConfigKeyMigrationReport>()
        val manager = manager(defaults, reports, "2026-08-02-12-00-00-UTC+08-00")

        assertInstanceOf(ItemDisplaySettingsUpdateResult.Applied::class.java, manager.reload())

        val migrated = YamlConfiguration.loadConfiguration(configFile)
        val migratedBytes = configFile.readBytes()
        val migratedText = migratedBytes.toString(Charsets.UTF_8)
        assertAllCurrentKeysMoved(migrated, migratedText)
        assertEquals(
            "deny",
            manager
                .settings()
                .ownership.pickup.creativeNoCapacityPickupMode.configValue,
        )
        assertEquals(1, reports.size)
        assertEquals(OBSOLETE_PATH, reports.single().obsoletePath)
        assertEquals(CANONICAL_PATH, reports.single().canonicalPath)
        assertEquals(false, reports.single().canonicalValueAlreadyPresent)
        val backup = directory.resolve(requireNotNull(reports.single().backupFileName)).toFile()
        assertEquals(true, backup.isFile)
        assertArrayEquals(legacyKeyConfig, backup.readBytes())

        assertInstanceOf(ItemDisplaySettingsUpdateResult.Applied::class.java, manager.reload())
        assertArrayEquals(migratedBytes, configFile.readBytes())
        assertEquals(1, reports.size)
        assertEquals(1, backupFiles().size)
    }

    @Test
    fun `explicit null obsolete creative key uses canonical wins with backup and report`() {
        val defaults = validConfig()
        val original =
            defaults.replace(
                CANONICAL_LINE,
                "# obsolete creative explanation\n" +
                    "    creative-full-inventory-pickup: null # obsolete creative inline\n" +
                    "    creative-extension: keep-me\n" +
                    "    # canonical creative explanation\n" +
                    "    creative-no-capacity-pickup: deny # canonical creative inline",
            )
        directory.resolve("config.yml").toFile().writeText(original)
        val reports = mutableListOf<ConfigKeyMigrationReport>()

        assertInstanceOf(
            ItemDisplaySettingsUpdateResult.Applied::class.java,
            manager(defaults, reports, "2026-08-02-12-00-01-UTC+08-00").reload(),
        )

        val migrated = YamlConfiguration.loadConfiguration(directory.resolve("config.yml").toFile())
        assertEquals(false, migrated.contains(OBSOLETE_PATH))
        assertEquals("deny", migrated.getString(CANONICAL_PATH))
        assertEquals(true, reports.single().canonicalValueAlreadyPresent)
        val backup = directory.resolve(requireNotNull(reports.single().backupFileName)).toFile()
        assertEquals(original, backup.readText())
        val migratedText = directory.resolve("config.yml").toFile().readText()
        assertEquals(
            true,
            migratedText.contains(
                "    # canonical creative explanation\n" +
                    "    creative-no-capacity-pickup: deny # canonical creative inline",
            ),
        )
        assertEquals(true, migratedText.contains("    creative-extension: keep-me\n"))
        assertEquals(true, migratedText.contains("# Legacy comments preserved during migration\n"))
        assertEquals(true, migratedText.contains("# obsolete creative explanation\n"))
        assertEquals(true, migratedText.contains("# obsolete creative inline\n"))
    }

    @Test
    fun `explicit null obsolete creative key without canonical fails before backup report or default fallback`() {
        val defaults = validConfig()
        val original =
            withBomAndCrLf(
                defaults.replace(
                    CANONICAL_LINE,
                    "# explicit null creative explanation\n" +
                        "    creative-full-inventory-pickup: null # explicit null creative inline",
                ),
            )
        val configFile = directory.resolve("config.yml").toFile().apply { writeBytes(original) }
        val reports = mutableListOf<ConfigKeyMigrationReport>()
        val manager = manager(defaults, reports, "2026-08-02-12-00-06-UTC+08-00")

        val failed = assertInstanceOf(ItemDisplaySettingsUpdateResult.Failed::class.java, manager.reload())

        assertEquals(true, failed.reason.contains(OBSOLETE_PATH))
        assertArrayEquals(original, configFile.readBytes())
        assertEquals(
            "destroy",
            manager
                .settings()
                .ownership.pickup.creativeNoCapacityPickupMode.configValue,
        )
        assertEquals(true, reports.isEmpty())
        assertEquals(true, backupFiles().isEmpty())
    }

    @Test
    fun `invalid canonical creative key uses the default instead of the obsolete value`() {
        val defaults = validConfig()
        val mixedConfig = defaults.replace(CANONICAL_LINE, "creative-no-capacity-pickup: keep\n    $OBSOLETE_LINE")
        val configFile = directory.resolve("config.yml").toFile().apply { writeText(mixedConfig) }

        val result = assertInstanceOf(ItemDisplaySettingsUpdateResult.Applied::class.java, manager(defaults).reload())

        assertEquals("destroy", result.settings.ownership.pickup.creativeNoCapacityPickupMode.configValue)
        assertEquals("destroy", YamlConfiguration.loadConfiguration(configFile).getString(CANONICAL_PATH))
        assertEquals(false, configFile.readText().contains(OBSOLETE_PATH))
        assertEquals(mixedConfig, backupFiles().single().toFile().readText())
    }

    @Test
    fun `creative pickup key migration replacement failure restores original`() {
        val defaults = validConfig()
        val legacyKeyConfig = defaults.replace(CANONICAL_LINE, OBSOLETE_LINE)
        val configFile = directory.resolve("config.yml").toFile().apply { writeText(legacyKeyConfig) }
        val manager =
            manager(defaults, timestamp = "2026-08-02-12-00-02-UTC+08-00") { _, target ->
                val corrupted = "corrupted".toByteArray()
                Files.write(target, corrupted)
                throw ownedYamlDocumentReplacementFailure(
                    corrupted,
                    IOException("injected replacement failure"),
                )
            }

        val result = manager.reload()

        assertEquals(
            "config key migration failed; original restored (config write failed (IOException))",
            assertInstanceOf(ItemDisplaySettingsUpdateResult.Failed::class.java, result).reason,
        )
        assertEquals(legacyKeyConfig, configFile.readText())
        assertEquals(1, backupFiles().size)
        assertEquals(legacyKeyConfig, backupFiles().single().toFile().readText())
    }

    @Test
    fun `direct toggle migrates obsolete creative pickup key in one replacement before reporting`() {
        val defaults = validConfig()
        val legacyKeyConfig = defaults.replace(CANONICAL_LINE, OBSOLETE_LINE)
        val configFile = directory.resolve("config.yml").toFile().apply { writeText(legacyKeyConfig) }
        val reports = mutableListOf<ConfigKeyMigrationReport>()
        var replacements = 0
        var commitObservedCandidateBeforeReport = false
        val manager =
            manager(
                defaults = defaults,
                reports = reports,
                timestamp = "2026-08-02-12-00-03-UTC+08-00",
                publicationPreparer = { _, settings ->
                    ItemDisplaySettingsPublicationPreparation.Ready(
                        settings = settings,
                        commit = {
                            val persisted = YamlConfiguration.loadConfiguration(configFile)
                            commitObservedCandidateBeforeReport =
                                !persisted.getBoolean("general.enabled") &&
                                persisted.getString(CANONICAL_PATH) == "deny" &&
                                !persisted.contains(OBSOLETE_PATH) &&
                                reports.isEmpty()
                            null
                        },
                    )
                },
            ) { source, target ->
                replacements += 1
                Files.move(source, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            }

        assertInstanceOf(ItemDisplaySettingsUpdateResult.Applied::class.java, manager.setEnabled(false))

        val migrated = YamlConfiguration.loadConfiguration(configFile)
        assertEquals(false, migrated.getBoolean("general.enabled"))
        assertEquals("deny", migrated.getString(CANONICAL_PATH))
        assertEquals(false, migrated.contains(OBSOLETE_PATH))
        assertEquals(false, manager.settings().enabled)
        assertEquals(1, replacements)
        assertEquals(true, commitObservedCandidateBeforeReport)
        assertEquals(1, reports.size)
        assertEquals(legacyKeyConfig, backupFiles().single().toFile().readText())
    }

    @Test
    fun `direct obsolete creative pickup toggle publication failure restores exact original`() {
        val defaults = validConfig()
        val legacyKeyConfig = defaults.replace(CANONICAL_LINE, OBSOLETE_LINE)
        val configFile = directory.resolve("config.yml").toFile().apply { writeText(legacyKeyConfig) }
        val reports = mutableListOf<ConfigKeyMigrationReport>()
        var replacements = 0
        val manager =
            manager(
                defaults = defaults,
                reports = reports,
                timestamp = "2026-08-02-12-00-04-UTC+08-00",
                publicationPreparer = { _, settings ->
                    ItemDisplaySettingsPublicationPreparation.Ready(
                        settings = settings,
                        commit = { "injected catalog commit failure" },
                    )
                },
            ) { source, target ->
                replacements += 1
                Files.move(source, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            }

        val failed = assertInstanceOf(ItemDisplaySettingsUpdateResult.Failed::class.java, manager.setEnabled(false))

        assertEquals("injected catalog commit failure", failed.reason)
        assertEquals(legacyKeyConfig, configFile.readText())
        assertEquals(true, manager.settings().enabled)
        assertEquals(1, replacements)
        assertEquals(true, reports.isEmpty())
        assertEquals(1, backupFiles().size)
        assertEquals(legacyKeyConfig, backupFiles().single().toFile().readText())
    }

    @Test
    fun `migration reporter failure rolls back the committed publication and exact config`() {
        val defaults = validConfig()
        val legacyKeyConfig = defaults.replace(CANONICAL_LINE, OBSOLETE_LINE)
        val configFile = directory.resolve("config.yml").toFile().apply { writeText(legacyKeyConfig) }
        val configuration = YamlConfiguration().apply { loadFromString(defaults) }
        val settings =
            assertInstanceOf(
                BukkitDisplaySettingsLoadResult.Loaded::class.java,
                BukkitDisplaySettingsLoader().load(configuration),
            ).settings
        var publicationRolledBack = false
        val manager =
            BukkitItemDisplaySettingsManager(
                initialSettings = settings,
                configFile = configFile,
                defaultConfiguration = configuration,
                defaultDocumentBytes = defaults.toByteArray(Charsets.UTF_8),
                publicationPreparer = { _, candidate ->
                    ItemDisplaySettingsPublicationPreparation.Ready(
                        settings = candidate,
                        commit = { null },
                        rollback = { publicationRolledBack = true },
                    )
                },
                configKeyMigrationReporter = { throw IllegalStateException("injected reporter failure") },
                migrationTimestamp = { "2026-08-02-12-00-05-UTC+08-00" },
            )

        val failed = assertInstanceOf(ItemDisplaySettingsUpdateResult.Failed::class.java, manager.reload())

        assertEquals("migration report failed (IllegalStateException)", failed.reason)
        assertEquals(true, publicationRolledBack)
        assertEquals(legacyKeyConfig, configFile.readText())
        assertEquals(true, manager.settings().enabled)
        assertEquals(1, backupFiles().size)
        assertEquals(legacyKeyConfig, backupFiles().single().toFile().readText())
    }

    private fun manager(
        defaults: String,
        reports: MutableList<ConfigKeyMigrationReport> = mutableListOf(),
        timestamp: String = "2026-08-02-12-00-00-UTC+08-00",
        publicationPreparer: (
            (
                com.github.command1264.itemdropv2.core.ItemDisplaySettings,
                com.github.command1264.itemdropv2.core.ItemDisplaySettings,
            ) ->
            ItemDisplaySettingsPublicationPreparation
        )? = null,
        replacer: (Path, Path) -> Unit = { source, target ->
            Files.move(source, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        },
    ): BukkitItemDisplaySettingsManager {
        val configuration = YamlConfiguration().apply { loadFromString(defaults) }
        val settings =
            assertInstanceOf(
                BukkitDisplaySettingsLoadResult.Loaded::class.java,
                BukkitDisplaySettingsLoader().load(configuration),
            ).settings
        return BukkitItemDisplaySettingsManager(
            initialSettings = settings,
            configFile = directory.resolve("config.yml").toFile(),
            defaultConfiguration = configuration,
            defaultDocumentBytes = defaults.toByteArray(Charsets.UTF_8),
            configKeyMigrationReporter = reports::add,
            migrationTimestamp = { timestamp },
            migrationFileReplacer = replacer,
            publicationPreparer = publicationPreparer,
        )
    }

    private fun allCurrentKeysDocument(defaults: String): ByteArray =
        withBomAndCrLf(
            defaults
                .replace(
                    "  rarity-display:\n    enabled: true\n",
                    "  # rarity migration note\n" +
                        "  item-name-rarity-display: false # rarity migration inline\n",
                ).replace(
                    "    display:\n" +
                        "      rotation-seconds: 5\n" +
                        "      single-owner-prefix: '&7[&a%player_name%&7]&r '\n",
                    "    # owner migration note\n" +
                        "    display-prefix: '&b%player_name%&r ' # owner migration inline\n" +
                        "    display:\n" +
                        "      rotation-seconds: 5\n",
                ).replace(
                    CANONICAL_LINE,
                    "# creative migration note\n" +
                        "    $OBSOLETE_LINE # creative migration inline\n" +
                        "    creative-extension: keep-me  ",
                ),
        )

    private fun assertAllCurrentKeysMoved(
        migrated: YamlConfiguration,
        migratedText: String,
    ) {
        assertEquals(false, migrated.contains("items.ownership.display-prefix"))
        assertEquals("&b%player_name%&r ", migrated.getString("items.ownership.display.single-owner-prefix"))
        assertEquals(false, migrated.contains("items.item-name-rarity-display"))
        assertEquals(false, migrated.getBoolean("items.rarity-display.enabled"))
        assertEquals(false, migrated.contains(OBSOLETE_PATH))
        assertEquals("deny", migrated.getString(CANONICAL_PATH))
        assertEquals(true, migratedText.startsWith("\uFEFF"))
        assertEquals(false, migratedText.replace("\r\n", "").contains('\n'))
        assertEquals(
            true,
            migratedText.contains(
                "    # creative migration note\r\n" +
                    "    creative-no-capacity-pickup: deny # creative migration inline",
            ),
        )
        assertEquals(
            true,
            migratedText.contains(
                "      # owner migration note\r\n" +
                    "      single-owner-prefix: '&b%player_name%&r ' # owner migration inline",
            ),
        )
        assertEquals(
            true,
            migratedText.contains(
                "    # rarity migration note\r\n" +
                    "    enabled: false # rarity migration inline",
            ),
        )
        assertEquals(true, migratedText.contains("    creative-extension: keep-me  \r\n"))
    }

    private fun backupFiles(): List<Path> =
        Files.list(directory).use { paths ->
            paths
                .iterator()
                .asSequence()
                .filter { it.fileName.toString().endsWith(".bak") }
                .toList()
        }

    private fun withBomAndCrLf(content: String): ByteArray =
        byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) +
            content.replace("\n", "\r\n").toByteArray(Charsets.UTF_8)

    private fun validConfig(): String =
        """
        schema-version: 1
        general:
          enabled: true
          language: zh_tw
          minecraft-language: en_us
          blocked-worlds: []
        items:
          processing:
            maximum-items-per-tick: 256
          rarity-display:
            enabled: true
          merge:
            lifetime-strategy: average
          ownership:
            enabled: true
            protection-seconds: 30
            entity:
              strategy: highest-damage
              minimum-damage-percent-of-max-health: 50.0
              combat-timeout-seconds: 300
            display:
              rotation-seconds: 5
              single-owner-prefix: '&7[&a%player_name%&7]&r '
              multiple-owners-prefix: '&7[&a%player_name%&7]&e+%additional_owner_count%&r '
            allow-hopper-pickup: false
            $CANONICAL_LINE
            pickup-warning-cooldown-seconds: 5
            pickup-warning-message-type: action-bar
          display-name-format:
            single: '%item_display_name%'
            multi: '%item_display_name% x%amount%'
          display-placeholders:
            no-owner: '無'
            lifetime-permanent: '永久'
            lifetime-unknown: '未知'
        """.trimIndent()

    private companion object {
        private const val CANONICAL_PATH = "items.ownership.creative-no-capacity-pickup"
        private const val OBSOLETE_PATH = "items.ownership.creative-full-inventory-pickup"
        private const val CANONICAL_LINE = "creative-no-capacity-pickup: destroy"
        private const val OBSOLETE_LINE = "creative-full-inventory-pickup: deny"
    }
}
