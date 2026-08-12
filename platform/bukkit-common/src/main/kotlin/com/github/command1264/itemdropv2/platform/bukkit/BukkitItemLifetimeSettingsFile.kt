package com.github.command1264.itemdropv2.platform.bukkit

import com.github.command1264.itemdropv2.core.ItemLifetimeSettings
import com.github.command1264.itemdropv2.platform.bukkit.yaml.MappingTreeSpacingPolicy
import com.github.command1264.itemdropv2.platform.bukkit.yaml.ScannedYamlDocument
import com.github.command1264.itemdropv2.platform.bukkit.yaml.StrictYamlDocumentEditor
import com.github.command1264.itemdropv2.platform.bukkit.yaml.StrictYamlDocumentScanner
import com.github.command1264.itemdropv2.platform.bukkit.yaml.YamlDocumentCandidateValidator
import com.github.command1264.itemdropv2.platform.bukkit.yaml.YamlDocumentEditRequest
import com.github.command1264.itemdropv2.platform.bukkit.yaml.YamlDocumentEditResult
import com.github.command1264.itemdropv2.platform.bukkit.yaml.YamlDocumentFileUpdater
import com.github.command1264.itemdropv2.platform.bukkit.yaml.YamlDocumentOperation
import com.github.command1264.itemdropv2.platform.bukkit.yaml.YamlDocumentRejection
import com.github.command1264.itemdropv2.platform.bukkit.yaml.YamlDocumentRejectionCategory
import com.github.command1264.itemdropv2.platform.bukkit.yaml.YamlDocumentScanResult
import com.github.command1264.itemdropv2.platform.bukkit.yaml.YamlDocumentUpdateResult
import com.github.command1264.itemdropv2.platform.bukkit.yaml.YamlDocumentValidationResult
import com.github.command1264.itemdropv2.platform.bukkit.yaml.YamlNodeValue
import com.github.command1264.itemdropv2.platform.bukkit.yaml.YamlPath
import com.github.command1264.itemdropv2.platform.bukkit.yaml.YamlScalar
import org.bukkit.configuration.InvalidConfigurationException
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.StandardOpenOption

