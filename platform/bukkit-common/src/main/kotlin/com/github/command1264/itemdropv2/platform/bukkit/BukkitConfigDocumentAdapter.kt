package com.github.command1264.itemdropv2.platform.bukkit

import com.github.command1264.itemdropv2.core.ItemDisplaySettings
import com.github.command1264.itemdropv2.platform.bukkit.yaml.MappingTreeSpacingPolicy
import com.github.command1264.itemdropv2.platform.bukkit.yaml.ScannedYamlDocument
import com.github.command1264.itemdropv2.platform.bukkit.yaml.StrictYamlDocumentScanner
import com.github.command1264.itemdropv2.platform.bukkit.yaml.YamlDocumentEditRequest
import com.github.command1264.itemdropv2.platform.bukkit.yaml.YamlDocumentEditResult
import com.github.command1264.itemdropv2.platform.bukkit.yaml.YamlDocumentEditor
import com.github.command1264.itemdropv2.platform.bukkit.yaml.YamlDocumentMigrationRequest
import com.github.command1264.itemdropv2.platform.bukkit.yaml.YamlDocumentOperation
import com.github.command1264.itemdropv2.platform.bukkit.yaml.YamlDocumentRejection
import com.github.command1264.itemdropv2.platform.bukkit.yaml.YamlDocumentRejectionCategory
import com.github.command1264.itemdropv2.platform.bukkit.yaml.YamlDocumentScanResult
import com.github.command1264.itemdropv2.platform.bukkit.yaml.YamlMappingEntry
import com.github.command1264.itemdropv2.platform.bukkit.yaml.YamlNodeValue
import com.github.command1264.itemdropv2.platform.bukkit.yaml.YamlPath
import com.github.command1264.itemdropv2.platform.bukkit.yaml.YamlScalar
import org.bukkit.configuration.InvalidConfigurationException
import org.bukkit.configuration.file.YamlConfiguration
import java.math.BigDecimal
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

internal sealed interface BukkitConfigCandidateResult {
    data class Valid(
        val bytes: ByteArray,
        val configuration: YamlConfiguration,
        val settings: ItemDisplaySettings,
    ) : BukkitConfigCandidateResult

    data class Rejected(
        val reason: String,
    ) : BukkitConfigCandidateResult
}

internal data class BukkitCurrentSchemaDocumentRepair(
    val edit: YamlDocumentEditResult,
    val configKeyMigration: ConfigKeyMigrationReport?,
    val parseRecoveryPaths: List<String> = emptyList(),
)

