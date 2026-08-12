package com.github.command1264.itemdropv2.platform.bukkit

import com.github.command1264.itemdropv2.platform.bukkit.yaml.MappingTreeSpacingPolicy
import com.github.command1264.itemdropv2.platform.bukkit.yaml.StrictYamlDocumentEditor
import com.github.command1264.itemdropv2.platform.bukkit.yaml.YamlDocumentEditRequest
import com.github.command1264.itemdropv2.platform.bukkit.yaml.YamlDocumentEditResult
import com.github.command1264.itemdropv2.platform.bukkit.yaml.YamlDocumentEditor
import com.github.command1264.itemdropv2.platform.bukkit.yaml.YamlDocumentMigrationRequest
import com.github.command1264.itemdropv2.platform.bukkit.yaml.YamlDocumentRejectionCategory
import org.bukkit.configuration.file.YamlConfiguration
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.math.BigInteger
import java.nio.charset.StandardCharsets
import java.nio.file.Path

class BukkitConfigDocumentAdapterTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `missing candidate preserves raw embedded bytes and owns a defensive copy`() {
        val embedded = withBomAndCrLf(validConfig())
        val expected = embedded.copyOf()
        val adapter = adapter(embedded)

        embedded.fill(0)

        val candidate = assertInstanceOf(YamlDocumentEditResult.Candidate::class.java, adapter.createMissingCandidate())
        assertArrayEquals(spaced(expected), candidate.bytes)
    }

    @Test
    fun `same-schema repair restores leaf and subtree spans without changing user document bytes`() {
        val template =
            validConfig()
                .replace(
                    "  minecraft-language: en_us\n",
                    "  # Embedded language explanation.\n  minecraft-language: en_us\n",
                ).replace(
                    "  processing:\n    maximum-items-per-tick: 256\n",
                    "  # Embedded processing explanation.\n  processing:\n    maximum-items-per-tick: 256\n",
                )
        val original =
            template
                .replace("  # Embedded language explanation.\n  minecraft-language: en_us\n", "")
                .replace(
                    "  # Embedded processing explanation.\n  processing:\n    maximum-items-per-tick: 256\n",
                    "",
                ).replace("general:\n", "# User heading.\ngeneral:\n")
                .replace("  language: zh_tw\n", "  language: ja_jp # user language\n")
                .replace("items:\n", "user-root: keep-me  \n\nitems:\n") +
                "\n# User trailer.  "
        val expected =
            template
                .replace("general:\n", "# User heading.\ngeneral:\n")
                .replace("  language: zh_tw\n", "  language: ja_jp # user language\n")
                .replace("items:\n", "user-root: keep-me  \n\nitems:\n") +
                "\n# User trailer.  "
        val originalBytes = withBomAndCrLf(original)

        val candidate =
            assertInstanceOf(
                YamlDocumentEditResult.Candidate::class.java,
                adapter(withBomAndCrLf(template)).repairCurrentSchema(
                    originalBytes,
                ),
            )

        assertArrayEquals(spaced(withBomAndCrLf(expected)), candidate.bytes)
    }

    @Test
    fun `restored first processing subtree remains adjacent to both mapping parents`() {
        val processing =
            "  # Embedded processing explanation.\n" +
                "  processing:\n" +
                "    # Embedded maximum explanation.\n" +
                "    maximum-items-per-tick: 256\n"
        val template =
            validConfig().replace(
                "  processing:\n    maximum-items-per-tick: 256\n  rarity-display:",
                "$processing\n  rarity-display:",
            )
        val original = template.replace(processing, "")

        val candidate =
            assertInstanceOf(
                YamlDocumentEditResult.Candidate::class.java,
                adapter(template.toByteArray()).repairCurrentSchema(original.toByteArray()),
            )

        assertTrue(
            candidate.bytes.toString(StandardCharsets.UTF_8).contains(
                "items:\n" +
                    "  # Embedded processing explanation.\n" +
                    "  processing:\n" +
                    "    # Embedded maximum explanation.\n" +
                    "    maximum-items-per-tick: 256\n\n" +
                    "  rarity-display:",
            ),
        )
    }

    @Test
    fun `same-schema repair restores a blank-separated embedded explanation with its missing leaf`() {
        val template =
            validConfig().replace(
                "  language: zh_tw\n  minecraft-language: en_us\n",
                "  language: zh_tw\n\n" +
                    "  # Embedded language explanation.\n" +
                    "  minecraft-language: en_us\n",
            )
        val original =
            template.replace(
                "\n  # Embedded language explanation.\n  minecraft-language: en_us\n",
                "",
            )

        val candidate =
            assertInstanceOf(
                YamlDocumentEditResult.Candidate::class.java,
                adapter(template.toByteArray(StandardCharsets.UTF_8)).repairCurrentSchema(
                    original.toByteArray(StandardCharsets.UTF_8),
                ),
            )

        assertArrayEquals(spaced(template.toByteArray(StandardCharsets.UTF_8)), candidate.bytes)
    }

    @Test
    fun `same-schema repair validates reserved migration footer when no key operation is needed`() {
        val invalidDocuments =
            listOf(
                validConfig() +
                    "\n# Legacy comments preserved during migration\n" +
                    "# first\n" +
                    "# Legacy comments preserved during migration\n" +
                    "# second\n",
                validConfig() +
                    "\n# Legacy comments preserved during migration\n" +
                    "# preserved\n" +
                    "unknown-after-footer: keep\n",
            )
        val documentAdapter = adapter(validConfig().toByteArray(StandardCharsets.UTF_8))

        invalidDocuments.forEach { original ->
            val rejected =
                assertInstanceOf(
                    YamlDocumentEditResult.Rejected::class.java,
                    documentAdapter.repairCurrentSchema(original.toByteArray(StandardCharsets.UTF_8)),
                )

            assertEquals(YamlDocumentRejectionCategory.STRUCTURE, rejected.rejection.category)
        }
    }

    @Test
    fun `current key moves relocate values and owned comments to raw template positions`() {
        currentKeyMoveCases().forEach { case ->
            val originalBytes = withBomAndCrLf(case.missingCanonicalDocument)
            val documentAdapter = adapter(withBomAndCrLf(validConfig()))

            val candidate =
                assertInstanceOf(
                    YamlDocumentEditResult.Candidate::class.java,
                    documentAdapter.repairCurrentSchema(
                        originalBytes,
                    ),
                    case.label,
                )
            val migrated = candidate.bytes.toString(StandardCharsets.UTF_8)

            assertTrue(migrated.startsWith("\uFEFF"), case.label)
            assertFalse(migrated.replace("\r\n", "").contains('\n'), case.label)
            assertFalse(migrated.contains(case.obsoleteKey), case.label)
            assertTrue(migrated.contains(case.movedSpan), case.label)
            assertTrue(migrated.contains(case.unknownSibling), case.label)
            assertTrue(migrated.indexOf(case.templatePredecessor) < migrated.indexOf(case.movedSpan), case.label)
            assertTrue(migrated.indexOf(case.movedSpan) < migrated.indexOf(case.templateSuccessor), case.label)
            assertInstanceOf(BukkitConfigCandidateResult.Valid::class.java, documentAdapter.validate(candidate.bytes))
        }
    }

    @Test
    fun `current key canonical values and comments win while obsolete comments move to one footer`() {
        currentKeyMoveCases().forEach { case ->
            val originalBytes = withBomAndCrLf(case.canonicalWinsDocument)
            val documentAdapter = adapter(withBomAndCrLf(validConfig()))

            val candidate =
                assertInstanceOf(
                    YamlDocumentEditResult.Candidate::class.java,
                    documentAdapter.repairCurrentSchema(
                        originalBytes,
                    ),
                    case.label,
                )
            val migrated = candidate.bytes.toString(StandardCharsets.UTF_8)

            assertFalse(migrated.contains(case.obsoleteKey), case.label)
            assertTrue(migrated.contains(case.canonicalSpan), case.label)
            assertTrue(migrated.contains(case.unknownSibling), case.label)
            assertEquals(1, migrated.windowed(LEGACY_FOOTER.length).count { it == LEGACY_FOOTER }, case.label)
            assertTrue(migrated.indexOf(LEGACY_FOOTER) < migrated.indexOf(case.obsoleteLeadingComment), case.label)
            assertTrue(migrated.indexOf(case.obsoleteLeadingComment) < migrated.indexOf(case.obsoleteInlineComment), case.label)
            assertInstanceOf(BukkitConfigCandidateResult.Valid::class.java, documentAdapter.validate(candidate.bytes))
        }
    }

    @Test
    fun `explicit null current key sources fail closed when the raw canonical path is missing`() {
        currentKeyMoveCases().forEach { case ->
            val original = case.withNullObsolete(case.missingCanonicalDocument)
            val originalBytes = withBomAndCrLf(original)

            val repair =
                adapter(withBomAndCrLf(validConfig())).prepareCurrentSchemaRepair(
                    originalBytes,
                )

            val rejection = assertInstanceOf(YamlDocumentEditResult.Rejected::class.java, repair.edit, case.label).rejection
            assertEquals(YamlDocumentRejectionCategory.VALIDATION, rejection.category, case.label)
            assertEquals(case.obsoletePath, rejection.path?.dotted, case.label)
            assertEquals(null, repair.configKeyMigration, case.label)
        }
    }

    @Test
    fun `explicit null current key sources use canonical wins when the raw canonical path exists`() {
        currentKeyMoveCases().forEach { case ->
            val original = case.withNullObsolete(case.canonicalWinsDocument)
            val documentAdapter = adapter(withBomAndCrLf(validConfig()))

            val repair =
                documentAdapter.prepareCurrentSchemaRepair(
                    withBomAndCrLf(original),
                )
            val candidate = assertInstanceOf(YamlDocumentEditResult.Candidate::class.java, repair.edit, case.label)
            val migrated = candidate.bytes.toString(StandardCharsets.UTF_8)

            assertFalse(migrated.contains(case.obsoleteKey), case.label)
            assertTrue(migrated.contains(case.canonicalSpan), case.label)
            assertTrue(migrated.contains(case.unknownSibling), case.label)
            assertTrue(migrated.contains(case.obsoleteLeadingComment), case.label)
            assertTrue(migrated.contains(case.obsoleteInlineComment), case.label)
            assertEquals(case.requiresBackupAndReport, repair.configKeyMigration != null, case.label)
            assertInstanceOf(BukkitConfigCandidateResult.Valid::class.java, documentAdapter.validate(candidate.bytes))
        }
    }

    @Test
    fun `enabled edit delegates a typed scalar operation without reserializing the document`() {
        val original = validConfig().replaceFirst("enabled: true", "enabled: true # user note")

        val candidate =
            assertInstanceOf(
                YamlDocumentEditResult.Candidate::class.java,
                adapter(validConfig().toByteArray(StandardCharsets.UTF_8)).setEnabled(
                    original.toByteArray(StandardCharsets.UTF_8),
                    false,
                ),
            )

        assertEquals(
            original.replaceFirst("enabled: true", "enabled: false"),
            candidate.bytes.toString(StandardCharsets.UTF_8),
        )
    }

    @Test
    fun `candidate validation strictly decodes parses and loads display settings`() {
        val adapter = adapter(validConfig().toByteArray(StandardCharsets.UTF_8))
        val validBytes = validConfig().toByteArray(StandardCharsets.UTF_8)

        val valid = assertInstanceOf(BukkitConfigCandidateResult.Valid::class.java, adapter.validate(validBytes))
        validBytes.fill(0)

        assertEquals(true, valid.settings.enabled)
        assertEquals(true, valid.configuration.getBoolean("general.enabled"))
        assertEquals('s'.code.toByte(), valid.bytes.first())
        assertEquals(
            true,
            assertInstanceOf(
                BukkitConfigCandidateResult.Rejected::class.java,
                adapter.validate(byteArrayOf(0xC3.toByte(), 0x28)),
            ).reason.contains("UTF-8"),
        )
        assertEquals(
            true,
            assertInstanceOf(
                BukkitConfigCandidateResult.Rejected::class.java,
                adapter.validate("general: [broken".toByteArray(StandardCharsets.UTF_8)),
            ).reason.contains("YAML"),
        )
        assertEquals(
            true,
            assertInstanceOf(
                BukkitConfigCandidateResult.Rejected::class.java,
                adapter.validate(validConfig().replace("schema-version: 1", "schema-version: 2").toByteArray()),
            ).reason.contains("schema-version"),
        )
    }

    @Test
    fun `legacy migrator records every applied comment route and no missing source route`() {
        val template = legacyCompatibleTemplate()
        val migrator = BukkitLegacyConfigMigrator(yaml(template), BukkitDisplaySettingsLoader())
        val cases =
            listOf(
                fixture("legacy-config/itemdropv2.yml") to ITEM_DROP_V2_COMMENT_MAPPINGS,
                fixture("legacy-config/itemdrop.yml") to ITEM_DROP_COMMENT_MAPPINGS,
            )

        cases.forEach { (source, expected) ->
            val migrated =
                assertInstanceOf(
                    LegacyConfigMigrationResult.Migrated::class.java,
                    migrator.migrate(yaml(source), source.toByteArray(StandardCharsets.UTF_8)),
                )
            val actual = migrated.commentMappings.map { mapping -> mapping.source.dotted to mapping.target?.dotted }

            assertEquals(expected, actual.toSet())
            assertEquals(expected.size, actual.size)
        }

        val withoutLanguage = fixture("legacy-config/itemdropv2.yml").replace("  language: \"en-US\"\n", "")
        val migratedWithoutLanguage =
            assertInstanceOf(
                LegacyConfigMigrationResult.Migrated::class.java,
                migrator.migrate(yaml(withoutLanguage), withoutLanguage.toByteArray(StandardCharsets.UTF_8)),
            )
        assertFalse(migratedWithoutLanguage.commentMappings.any { it.source.dotted == "general.language" })
        assertEquals("zh_tw", migratedWithoutLanguage.configuration.getString("general.language"))
    }

    @Test
    fun `legacy adapter renders semantic leaf values without serialization`() {
        val template = "# raw legacy migration template\n${legacyCompatibleTemplate()}"
        val documentAdapter = adapter(template.toByteArray(StandardCharsets.UTF_8))
        val migrator = BukkitLegacyConfigMigrator(yaml(template), BukkitDisplaySettingsLoader())
        val source = fixture("legacy-config/itemdropv2.yml")
        val migration =
            assertInstanceOf(
                LegacyConfigMigrationResult.Migrated::class.java,
                migrator.migrate(yaml(source), source.toByteArray(StandardCharsets.UTF_8)),
            )

        val candidate =
            assertInstanceOf(
                YamlDocumentEditResult.Candidate::class.java,
                documentAdapter.migrateLegacy(source.toByteArray(StandardCharsets.UTF_8), migration),
            )
        val parsed = yaml(candidate.bytes.toString(StandardCharsets.UTF_8))

        assertTrue(candidate.bytes.toString(StandardCharsets.UTF_8).startsWith("# raw legacy migration template\n"))
        assertEquals(true, parsed.getBoolean("general.enabled"))
        assertEquals(30L, parsed.getLong("items.ownership.protection-seconds"))
        assertEquals(50.0, parsed.getDouble("items.ownership.entity.minimum-damage-percent-of-max-health"))
        assertEquals("maximum", parsed.getString("items.merge.lifetime-strategy"))
        assertEquals(listOf("blocked-world1", "blocked-world2"), parsed.getStringList("general.blocked-worlds"))
    }

    @Test
    fun `legacy adapter rejects every unsupported canonical JVM value before editing`() {
        val template = "# raw legacy migration template\n${legacyCompatibleTemplate()}"
        val editor = CountingYamlDocumentEditor(StrictYamlDocumentEditor())
        val documentAdapter = adapter(template.toByteArray(StandardCharsets.UTF_8), editor)
        val migrator = BukkitLegacyConfigMigrator(yaml(template), BukkitDisplaySettingsLoader())
        val source = fixture("legacy-config/itemdropv2.yml")
        unsupportedCanonicalValueCases().forEach { case ->
            val migration =
                assertInstanceOf(
                    LegacyConfigMigrationResult.Migrated::class.java,
                    migrator.migrate(yaml(source), source.toByteArray(StandardCharsets.UTF_8)),
                    case.label,
                )
            migration.configuration.set(case.path, case.value)
            assertEquals(case.storedRuntimeType, case.value.javaClass.simpleName, case.label)
            assertEquals(
                case.storedRuntimeType,
                migration.configuration
                    .get(case.path)
                    ?.javaClass
                    ?.simpleName,
                case.label,
            )
            val rejected =
                assertInstanceOf(
                    YamlDocumentEditResult.Rejected::class.java,
                    documentAdapter.migrateLegacy(source.toByteArray(StandardCharsets.UTF_8), migration),
                    case.label,
                ).rejection
            assertEquals(YamlDocumentRejectionCategory.VALIDATION, rejected.category, case.label)
            assertEquals(case.path, rejected.path?.dotted, case.label)
            assertEquals(
                "${case.path}: unsupported canonical value type ${case.runtimeType}",
                rejected.detail,
                case.label,
            )
            assertEquals(0, editor.migrationCalls, case.label)
        }
    }

    @Test
    fun `embedded template validation rejects unsafe scanner input and semantic mismatch`() {
        val mixedNewlines = validConfig().replaceFirst("\n", "\r\n").toByteArray(StandardCharsets.UTF_8)
        assertThrows(IllegalArgumentException::class.java) {
            adapter(mixedNewlines)
        }

        val raw = validConfig().toByteArray(StandardCharsets.UTF_8)
        val mismatched = yaml(validConfig().replace("enabled: true", "enabled: false"))
        assertThrows(IllegalArgumentException::class.java) {
            BukkitConfigDocumentAdapter(raw, mismatched, BukkitDisplaySettingsLoader(), StrictYamlDocumentEditor())
        }
    }

    @Test
    fun `manager requires semantic and raw defaults together and rejects mismatch`() {
        val text = validConfig()
        val configuration = yaml(text)
        val initial = loadedSettings(configuration)

        assertThrows(IllegalArgumentException::class.java) {
            BukkitItemDisplaySettingsManager(
                initialSettings = initial,
                configFile = directory.resolve("raw-only.yml").toFile(),
                defaultDocumentBytes = text.toByteArray(StandardCharsets.UTF_8),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            BukkitItemDisplaySettingsManager(
                initialSettings = initial,
                configFile = directory.resolve("semantic-only.yml").toFile(),
                defaultConfiguration = configuration,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            BukkitItemDisplaySettingsManager(
                initialSettings = initial,
                configFile = directory.resolve("mismatch.yml").toFile(),
                defaultConfiguration = configuration,
                defaultDocumentBytes = text.replace("enabled: true", "enabled: false").toByteArray(StandardCharsets.UTF_8),
            )
        }
    }

    private fun adapter(
        templateBytes: ByteArray,
        editor: YamlDocumentEditor = StrictYamlDocumentEditor(),
    ): BukkitConfigDocumentAdapter =
        BukkitConfigDocumentAdapter(
            templateBytes = templateBytes,
            defaults = yaml(templateBytes.toString(StandardCharsets.UTF_8).removePrefix("\uFEFF")),
            loader = BukkitDisplaySettingsLoader(),
            editor = editor,
        )

    private fun loadedSettings(configuration: YamlConfiguration) =
        assertInstanceOf(
            BukkitDisplaySettingsLoadResult.Loaded::class.java,
            BukkitDisplaySettingsLoader().load(configuration),
        ).settings

    private fun yaml(content: String): YamlConfiguration = YamlConfiguration().apply { loadFromString(content) }

    private fun fixture(path: String): String =
        requireNotNull(javaClass.classLoader.getResourceAsStream(path)) { "missing test fixture $path" }
            .bufferedReader(Charsets.UTF_8)
            .use { it.readText() }

    private fun legacyCompatibleTemplate(): String =
        validConfig().replace(
            "  blocked-worlds: []\n",
            "  blocked-worlds:\n    - template-world\n",
        )

    private fun withBomAndCrLf(content: String): ByteArray =
        byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) +
            content.replace("\n", "\r\n").toByteArray(StandardCharsets.UTF_8)

    private fun spaced(bytes: ByteArray): ByteArray =
        assertInstanceOf(YamlDocumentEditResult.Candidate::class.java, MappingTreeSpacingPolicy().apply(bytes)).bytes

    private fun currentKeyMoveCases(): List<CurrentKeyMoveCase> =
        listOf(ownerPrefixMoveCase(), rarityDisplayMoveCase(), creativePickupMoveCase())

    private fun ownerPrefixMoveCase(): CurrentKeyMoveCase {
        val template = validConfig()
        return CurrentKeyMoveCase(
            label = "owner display prefix",
            obsoletePath = "items.ownership.display-prefix",
            obsoleteKey = "display-prefix:",
            missingCanonicalDocument =
                template.replace(
                    OWNER_DISPLAY_BLOCK,
                    "    # owner legacy note\n" +
                        "    display-prefix: '&b%player_name%&r ' # owner legacy inline\n" +
                        "    owner-extension: keep-owner\n",
                ),
            canonicalWinsDocument =
                template
                    .replace(
                        "    display:\n",
                        "    # obsolete owner note\n" +
                            "    display-prefix: '&b%player_name%&r ' # obsolete owner inline\n" +
                            "    owner-extension: keep-owner\n" +
                            "    display:\n",
                    ).replace(
                        "      single-owner-prefix: '&7[&a%player_name%&7]&r '\n",
                        "      # canonical owner note\n" +
                            "      single-owner-prefix: '&c%player_name%&r ' # canonical owner inline\n",
                    ),
            movedSpan =
                "      # owner legacy note\r\n" +
                    "      single-owner-prefix: '&b%player_name%&r ' # owner legacy inline",
            canonicalSpan =
                "      # canonical owner note\r\n" +
                    "      single-owner-prefix: '&c%player_name%&r ' # canonical owner inline",
            unknownSibling = "    owner-extension: keep-owner",
            templatePredecessor = "      rotation-seconds: 5",
            templateSuccessor = "      multiple-owners-prefix:",
            obsoleteLeadingComment = "# obsolete owner note",
            obsoleteInlineComment = "# obsolete owner inline",
            requiresBackupAndReport = false,
        )
    }

    private fun rarityDisplayMoveCase(): CurrentKeyMoveCase {
        val template = validConfig()
        return CurrentKeyMoveCase(
            label = "rarity display enabled",
            obsoletePath = "items.item-name-rarity-display",
            obsoleteKey = "item-name-rarity-display:",
            missingCanonicalDocument =
                template.replace(
                    "  rarity-display:\n    enabled: true\n",
                    "  # rarity legacy note\n" +
                        "  item-name-rarity-display: false # rarity legacy inline\n" +
                        "  rarity-extension: keep-rarity\n",
                ),
            canonicalWinsDocument =
                template
                    .replace(
                        "  rarity-display:\n",
                        "  # obsolete rarity note\n" +
                            "  item-name-rarity-display: false # obsolete rarity inline\n" +
                            "  rarity-extension: keep-rarity\n" +
                            "  rarity-display:\n",
                    ).replace(
                        "    enabled: true\n",
                        "    # canonical rarity note\n    enabled: false # canonical rarity inline\n",
                    ),
            movedSpan = "    # rarity legacy note\r\n    enabled: false # rarity legacy inline",
            canonicalSpan = "    # canonical rarity note\r\n    enabled: false # canonical rarity inline",
            unknownSibling = "  rarity-extension: keep-rarity",
            templatePredecessor = "    maximum-items-per-tick: 256",
            templateSuccessor = "  merge:",
            obsoleteLeadingComment = "# obsolete rarity note",
            obsoleteInlineComment = "# obsolete rarity inline",
            requiresBackupAndReport = false,
        )
    }

    private fun creativePickupMoveCase(): CurrentKeyMoveCase {
        val template = validConfig()
        return CurrentKeyMoveCase(
            label = "creative no-capacity pickup",
            obsoletePath = "items.ownership.creative-full-inventory-pickup",
            obsoleteKey = "creative-full-inventory-pickup:",
            missingCanonicalDocument =
                template.replace(
                    "    creative-no-capacity-pickup: destroy\n",
                    "\n    # creative legacy note\n" +
                        "    creative-full-inventory-pickup: deny # creative legacy inline\n" +
                        "    creative-extension: keep-creative\n",
                ),
            canonicalWinsDocument =
                template.replace(
                    "    creative-no-capacity-pickup: destroy\n",
                    "    # obsolete creative note\n" +
                        "    creative-full-inventory-pickup: destroy # obsolete creative inline\n" +
                        "    creative-extension: keep-creative\n" +
                        "    # canonical creative note\n" +
                        "    creative-no-capacity-pickup: deny # canonical creative inline\n",
                ),
            movedSpan =
                "    # creative legacy note\r\n" +
                    "    creative-no-capacity-pickup: deny # creative legacy inline",
            canonicalSpan =
                "    # canonical creative note\r\n" +
                    "    creative-no-capacity-pickup: deny # canonical creative inline",
            unknownSibling = "    creative-extension: keep-creative",
            templatePredecessor = "    allow-hopper-pickup: false",
            templateSuccessor = "    pickup-warning-cooldown-seconds: 5",
            obsoleteLeadingComment = "# obsolete creative note",
            obsoleteInlineComment = "# obsolete creative inline",
            requiresBackupAndReport = true,
        )
    }

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
            creative-no-capacity-pickup: destroy
            pickup-warning-cooldown-seconds: 5
            # Keep the embedded explanation.
            pickup-warning-message-type: action-bar
          display-name-format:
            single: '%item_display_name%'
            multi: '%item_display_name% x%amount%'
          display-placeholders:
            no-owner: '無'
            lifetime-permanent: '永久'
            lifetime-unknown: '未知'
        """.trimIndent()

    private data class CurrentKeyMoveCase(
        val label: String,
        val obsoletePath: String,
        val obsoleteKey: String,
        val missingCanonicalDocument: String,
        val canonicalWinsDocument: String,
        val movedSpan: String,
        val canonicalSpan: String,
        val unknownSibling: String,
        val templatePredecessor: String,
        val templateSuccessor: String,
        val obsoleteLeadingComment: String,
        val obsoleteInlineComment: String,
        val requiresBackupAndReport: Boolean,
    ) {
        fun withNullObsolete(document: String): String =
            document.replaceFirst(
                Regex("(${Regex.escape(obsoleteKey)}\\s*)[^#\\r\\n]+"),
                "$1null ",
            )
    }

    private companion object {
        private const val LEGACY_FOOTER = "# Legacy comments preserved during migration"
        private const val OWNER_DISPLAY_BLOCK =
            "    display:\n" +
                "      rotation-seconds: 5\n" +
                "      single-owner-prefix: '&7[&a%player_name%&7]&r '\n" +
                "      multiple-owners-prefix: '&7[&a%player_name%&7]&e+%additional_owner_count%&r '\n"
        private val ITEM_DROP_V2_COMMENT_MAPPINGS =
            setOf(
                "general.enabled" to "general.enabled",
                "general.language" to "general.language",
                "general.mc-language" to "general.minecraft-language",
                "general.blocked-worlds" to "general.blocked-worlds",
                "items.owner-owns-time" to "items.ownership.protection-seconds",
                "items.hopper-pickup-owner" to "items.ownership.allow-hopper-pickup",
                "items.player-pickup-warning-message-delay" to
                    "items.ownership.pickup-warning-cooldown-seconds",
                "items.player-pickup-warning-message-type" to
                    "items.ownership.pickup-warning-message-type",
                "items.player-damage-entity-min-health-percent" to
                    "items.ownership.entity.minimum-damage-percent-of-max-health",
                "items.item-name-rarity-display" to "items.rarity-display.enabled",
                "items.item-owner-decide" to "items.ownership.entity.strategy",
                "items.item-merge-lived-time-mode" to "items.merge.lifetime-strategy",
                "items.placeholder.owner-player-prefix" to
                    "items.ownership.display.single-owner-prefix",
                "items.placeholder.item-time-left" to null,
                "items.display-name-format.single" to "items.display-name-format.single",
                "items.display-name-format.multi" to "items.display-name-format.multi",
                "general.mc-language-check-updates" to null,
                "general.mc-language-check-updates-message" to null,
                "general.mc-language-check-updates-interval" to null,
                "general.mc-language-auto-update" to null,
                "general.mc-language-auto-updates-message" to null,
                "general.mc-language-cant-find-item-in-lang-message" to null,
                "items.async" to null,
                "items.item-age-type" to null,
                "items.custom-item-death-time" to null,
                "items.item-merge-owner-time-mode" to null,
            )
        private val ITEM_DROP_COMMENT_MAPPINGS =
            setOf(
                "General.Enable" to "general.enabled",
                "General.Language" to "general.language",
                "General.MCLanguage" to "general.minecraft-language",
                "General.Blocked_Worlds" to "general.blocked-worlds",
                "Item_Hologram.Owner_Owns_Time" to "items.ownership.protection-seconds",
                "Item_Hologram.Player_Can_Not_PickUp_Item_Message_Delay" to
                    "items.ownership.pickup-warning-cooldown-seconds",
                "Item_Hologram.Player_Damage_Entity_Min_Health_Percent" to
                    "items.ownership.entity.minimum-damage-percent-of-max-health",
                "Item_Hologram.Item_Name_Rarity_Display" to "items.rarity-display.enabled",
                "Item_Hologram.Item_Owner_Judge" to "items.ownership.entity.strategy",
                "Item_Hologram.Item_Marge_Mode" to "items.merge.lifetime-strategy",
                "Item_Hologram.Owner_Player" to "items.ownership.display.single-owner-prefix",
                "Item_Hologram.Item_Display_Name" to null,
                "Item_Hologram.Display.Single" to "items.display-name-format.single",
                "Item_Hologram.Display.Multi" to "items.display-name-format.multi",
                "General.Version" to null,
            )
    }
}

private class CountingYamlDocumentEditor(
    private val delegate: YamlDocumentEditor,
) : YamlDocumentEditor {
    var migrationCalls: Int = 0
        private set

    override fun edit(request: YamlDocumentEditRequest): YamlDocumentEditResult = delegate.edit(request)

    override fun migrate(request: YamlDocumentMigrationRequest): YamlDocumentEditResult {
        migrationCalls += 1
        return delegate.migrate(request)
    }
}

private data class UnsupportedCanonicalValueCase(
    val label: String,
    val path: String,
    val value: Any,
    val storedRuntimeType: String,
    val runtimeType: String,
)

private fun unsupportedCanonicalValueCases(): List<UnsupportedCanonicalValueCase> =
    listOf(
        UnsupportedCanonicalValueCase(
            label = "nested collection",
            path = "general.blocked-worlds",
            value = arrayListOf(arrayListOf("nested")),
            storedRuntimeType = "ArrayList",
            runtimeType = "ArrayList",
        ),
        UnsupportedCanonicalValueCase(
            label = "map in collection",
            path = "general.blocked-worlds",
            value = arrayListOf(linkedMapOf("nested" to "value")),
            storedRuntimeType = "ArrayList",
            runtimeType = "LinkedHashMap",
        ),
        UnsupportedCanonicalValueCase(
            label = "out-of-range integer",
            path = "items.ownership.protection-seconds",
            value = BigInteger.valueOf(Long.MAX_VALUE).add(BigInteger.ONE),
            storedRuntimeType = "BigInteger",
            runtimeType = "BigInteger",
        ),
        UnsupportedCanonicalValueCase(
            label = "NaN",
            path = "items.ownership.entity.minimum-damage-percent-of-max-health",
            value = Double.NaN,
            storedRuntimeType = "Double",
            runtimeType = "Double",
        ),
        UnsupportedCanonicalValueCase(
            label = "positive infinity",
            path = "items.ownership.entity.minimum-damage-percent-of-max-health",
            value = Double.POSITIVE_INFINITY,
            storedRuntimeType = "Double",
            runtimeType = "Double",
        ),
        UnsupportedCanonicalValueCase(
            label = "negative infinity",
            path = "items.ownership.entity.minimum-damage-percent-of-max-health",
            value = Double.NEGATIVE_INFINITY,
            storedRuntimeType = "Double",
            runtimeType = "Double",
        ),
    )
