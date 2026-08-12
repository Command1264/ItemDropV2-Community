package com.github.command1264.itemdropv2.platform.bukkit

import com.github.command1264.itemdropv2.core.ItemDisplayPlaceholderSettings
import com.github.command1264.itemdropv2.core.ItemDisplaySettingsUpdateResult
import com.github.command1264.itemdropv2.platform.bukkit.yaml.ScannedYamlDocument
import com.github.command1264.itemdropv2.platform.bukkit.yaml.StrictYamlDocumentScanner
import com.github.command1264.itemdropv2.platform.bukkit.yaml.YamlDocumentScanResult
import com.github.command1264.itemdropv2.platform.bukkit.yaml.YamlPath
import com.github.command1264.itemdropv2.platform.bukkit.yaml.ownedYamlDocumentReplacementFailure
import org.bukkit.configuration.file.YamlConfiguration
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@Suppress("LargeClass")
class BukkitItemDisplaySettingsManagerTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `applies localized display placeholders after reload validation`() {
        val defaults = validConfig(enabled = true)
        val configFile = directory.resolve("config.yml").toFile().apply { writeText(defaults) }
        val manager =
            BukkitItemDisplaySettingsManager(
                initialSettings = loadSettings(defaults),
                configFile = configFile,
                defaultConfiguration = defaultConfig(defaults),
                defaultDocumentBytes = defaultDocument(defaults),
                settingsTransformer = { settings ->
                    settings.copy(
                        placeholders =
                            ItemDisplayPlaceholderSettings(
                                noOwner = "None",
                                lifetimePermanent = "Permanent",
                                lifetimeUnknown = "Unknown",
                            ),
                    )
                },
            )

        val applied = assertInstanceOf(ItemDisplaySettingsUpdateResult.Applied::class.java, manager.reload())