@Suppress("TooManyFunctions")
internal class BukkitConfigDocumentAdapter(
    templateBytes: ByteArray,
    private val defaults: YamlConfiguration,
    private val loader: BukkitDisplaySettingsLoader,
    private val editor: YamlDocumentEditor,
) {
    private val templateBytes: ByteArray = templateBytes.copyOf()
    private val scanner = StrictYamlDocumentScanner()
    private val spacingPolicy = MappingTreeSpacingPolicy()
    private val canonicalValueExtractor = CanonicalYamlValueExtractor()
    private val defaultCanonicalValues =
        when (val extracted = canonicalValueExtractor.extract(defaults)) {
            is CanonicalValuesResult.Valid -> extracted.values
            is CanonicalValuesResult.Rejected -> error("embedded config defaults cannot be rendered")
        }
    private val defaultCanonicalValuesByPath = defaultCanonicalValues.entries.associate { (path, value) -> path.dotted to value }

    init {
        val scan =
            editor.edit(
                YamlDocumentEditRequest(
                    original = this.templateBytes,
                    template = this.templateBytes,
                    operations = emptyList(),
                ),
            )
        require(scan is YamlDocumentEditResult.Candidate) {
            "embedded config document is unsafe: ${(scan as YamlDocumentEditResult.Rejected).rejection.category}"
        }
        val validation = validate(this.templateBytes)
        require(validation is BukkitConfigCandidateResult.Valid) {
            "embedded config document is invalid: ${(validation as BukkitConfigCandidateResult.Rejected).reason}"
        }
        require(configurationSnapshot(defaults) == configurationSnapshot(validation.configuration)) {
            "embedded config semantic defaults do not match raw document"
        }
    }

    fun createMissingCandidate(): YamlDocumentEditResult = spacingPolicy.apply(templateBytes)

    fun migrateLegacy(
        original: ByteArray,
        migration: LegacyConfigMigrationResult.Migrated,
    ): YamlDocumentEditResult =
        when (val values = canonicalValueExtractor.extract(migration.configuration)) {
            is CanonicalValuesResult.Rejected -> YamlDocumentEditResult.Rejected(values.rejection)
            is CanonicalValuesResult.Valid ->
                withSpacing(
                    editor.migrate(
                        YamlDocumentMigrationRequest(
                            original = original,
                            template = templateBytes,
                            canonicalValues = values.values,
                            commentMappings = migration.commentMappings,
                        ),
                    ),
                )
        }

    fun repairCurrentSchema(original: ByteArray): YamlDocumentEditResult = prepareCurrentSchemaRepair(original).edit

    fun prepareCurrentSchemaRepair(original: ByteArray): BukkitCurrentSchemaDocumentRepair =
        when (val scan = scanner.scan(original)) {
            is YamlDocumentScanResult.Rejected ->
                BukkitCurrentSchemaDocumentRepair(YamlDocumentEditResult.Rejected(scan.rejection), null)
            is YamlDocumentScanResult.Scanned -> prepareScannedCurrentSchemaRepair(original, scan.document)
        }

    @Suppress("ReturnCount")
    private fun prepareScannedCurrentSchemaRepair(
        original: ByteArray,
        document: ScannedYamlDocument,
    ): BukkitCurrentSchemaDocumentRepair {
        val applicableMoves = CURRENT_SCHEMA_KEY_MOVES.filter { move -> document.entryOrNull(move.source) != null }
        val invalidNullMove =
            applicableMoves.firstOrNull { move ->
                document.entry(move.source).isExplicitNullNode() && document.entryOrNull(move.target) == null
            }
        if (invalidNullMove != null) {
            return rejectExplicitNullMove(invalidNullMove)
        }
        val movedTargets = applicableMoves.mapTo(mutableSetOf()) { move -> move.target }
        val missingPaths =
            defaults
                .getKeys(true)
                .asSequence()
                .filterNot(defaults::isConfigurationSection)
                .map(YamlPath::parse)
                .filter { path -> document.entryOrNull(path) == null }
                .filterNot(movedTargets::contains)
                .map(YamlDocumentOperation::EnsurePath)
                .toList()
        val operations = applicableMoves.map(CurrentSchemaKeyMove::operation) + missingPaths
        val edit =
            editor.edit(
                YamlDocumentEditRequest(
                    original = original,
                    template = templateBytes,
                    operations = operations,
                ),
            )
        val reportedMove = applicableMoves.singleOrNull(CurrentSchemaKeyMove::requiresBackupAndReport)
        val baseRepair =
            BukkitCurrentSchemaDocumentRepair(
                edit = edit,
                configKeyMigration =
                    reportedMove?.let { move ->
                        ConfigKeyMigrationReport(
                            obsoletePath = move.source.dotted,
                            canonicalPath = move.target.dotted,
                            canonicalValueAlreadyPresent = document.entryOrNull(move.target) != null,
                        )
                    },
            )
        val repaired = repairInvalidKnownValues(baseRepair)
        val candidate = repaired.edit as? YamlDocumentEditResult.Candidate ?: return repaired
        return if (candidate.bytes.contentEquals(original)) repaired else repaired.copy(edit = withSpacing(candidate))
    }

    @Suppress("ReturnCount")
    private fun repairInvalidKnownValues(repair: BukkitCurrentSchemaDocumentRepair): BukkitCurrentSchemaDocumentRepair {
        val candidate = repair.edit as? YamlDocumentEditResult.Candidate ?: return repair
        val parsed = parseCandidate(candidate.bytes) as? CandidateParseResult.Parsed ?: return repair
        val invalid = loader.load(parsed.configuration) as? BukkitDisplaySettingsLoadResult.Invalid ?: return repair
        val reportedPaths = invalid.errors.map(::repairableDefaultPath)
        if (reportedPaths.any { it == null }) return repair
        val paths = reportedPaths.filterNotNull().distinct()
        if (paths.isEmpty()) return repair
        val operations =
            paths.map { rawPath ->
                val path = YamlPath.parse(rawPath)
                YamlDocumentOperation.SetValue(path, requireNotNull(defaultCanonicalValuesByPath[rawPath]))
            }
        return repair.copy(
            edit =
                editor.edit(
                    YamlDocumentEditRequest(
                        original = candidate.bytes,
                        template = templateBytes,
                        operations = operations,
                    ),
                ),
            parseRecoveryPaths = paths,
        )
    }

    @Suppress("ReturnCount")
    private fun repairableDefaultPath(error: String): String? {
        val reported = error.substringBefore(':', missingDelimiterValue = "").trim()
        if (reported.isEmpty()) return null
        val normalized = reported.replace(TRAILING_SEQUENCE_INDEX, "")
        if (normalized == SCHEMA_VERSION_PATH) return null
        if (loader.rejectsAutomaticRepair(normalized)) return null
        return normalized.takeIf(defaultCanonicalValuesByPath::containsKey)
    }

    private fun rejectExplicitNullMove(move: CurrentSchemaKeyMove): BukkitCurrentSchemaDocumentRepair =
        BukkitCurrentSchemaDocumentRepair(
            edit =
                YamlDocumentEditResult.Rejected(
                    YamlDocumentRejection(
                        category = YamlDocumentRejectionCategory.VALIDATION,
                        path = move.source,
                        detail = "${move.source.dotted}: explicit null cannot migrate to a missing canonical path",
                    ),
                ),
            configKeyMigration = null,
        )

    fun setEnabled(
        original: ByteArray,
        enabled: Boolean,
    ): YamlDocumentEditResult =
        editor.edit(
            YamlDocumentEditRequest(
                original = original,
                template = templateBytes,
                operations =
                    listOf(
                        YamlDocumentOperation.SetScalar(
                            path = YamlPath.parse(ENABLED_PATH),
                            value = YamlScalar.BooleanValue(enabled),
                        ),
                    ),
            ),
        )

    fun createMissingAndSetEnabledCandidate(enabled: Boolean): YamlDocumentEditResult =
        when (val created = createMissingCandidate()) {
            is YamlDocumentEditResult.Rejected -> created
            is YamlDocumentEditResult.Candidate -> setEnabled(created.bytes, enabled)
        }

    fun repairAndSetEnabled(
        original: ByteArray,
        enabled: Boolean,
    ): BukkitCurrentSchemaDocumentRepair {
        val repair = prepareCurrentSchemaRepair(original)
        val edit =
            when (val repaired = repair.edit) {
                is YamlDocumentEditResult.Rejected -> repaired
                is YamlDocumentEditResult.Candidate -> setEnabled(repaired.bytes, enabled)
            }
        return repair.copy(edit = edit)
    }

    private fun withSpacing(result: YamlDocumentEditResult): YamlDocumentEditResult =
        when (result) {
            is YamlDocumentEditResult.Rejected -> result
            is YamlDocumentEditResult.Candidate -> spacingPolicy.apply(result.bytes)
        }

    fun validate(candidate: ByteArray): BukkitConfigCandidateResult =
        when (val parsed = parseCandidate(candidate)) {
            is CandidateParseResult.Rejected -> BukkitConfigCandidateResult.Rejected(parsed.reason)
            is CandidateParseResult.Parsed ->
                when (val loaded = loader.load(parsed.configuration)) {
                    is BukkitDisplaySettingsLoadResult.Loaded ->
                        BukkitConfigCandidateResult.Valid(
                            bytes = candidate.copyOf(),
                            configuration = parsed.configuration,
                            settings = loaded.settings,
                        )
                    is BukkitDisplaySettingsLoadResult.Invalid ->
                        BukkitConfigCandidateResult.Rejected(loaded.errors.joinToString("; "))
                }
        }

    private fun parseCandidate(candidate: ByteArray): CandidateParseResult =
        try {
            val text = decodeStrictUtf8(candidate)
            try {
                CandidateParseResult.Parsed(YamlConfiguration().apply { loadFromString(text) })
            } catch (_: InvalidConfigurationException) {
                CandidateParseResult.Rejected("candidate is invalid YAML")
            }
        } catch (_: CharacterCodingException) {
            CandidateParseResult.Rejected("candidate is not strict UTF-8")
        }

    private fun configurationSnapshot(configuration: YamlConfiguration): Map<String, ConfigurationValue> =
        configuration.getKeys(true).associateWith { path ->
            if (configuration.isConfigurationSection(path)) {
                ConfigurationValue.Section
            } else {
                ConfigurationValue.Leaf(configuration.get(path))
            }
        }

    private sealed interface ConfigurationValue {
        data object Section : ConfigurationValue

        data class Leaf(
            val value: Any?,
        ) : ConfigurationValue
    }

    private sealed interface CandidateParseResult {
        data class Parsed(
            val configuration: YamlConfiguration,
        ) : CandidateParseResult

        data class Rejected(
            val reason: String,
        ) : CandidateParseResult
    }

    private companion object {
        const val ENABLED_PATH = "general.enabled"
        const val SCHEMA_VERSION_PATH = "schema-version"
        val TRAILING_SEQUENCE_INDEX = Regex("\\[\\d+]$")
        val CURRENT_SCHEMA_KEY_MOVES =
            listOf(
                CurrentSchemaKeyMove(
                    source = YamlPath.parse("items.ownership.display-prefix"),
                    target = YamlPath.parse("items.ownership.display.single-owner-prefix"),
                ),
                CurrentSchemaKeyMove(
                    source = YamlPath.parse("items.item-name-rarity-display"),
                    target = YamlPath.parse("items.rarity-display.enabled"),
                ),
                CurrentSchemaKeyMove(
                    source = YamlPath.parse("items.ownership.creative-full-inventory-pickup"),
                    target = YamlPath.parse("items.ownership.creative-no-capacity-pickup"),
                    requiresBackupAndReport = true,
                ),
            )

        @Throws(CharacterCodingException::class)
        fun decodeStrictUtf8(bytes: ByteArray): String =
            StandardCharsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
    }
}