@Suppress("TooManyFunctions")
internal class BukkitItemLifetimeSettingsFile(
    private val file: File,
    private val defaults: YamlConfiguration?,
    private val loader: BukkitItemLifetimeSettingsLoader,
    defaultDocumentBytes: ByteArray? = null,
    private val backupTimestamp: () -> String = AdministratorTimestampFormatter::formatNow,
) {
    private val templateBytes = defaultDocumentBytes?.copyOf() ?: defaults?.saveToString()?.toByteArray(StandardCharsets.UTF_8)
    private val scanner = StrictYamlDocumentScanner()
    private val editor = StrictYamlDocumentEditor()
    private val spacing = MappingTreeSpacingPolicy()
    private val updater = YamlDocumentFileUpdater()

    @Suppress("CyclomaticComplexMethod", "ReturnCount")
    fun loadAndRepair(): LifetimeFileLoadResult {
        val template = templateBytes ?: return LifetimeFileLoadResult.Failed("item-lifetime.yml embedded defaults are unavailable")
        val templateDocument = scanned(template) ?: return LifetimeFileLoadResult.Failed("embedded item-lifetime.yml is invalid")
        val embedded = defaults ?: return LifetimeFileLoadResult.Failed("item-lifetime.yml embedded defaults are unavailable")
        if (loader.load(embedded) is BukkitItemLifetimeSettingsLoadResult.Invalid) {
            return LifetimeFileLoadResult.Failed("embedded item-lifetime.yml is invalid")
        }
        Files.createDirectories(file.toPath().toAbsolutePath().parent)

        if (!file.isFile) {
            val candidate = spaced(template) ?: return LifetimeFileLoadResult.Failed(SPACING_FAILURE)
            return persist(candidate, backupOriginal = null, repairedPaths = emptyList(), documentRecreated = false)
        }
        val original =
            try {
                Files.readAllBytes(file.toPath())
            } catch (error: IOException) {
                return LifetimeFileLoadResult.Failed("item-lifetime.yml read failed (${error.javaClass.simpleName})")
            }
        val originalDocument = scanned(original)
        val originalConfiguration = loadConfiguration(original)
        if (originalConfiguration == null) {
            val candidate = spaced(template) ?: return LifetimeFileLoadResult.Failed(SPACING_FAILURE)
            return persist(candidate, backupOriginal = original, repairedPaths = emptyList(), documentRecreated = true)
        }
        if (originalDocument == null) return LifetimeFileLoadResult.Failed("item-lifetime.yml structure cannot be repaired safely")
        if (!originalConfiguration.isInt(SCHEMA_VERSION_PATH) || originalConfiguration.getInt(SCHEMA_VERSION_PATH) != SCHEMA_VERSION) {
            return loadedOrFailed(originalConfiguration)
        }
        return repairCurrentSchema(original, originalDocument, template, templateDocument, embedded)
    }

    @Suppress("ReturnCount")
    private fun repairCurrentSchema(
        original: ByteArray,
        originalDocument: ScannedYamlDocument,
        template: ByteArray,
        templateDocument: ScannedYamlDocument,
        embedded: YamlConfiguration,
    ): LifetimeFileLoadResult {
        val operations = missingOperations(originalDocument, templateDocument).toMutableList()
        val initialBytes =
            when (val initialEdit = edit(original, template, operations)) {
                is YamlDocumentEditResult.Candidate -> initialEdit.bytes
                is YamlDocumentEditResult.Rejected ->
                    return LifetimeFileLoadResult.Failed(
                        repairRejection("item-lifetime.yml structure cannot be repaired safely", initialEdit),
                    )
            }
        val initialConfiguration =
            loadConfiguration(initialBytes) ?: return LifetimeFileLoadResult.Failed("item-lifetime.yml repair is invalid")
        val invalid = loader.load(initialConfiguration) as? BukkitItemLifetimeSettingsLoadResult.Invalid
        if (operations.isEmpty() && invalid == null) return loadedOrFailed(initialConfiguration)
        var backup: ByteArray? = null
        var candidate = initialBytes
        if (invalid != null) {
            val valueRepairs =
                valueRepairOperations(invalid.errors, initialConfiguration, embedded)
                    ?: return LifetimeFileLoadResult.Failed(invalid.errors.joinToString("; "))
            candidate =
                when (val repaired = edit(initialBytes, template, valueRepairs)) {
                    is YamlDocumentEditResult.Candidate -> repaired.bytes
                    is YamlDocumentEditResult.Rejected ->
                        return LifetimeFileLoadResult.Failed(
                            repairRejection("item-lifetime.yml values cannot be repaired safely", repaired),
                        )
                }
            backup = original
        }
        candidate = spaced(candidate) ?: return LifetimeFileLoadResult.Failed(SPACING_FAILURE)
        return if (candidate.contentEquals(original)) {
            loadedOrFailed(initialConfiguration)
        } else {
            persist(
                candidate,
                backup,
                repairedPaths = invalid?.errors?.map { it.substringBefore(':') }.orEmpty(),
                documentRecreated = false,
            )
        }
    }

    private fun missingOperations(
        original: ScannedYamlDocument,
        template: ScannedYamlDocument,
    ): List<YamlDocumentOperation> {
        val missing = template.entries.map { it.path }.filter { original.entryOrNull(it) == null }
        return missing
            .filter { path -> path.parent()?.let { parent -> missing.any { it == parent } } != true }
            .map(YamlDocumentOperation::EnsurePath)
    }

    @Suppress("ReturnCount")
    private fun valueRepairOperations(
        errors: List<String>,
        configuration: YamlConfiguration,
        embedded: YamlConfiguration,
    ): List<YamlDocumentOperation>? {
        val normalizedNames = mutableSetOf<String>()
        configuration.getConfigurationSection(MATERIALS_PATH)?.getKeys(false)?.forEach { raw ->
            val normalized = raw.uppercase()
            if (!normalized.matches(MATERIAL_NAME_PATTERN) || !normalizedNames.add(normalized)) return null
        }
        val defaultSeconds =
            configuration.getLong(DEFAULT_SECONDS_PATH).takeIf {
                (configuration.isLong(DEFAULT_SECONDS_PATH) || configuration.isInt(DEFAULT_SECONDS_PATH)) && it >= -1L
            }
                ?: embedded.getLong(DEFAULT_SECONDS_PATH)
        return errors.map { error ->
            val path = error.substringBefore(':')
            when {
                path == DEFAULT_SECONDS_PATH -> setInteger(path, embedded.getLong(DEFAULT_SECONDS_PATH))
                path.startsWith("$MATERIALS_PATH.") && error.contains("expected -1, 0, or a positive") -> setInteger(path, defaultSeconds)
                path.startsWith("$MATERIALS_PATH.") && error.contains("expected integer") -> setInteger(path, defaultSeconds)
                else -> return null
            }
        }
    }

    private fun setInteger(
        path: String,
        value: Long,
    ): YamlDocumentOperation = YamlDocumentOperation.SetValue(YamlPath.parse(path), YamlNodeValue.Scalar(YamlScalar.IntegerValue(value)))

    private fun edit(
        original: ByteArray,
        template: ByteArray,
        operations: List<YamlDocumentOperation>,
    ): YamlDocumentEditResult = editor.edit(YamlDocumentEditRequest(original, template, operations))

    private fun repairRejection(
        summary: String,
        rejected: YamlDocumentEditResult.Rejected,
    ): String = "$summary (${rejected.rejection.category.name.lowercase()}: ${rejected.rejection.detail})"

    private fun spaced(bytes: ByteArray): ByteArray? =
        when (val result = spacing.apply(bytes)) {
            is YamlDocumentEditResult.Candidate -> result.bytes
            is YamlDocumentEditResult.Rejected -> null
        }

    @Suppress("TooGenericExceptionCaught")
    private fun persist(
        candidate: ByteArray,
        backupOriginal: ByteArray?,
        repairedPaths: List<String>,
        documentRecreated: Boolean,
    ): LifetimeFileLoadResult {
        val backup = if (backupOriginal == null) null else createBackup(backupOriginal)
        if (backup is BackupCreation.Failed) return LifetimeFileLoadResult.Failed(backup.reason)
        return try {
            when (val updated = updater.update(file.toPath(), candidate, YamlDocumentCandidateValidator(::validateCandidate))) {
                is YamlDocumentUpdateResult.Updated ->
                    when (val loaded = loadedOrFailed(requireNotNull(loadConfiguration(updated.bytes)))) {
                        is LifetimeFileLoadResult.Failed -> loaded
                        is LifetimeFileLoadResult.Loaded ->
                            loaded.copy(
                                recoveryReport =
                                    (backup as? BackupCreation.Created)?.let { created ->
                                        ConfigParseRecoveryReport(
                                            repairedPaths = repairedPaths,
                                            documentRecreated = documentRecreated,
                                            backupFileName = created.fileName,
                                            fileName = file.name,
                                        )
                                    },
                            )
                    }
                is YamlDocumentUpdateResult.Rejected -> LifetimeFileLoadResult.Failed(updated.rejection.detail)
            }
        } catch (error: IOException) {
            LifetimeFileLoadResult.Failed("item-lifetime.yml write failed (${error.javaClass.simpleName})")
        } catch (error: RuntimeException) {
            LifetimeFileLoadResult.Failed("item-lifetime.yml write failed (${error.javaClass.simpleName})")
        }
    }

    private fun validateCandidate(bytes: ByteArray): YamlDocumentValidationResult {
        val configuration =
            loadConfiguration(bytes)
                ?: return invalid("item-lifetime.yml candidate is invalid YAML")
        return when (val loaded = loader.load(configuration)) {
            is BukkitItemLifetimeSettingsLoadResult.Loaded -> YamlDocumentValidationResult.Valid
            is BukkitItemLifetimeSettingsLoadResult.Invalid -> invalid(loaded.errors.joinToString("; ").take(MAX_REJECTION_DETAIL_LENGTH))
        }
    }

    private fun loadedOrFailed(configuration: YamlConfiguration): LifetimeFileLoadResult =
        when (val loaded = loader.load(configuration)) {
            is BukkitItemLifetimeSettingsLoadResult.Loaded -> LifetimeFileLoadResult.Loaded(loaded.settings)
            is BukkitItemLifetimeSettingsLoadResult.Invalid -> LifetimeFileLoadResult.Failed(loaded.errors.joinToString("; "))
        }

    @Suppress("ReturnCount")
    private fun createBackup(original: ByteArray): BackupCreation {
        repeat(MAX_BACKUP_NAME_ATTEMPTS) { suffix ->
            val timestamp = backupTimestamp()
            val extra = if (suffix == 0) "" else "-$suffix"
            val backup = file.toPath().resolveSibling("${file.name}.parse-recovery-$timestamp$extra.bak")
            try {
                Files.write(backup, original, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
                return BackupCreation.Created(backup.fileName.toString())
            } catch (_: FileAlreadyExistsException) {
                // Try a bounded suffix without overwriting an earlier recovery artifact.
            } catch (error: IOException) {
                return BackupCreation.Failed("item-lifetime.yml backup failed (${error.javaClass.simpleName})")
            }
        }
        return BackupCreation.Failed("item-lifetime.yml backup failed (name collision)")
    }

    private fun scanned(bytes: ByteArray): ScannedYamlDocument? = (scanner.scan(bytes) as? YamlDocumentScanResult.Scanned)?.document

    private fun loadConfiguration(bytes: ByteArray): YamlConfiguration? =
        try {
            YamlConfiguration().apply { loadFromString(bytes.toString(StandardCharsets.UTF_8).removePrefix("\uFEFF")) }
        } catch (_: InvalidConfigurationException) {
            null
        }

    private fun invalid(detail: String): YamlDocumentValidationResult.Invalid =
        YamlDocumentValidationResult.Invalid(
            YamlDocumentRejection(YamlDocumentRejectionCategory.VALIDATION, detail = detail),
        )

    private companion object {
        private const val SCHEMA_VERSION_PATH = "schema-version"
        private const val DEFAULT_SECONDS_PATH = "default-seconds"
        private const val MATERIALS_PATH = "materials"
        private const val SCHEMA_VERSION = 1
        private const val MAX_REJECTION_DETAIL_LENGTH = 160
        private const val MAX_BACKUP_NAME_ATTEMPTS = 100
        private const val SPACING_FAILURE = "item-lifetime.yml mapping spacing cannot be applied safely"
        private val MATERIAL_NAME_PATTERN = Regex("[A-Z0-9_]+")
    }
}

internal sealed interface LifetimeFileLoadResult {
    data class Loaded(
        val settings: ItemLifetimeSettings,
        val recoveryReport: ConfigParseRecoveryReport? = null,
    ) : LifetimeFileLoadResult

    data class Failed(
        val reason: String,
    ) : LifetimeFileLoadResult
}

private sealed interface BackupCreation {
    data class Created(
        val fileName: String,
    ) : BackupCreation

    data class Failed(
        val reason: String,
    ) : BackupCreation
}