        assertEquals("Permanent", applied.settings.placeholders.lifetimePermanent)
        assertEquals("Unknown", manager.settings().placeholders.lifetimeUnknown)
    }

    @Test
    fun `publication receives previous settings and transforms only after persisted commit`() {
        val defaults = validConfig(enabled = true)
        val configFile = directory.resolve("config.yml").toFile().apply { writeText(defaults) }
        val events = mutableListOf<String>()
        var observedPreviousEnabled: Boolean? = null
        val manager =
            BukkitItemDisplaySettingsManager(
                initialSettings = loadSettings(defaults),
                configFile = configFile,
                defaultConfiguration = defaultConfig(defaults),
                defaultDocumentBytes = defaultDocument(defaults),
                settingsTransformer = { settings ->
                    events += "transform"
                    settings
                },
                publicationPreparer = { previous, candidate ->
                    observedPreviousEnabled = previous.enabled
                    ItemDisplaySettingsPublicationPreparation.Ready(
                        settings = candidate,
                        commit = {
                            assertFalse(YamlConfiguration.loadConfiguration(configFile).getBoolean("general.enabled"))
                            events += "commit"
                            null
                        },
                    )
                },
            )
        events.clear()

        val result = manager.setEnabled(false)

        assertInstanceOf(ItemDisplaySettingsUpdateResult.Applied::class.java, result)
        assertEquals(true, observedPreviousEnabled)
        assertEquals(listOf("commit", "transform"), events)
    }

    @Test
    fun `transition validator blocks virtual stacking shutdown before settings swap`() {
        val enabledConfig =
            validConfig(enabled = true).replace(
                "items:\n",
                "items:\n  virtual-stacking:\n    enabled: true\n    max-amount-per-entity: 8192\n",
            )
        val disabledConfig =
            enabledConfig.replace(
                "virtual-stacking:\n    enabled: true",
                "virtual-stacking:\n    enabled: false",
            )
        val configFile = directory.resolve("config.yml").toFile().apply { writeText(disabledConfig) }
        val manager =
            BukkitItemDisplaySettingsManager(
                initialSettings = loadSettings(enabledConfig),
                configFile = configFile,
                defaultConfiguration = defaultConfig(disabledConfig),
                defaultDocumentBytes = defaultDocument(disabledConfig),
            )
        var transitions = 0
        var validatorObservedCandidate = false
        var otherThreadObservedCurrent = false
        manager.installTransitionValidator { previous, candidate ->
            transitions += 1
            validatorObservedCandidate = !manager.settings().virtualStacking.enabled
            Thread {
                otherThreadObservedCurrent = manager.settings().virtualStacking.enabled
            }.apply {
                start()
                join()
            }
            if (previous.virtualStacking.enabled && !candidate.virtualStacking.enabled) "materialization blocked" else null
        }

        val result = manager.reload()

        assertEquals("materialization blocked", assertInstanceOf(ItemDisplaySettingsUpdateResult.Failed::class.java, result).reason)
        assertTrue(validatorObservedCandidate)
        assertTrue(otherThreadObservedCurrent)
        assertTrue(manager.settings().virtualStacking.enabled)
        assertEquals(1, transitions)
    }

    @Test
    fun `publication callback can await cross-thread mutation rejection without holding the manager monitor`() {
        val defaults = validConfig(enabled = true)
        val configFile = directory.resolve("config.yml").toFile().apply { writeText(defaults) }
        val nestedFinished = CountDownLatch(1)
        val nestedResult = AtomicReference<ItemDisplaySettingsUpdateResult>()
        val nestedThread = AtomicReference<Thread>()
        lateinit var manager: BukkitItemDisplaySettingsManager
        manager =
            BukkitItemDisplaySettingsManager(
                initialSettings = loadSettings(defaults),
                configFile = configFile,
                defaultConfiguration = defaultConfig(defaults),
                defaultDocumentBytes = defaultDocument(defaults),
                publicationPreparer = { _, settings ->
                    ItemDisplaySettingsPublicationPreparation.Ready(
                        settings = settings,
                        commit = {
                            nestedThread.set(
                                Thread {
                                    nestedResult.set(manager.reload())
                                    nestedFinished.countDown()
                                }.apply(Thread::start),
                            )
                            if (nestedFinished.await(1, TimeUnit.SECONDS)) null else "nested mutation remained blocked"
                        },
                    )
                },
            )

        val result = manager.setEnabled(false)
        nestedThread.get().join(5_000)

        assertInstanceOf(ItemDisplaySettingsUpdateResult.Applied::class.java, result)
        assertEquals(
            "config mutation is already in progress",
            assertInstanceOf(ItemDisplaySettingsUpdateResult.Failed::class.java, nestedResult.get()).reason,
        )
        assertEquals(false, manager.settings().enabled)
        assertEquals(false, YamlConfiguration.loadConfiguration(configFile).getBoolean("general.enabled"))
    }

    @Test
    fun `publication rollback does not overwrite a config whose ownership changed externally`() {
        val defaults = validConfig(enabled = true)
        val original = defaults.replace("  minecraft-language: en_us\n", "")
        val external = "external-owner: true\n".toByteArray(StandardCharsets.UTF_8)
        val configFile = directory.resolve("config.yml").toFile().apply { writeText(original) }
        val manager =
            BukkitItemDisplaySettingsManager(
                initialSettings = loadSettings(defaults),
                configFile = configFile,
                defaultConfiguration = defaultConfig(defaults),
                defaultDocumentBytes = defaultDocument(defaults),
                publicationPreparer = { _, settings ->
                    ItemDisplaySettingsPublicationPreparation.Ready(
                        settings = settings,
                        commit = {
                            Files.write(configFile.toPath(), external)
                            "injected publication failure"
                        },
                    )
                },
            )

        val failed = assertInstanceOf(ItemDisplaySettingsUpdateResult.Failed::class.java, manager.reload())

        assertTrue(failed.reason.contains("config rollback failed (ConfigRollbackOwnershipException)"))
        assertArrayEquals(external, Files.readAllBytes(configFile.toPath()))
        assertEquals(true, manager.settings().enabled)
    }

    @Test
    fun `validation rejection preserves external edits for present and initially missing configs`() {
        val defaults = validConfig(enabled = true)
        val external = "external-validation-owner: true\n".toByteArray(StandardCharsets.UTF_8)
        val cases =
            listOf(
                "present" to defaults.replace("  minecraft-language: en_us\n", "").toByteArray(StandardCharsets.UTF_8),
                "missing" to null,
            )

        cases.forEach { (label, original) ->
            val file = directory.resolve("validation-$label.yml").toFile()
            original?.let { Files.write(file.toPath(), it) }
            val manager =
                managerWithDefaults(
                    defaults = defaults,
                    configFile = file,
                    settingsValidator = {
                        Files.write(file.toPath(), external)
                        "injected validation rejection"
                    },
                )

            assertInstanceOf(ItemDisplaySettingsUpdateResult.Failed::class.java, manager.reload(), label)
            assertArrayEquals(external, Files.readAllBytes(file.toPath()), label)
            assertEquals(true, manager.settings().enabled, label)
        }
    }

    @Test
    fun `replacement failure preserves external edits for present and initially missing configs`() {
        val defaults = validConfig(enabled = true)
        val external = "external-replacement-owner: true\n".toByteArray(StandardCharsets.UTF_8)
        val cases =
            listOf(
                "present" to defaults.replace("  minecraft-language: en_us\n", "").toByteArray(StandardCharsets.UTF_8),
                "missing" to null,
            )

        cases.forEach { (label, original) ->
            val file = directory.resolve("replacement-$label.yml").toFile()
            original?.let { Files.write(file.toPath(), it) }
            val manager =
                managerWithDefaults(
                    defaults = defaults,
                    configFile = file,
                    configFileReplacer = { _, target ->
                        Files.write(target, external)
                        throw IOException("injected external replacement failure")
                    },
                )

            assertInstanceOf(ItemDisplaySettingsUpdateResult.Failed::class.java, manager.reload(), label)
            assertArrayEquals(external, Files.readAllBytes(file.toPath()), label)
            assertEquals(true, manager.settings().enabled, label)
        }
    }

    @Test
    fun `normal replacement followed by external replacement preserves present and initially missing configs`() {
        val defaults = validConfig(enabled = true)
        val external = "external-after-replacer: true\n".toByteArray(StandardCharsets.UTF_8)
        val cases =
            listOf(
                "present" to defaults.replace("  minecraft-language: en_us\n", "").toByteArray(StandardCharsets.UTF_8),
                "missing" to null,
            )

        cases.forEach { (label, original) ->
            val file = directory.resolve("post-replacement-$label.yml").toFile()
            original?.let { Files.write(file.toPath(), it) }
            val manager =
                managerWithDefaults(
                    defaults = defaults,
                    configFile = file,
                    configFileReplacer = { source, target ->
                        Files.move(source, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                        Files.write(target, external)
                    },
                )

            val failed = assertInstanceOf(ItemDisplaySettingsUpdateResult.Failed::class.java, manager.reload(), label)

            assertTrue(failed.reason.contains("config rollback failed (ConfigRollbackOwnershipException)"), label)
            assertArrayEquals(external, Files.readAllBytes(file.toPath()), label)
            assertEquals(true, manager.settings().enabled, label)
        }
    }

    @Test
    fun `backup failure preserves an external edit made during the backup attempt`() {
        val defaults = validConfig(enabled = true)
        val legacy = fixture("legacy-config/itemdropv2.yml")
        val external = "external-backup-owner: true\n".toByteArray(StandardCharsets.UTF_8)
        val configFile = directory.resolve("config.yml").toFile().apply { writeText(legacy) }
        val manager =
            BukkitItemDisplaySettingsManager(
                initialSettings = loadSettings(defaults),
                configFile = configFile,
                defaultConfiguration = defaultConfig(defaults),
                defaultDocumentBytes = defaultDocument(defaults),
                migrationTimestamp = { "2026-08-06-12-00-00-UTC+08-00" },
                configBackupCreator = { _, _ ->
                    Files.write(configFile.toPath(), external)
                    throw IOException("injected backup failure")
                },
            )

        val failed = assertInstanceOf(ItemDisplaySettingsUpdateResult.Failed::class.java, manager.reload())

        assertTrue(failed.reason.contains("backup failed (IOException)"))
        assertArrayEquals(external, Files.readAllBytes(configFile.toPath()))
        assertEquals(true, manager.settings().enabled)
    }

    @Test
    fun `owned replacement failure deletes its exact candidate when the config was initially missing`() {
        val defaults = validConfig(enabled = true)
        val configFile = directory.resolve("config.yml").toFile()
        val manager =
            managerWithDefaults(
                defaults = defaults,
                configFile = configFile,
                configFileReplacer = { source, target ->
                    val candidate = Files.readAllBytes(source)
                    Files.move(source, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                    throw ownedYamlDocumentReplacementFailure(candidate, IOException("injected owned failure"))
                },
            )

        val failed = assertInstanceOf(ItemDisplaySettingsUpdateResult.Failed::class.java, manager.reload())

        assertTrue(failed.reason.contains("config write failed (IOException)"))
        assertFalse(configFile.exists())
        assertEquals(true, manager.settings().enabled)
    }

    @Test
    fun `persisted verification rollback preserves external edits for present and initially missing configs`() {
        val defaults = validConfig(enabled = true)
        val corrupted = "schema-version: broken\n".toByteArray(StandardCharsets.UTF_8)
        val external = "external-verification-owner: true\n".toByteArray(StandardCharsets.UTF_8)
        val cases =
            listOf(
                "present" to defaults.replace("  minecraft-language: en_us\n", "").toByteArray(StandardCharsets.UTF_8),
                "missing" to null,
            )

        cases.forEach { (label, original) ->
            val configFile = directory.resolve("verification-$label.yml").toFile()
            original?.let { Files.write(configFile.toPath(), it) }
            val manager =
                BukkitItemDisplaySettingsManager(
                    initialSettings = loadSettings(defaults),
                    configFile = configFile,
                    defaultConfiguration = defaultConfig(defaults),
                    defaultDocumentBytes = defaultDocument(defaults),
                    publicationPreparer = { _, settings ->
                        ItemDisplaySettingsPublicationPreparation.Ready(
                            settings = settings,
                            commit = { null },
                            rollback = { Files.write(configFile.toPath(), external) },
                        )
                    },
                    configFileReplacer = { source, target ->
                        Files.write(target, corrupted)
                        Files.deleteIfExists(source)
                    },
                )

            val failed = assertInstanceOf(ItemDisplaySettingsUpdateResult.Failed::class.java, manager.reload(), label)

            assertTrue(failed.reason.contains("config rollback failed (ConfigRollbackOwnershipException)"), label)
            assertArrayEquals(external, Files.readAllBytes(configFile.toPath()), label)
            assertEquals(true, manager.settings().enabled, label)
        }
    }

    @Test
    fun `reload migrates ItemDropV2 values and comments once from the raw template`() {
        val legacy = commentedLegacyFixture("legacy-config/itemdropv2.yml", ITEM_DROP_V2_COMMENT_ROUTES, withBom = true)
        val configFile = directory.resolve("config.yml").toFile().apply { writeBytes(legacy.bytes) }
        val defaults = validConfig(enabled = true)
        val rawDefaults = withBomAndCrLf("# raw current template heading\n$defaults\n# raw current template trailer  ")
        val reports = mutableListOf<LegacyConfigMigrationReport>()
        val manager =
            BukkitItemDisplaySettingsManager(
                initialSettings = loadSettings(defaults),
                configFile = configFile,
                defaultConfiguration = defaultConfig(defaults),
                defaultDocumentBytes = rawDefaults,
                migrationReporter = reports::add,
                migrationTimestamp = { "2026-07-24-12-00-00-UTC+08-00" },
            )

        assertInstanceOf(ItemDisplaySettingsUpdateResult.Applied::class.java, manager.reload())

        val migrated = YamlConfiguration.loadConfiguration(configFile)
        assertItemDropV2SemanticValues(migrated)
        assertEquals(
            listOf(
                "general.mc-language-check-updates",
                "general.mc-language-check-updates-message",
                "general.mc-language-check-updates-interval",
                "general.mc-language-auto-update",
                "general.mc-language-auto-updates-message",
                "general.mc-language-cant-find-item-in-lang-message",
                "items.async",
                "items.item-age-type",
                "items.custom-item-death-time",
                "items.item-merge-owner-time-mode",
            ),
            reports.single().ignoredPaths,
        )
        assertEquals(LegacyConfigSource.ITEM_DROP_V2, reports.single().source)
        assertEquals("config.yml.itemdropv2-2026-07-24-12-00-00-UTC+08-00.bak", reports.single().backupFileName)
        val backup = directory.resolve("config.yml.itemdropv2-2026-07-24-12-00-00-UTC+08-00.bak")
        assertEquals(sha256(legacy.bytes), sha256(Files.readAllBytes(backup)))
        val firstCandidate = Files.readAllBytes(configFile.toPath())
        assertRawTemplateAndCommentMigration(firstCandidate, legacy, rawDefaults)

        assertInstanceOf(ItemDisplaySettingsUpdateResult.Applied::class.java, manager.reload())
        assertArrayEquals(firstCandidate, Files.readAllBytes(configFile.toPath()))
        assertInstanceOf(ItemDisplaySettingsUpdateResult.Applied::class.java, manager.setEnabled(false))
        assertEquals(1, backupFiles().size)
        assertEquals(1, reports.size)
        assertEquals(1, firstCandidate.toString(StandardCharsets.UTF_8).windowed(LEGACY_FOOTER.length).count { it == LEGACY_FOOTER })
    }

    @Test
    fun `reload migrates ItemDrop macros values and comments once from the raw template`() {
        val legacy = commentedLegacyFixture("legacy-config/itemdrop.yml", ITEM_DROP_COMMENT_ROUTES, withBom = false)
        val configFile = directory.resolve("config.yml").toFile().apply { writeBytes(legacy.bytes) }
        val defaults = validConfig(enabled = false)
        val rawDefaults = "# raw current template heading\n$defaults\n# raw current template trailer  ".toByteArray(StandardCharsets.UTF_8)
        val reports = mutableListOf<LegacyConfigMigrationReport>()
        val manager =
            BukkitItemDisplaySettingsManager(
                initialSettings = loadSettings(defaults),
                configFile = configFile,
                defaultConfiguration = defaultConfig(defaults),
                defaultDocumentBytes = rawDefaults,
                migrationReporter = reports::add,
                migrationTimestamp = { "2026-07-24-12-00-01-UTC+08-00" },
            )

        assertInstanceOf(ItemDisplaySettingsUpdateResult.Applied::class.java, manager.reload())

        val migrated = YamlConfiguration.loadConfiguration(configFile)
        assertTrue(migrated.getBoolean("general.enabled"))
        assertEquals("en_us", migrated.getString("general.language"))
        assertEquals("en_us", migrated.getString("general.minecraft-language"))
        assertEquals(listOf("blocked_world", "blocked_world2"), migrated.getStringList("general.blocked-worlds"))
        assertEquals(30, migrated.getLong("items.ownership.protection-seconds"))
        assertEquals("&7[&a%player_name%&7]&r ", migrated.getString("items.ownership.display.single-owner-prefix"))
        assertEquals(
            "&7[&a%player_name%&7]&r&e+%additional_owner_count%&r ",
            migrated.getString("items.ownership.display.multiple-owners-prefix"),
        )
        assertEquals("%item_display_name%", migrated.getString("items.display-name-format.single"))
        assertEquals("%item_display_name% &cx%amount%", migrated.getString("items.display-name-format.multi"))
        assertEquals("average", migrated.getString("items.merge.lifetime-strategy"))
        assertEquals("highest-damage", migrated.getString("items.ownership.entity.strategy"))
        assertEquals(50.0, migrated.getDouble("items.ownership.entity.minimum-damage-percent-of-max-health"))
        assertTrue(migrated.getBoolean("items.rarity-display.enabled"))
        assertEquals(5, migrated.getLong("items.ownership.pickup-warning-cooldown-seconds"))
        assertEquals(LegacyConfigSource.ITEM_DROP, reports.single().source)
        assertEquals(listOf("General.Version"), reports.single().ignoredPaths)
        assertEquals("config.yml.itemdrop-2026-07-24-12-00-01-UTC+08-00.bak", reports.single().backupFileName)
        val backup = directory.resolve("config.yml.itemdrop-2026-07-24-12-00-01-UTC+08-00.bak")
        assertEquals(sha256(legacy.bytes), sha256(Files.readAllBytes(backup)))
        val firstCandidate = Files.readAllBytes(configFile.toPath())
        assertRawTemplateAndCommentMigration(firstCandidate, legacy, rawDefaults)

        assertInstanceOf(ItemDisplaySettingsUpdateResult.Applied::class.java, manager.reload())
        assertArrayEquals(firstCandidate, Files.readAllBytes(configFile.toPath()))
        assertEquals(1, backupFiles().size)
        assertEquals(1, reports.size)
        assertEquals(1, firstCandidate.toString(StandardCharsets.UTF_8).windowed(LEGACY_FOOTER.length).count { it == LEGACY_FOOTER })
    }

    @Test
    fun `direct toggle migrates both representative legacy shapes in one replacement`() {
        val cases =
            listOf(
                Triple(LegacyConfigSource.ITEM_DROP_V2, "legacy-config/itemdropv2.yml", "2026-07-24-13-00-00-UTC+08-00"),
                Triple(LegacyConfigSource.ITEM_DROP, "legacy-config/itemdrop.yml", "2026-07-24-13-00-01-UTC+08-00"),
            )

        cases.forEach { (source, resource, timestamp) ->
            val caseDirectory = Files.createDirectories(directory.resolve(source.fileLabel))
            val legacy = fixture(resource)
            val configFile = caseDirectory.resolve("config.yml").toFile().apply { writeText(legacy) }
            val defaults = validConfig(enabled = true)
            val reports = mutableListOf<LegacyConfigMigrationReport>()
            var replacements = 0
            var commitObservedCandidateBeforeReport = false
            val manager =
                BukkitItemDisplaySettingsManager(
                    initialSettings = loadSettings(defaults),
                    configFile = configFile,
                    defaultConfiguration = defaultConfig(defaults),
                    defaultDocumentBytes = defaultDocument(defaults),
                    publicationPreparer = { _, settings ->
                        ItemDisplaySettingsPublicationPreparation.Ready(
                            settings = settings,
                            commit = {
                                commitObservedCandidateBeforeReport =
                                    !YamlConfiguration.loadConfiguration(configFile).getBoolean("general.enabled") &&
                                    reports.isEmpty()
                                null
                            },
                        )
                    },
                    migrationReporter = reports::add,
                    migrationTimestamp = { timestamp },
                    migrationFileReplacer = { moveFrom, moveTo ->
                        replacements += 1
                        Files.move(moveFrom, moveTo, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                    },
                )

            assertInstanceOf(ItemDisplaySettingsUpdateResult.Applied::class.java, manager.setEnabled(false), source.name)

            val migrated = YamlConfiguration.loadConfiguration(configFile)
            assertEquals(1, migrated.getInt("schema-version"), source.name)
            assertEquals(false, migrated.getBoolean("general.enabled"), source.name)
            assertEquals(false, manager.settings().enabled, source.name)
            assertEquals(1, replacements, source.name)
            assertEquals(true, commitObservedCandidateBeforeReport, source.name)
            assertEquals(source, reports.single().source, source.name)
            val backup = caseDirectory.resolve(requireNotNull(reports.single().backupFileName)).toFile()
            assertEquals(legacy, backup.readText(), source.name)
        }
    }

    @Test
    fun `direct legacy toggle publication failure restores both representative source documents`() {
        val cases =
            listOf(
                Triple(LegacyConfigSource.ITEM_DROP_V2, "legacy-config/itemdropv2.yml", "2026-07-24-13-00-02-UTC+08-00"),
                Triple(LegacyConfigSource.ITEM_DROP, "legacy-config/itemdrop.yml", "2026-07-24-13-00-03-UTC+08-00"),
            )

        cases.forEach { (source, resource, timestamp) ->
            val caseDirectory = Files.createDirectories(directory.resolve("${source.fileLabel}-failure"))
            val legacy = fixture(resource)
            val configFile = caseDirectory.resolve("config.yml").toFile().apply { writeText(legacy) }
            val defaults = validConfig(enabled = true)
            val reports = mutableListOf<LegacyConfigMigrationReport>()
            var replacements = 0
            val manager =
                BukkitItemDisplaySettingsManager(
                    initialSettings = loadSettings(defaults),
                    configFile = configFile,
                    defaultConfiguration = defaultConfig(defaults),
                    defaultDocumentBytes = defaultDocument(defaults),
                    publicationPreparer = { _, settings ->
                        ItemDisplaySettingsPublicationPreparation.Ready(
                            settings = settings,
                            commit = { "injected catalog commit failure" },
                        )
                    },
                    migrationReporter = reports::add,
                    migrationTimestamp = { timestamp },
                    migrationFileReplacer = { moveFrom, moveTo ->
                        replacements += 1
                        Files.move(moveFrom, moveTo, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                    },
                )

            val failed =
                assertInstanceOf(
                    ItemDisplaySettingsUpdateResult.Failed::class.java,
                    manager.setEnabled(false),
                    source.name,
                )

            assertEquals("injected catalog commit failure", failed.reason, source.name)
            assertEquals(legacy, configFile.readText(), source.name)
            assertEquals(true, manager.settings().enabled, source.name)
            assertEquals(1, replacements, source.name)
            assertTrue(reports.isEmpty(), source.name)
            val backups =
                Files.list(caseDirectory).use { paths ->
                    paths
                        .iterator()
                        .asSequence()
                        .filter { it.fileName.toString().endsWith(".bak") }
                        .toList()
                }
            assertEquals(1, backups.size, source.name)
            assertEquals(legacy, backups.single().toFile().readText(), source.name)
        }
    }

    @Test
    fun `legacy candidate domain and transition failures retain exact recovery backups`() {
        val defaults = validConfig(enabled = true)
        val original = fixture("legacy-config/itemdropv2.yml").toByteArray(StandardCharsets.UTF_8)
        val cases =
            listOf(
                "domain" to "2026-08-06-14-00-00-UTC+08-00",
                "transition" to "2026-08-06-14-00-01-UTC+08-00",
            )

        cases.forEach { (label, timestamp) ->
            val caseDirectory = Files.createDirectories(directory.resolve("legacy-$label"))
            val configFile = caseDirectory.resolve("config.yml").toFile().apply { writeBytes(original) }
            val manager =
                BukkitItemDisplaySettingsManager(
                    initialSettings = loadSettings(defaults),
                    configFile = configFile,
                    defaultConfiguration = defaultConfig(defaults),
                    defaultDocumentBytes = defaultDocument(defaults),
                    settingsValidator = { if (label == "domain") "domain rejected" else null },
                    migrationTimestamp = { timestamp },
                ).apply {
                    if (label == "transition") installTransitionValidator { _, _ -> "transition rejected" }
                }

            val failed = assertInstanceOf(ItemDisplaySettingsUpdateResult.Failed::class.java, manager.reload(), label)

            assertEquals("validation: $label rejected", failed.reason, label)
            assertArrayEquals(original, Files.readAllBytes(configFile.toPath()), label)
            assertTrue(manager.settings().enabled, label)
            val backups = caseDirectory.backupFiles()
            assertEquals(1, backups.size, label)
            assertArrayEquals(original, Files.readAllBytes(backups.single()), label)
        }
    }

    @Test
    fun `legacy candidate parse and temporary write failures retain exact recovery backups`() {
        val defaults = validConfig(enabled = true)
        val original = fixture("legacy-config/itemdropv2.yml").toByteArray(StandardCharsets.UTF_8)
        val cases =
            listOf<Pair<String, (Path, ByteArray) -> Unit>>(
                "candidate-parse" to { target, _ -> Files.write(target, "schema-version: [".toByteArray()) },
                "temporary-write" to { _, _ -> throw IOException("injected temporary write failure") },
            )

        cases.forEach { (label, candidateWriter) ->
            val caseDirectory = Files.createDirectories(directory.resolve("legacy-$label"))
            val configFile = caseDirectory.resolve("config.yml").toFile().apply { writeBytes(original) }
            val reports = mutableListOf<LegacyConfigMigrationReport>()
            val manager =
                BukkitItemDisplaySettingsManager(
                    initialSettings = loadSettings(defaults),
                    configFile = configFile,
                    defaultConfiguration = defaultConfig(defaults),
                    defaultDocumentBytes = defaultDocument(defaults),
                    configCandidateWriter = candidateWriter,
                    migrationReporter = reports::add,
                )

            assertInstanceOf(ItemDisplaySettingsUpdateResult.Failed::class.java, manager.reload(), label)

            assertArrayEquals(original, Files.readAllBytes(configFile.toPath()), label)
            assertTrue(manager.settings().enabled, label)
            assertTrue(reports.isEmpty(), label)
            val backups = caseDirectory.backupFiles()
            assertEquals(1, backups.size, label)
            assertArrayEquals(original, Files.readAllBytes(backups.single()), label)
            Files.list(caseDirectory).use { files ->
                assertFalse(files.anyMatch { it.fileName.toString().startsWith(".yaml-document-") }, label)
            }
        }
    }

    @Test
    fun `legacy reporter failure restores exact source and retains recovery backup`() {
        val defaults = validConfig(enabled = true)
        val original = fixture("legacy-config/itemdropv2.yml").toByteArray(StandardCharsets.UTF_8)
        val configFile = directory.resolve("config.yml").toFile().apply { writeBytes(original) }
        var reportAttempts = 0
        val manager =
            BukkitItemDisplaySettingsManager(
                initialSettings = loadSettings(defaults),
                configFile = configFile,
                defaultConfiguration = defaultConfig(defaults),
                defaultDocumentBytes = defaultDocument(defaults),
                migrationReporter = {
                    reportAttempts += 1
                    throw IllegalStateException("injected reporter failure")
                },
                migrationTimestamp = { "2026-08-06-14-00-02-UTC+08-00" },
            )

        val failed = assertInstanceOf(ItemDisplaySettingsUpdateResult.Failed::class.java, manager.reload())

        assertEquals("migration report failed (IllegalStateException)", failed.reason)
        assertArrayEquals(original, Files.readAllBytes(configFile.toPath()))
        assertTrue(manager.settings().enabled)
        assertEquals(1, reportAttempts)
        assertEquals(1, backupFiles().size)
        assertArrayEquals(original, Files.readAllBytes(backupFiles().single()))
    }

    @Test
    fun `legacy persisted lifetime verification failure restores source and runtime but retains backup`() {
        val defaults = validConfig(enabled = true)
        val original = fixture("legacy-config/itemdropv2.yml").toByteArray(StandardCharsets.UTF_8)
        val configFile = directory.resolve("config.yml").toFile().apply { writeBytes(original) }
        val lifetimeFile = directory.resolve("item-lifetime.yml").toFile().apply { writeText(lifetimeConfig(300)) }
        val lifetimeDefaults = defaultConfig(lifetimeConfig(300))
        val initialLifetime =
            assertInstanceOf(
                BukkitItemLifetimeSettingsLoadResult.Loaded::class.java,
                BukkitItemLifetimeSettingsLoader().load(lifetimeDefaults),
            ).settings
        val manager =
            BukkitItemDisplaySettingsManager(
                initialSettings = loadSettings(defaults).copy(lifetime = initialLifetime),
                configFile = configFile,
                defaultConfiguration = defaultConfig(defaults),
                defaultDocumentBytes = defaultDocument(defaults),
                lifetimeConfigFile = lifetimeFile,
                defaultLifetimeConfiguration = lifetimeDefaults,
                migrationTimestamp = { "2026-08-06-14-00-03-UTC+08-00" },
                migrationFileReplacer = { source, target ->
                    Files.move(source, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                    lifetimeFile.writeText(lifetimeConfig(301))
                },
            )

        val failed = assertInstanceOf(ItemDisplaySettingsUpdateResult.Failed::class.java, manager.reload())

        assertTrue(failed.reason.contains("lifetime settings changed during config replacement"))
        assertArrayEquals(original, Files.readAllBytes(configFile.toPath()))
        assertEquals(300, manager.settings().lifetime.defaultLifetimeSeconds)
        assertEquals(1, backupFiles().size)
        assertArrayEquals(original, Files.readAllBytes(backupFiles().single()))
    }

    @Test
    fun `mixed legacy schemas fail closed without backup or overwrite`() {
        val legacy =
            fixture("legacy-config/itemdropv2.yml") +
                "\n" +
                fixture("legacy-config/itemdrop.yml")
        val configFile = directory.resolve("config.yml").toFile().apply { writeText(legacy) }
        val defaults = validConfig(enabled = true)
        val manager = managerWithDefaults(defaults, configFile)

        val result = manager.reload()

        assertEquals(
            "legacy config mixes ItemDropV2 and ItemDrop schemas",
            assertInstanceOf(ItemDisplaySettingsUpdateResult.Failed::class.java, result).reason,
        )
        assertEquals(legacy, configFile.readText())
        assertTrue(backupFiles().isEmpty())
    }

    @Test
    fun `recognized raw null legacy leaves fail before fallback backup report or replacement`() {
        val cases =
            listOf(
                LegacyRawNullCase(
                    label = "itemdropv2-null-reload",
                    resource = "legacy-config/itemdropv2.yml",
                    originalLine = "  language: \"en-US\"",
                    replacementLine = "  language: null # explicit null",
                    expectedPath = "general.language",
                    directToggle = false,
                ),
                LegacyRawNullCase(
                    label = "itemdropv2-comment-only-toggle",
                    resource = "legacy-config/itemdropv2.yml",
                    originalLine = "  owner-owns-time: 30",
                    replacementLine = "  owner-owns-time: # comment-only null",
                    expectedPath = "items.owner-owns-time",
                    directToggle = true,
                ),
                LegacyRawNullCase(
                    label = "itemdropv2-ignored-null-reload",
                    resource = "legacy-config/itemdropv2.yml",
                    originalLine = "  async: true",
                    replacementLine = "  async: null",
                    expectedPath = "items.async",
                    directToggle = false,
                ),
                LegacyRawNullCase(
                    label = "itemdrop-tilde-reload",
                    resource = "legacy-config/itemdrop.yml",
                    originalLine = "  Owner_Player: '&7[&a%player_name%&7]&r '",
                    replacementLine = "  Owner_Player: ~",
                    expectedPath = "Item_Hologram.Owner_Player",
                    directToggle = false,
                ),
                LegacyRawNullCase(
                    label = "itemdrop-empty-toggle",
                    resource = "legacy-config/itemdrop.yml",
                    originalLine = "  Item_Display_Name: '%item_display_name%'",
                    replacementLine = "  Item_Display_Name:",
                    expectedPath = "Item_Hologram.Item_Display_Name",
                    directToggle = true,
                ),
                LegacyRawNullCase(
                    label = "itemdrop-ignored-tilde-toggle",
                    resource = "legacy-config/itemdrop.yml",
                    originalLine = "  Version: 0.1",
                    replacementLine = "  Version: ~",
                    expectedPath = "General.Version",
                    directToggle = true,
                ),
            )

        cases.forEach(::assertRawNullLegacyLeafRejected)
    }

    @Test
    fun `raw null sole marker identifies each legacy schema before semantic marker loss`() {
        val migrator = BukkitLegacyConfigMigrator(defaultConfig(validConfig(enabled = true)), BukkitDisplaySettingsLoader())
        val cases =
            listOf(
                "general:\n  mc-language: null\nitems:\n  extension: true\n" to "general.mc-language",
                "General:\n  Version: ~\nItem_Hologram:\n  Extension: true\n" to "General.Version",
            )

        cases.forEach { (raw, expectedPath) ->
            val rejected =
                assertInstanceOf(
                    LegacyConfigMigrationResult.Rejected::class.java,
                    migrator.migrate(defaultConfig(raw), raw.toByteArray(StandardCharsets.UTF_8)),
                    expectedPath,
                )
            assertEquals("$expectedPath: explicit null is not allowed", rejected.reason, expectedPath)
        }
    }

    @Test
    fun `every recognized legacy fixture leaf rejects raw explicit null`() {
        val migrator = BukkitLegacyConfigMigrator(defaultConfig(validConfig(enabled = true)), BukkitDisplaySettingsLoader())
        val cases =
            listOf(
                "legacy-config/itemdropv2.yml" to 26,
                "legacy-config/itemdrop.yml" to 15,
            )

        cases.forEach { (resource, expectedLeafCount) ->
            val original = fixture(resource).toByteArray(StandardCharsets.UTF_8)
            val source = defaultConfig(original.toString(StandardCharsets.UTF_8))
            val document =
                assertInstanceOf(
                    YamlDocumentScanResult.Scanned::class.java,
                    StrictYamlDocumentScanner().scan(original),
                    resource,
                ).document
            val leafPaths = document.entries.map { it.path }.filterNot { path -> source.isConfigurationSection(path.dotted) }
            assertEquals(expectedLeafCount, leafPaths.size, resource)

            leafPaths.forEach { path ->
                val rawNull = replaceRawValueWithNull(original, document, path)
                val rejected =
                    assertInstanceOf(
                        LegacyConfigMigrationResult.Rejected::class.java,
                        migrator.migrate(defaultConfig(rawNull.toString(StandardCharsets.UTF_8)), rawNull),
                        path.dotted,
                    )
                assertEquals("${path.dotted}: explicit null is not allowed", rejected.reason, path.dotted)
            }
        }
    }

    @Test
    fun `legacy typed value matrix fails before backup report or replacement`() {
        legacyTypedValueCases().forEach { case ->
            assertLegacyMigrationRejected(case.label, case.raw, case.expectedReason, case.directToggle)
        }
    }

    @Test
    fun `invalid legacy enum reports original path without backup or overwrite`() {
        val legacy = fixture("legacy-config/itemdropv2.yml").replace("item-owner-decide: \"max_damage\"", "item-owner-decide: random")
        val configFile = directory.resolve("config.yml").toFile().apply { writeText(legacy) }
        val defaults = validConfig(enabled = true)
        val manager = managerWithDefaults(defaults, configFile)

        val result = manager.reload()

        assertEquals(
            "items.item-owner-decide: expected one of max_damage, first_damage, last_damage",
            assertInstanceOf(ItemDisplaySettingsUpdateResult.Failed::class.java, result).reason,
        )
        assertEquals(legacy, configFile.readText())
        assertTrue(backupFiles().isEmpty())
    }

    @Test
    fun `invalid legacy range is rejected before backup or overwrite`() {
        val legacy =
            fixture("legacy-config/itemdropv2.yml")
                .replace("owner-owns-time: 30", "owner-owns-time: -1")
        val configFile = directory.resolve("config.yml").toFile().apply { writeText(legacy) }
        val defaults = validConfig(enabled = true)
        val manager = managerWithDefaults(defaults, configFile)

        assertInstanceOf(ItemDisplaySettingsUpdateResult.Failed::class.java, manager.reload())

        assertEquals(legacy, configFile.readText())
        assertTrue(backupFiles().isEmpty())
    }

    @Test
    fun `migration never overwrites a backup with the same timestamp`() {
        val legacy = fixture("legacy-config/itemdropv2.yml")
        val configFile = directory.resolve("config.yml").toFile().apply { writeText(legacy) }
        val existing = directory.resolve("config.yml.itemdropv2-2026-07-24-12-00-03-UTC+08-00.bak").toFile()
        existing.writeText("existing backup")
        val reports = mutableListOf<LegacyConfigMigrationReport>()
        val defaults = validConfig(enabled = true)
        val manager =
            BukkitItemDisplaySettingsManager(
                initialSettings = loadSettings(defaults),
                configFile = configFile,
                defaultConfiguration = defaultConfig(defaults),
                defaultDocumentBytes = defaultDocument(defaults),
                migrationReporter = reports::add,
                migrationTimestamp = { "2026-07-24-12-00-03-UTC+08-00" },
            )

        assertInstanceOf(ItemDisplaySettingsUpdateResult.Applied::class.java, manager.reload())

        assertEquals("existing backup", existing.readText())
        assertEquals(legacy, directory.resolve("config.yml.itemdropv2-2026-07-24-12-00-03-UTC+08-00-1.bak").toFile().readText())
        assertEquals("config.yml.itemdropv2-2026-07-24-12-00-03-UTC+08-00-1.bak", reports.single().backupFileName)
    }

    @Test
    fun `legacy textual strategy aliases preserve their documented semantics`() {
        val defaults = defaultConfig(validConfig(enabled = true))
        val migrator = BukkitLegacyConfigMigrator(defaults, BukkitDisplaySettingsLoader())
        val cases =
            listOf(
                Triple("first_damage", "min", "first-hit" to "minimum"),
                Triple("last_damage", "avg", "final-hit" to "average"),
                Triple("max_damage", "max", "highest-damage" to "maximum"),
            )

        cases.forEach { (legacyOwner, legacyMerge, expected) ->
            val raw =
                fixture("legacy-config/itemdropv2.yml")
                    .replace("item-owner-decide: \"max_damage\"", "item-owner-decide: \"$legacyOwner\"")
                    .replace("item-merge-lived-time-mode: \"max\"", "item-merge-lived-time-mode: \"$legacyMerge\"")
            val source = defaultConfig(raw)
            val migrated =
                assertInstanceOf(
                    LegacyConfigMigrationResult.Migrated::class.java,
                    migrator.migrate(source, raw.toByteArray(StandardCharsets.UTF_8)),
                ).configuration

            assertEquals(expected.first, migrated.getString("items.ownership.entity.strategy"))
            assertEquals(expected.second, migrated.getString("items.merge.lifetime-strategy"))
        }
    }

    @Test
    fun `legacy numeric merge modes preserve average minimum and maximum ordering`() {
        val defaults = defaultConfig(validConfig(enabled = true))
        val migrator = BukkitLegacyConfigMigrator(defaults, BukkitDisplaySettingsLoader())

        listOf(0 to "average", 1 to "minimum", 2 to "maximum").forEach { (legacyMode, expected) ->
            val raw = fixture("legacy-config/itemdrop.yml").replace("Item_Marge_Mode: 0", "Item_Marge_Mode: $legacyMode")
            val source = defaultConfig(raw)
            val migrated =
                assertInstanceOf(
                    LegacyConfigMigrationResult.Migrated::class.java,
                    migrator.migrate(source, raw.toByteArray(StandardCharsets.UTF_8)),
                ).configuration

            assertEquals(expected, migrated.getString("items.merge.lifetime-strategy"))
        }
    }

    @Test
    fun `migration replacement failure restores original and retains recovery backup`() {
        val legacy = fixture("legacy-config/itemdropv2.yml")
        val configFile = directory.resolve("config.yml").toFile().apply { writeText(legacy) }
        val defaults = validConfig(enabled = true)
        val manager =
            BukkitItemDisplaySettingsManager(
                initialSettings = loadSettings(defaults),
                configFile = configFile,
                defaultConfiguration = defaultConfig(defaults),
                defaultDocumentBytes = defaultDocument(defaults),
                migrationTimestamp = { "2026-07-24-12-00-02-UTC+08-00" },
                migrationFileReplacer = { _, target ->
                    val corrupted = "corrupted".toByteArray()
                    Files.write(target, corrupted)
                    throw ownedYamlDocumentReplacementFailure(
                        corrupted,
                        IOException("injected replacement failure"),
                    )
                },
            )

        val result = manager.reload()

        assertEquals(
            "legacy config migration write failed (IOException); original restored",
            assertInstanceOf(ItemDisplaySettingsUpdateResult.Failed::class.java, result).reason,
        )
        assertEquals(legacy, configFile.readText())
        assertEquals(legacy, directory.resolve("config.yml.itemdropv2-2026-07-24-12-00-02-UTC+08-00.bak").toFile().readText())
    }

    @Test
    fun `reload recreates and repairs dedicated lifetime file without backup`() {
        val configFile = directory.resolve("config.yml").toFile().apply { writeText(validConfig(enabled = true)) }
        val lifetimeFile = directory.resolve("item-lifetime.yml").toFile()
        val lifetimeDefaults =
            """
            schema-version: 1
            default-seconds: 300
            materials:
              NETHER_STAR: 600
            """.trimIndent()
        val manager =
            lifetimeManager(
                configFile = configFile,
                lifetimeFile = lifetimeFile,
                lifetimeDefaults = lifetimeDefaults,
            )

        val firstReload = manager.reload()
        if (firstReload is ItemDisplaySettingsUpdateResult.Failed) error(firstReload.reason)
        assertInstanceOf(ItemDisplaySettingsUpdateResult.Applied::class.java, firstReload)
        assertEquals(300, manager.settings().lifetime.defaultLifetimeSeconds)
        assertEquals(600, manager.settings().lifetime.resolve("NETHER_STAR"))
        assertEquals(true, lifetimeFile.isFile)

        lifetimeFile.writeText(
            """
            schema-version: 1
            materials:
              DIAMOND: -1
            custom-option: keep-me
            """.trimIndent(),
        )
        assertInstanceOf(ItemDisplaySettingsUpdateResult.Applied::class.java, manager.reload())

        val repaired = YamlConfiguration.loadConfiguration(lifetimeFile)
        assertEquals(300, repaired.getLong("default-seconds"))
        assertEquals(-1, manager.settings().lifetime.resolve("DIAMOND"))
        assertEquals(600, repaired.getLong("materials.NETHER_STAR"))
        assertEquals(600, manager.settings().lifetime.resolve("NETHER_STAR"))
        assertEquals("keep-me", repaired.getString("custom-option"))
        assertEquals(false, Files.list(directory).use { paths -> paths.anyMatch { it.fileName.toString().endsWith(".bak") } })

        repaired.set("materials.NETHER_STAR", -1)
        repaired.save(lifetimeFile)
        assertInstanceOf(ItemDisplaySettingsUpdateResult.Applied::class.java, manager.reload())
        assertEquals(-1, manager.settings().lifetime.resolve("NETHER_STAR"))
    }

    @Test
    fun `invalid known lifetime value is backed up repaired and published`() {
        val configFile = directory.resolve("config.yml").toFile().apply { writeText(validConfig(enabled = true)) }
        val lifetimeFile = directory.resolve("item-lifetime.yml").toFile().apply { writeText(lifetimeConfig(300)) }
        val manager = lifetimeManager(configFile, lifetimeFile, lifetimeConfig(300))
        assertInstanceOf(ItemDisplaySettingsUpdateResult.Applied::class.java, manager.reload())

        lifetimeFile.writeText("schema-version: 1\ndefault-seconds: -2\nmaterials: {}")
        assertInstanceOf(ItemDisplaySettingsUpdateResult.Applied::class.java, manager.reload())

        assertEquals(300, manager.settings().lifetime.defaultLifetimeSeconds)
        assertEquals(1, Files.list(directory).use { paths -> paths.filter { it.fileName.toString().contains("parse-recovery") }.count() })
    }

    @Test
    fun `external settings validation failure retains the active language`() {
        val configFile = directory.resolve("config.yml").toFile().apply { writeText(validConfig(enabled = true)) }
        val initial = loadSettings(validConfig(enabled = true))
        val manager =
            BukkitItemDisplaySettingsManager(
                initialSettings = initial,
                configFile = configFile,
                settingsValidator = { settings ->
                    if (settings.messageLanguage.code == "ja_jp") "missing language catalog" else null
                },
            )
        configFile.writeText(configFile.readText().replace("language: zh_tw", "language: ja_jp"))

        val result = manager.reload()

        assertEquals(
            "missing language catalog",
            assertInstanceOf(ItemDisplaySettingsUpdateResult.Failed::class.java, result).reason,
        )
        assertEquals("zh_tw", manager.settings().messageLanguage.code)
    }

    @Test
    fun `reload moves obsolete owner prefix comments and value without reserializing`() {
        val configFile = directory.resolve("config.yml").toFile()
        val defaults = validConfig(enabled = true)
        val original =
            withBomAndCrLf(
                defaults.replace(
                    "    display:\n      rotation-seconds: 5\n      single-owner-prefix: '&7[&a%player_name%&7]&r '\n" +
                        "      multiple-owners-prefix: '&7[&a%player_name%&7]&e+%additional_owner_count%&r '\n",
                    "    # keep old owner explanation\n" +
                        "    display-prefix: '&b%player_name%&r ' # keep old owner inline\n" +
                        "    owner-extension: unchanged  \n",
                ),
            )
        Files.write(configFile.toPath(), original)
        val manager = managerWithDefaults(defaults, configFile)

        assertInstanceOf(ItemDisplaySettingsUpdateResult.Applied::class.java, manager.reload())

        val migrated = YamlConfiguration.loadConfiguration(configFile)
        val migratedText = Files.readAllBytes(configFile.toPath()).toString(StandardCharsets.UTF_8)
        assertEquals(false, migrated.contains("items.ownership.display-prefix"))
        assertEquals("&b%player_name%&r ", migrated.getString("items.ownership.display.single-owner-prefix"))
        assertEquals(5, migrated.getLong("items.ownership.display.rotation-seconds"))
        assertTrue(migratedText.startsWith("\uFEFF"))
        assertFalse(migratedText.replace("\r\n", "").contains('\n'))
        assertTrue(
            migratedText.contains(
                "      # keep old owner explanation\r\n" +
                    "      single-owner-prefix: '&b%player_name%&r ' # keep old owner inline",
            ),
        )
        assertTrue(migratedText.contains("    owner-extension: unchanged  \r\n"))
        assertFalse(Files.list(directory).use { paths -> paths.anyMatch { it.fileName.toString().endsWith(".bak") } })
    }

    @Test
    fun `reload moves obsolete rarity comments and value without reserializing`() {
        val configFile = directory.resolve("config.yml").toFile()
        val defaults = validConfig(enabled = true)
        val original =
            withBomAndCrLf(
                defaults.replace(
                    "  rarity-display:\n    enabled: true\n",
                    "  # keep old rarity explanation\n" +
                        "  item-name-rarity-display: false # keep old rarity inline\n" +
                        "  rarity-extension: unchanged  \n",
                ),
            )
        Files.write(configFile.toPath(), original)
        val manager = managerWithDefaults(defaults, configFile)

        assertInstanceOf(ItemDisplaySettingsUpdateResult.Applied::class.java, manager.reload())

        val migrated = YamlConfiguration.loadConfiguration(configFile)
        val migratedText = Files.readAllBytes(configFile.toPath()).toString(StandardCharsets.UTF_8)
        assertEquals(false, migrated.contains("items.item-name-rarity-display"))
        assertEquals(false, migrated.getBoolean("items.rarity-display.enabled"))
        assertEquals(false, manager.settings().rarityDisplayEnabled)
        assertTrue(
            migratedText.contains(
                "    # keep old rarity explanation\r\n" +
                    "    enabled: false # keep old rarity inline",
            ),
        )
        assertTrue(migratedText.contains("  rarity-extension: unchanged  \r\n"))
        assertFalse(Files.list(directory).use { paths -> paths.anyMatch { it.fileName.toString().endsWith(".bak") } })
    }

    @Test
    fun `toggle recreates a config deleted after startup before writing`() {
        val configFile = directory.resolve("config.yml").toFile()
        val defaults = validConfig(enabled = true)
        configFile.writeText(defaults)
        val manager = managerWithDefaults(defaults, configFile)
        assertEquals(true, configFile.delete())

        assertInstanceOf(ItemDisplaySettingsUpdateResult.Applied::class.java, manager.setEnabled(false))

        assertEquals(false, loadSettings(configFile.readText()).enabled)
    }

    @Test
    fun `toggle backs up repairs invalid known config and applies requested state`() {
        val configFile = directory.resolve("config.yml").toFile()
        val defaults = validConfig(enabled = true)
        val original = defaults.replaceFirst("enabled: true", "enabled: invalid")
        configFile.writeText(original)
        val reports = mutableListOf<ConfigParseRecoveryReport>()
        val manager = managerWithDefaults(defaults, configFile, configParseRecoveryReporter = reports::add)

        assertInstanceOf(ItemDisplaySettingsUpdateResult.Applied::class.java, manager.setEnabled(false))

        assertFalse(manager.settings().enabled)
        assertEquals(listOf("general.enabled"), reports.single().repairedPaths)
        assertEquals(original, directory.resolve(reports.single().backupFileName).toFile().readText())
    }

    @Test
    fun `invalid reload leaves current runtime settings unchanged`() {
        val configFile = directory.resolve("config.yml").toFile()
        val defaults = validConfig(enabled = true)
        configFile.writeText("schema-version: broken")
        val manager = managerWithDefaults(defaults, configFile)

        assertInstanceOf(ItemDisplaySettingsUpdateResult.Failed::class.java, manager.reload())
        assertEquals(true, manager.settings().enabled)
        assertEquals("schema-version: broken", configFile.readText())
        assertTrue(backupFiles().isEmpty())
    }

    private fun loadSettings(content: String) =
        assertInstanceOf(
            BukkitDisplaySettingsLoadResult.Loaded::class.java,
            BukkitDisplaySettingsLoader().load(YamlConfiguration().apply { loadFromString(content) }),
        ).settings

    private fun assertRawNullLegacyLeafRejected(case: LegacyRawNullCase) {
        val original =
            fixture(case.resource)
                .replace(case.originalLine, case.replacementLine)
                .toByteArray(StandardCharsets.UTF_8)
        assertLegacyMigrationRejected(
            label = case.label,
            original = original,
            expectedReason = "${case.expectedPath}: explicit null is not allowed",
            directToggle = case.directToggle,
        )
    }

    private fun assertLegacyMigrationRejected(
        label: String,
        original: ByteArray,
        expectedReason: String,
        directToggle: Boolean,
    ) {
        val caseDirectory = Files.createDirectories(directory.resolve(label))
        val configFile = caseDirectory.resolve("config.yml").toFile().apply { writeBytes(original) }
        val defaults = validConfig(enabled = true)
        val initialSettings = loadSettings(defaults)
        val reports = mutableListOf<LegacyConfigMigrationReport>()
        var replacements = 0
        val manager =
            BukkitItemDisplaySettingsManager(
                initialSettings = initialSettings,
                configFile = configFile,
                defaultConfiguration = defaultConfig(defaults),
                defaultDocumentBytes = defaultDocument(defaults),
                migrationReporter = reports::add,
                migrationFileReplacer = { source, target ->
                    replacements += 1
                    Files.move(source, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                },
            )

        val result = if (directToggle) manager.setEnabled(false) else manager.reload()

        assertEquals(
            expectedReason,
            assertInstanceOf(ItemDisplaySettingsUpdateResult.Failed::class.java, result, label).reason,
            label,
        )
        assertArrayEquals(original, Files.readAllBytes(configFile.toPath()), label)
        assertEquals(initialSettings, manager.settings(), label)
        assertTrue(reports.isEmpty(), label)
        assertTrue(caseDirectory.backupFiles().isEmpty(), label)
        assertEquals(0, replacements, label)
    }

    private fun legacyTypedValueCases(): List<LegacyTypedValueCase> {
        val source = fixture("legacy-config/itemdropv2.yml")
        val blockedWorlds = "    - \"blocked-world1\"\n    - \"blocked-world2\""
        val damagePercent = "player-damage-entity-min-health-percent: 50"
        return listOf(
            legacyTypedValueCase(
                "nested-collection-reload",
                source.replace(blockedWorlds, "    - [nested]"),
                "general.blocked-worlds: expected string list",
                false,
            ),
            legacyTypedValueCase(
                "map-in-collection-toggle",
                source.replace(blockedWorlds, "    - {nested: value}"),
                "general.blocked-worlds: expected string list",
                true,
            ),
            legacyTypedValueCase(
                "out-of-range-integer-reload",
                source.replace("owner-owns-time: 30", "owner-owns-time: 9223372036854775808"),
                "items.owner-owns-time: expected integer",
                false,
            ),
            legacyTypedValueCase(
                "nan-toggle",
                source.replace(damagePercent, "player-damage-entity-min-health-percent: .NaN"),
                "items.ownership.entity.minimum-damage-percent-of-max-health: expected value between 0 and 100",
                true,
            ),
            legacyTypedValueCase(
                "positive-infinity-reload",
                source.replace(damagePercent, "player-damage-entity-min-health-percent: .inf"),
                "items.ownership.entity.minimum-damage-percent-of-max-health: expected value between 0 and 100",
                false,
            ),
            legacyTypedValueCase(
                "negative-infinity-toggle",
                source.replace(damagePercent, "player-damage-entity-min-health-percent: -.inf"),
                "items.ownership.entity.minimum-damage-percent-of-max-health: expected value between 0 and 100",
                true,
            ),
        )
    }

    private fun legacyTypedValueCase(
        label: String,
        raw: String,
        expectedReason: String,
        directToggle: Boolean,
    ): LegacyTypedValueCase =
        LegacyTypedValueCase(
            label = label,
            raw = raw.toByteArray(StandardCharsets.UTF_8),
            expectedReason = expectedReason,
            directToggle = directToggle,
        )

    private fun replaceRawValueWithNull(
        original: ByteArray,
        document: ScannedYamlDocument,
        path: YamlPath,
    ): ByteArray {
        val entry = document.entry(path)
        val valueSpan = entry.valueSpan
        if (valueSpan != null) return replaceBytes(original, valueSpan.start, valueSpan.endExclusive, "null".toByteArray())

        val line = document.lines[entry.lineIndex]
        val colonIndex = line.rawBytes.indexOf(':'.code.toByte())
        require(colonIndex >= 0) { "missing mapping colon for ${path.dotted}" }
        val replacement =
            line.rawBytes.copyOfRange(0, colonIndex + 1) +
                " null".toByteArray(StandardCharsets.UTF_8) +
                line.newlineBytes
        return replaceBytes(original, entry.subtreeSpan.start, entry.subtreeSpan.endExclusive, replacement)
    }

    private fun replaceBytes(
        original: ByteArray,
        start: Int,
        endExclusive: Int,
        replacement: ByteArray,
    ): ByteArray = original.copyOfRange(0, start) + replacement + original.copyOfRange(endExclusive, original.size)

    private fun assertItemDropV2SemanticValues(migrated: YamlConfiguration) {
        assertEquals(1, migrated.getInt("schema-version"))
        assertEquals("en_us", migrated.getString("general.language"))
        assertEquals("en_us", migrated.getString("general.minecraft-language"))
        assertEquals(listOf("blocked-world1", "blocked-world2"), migrated.getStringList("general.blocked-worlds"))
        assertTrue(migrated.getBoolean("items.ownership.enabled"))
        assertEquals(30, migrated.getLong("items.ownership.protection-seconds"))
        assertFalse(migrated.getBoolean("items.ownership.allow-hopper-pickup"))
        assertEquals("highest-damage", migrated.getString("items.ownership.entity.strategy"))
        assertEquals(50.0, migrated.getDouble("items.ownership.entity.minimum-damage-percent-of-max-health"))
        assertEquals("maximum", migrated.getString("items.merge.lifetime-strategy"))
        assertTrue(migrated.getBoolean("items.rarity-display.enabled"))
        assertEquals(5, migrated.getLong("items.ownership.pickup-warning-cooldown-seconds"))
        assertEquals("action-bar", migrated.getString("items.ownership.pickup-warning-message-type"))
        assertEquals(
            "&7[&6%protection_remaining%&r&7]&r&7[&a%player_name%&7]&r ",
            migrated.getString("items.ownership.display.single-owner-prefix"),
        )
        assertEquals(
            "&7[&6%protection_remaining%&r&7]&r&7[&a%player_name%&7]&r&e+%additional_owner_count%&r ",
            migrated.getString("items.ownership.display.multiple-owners-prefix"),
        )
        assertEquals(
            "%item_display_name%&7[&e%lifetime_remaining%&r&7]&r &cx%amount%",
            migrated.getString("items.display-name-format.single"),
        )
        assertEquals(
            "%item_display_name%&7[&e%lifetime_remaining%&r&7]&r &cx%amount%",
            migrated.getString("items.display-name-format.multi"),
        )
        assertFalse(migrated.contains("items.async"))
    }

    private fun fixture(path: String): String =
        requireNotNull(javaClass.classLoader.getResourceAsStream(path)) { "missing test fixture $path" }
            .bufferedReader(Charsets.UTF_8)
            .use { it.readText() }

    private fun commentedLegacyFixture(
        path: String,
        routes: List<LegacyCommentRoute>,
        withBom: Boolean,
    ): CommentedLegacyFixture {
        val routesBySource = routes.associateBy(LegacyCommentRoute::source)
        val segments = mutableListOf<String>()
        val output = mutableListOf<String>()
        val footerComments = mutableListOf<String>()
        fixture(path).lineSequence().forEach { line ->
            val parsed = parseLegacyFixturePath(line, segments)
            if (parsed == LEGACY_SECOND_SECTION[routes.first().source.substringBefore('.')]) {
                output += ""
                output += STANDALONE_LEGACY_COMMENT
                footerComments += STANDALONE_LEGACY_COMMENT
            }
            val route = parsed?.let(routesBySource::get)
            if (route == null) {
                output += line
            } else {
                val indent = line.takeWhile(Char::isWhitespace)
                output += indent + route.ownedComment
                output += line + " " + route.inlineComment
                if (route.target == null) {
                    footerComments += route.ownedComment
                    footerComments += route.inlineComment
                }
            }
        }
        output += ""
        output += UNMAPPED_LEGACY_COMMENT
        output += "legacy-extension: keep " + UNMAPPED_LEGACY_INLINE_COMMENT
        footerComments += UNMAPPED_LEGACY_COMMENT
        footerComments += UNMAPPED_LEGACY_INLINE_COMMENT
        val text = output.joinToString("\n", postfix = "\n")
        val bytes = if (withBom) withBomAndCrLf(text) else text.toByteArray(StandardCharsets.UTF_8)
        return CommentedLegacyFixture(bytes, routes, footerComments)
    }

    private fun parseLegacyFixturePath(
        line: String,
        segments: MutableList<String>,
    ): String? {
        val trimmed = line.trimStart()
        val key = legacyMappingKeyOrNull(trimmed) ?: return null
        val indent = line.length - trimmed.length
        val depth = indent / 2
        while (segments.size > depth) segments.removeLast()
        return if (segments.size == depth) {
            segments += key
            segments.joinToString(".")
        } else {
            null
        }
    }

    private fun legacyMappingKeyOrNull(trimmed: String): String? {
        val isCommentOrSequence = trimmed.startsWith('#') || trimmed.startsWith('-')
        return if (trimmed.isEmpty() || isCommentOrSequence || ':' !in trimmed) null else trimmed.substringBefore(':')
    }

    private fun assertRawTemplateAndCommentMigration(
        candidate: ByteArray,
        legacy: CommentedLegacyFixture,
        rawDefaults: ByteArray,
    ) {
        val candidateText = candidate.toString(StandardCharsets.UTF_8)
        val rawDefaultsText = rawDefaults.toString(StandardCharsets.UTF_8)
        assertEquals(rawDefaultsText.startsWith("\uFEFF"), candidateText.startsWith("\uFEFF"))
        assertTrue(candidateText.contains("# raw current template heading"))
        assertTrue(candidateText.contains("# raw current template trailer  "))
        if (rawDefaultsText.contains("\r\n")) {
            assertFalse(candidateText.replace("\r\n", "").contains('\n'))
        } else {
            assertFalse(candidateText.contains("\r\n"))
        }
        val document =
            assertInstanceOf(
                YamlDocumentScanResult.Scanned::class.java,
                StrictYamlDocumentScanner().scan(candidate),
            ).document
        legacy.routes.filter { it.target != null }.forEach { route ->
            val comments =
                document
                    .entry(YamlPath.parse(requireNotNull(route.target)))
                    .ownedLeadingComments
                    .map(String::trimStart)
            assertTrue(comments.contains(route.ownedComment), "${route.source}: $comments\n$candidateText")
            assertTrue(comments.contains(route.inlineComment), "${route.source}: $comments\n$candidateText")
        }
        val footerStart = candidateText.indexOf(LEGACY_FOOTER)
        assertTrue(footerStart >= 0)
        val footer = candidateText.substring(footerStart)
        val footerLines = footer.lineSequence().map(String::trim).toList()
        var previous = -1
        legacy.footerComments.forEach { comment ->
            val index = footerLines.indexOf(comment)
            assertTrue(index > previous, comment)
            assertEquals(1, footerLines.count { it == comment }, comment)
            previous = index
        }
        legacy.routes.filter { it.target != null }.forEach { route ->
            assertFalse(footerLines.contains(route.ownedComment), route.source)
            assertFalse(footerLines.contains(route.inlineComment), route.source)
        }
    }

    private fun backupFiles(): List<Path> = directory.backupFiles()

    private fun Path.backupFiles(): List<Path> =
        Files.list(this).use { paths ->
            paths
                .iterator()
                .asSequence()
                .filter { it.fileName.toString().endsWith(".bak") }
                .toList()
        }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { byte -> "%02x".format(byte) }

    private fun defaultConfig(content: String): YamlConfiguration = YamlConfiguration().apply { loadFromString(content) }

    private fun defaultDocument(content: String): ByteArray = content.toByteArray(Charsets.UTF_8)

    private fun withBomAndCrLf(content: String): ByteArray =
        byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) +
            content.replace("\n", "\r\n").toByteArray(StandardCharsets.UTF_8)

    private fun managerWithDefaults(
        defaults: String,
        configFile: java.io.File,
        settingsValidator: (com.github.command1264.itemdropv2.core.ItemDisplaySettings) -> String? = { null },
        configFileReplacer: ((Path, Path) -> Unit)? = null,
        configParseRecoveryReporter: (ConfigParseRecoveryReport) -> Unit = {},
    ): BukkitItemDisplaySettingsManager =
        BukkitItemDisplaySettingsManager(
            initialSettings = loadSettings(defaults),
            configFile = configFile,
            defaultConfiguration = defaultConfig(defaults),
            defaultDocumentBytes = defaultDocument(defaults),
            settingsValidator = settingsValidator,
            configFileReplacer = configFileReplacer,
            configParseRecoveryReporter = configParseRecoveryReporter,
        )

    private fun lifetimeManager(
        configFile: java.io.File,
        lifetimeFile: java.io.File,
        lifetimeDefaults: String,
    ): BukkitItemDisplaySettingsManager {
        val displayDefaults = validConfig(enabled = true)
        val lifetimeConfiguration = defaultConfig(lifetimeDefaults)
        val lifetime =
            assertInstanceOf(
                BukkitItemLifetimeSettingsLoadResult.Loaded::class.java,
                BukkitItemLifetimeSettingsLoader().load(lifetimeConfiguration),
            ).settings
        return BukkitItemDisplaySettingsManager(
            initialSettings = loadSettings(displayDefaults).copy(lifetime = lifetime),
            configFile = configFile,
            defaultConfiguration = defaultConfig(displayDefaults),
            defaultDocumentBytes = defaultDocument(displayDefaults),
            lifetimeConfigFile = lifetimeFile,
            defaultLifetimeConfiguration = lifetimeConfiguration,
        )
    }

    private fun lifetimeConfig(defaultSeconds: Long): String =
        """
        schema-version: 1
        default-seconds: $defaultSeconds
        materials: {}
        """.trimIndent()

    private fun validConfig(enabled: Boolean): String =
        """
        schema-version: 1
        general:
          enabled: $enabled
          language: zh_tw
          minecraft-language: en_us
          blocked-worlds:
            - template-world
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
            creative-no-capacity-pickup: destroy
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

    private data class LegacyCommentRoute(
        val source: String,
        val target: String?,
    ) {
        private val marker = source.lowercase().map { character -> if (character.isLetterOrDigit()) character else '-' }.joinToString("")
        val ownedComment: String = "# legacy-owned-$marker"
        val inlineComment: String = "# legacy-inline-$marker"
    }

    private data class CommentedLegacyFixture(
        val bytes: ByteArray,
        val routes: List<LegacyCommentRoute>,
        val footerComments: List<String>,
    )

    private data class LegacyRawNullCase(
        val label: String,
        val resource: String,
        val originalLine: String,
        val replacementLine: String,
        val expectedPath: String,
        val directToggle: Boolean,
    )

    private data class LegacyTypedValueCase(
        val label: String,
        val raw: ByteArray,
        val expectedReason: String,
        val directToggle: Boolean,
    )

    private companion object {
        private const val LEGACY_FOOTER = "# Legacy comments preserved during migration"
        private const val STANDALONE_LEGACY_COMMENT = "# legacy-standalone-section-explanation"
        private const val UNMAPPED_LEGACY_COMMENT = "# legacy-unmapped-owned"
        private const val UNMAPPED_LEGACY_INLINE_COMMENT = "# legacy-unmapped-inline"
        private val LEGACY_SECOND_SECTION = mapOf("general" to "items", "General" to "Item_Hologram")
        private val ITEM_DROP_V2_COMMENT_ROUTES =
            listOf(
                LegacyCommentRoute("general.enabled", "general.enabled"),
                LegacyCommentRoute("general.language", "general.language"),
                LegacyCommentRoute("general.mc-language", "general.minecraft-language"),
                LegacyCommentRoute("general.mc-language-check-updates", null),
                LegacyCommentRoute("general.mc-language-check-updates-message", null),
                LegacyCommentRoute("general.mc-language-check-updates-interval", null),
                LegacyCommentRoute("general.mc-language-auto-update", null),
                LegacyCommentRoute("general.mc-language-auto-updates-message", null),
                LegacyCommentRoute("general.mc-language-cant-find-item-in-lang-message", null),
                LegacyCommentRoute("general.blocked-worlds", "general.blocked-worlds"),
                LegacyCommentRoute("items.async", null),
                LegacyCommentRoute("items.item-age-type", null),
                LegacyCommentRoute("items.custom-item-death-time", null),
                LegacyCommentRoute(
                    "items.hopper-pickup-owner",
                    "items.ownership.allow-hopper-pickup",
                ),
                LegacyCommentRoute(
                    "items.placeholder.owner-player-prefix",
                    "items.ownership.display.single-owner-prefix",
                ),
                LegacyCommentRoute("items.placeholder.item-time-left", null),
                LegacyCommentRoute("items.display-name-format.single", "items.display-name-format.single"),
                LegacyCommentRoute("items.display-name-format.multi", "items.display-name-format.multi"),
                LegacyCommentRoute("items.owner-owns-time", "items.ownership.protection-seconds"),
                LegacyCommentRoute("items.item-merge-owner-time-mode", null),
                LegacyCommentRoute("items.item-merge-lived-time-mode", "items.merge.lifetime-strategy"),
                LegacyCommentRoute(
                    "items.player-pickup-warning-message-delay",
                    "items.ownership.pickup-warning-cooldown-seconds",
                ),
                LegacyCommentRoute(
                    "items.player-pickup-warning-message-type",
                    "items.ownership.pickup-warning-message-type",
                ),
                LegacyCommentRoute("items.item-owner-decide", "items.ownership.entity.strategy"),
                LegacyCommentRoute(
                    "items.player-damage-entity-min-health-percent",
                    "items.ownership.entity.minimum-damage-percent-of-max-health",
                ),
                LegacyCommentRoute("items.item-name-rarity-display", "items.rarity-display.enabled"),
            )
        private val ITEM_DROP_COMMENT_ROUTES =
            listOf(
                LegacyCommentRoute("General.Enable", "general.enabled"),
                LegacyCommentRoute("General.Version", null),
                LegacyCommentRoute("General.Language", "general.language"),
                LegacyCommentRoute("General.MCLanguage", "general.minecraft-language"),
                LegacyCommentRoute("General.Blocked_Worlds", "general.blocked-worlds"),
                LegacyCommentRoute(
                    "Item_Hologram.Owner_Player",
                    "items.ownership.display.single-owner-prefix",
                ),
                LegacyCommentRoute("Item_Hologram.Item_Display_Name", null),
                LegacyCommentRoute("Item_Hologram.Owner_Owns_Time", "items.ownership.protection-seconds"),
                LegacyCommentRoute("Item_Hologram.Item_Marge_Mode", "items.merge.lifetime-strategy"),
                LegacyCommentRoute(
                    "Item_Hologram.Player_Can_Not_PickUp_Item_Message_Delay",
                    "items.ownership.pickup-warning-cooldown-seconds",
                ),
                LegacyCommentRoute(
                    "Item_Hologram.Player_Damage_Entity_Min_Health_Percent",
                    "items.ownership.entity.minimum-damage-percent-of-max-health",
                ),
                LegacyCommentRoute("Item_Hologram.Item_Owner_Judge", "items.ownership.entity.strategy"),
                LegacyCommentRoute("Item_Hologram.Item_Name_Rarity_Display", "items.rarity-display.enabled"),
                LegacyCommentRoute("Item_Hologram.Display.Single", "items.display-name-format.single"),
                LegacyCommentRoute("Item_Hologram.Display.Multi", "items.display-name-format.multi"),
            )
    }
}

class BukkitItemDisplaySettingsManagerDocumentTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `reload backs up and repairs invalid known values while preserving comments`() {
        val configFile = directory.resolve("config.yml").toFile()
        val defaults = validConfig(enabled = true)
        val original = defaults.replaceFirst("  enabled: true", "  enabled: invalid # keep operator note")
        configFile.writeText(original)
        val reports = mutableListOf<ConfigParseRecoveryReport>()
        val manager = manager(defaults, configFile, configParseRecoveryReporter = reports::add)

        val result = manager.reload()

        assertInstanceOf(ItemDisplaySettingsUpdateResult.Applied::class.java, result, result.toString())
        assertTrue(configFile.readText().contains("  enabled: true # keep operator note"))
        assertEquals(true, manager.settings().enabled)
        assertEquals(listOf("general.enabled"), reports.single().repairedPaths)
        assertFalse(reports.single().documentRecreated)
        val backup = directory.resolve(reports.single().backupFileName)
        assertEquals(original, backup.toFile().readText())
        assertNoTemporaryFiles()
    }

    @Test
    fun `reload backs up malformed YAML and recreates the embedded document`() {
        val configFile = directory.resolve("config.yml").toFile()
        val defaults = validConfig(enabled = true)
        val original = "schema-version: 1\ngeneral: [\n"
        configFile.writeText(original)
        val reports = mutableListOf<ConfigParseRecoveryReport>()
        val manager = manager(defaults, configFile, configParseRecoveryReporter = reports::add)

        val result = manager.reload()

        assertInstanceOf(ItemDisplaySettingsUpdateResult.Applied::class.java, result, result.toString())
        assertArrayEquals(spaced(defaults.toByteArray()), configFile.readBytes())
        assertTrue(reports.single().documentRecreated)
        assertEquals(emptyList<String>(), reports.single().repairedPaths)
        assertEquals(original, directory.resolve(reports.single().backupFileName).toFile().readText())
        assertNoTemporaryFiles()
    }

    @Test
    fun `reload repairs repeated errors for one known sequence as one path`() {
        val configFile = directory.resolve("config.yml").toFile()
        val defaults = validConfig(enabled = true)
        val original =
            defaults.replace(
                "  blocked-worlds: []",
                "  blocked-worlds:\n    # keep blocked-world note\n    - world\n    - world",
            )
        configFile.writeText(original)
        val reports = mutableListOf<ConfigParseRecoveryReport>()
        val manager = manager(defaults, configFile, configParseRecoveryReporter = reports::add)

        val result = manager.reload()

        assertInstanceOf(ItemDisplaySettingsUpdateResult.Applied::class.java, result, result.toString())
        assertEquals(emptySet<String>(), manager.settings().blockedWorlds)
        assertEquals(listOf("general.blocked-worlds"), reports.single().repairedPaths)
        assertTrue(configFile.readText().contains("# keep blocked-world note"))
        assertEquals(original, directory.resolve(reports.single().backupFileName).toFile().readText())
    }

    @Test
    fun `reload recreates a deleted config from exact embedded bytes`() {
        val configFile = directory.resolve("config.yml").toFile()
        val defaults = validConfig(enabled = true)
        val rawDefaults = withBomAndCrLf(defaults + "\n# Embedded trailer.  ")
        val manager = manager(defaults, configFile, rawDefaults = rawDefaults)

        val result = manager.reload()

        assertInstanceOf(ItemDisplaySettingsUpdateResult.Applied::class.java, result, result.toString())
        assertTrue(spaced(rawDefaults).contentEquals(Files.readAllBytes(configFile.toPath())))
        assertTrue(manager.settings().enabled)
        assertNoTemporaryFiles()
    }

    @Test
    fun `same schema repair writes the exact comment-preserving leaf and subtree candidate`() {
        val configFile = directory.resolve("config.yml").toFile()
        val defaults = commentedDefaults()
        val original =
            defaults
                .replace("  # Embedded language explanation.\n  minecraft-language: en_us\n", "")
                .replace(
                    "  # Embedded processing explanation.\n  processing:\n    maximum-items-per-tick: 256\n",
                    "",
                ).withUserText()
        val expected = defaults.withUserText()
        Files.write(configFile.toPath(), withBomAndCrLf(original))
        val manager = manager(defaults, configFile)

        val result = manager.reload()

        assertInstanceOf(ItemDisplaySettingsUpdateResult.Applied::class.java, result, result.toString())
        assertTrue(spaced(withBomAndCrLf(expected)).contentEquals(Files.readAllBytes(configFile.toPath())))
        assertEquals(256, manager.settings().processing.maximumItemsPerTick)
        assertEquals("ja_jp", manager.settings().messageLanguage.code)
        assertNoTemporaryFiles()
    }

    @Test
    fun `same schema reload exposes reserved footer rejection reasons without changing state`() {
        val defaults = validConfig(enabled = true)
        val cases =
            listOf(
                "duplicate" to
                    (
                        defaults +
                            "\n# Legacy comments preserved during migration\n" +
                            "# first\n" +
                            "# Legacy comments preserved during migration\n" +
                            "# second\n"
                    ) to "at most one reserved legacy comment footer",
                "nonterminal" to
                    (
                        defaults +
                            "\n# Legacy comments preserved during migration\n" +
                            "# preserved\n" +
                            "unknown-after-footer: keep\n"
                    ) to "must be the terminal comment-only section",
            )

        cases.forEach { (labeledDocument, expectedDetail) ->
            val (label, document) = labeledDocument
            val original = document.toByteArray(StandardCharsets.UTF_8)
            val configFile = directory.resolve("footer-$label.yml").toFile().apply { writeBytes(original) }
            val manager = manager(defaults, configFile)

            val failed = assertInstanceOf(ItemDisplaySettingsUpdateResult.Failed::class.java, manager.reload(), label)

            assertTrue(failed.reason.startsWith("structure: "), label)
            assertTrue(failed.reason.contains(expectedDetail), label)
            assertArrayEquals(original, Files.readAllBytes(configFile.toPath()), label)
            assertTrue(manager.settings().enabled, label)
        }
        assertNoTemporaryFiles()
    }

    @Test
    fun `toggle changes only enabled value bytes and creates no backup`() {
        val configFile = directory.resolve("config.yml").toFile()
        val defaults = validConfig(enabled = true)
        val original =
            withBomAndCrLf(
                defaults
                    .replace("  enabled: true", "  enabled: true   # keep toggle note")
                    .replace("items:\n", "unknown-root: keep-me  \n\nitems:\n") +
                    "\n# trailing user note  ",
            )
        Files.write(configFile.toPath(), original)
        val manager = manager(defaults, configFile)

        val result = manager.setEnabled(false)

        assertInstanceOf(ItemDisplaySettingsUpdateResult.Applied::class.java, result, result.toString())
        val expected = original.toString(StandardCharsets.UTF_8).replaceFirst("enabled: true", "enabled: false").toByteArray()
        assertTrue(expected.contentEquals(Files.readAllBytes(configFile.toPath())))
        assertFalse(Files.list(directory).use { paths -> paths.anyMatch { it.fileName.toString().endsWith(".bak") } })
        assertFalse(manager.settings().enabled)
        assertNoTemporaryFiles()
    }

    @Test
    fun `failed toggle from a missing file restores pre-call absence`() {
        val defaults = validConfig(enabled = true)
        val configFile = directory.resolve("config.yml").toFile()
        var replacements = 0
        val manager =
            manager(
                defaults,
                configFile,
                configFileReplacer = { source, target ->
                    replacements += 1
                    if (Files.readAllBytes(source).toString(StandardCharsets.UTF_8).contains("enabled: false")) {
                        throw IOException("injected toggle replacement failure")
                    }
                    Files.move(source, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                },
            )

        val result = manager.setEnabled(false)

        assertInstanceOf(ItemDisplaySettingsUpdateResult.Failed::class.java, result)
        assertFalse(configFile.exists())
        assertEquals(1, replacements)
        assertTrue(manager.settings().enabled)
        assertNoTemporaryFiles()
    }

    @Test
    fun `failed toggle from a missing leaf restores exact pre-call bytes`() {
        val defaults = validConfig(enabled = true)
        val original = defaults.replace("  minecraft-language: en_us\n", "").toByteArray(StandardCharsets.UTF_8)
        val configFile = directory.resolve("config.yml").toFile().apply { writeBytes(original) }
        var replacements = 0
        val manager =
            manager(
                defaults,
                configFile,
                configFileReplacer = { source, target ->
                    replacements += 1
                    if (Files.readAllBytes(source).toString(StandardCharsets.UTF_8).contains("enabled: false")) {
                        throw IOException("injected toggle replacement failure")
                    }
                    Files.move(source, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                },
            )

        val result = manager.setEnabled(false)

        assertInstanceOf(ItemDisplaySettingsUpdateResult.Failed::class.java, result)
        assertArrayEquals(original, Files.readAllBytes(configFile.toPath()))
        assertEquals(1, replacements)
        assertTrue(manager.settings().enabled)
        assertNoTemporaryFiles()
    }

    @Test
    fun `toggle validates one combined repair and scalar transition exactly once`() {
        val defaults = validConfig(enabled = true)
        val original = defaults.replace("  minecraft-language: en_us\n", "")
        val configFile = directory.resolve("config.yml").toFile().apply { writeText(original) }
        val manager = manager(defaults, configFile)
        var transitions = 0
        manager.installTransitionValidator { _, _ ->
            transitions += 1
            null
        }

        val result = manager.setEnabled(false)

        assertInstanceOf(ItemDisplaySettingsUpdateResult.Applied::class.java, result, result.toString())
        assertEquals(1, transitions)
        assertFalse(manager.settings().enabled)
        assertNoTemporaryFiles()
    }

    @Test
    fun `repair validation gates keep the original hash runtime settings and directory clean`() {
        val defaults = validConfig(enabled = true)
        val original = defaults.replace("  minecraft-language: en_us\n", "")
        val cases =
            listOf(
                "settings-validator" to { file: java.io.File ->
                    file.writeText(original)
                    manager(defaults, file, settingsValidator = { "catalog rejected" })
                },
                "transition-validator" to { file: java.io.File ->
                    file.writeText(original)
                    manager(defaults, file).apply { installTransitionValidator { _, _ -> "transition rejected" } }
                },
            )

        cases.forEach { (label, createManager) ->
            val file = directory.resolve("$label.yml").toFile()
            val manager = createManager(file)
            val before = sha256(Files.readAllBytes(file.toPath()))

            assertInstanceOf(ItemDisplaySettingsUpdateResult.Failed::class.java, manager.reload(), label)
            assertEquals(before, sha256(Files.readAllBytes(file.toPath())), label)
            assertTrue(manager.settings().enabled, label)
        }
        assertNoTemporaryFiles()
    }

    @Test
    fun `lifetime value recovery and main config repair publish in the same reload`() {
        val defaults = validConfig(enabled = true)
        val configFile =
            directory.resolve("config.yml").toFile().apply {
                writeText(defaults.replace("  minecraft-language: en_us\n", ""))
            }
        val lifetimeFile =
            directory.resolve("item-lifetime.yml").toFile().apply {
                writeText("schema-version: 1\ndefault-seconds: -2\nmaterials: {}")
            }
        val manager = lifetimeManager(defaults, configFile, lifetimeFile)

        assertInstanceOf(ItemDisplaySettingsUpdateResult.Applied::class.java, manager.reload())

        assertTrue(configFile.readText().contains("minecraft-language: en_us"))
        assertEquals(300, manager.settings().lifetime.defaultLifetimeSeconds)
        assertEquals(1, Files.list(directory).use { paths -> paths.filter { it.fileName.toString().contains("parse-recovery") }.count() })
        assertNoTemporaryFiles()
    }

    @Test
    fun `replacement and explicitly owned corruption failures restore the original config`() {
        val defaults = validConfig(enabled = true)
        val original = defaults.replace("  minecraft-language: en_us\n", "")
        val cases =
            listOf(
                "replacement" to { _: Path, _: Path -> throw IOException("injected replacement failure") },
                "owned-corruption" to { source: Path, target: Path ->
                    val corrupted = "schema-version: broken".toByteArray(StandardCharsets.UTF_8)
                    Files.write(target, corrupted)
                    Files.deleteIfExists(source)
                    throw ownedYamlDocumentReplacementFailure(corrupted, IOException("injected owned corruption"))
                },
            )

        cases.forEach { (label, replacer) ->
            val file = directory.resolve("$label.yml").toFile().apply { writeText(original) }
            val before = sha256(Files.readAllBytes(file.toPath()))
            val manager = manager(defaults, file, configFileReplacer = replacer)

            assertInstanceOf(ItemDisplaySettingsUpdateResult.Failed::class.java, manager.reload())
            assertEquals(before, sha256(Files.readAllBytes(file.toPath())))
            assertTrue(manager.settings().enabled)
        }
        assertNoTemporaryFiles()
    }

    private fun manager(
        defaults: String,
        configFile: java.io.File,
        rawDefaults: ByteArray = defaults.toByteArray(StandardCharsets.UTF_8),
        settingsValidator: (com.github.command1264.itemdropv2.core.ItemDisplaySettings) -> String? = { null },
        configFileReplacer: ((Path, Path) -> Unit)? = null,
        configParseRecoveryReporter: (ConfigParseRecoveryReport) -> Unit = {},
    ): BukkitItemDisplaySettingsManager =
        if (configFileReplacer == null) {
            BukkitItemDisplaySettingsManager(
                initialSettings = loadSettings(defaults),
                configFile = configFile,
                defaultConfiguration = yaml(defaults),
                defaultDocumentBytes = rawDefaults,
                settingsValidator = settingsValidator,
                configParseRecoveryReporter = configParseRecoveryReporter,
            )
        } else {
            BukkitItemDisplaySettingsManager(
                initialSettings = loadSettings(defaults),
                configFile = configFile,
                defaultConfiguration = yaml(defaults),
                defaultDocumentBytes = rawDefaults,
                settingsValidator = settingsValidator,
                configFileReplacer = configFileReplacer,
                configParseRecoveryReporter = configParseRecoveryReporter,
            )
        }

    private fun lifetimeManager(
        displayDefaults: String,
        configFile: java.io.File,
        lifetimeFile: java.io.File,
    ): BukkitItemDisplaySettingsManager {
        val lifetimeDefaults = "schema-version: 1\ndefault-seconds: 300\nmaterials: {}"
        return BukkitItemDisplaySettingsManager(
            initialSettings = loadSettings(displayDefaults),
            configFile = configFile,
            defaultConfiguration = yaml(displayDefaults),
            defaultDocumentBytes = displayDefaults.toByteArray(StandardCharsets.UTF_8),
            lifetimeConfigFile = lifetimeFile,
            defaultLifetimeConfiguration = yaml(lifetimeDefaults),
        )
    }

    private fun loadSettings(content: String) =
        assertInstanceOf(
            BukkitDisplaySettingsLoadResult.Loaded::class.java,
            BukkitDisplaySettingsLoader().load(yaml(content)),
        ).settings

    private fun yaml(content: String): YamlConfiguration = YamlConfiguration().apply { loadFromString(content) }

    private fun commentedDefaults(): String =
        validConfig(enabled = true)
            .replace(
                "  minecraft-language: en_us\n",
                "  # Embedded language explanation.\n  minecraft-language: en_us\n",
            ).replace(
                "  processing:\n    maximum-items-per-tick: 256\n",
                "  # Embedded processing explanation.\n  processing:\n    maximum-items-per-tick: 256\n",
            )

    private fun String.withUserText(): String =
        replace("general:\n", "# User heading.\ngeneral:\n")
            .replace("  language: zh_tw\n", "  language: ja_jp # keep user comment\n")
            .replace("items:\n", "unknown-root: keep-me  \n\nitems:\n") +
            "\n# User trailer.  "

    private fun withBomAndCrLf(content: String): ByteArray =
        byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) +
            content.replace("\n", "\r\n").toByteArray(StandardCharsets.UTF_8)

    private fun spaced(bytes: ByteArray): ByteArray =
        assertInstanceOf(
            com.github.command1264.itemdropv2.platform.bukkit.yaml.YamlDocumentEditResult.Candidate::class.java,
            com.github.command1264.itemdropv2.platform.bukkit.yaml
                .MappingTreeSpacingPolicy()
                .apply(bytes),
        ).bytes

    private fun sha256(bytes: ByteArray): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { byte -> "%02x".format(byte) }

    private fun assertNoTemporaryFiles() {
        assertFalse(
            Files.list(directory).use { paths ->
                paths.anyMatch { path ->
                    path.fileName.toString().let { name -> name.endsWith(".tmp") || name.startsWith(".yaml-document-") }
                }
            },
        )
    }

    private fun validConfig(enabled: Boolean): String =
        """
        schema-version: 1
        general:
          enabled: $enabled
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
            creative-no-capacity-pickup: destroy
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
}