private class CanonicalYamlValueExtractor {
    fun extract(configuration: YamlConfiguration): CanonicalValuesResult {
        val values = linkedMapOf<YamlPath, YamlNodeValue>()
        configuration
            .getKeys(true)
            .asSequence()
            .filterNot(configuration::isConfigurationSection)
            .forEach { rawPath ->
                val path = YamlPath.parse(rawPath)
                when (val converted = nodeValue(path, configuration.get(rawPath))) {
                    is NodeValueResult.Rejected -> return CanonicalValuesResult.Rejected(converted.rejection)
                    is NodeValueResult.Valid -> values[path] = converted.value
                }
            }
        return CanonicalValuesResult.Valid(values)
    }

    private fun nodeValue(
        path: YamlPath,
        value: Any?,
    ): NodeValueResult =
        when (value) {
            is Collection<*> -> scalarSequence(path, value)
            else ->
                when (val scalar = scalarValue(path, value)) {
                    is ScalarValueResult.Rejected -> NodeValueResult.Rejected(scalar.rejection)
                    is ScalarValueResult.Valid -> NodeValueResult.Valid(YamlNodeValue.Scalar(scalar.value))
                }
        }

    private fun scalarSequence(
        path: YamlPath,
        values: Collection<*>,
    ): NodeValueResult {
        val converted = mutableListOf<YamlScalar>()
        values.forEach { value ->
            when (val scalar = scalarValue(path, value)) {
                is ScalarValueResult.Rejected -> return NodeValueResult.Rejected(scalar.rejection)
                is ScalarValueResult.Valid -> converted += scalar.value
            }
        }
        return NodeValueResult.Valid(YamlNodeValue.ScalarSequence(converted))
    }

    private fun scalarValue(
        path: YamlPath,
        value: Any?,
    ): ScalarValueResult =
        when (value) {
            is Boolean -> ScalarValueResult.Valid(YamlScalar.BooleanValue(value))
            is Byte -> ScalarValueResult.Valid(YamlScalar.IntegerValue(value.toLong()))
            is Short -> ScalarValueResult.Valid(YamlScalar.IntegerValue(value.toLong()))
            is Int -> ScalarValueResult.Valid(YamlScalar.IntegerValue(value.toLong()))
            is Long -> ScalarValueResult.Valid(YamlScalar.IntegerValue(value))
            is BigInteger -> exactInteger(path, value)
            is Float -> decimal(path, value, value.isFinite())
            is Double -> decimal(path, value, value.isFinite())
            is BigDecimal -> ScalarValueResult.Valid(YamlScalar.DecimalValue(value))
            is String -> ScalarValueResult.Valid(YamlScalar.StringValue(value))
            else -> rejectScalar(path, value)
        }

    private fun exactInteger(
        path: YamlPath,
        value: BigInteger,
    ): ScalarValueResult =
        try {
            ScalarValueResult.Valid(YamlScalar.IntegerValue(value.longValueExact()))
        } catch (_: ArithmeticException) {
            rejectScalar(path, value)
        }

    private fun decimal(
        path: YamlPath,
        value: Number,
        finite: Boolean,
    ): ScalarValueResult =
        if (finite) {
            ScalarValueResult.Valid(YamlScalar.DecimalValue(BigDecimal(value.toString())))
        } else {
            rejectScalar(path, value)
        }

    private fun rejectScalar(
        path: YamlPath,
        value: Any?,
    ): ScalarValueResult.Rejected =
        ScalarValueResult.Rejected(
            YamlDocumentRejection(
                category = YamlDocumentRejectionCategory.VALIDATION,
                path = path,
                detail = "${path.dotted}: unsupported canonical value type ${value?.javaClass?.simpleName ?: "null"}",
            ),
        )
}

private sealed interface CanonicalValuesResult {
    data class Valid(
        val values: Map<YamlPath, YamlNodeValue>,
    ) : CanonicalValuesResult

    data class Rejected(
        val rejection: YamlDocumentRejection,
    ) : CanonicalValuesResult
}

private sealed interface NodeValueResult {
    data class Valid(
        val value: YamlNodeValue,
    ) : NodeValueResult

    data class Rejected(
        val rejection: YamlDocumentRejection,
    ) : NodeValueResult
}

private sealed interface ScalarValueResult {
    data class Valid(
        val value: YamlScalar,
    ) : ScalarValueResult

    data class Rejected(
        val rejection: YamlDocumentRejection,
    ) : ScalarValueResult
}

private fun YamlMappingEntry.isExplicitNullNode(): Boolean =
    valueText.isNullOrEmpty() || valueText.equals("null", ignoreCase = true) || valueText == "~"

private data class CurrentSchemaKeyMove(
    val source: YamlPath,
    val target: YamlPath,
    val requiresBackupAndReport: Boolean = false,
) {
    val operation: YamlDocumentOperation.MovePath = YamlDocumentOperation.MovePath(source, target)
}
