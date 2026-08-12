package com.github.command1264.itemdropv2.platform.bukkit

import com.github.command1264.itemdropv2.core.ItemDisplayPlaceholderSettings
import com.github.command1264.itemdropv2.core.PluginMessageLanguage
import com.github.command1264.itemdropv2.platform.bukkit.yaml.MappingTreeSpacingPolicy
import com.github.command1264.itemdropv2.platform.bukkit.yaml.ScannedYamlDocument
import com.github.command1264.itemdropv2.platform.bukkit.yaml.StrictYamlDocumentEditor
import com.github.command1264.itemdropv2.platform.bukkit.yaml.StrictYamlDocumentScanner
import com.github.command1264.itemdropv2.platform.bukkit.yaml.YamlDocumentEditRequest
import com.github.command1264.itemdropv2.platform.bukkit.yaml.YamlDocumentEditResult
import com.github.command1264.itemdropv2.platform.bukkit.yaml.YamlDocumentOperation
import com.github.command1264.itemdropv2.platform.bukkit.yaml.YamlDocumentScanResult
import com.github.command1264.itemdropv2.platform.bukkit.yaml.YamlNodeValue
import com.github.command1264.itemdropv2.platform.bukkit.yaml.YamlPath
import com.github.command1264.itemdropv2.platform.bukkit.yaml.YamlScalar
import org.bukkit.ChatColor
import org.bukkit.configuration.InvalidConfigurationException
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.concurrent.atomic.AtomicReference

public sealed interface BukkitMessageCatalogReloadResult {
    public data object Applied : BukkitMessageCatalogReloadResult

    public data class Failed(
        public val reason: String,
    ) : BukkitMessageCatalogReloadResult
}

@Suppress("TooManyFunctions")
public class BukkitMessageCatalogStore internal constructor(
    private val directory: File,
    private val embeddedDefaults: Map<PluginMessageLanguage, YamlConfiguration>,
    embeddedDefaultBytes: Map<PluginMessageLanguage, ByteArray> =
        embeddedDefaults.mapValues { (_, configuration) -> configuration.saveToString().toByteArray(StandardCharsets.UTF_8) },
    private val recoveryReporter: (ConfigParseRecoveryReport) -> Unit = {},
    private val backupTimestamp: () -> String = AdministratorTimestampFormatter::formatNow,
) {
    private val embeddedDefaultBytes = embeddedDefaultBytes.mapValues { (_, bytes) -> bytes.copyOf() }
    private val activeCatalogs = AtomicReference<Map<PluginMessageLanguage, Map<String, String>>>(emptyMap())
    private val generatedFallbackLanguage = AtomicReference<PluginMessageLanguage?>(null)
    private val pendingRecoveryReports = AtomicReference<List<ConfigParseRecoveryReport>>(emptyList())
    private val scanner = StrictYamlDocumentScanner()
    private val editor = StrictYamlDocumentEditor()
    private val spacing = MappingTreeSpacingPolicy()

    @Suppress("TooGenericExceptionCaught")
    public fun reload(selectedLanguage: PluginMessageLanguage): BukkitMessageCatalogReloadResult {
        generatedFallbackLanguage.set(null)
        val languages = (PluginMessageLanguage.BUILT_IN + selectedLanguage).distinct()
        val candidates =
            try {
                languages.map(::prepareCandidate)
            } catch (error: MessageCatalogException) {
                return BukkitMessageCatalogReloadResult.Failed(error.message.orEmpty())
            }
        return try {
            var recoveryReports = emptyList<ConfigParseRecoveryReport>()
            commitCandidates(candidates) { backupNames ->
                recoveryReports =
                    candidates.mapNotNull { candidate ->
                        if (candidate.backupKind != BackupKind.PARSE_RECOVERY) return@mapNotNull null
                        ConfigParseRecoveryReport(
                            repairedPaths = candidate.repairedPaths,
                            documentRecreated = candidate.documentRecreated,
                            backupFileName = requireNotNull(backupNames[candidate.language]),
                            fileName = candidate.file.name,
                        )
                    }
                recoveryReports.forEach(recoveryReporter)
            }
            activeCatalogs.set(candidates.associate { it.language to it.messages })
            pendingRecoveryReports.set(recoveryReports)
            candidates
                .firstOrNull { it.language == selectedLanguage && it.created && selectedLanguage !in PluginMessageLanguage.BUILT_IN }
                ?.let { generatedFallbackLanguage.set(selectedLanguage) }
            BukkitMessageCatalogReloadResult.Applied
        } catch (error: IOException) {
            BukkitMessageCatalogReloadResult.Failed("language file write failed (${error.javaClass.simpleName})")
        } catch (error: RuntimeException) {
            BukkitMessageCatalogReloadResult.Failed("language file write failed (${error.javaClass.simpleName})")
        }
    }

    public fun consumeGeneratedFallbackLanguage(): PluginMessageLanguage? = generatedFallbackLanguage.getAndSet(null)

    public fun consumeRecoveryReports(): List<ConfigParseRecoveryReport> = pendingRecoveryReports.getAndSet(emptyList())

    public fun render(
        language: PluginMessageLanguage,
        path: String,
        placeholders: Map<String, String> = emptyMap(),
    ): String {
        val template = requireNotNull(activeCatalogs.get()[language]?.get(path)) { "missing message ${language.code}:$path" }
        return ChatColor.translateAlternateColorCodes(
            '&',
            placeholders.entries.fold(template) { text, (name, value) -> text.replace("%$name%", value) },
        )
    }

    public fun displayPlaceholders(language: PluginMessageLanguage): ItemDisplayPlaceholderSettings =
        ItemDisplayPlaceholderSettings(
            noOwner = render(language, DISPLAY_NO_OWNER_PATH),
            lifetimePermanent = render(language, DISPLAY_LIFETIME_PERMANENT_PATH),
            lifetimeUnknown = render(language, DISPLAY_LIFETIME_UNKNOWN_PATH),
        )

    @Suppress("CyclomaticComplexMethod", "LongMethod", "ThrowsCount")
    private fun prepareCandidate(language: PluginMessageLanguage): CatalogCandidate {
        val file = File(directory, "${language.code}.yml")
        val legacyFile = File(directory, "messages_${language.code}.yml")
        val sourceFile = file.takeIf(File::isFile) ?: legacyFile.takeIf(File::isFile)
        val defaults = embeddedDefaults[language] ?: requireNotNull(embeddedDefaults[PluginMessageLanguage.EN_US])
        val template = embeddedDefaultBytes[language] ?: requireNotNull(embeddedDefaultBytes[PluginMessageLanguage.EN_US])
        val templateDocument = scanned(template) ?: throw MessageCatalogException("embedded ${language.code}.yml cannot be safely scanned")
        val original = sourceFile?.let(::readFile)
        var backupKind: BackupKind? = null
        var repairedPaths = emptyList<String>()
        var documentRecreated = false
        var candidate = original ?: spaced(template)
        var changed = original == null || sourceFile != file

        val originalDocument = original?.let(::scanned)
        val originalConfiguration = original?.let(::loadConfiguration)
        if (original != null && originalConfiguration == null) {
            candidate = spaced(template)
            backupKind = BackupKind.PARSE_RECOVERY
            documentRecreated = true
            changed = true
        } else if (original != null && originalDocument == null) {
            throw MessageCatalogException("${sourceFile.name}: YAML structure cannot be repaired safely")
        } else if (originalDocument != null && originalConfiguration != null) {
            val missing = requiredMessagePaths.filter { originalDocument.entryOrNull(YamlPath.parse(it)) == null }
            if (missing.isNotEmpty()) {
                candidate = edit(candidate, template, missing.map { YamlDocumentOperation.EnsurePath(YamlPath.parse(it)) })
                changed = true
            }
            val repairedConfiguration =
                loadConfiguration(candidate) ?: throw MessageCatalogException("${sourceFile.name}: repaired YAML is invalid")
            val invalidPaths = requiredMessagePaths.filter { path -> messageValidation(repairedConfiguration, path) != null }
            if (invalidPaths.isNotEmpty()) {
                val repairs =
                    invalidPaths.map { path ->
                        val fallback = requireNotNull(defaults.getString(path))
                        YamlDocumentOperation.SetValue(
                            YamlPath.parse(path),
                            YamlNodeValue.Scalar(YamlScalar.StringValue(fallback)),
                        )
                    }
                candidate = edit(candidate, template, repairs)
                backupKind = BackupKind.PARSE_RECOVERY
                repairedPaths = invalidPaths
                changed = true
            }
            if (changed) candidate = spaced(candidate)
        }

        val configuration = loadConfiguration(candidate) ?: throw MessageCatalogException("${file.name}: invalid YAML")
        val messages = requiredMessagePaths.associateWith { path -> readMessage(configuration, path, file.name) }
        if (sourceFile == legacyFile && backupKind == null) backupKind = BackupKind.LEGACY_MIGRATION
        return CatalogCandidate(
            language = language,
            file = file,
            bytes = candidate,
            messages = messages,
            requiresWrite = changed || original?.contentEquals(candidate) == false,
            created = sourceFile == null,
            sourceFile = sourceFile,
            originalBytes = original,
            legacyFileToDelete = legacyFile.takeIf { sourceFile == it },
            backupKind = backupKind,
            repairedPaths = repairedPaths,
            documentRecreated = documentRecreated,
        )
    }

    private fun edit(
        original: ByteArray,
        template: ByteArray,
        operations: List<YamlDocumentOperation>,
    ): ByteArray =
        when (val result = editor.edit(YamlDocumentEditRequest(original, template, operations))) {
            is YamlDocumentEditResult.Candidate -> result.bytes
            is YamlDocumentEditResult.Rejected -> throw MessageCatalogException(
                "language YAML repair rejected (${result.rejection.category})",
            )
        }

    @Suppress("TooGenericExceptionCaught")
    private fun commitCandidates(
        candidates: List<CatalogCandidate>,
        onCommitted: (Map<PluginMessageLanguage, String>) -> Unit,
    ) {
        Files.createDirectories(directory.toPath())
        val backupNames =
            candidates
                .filter { it.backupKind != null && it.originalBytes != null }
                .associate { candidate -> candidate.language to createBackup(candidate) }
        val snapshots = snapshotFiles(candidates)
        val written = mutableListOf<CatalogCandidate>()
        val deletedLegacyFiles = mutableListOf<File>()
        try {
            candidates.filter(CatalogCandidate::requiresWrite).forEach { candidate ->
                writeCandidate(candidate)
                written += candidate
            }
            candidates.mapNotNull(CatalogCandidate::legacyFileToDelete).forEach { legacy ->
                if (Files.deleteIfExists(legacy.toPath())) deletedLegacyFiles += legacy
            }
            onCommitted(backupNames)
        } catch (error: Exception) {
            val rollbackFailures = mutableListOf<Throwable>()
            written.asReversed().forEach { candidate ->
                runCatching {
                    restoreOwnedTarget(candidate.file, candidate.bytes, snapshots[candidate.file.toPath()])
                }.exceptionOrNull()?.let(rollbackFailures::add)
            }
            deletedLegacyFiles.asReversed().forEach { legacy ->
                runCatching { restoreDeletedLegacy(legacy, requireNotNull(snapshots[legacy.toPath()])) }
                    .exceptionOrNull()
                    ?.let(rollbackFailures::add)
            }
            rollbackFailures.forEach(error::addSuppressed)
            if (rollbackFailures.isNotEmpty()) throw IOException("language rollback failed", error)
            throw error
        }
    }

    private fun snapshotFiles(candidates: List<CatalogCandidate>): Map<java.nio.file.Path, ByteArray?> =
        candidates
            .flatMap { candidate -> listOfNotNull(candidate.file, candidate.legacyFileToDelete) }
            .map(File::toPath)
            .distinct()
            .associateWith { path -> path.toFile().takeIf(File::isFile)?.readBytes() }

    private fun restoreOwnedTarget(
        target: File,
        expectedCandidate: ByteArray,
        original: ByteArray?,
    ) {
        val path = target.toPath()
        val current = path.takeIf(Files::isRegularFile)?.let(Files::readAllBytes)
        if (current == null || !current.contentEquals(expectedCandidate)) {
            throw IOException("language rollback ownership changed")
        }
        if (original == null) {
            Files.delete(path)
        } else {
            atomicWrite(path, original)
        }
    }

    private fun restoreDeletedLegacy(
        legacy: File,
        original: ByteArray,
    ) {
        val path = legacy.toPath()
        if (Files.exists(path)) throw IOException("legacy language rollback ownership changed")
        atomicWrite(path, original)
    }

    private fun atomicWrite(
        target: java.nio.file.Path,
        bytes: ByteArray,
    ) {
        val temporary = Files.createTempFile(requireNotNull(target.parent), ".language-rollback-", ".tmp")
        try {
            Files.write(temporary, bytes)
            moveReplacing(temporary, target)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun createBackup(candidate: CatalogCandidate): String {
        val original = requireNotNull(candidate.originalBytes)
        val source = requireNotNull(candidate.sourceFile)
        val label = if (candidate.backupKind == BackupKind.LEGACY_MIGRATION) "migration" else "parse-recovery"
        repeat(MAX_BACKUP_NAME_ATTEMPTS) { suffix ->
            val extra = if (suffix == 0) "" else "-$suffix"
            val backup = source.toPath().resolveSibling("${source.name}.$label-${backupTimestamp()}$extra.bak")
            try {
                Files.write(backup, original, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
                return backup.fileName.toString()
            } catch (_: FileAlreadyExistsException) {
                // Bounded retry preserves every prior recovery artifact.
            }
        }
        throw IOException("language backup name collision")
    }

    private fun readFile(file: File): ByteArray =
        try {
            Files.readAllBytes(file.toPath())
        } catch (error: IOException) {
            throw MessageCatalogException("${file.name}: read failed (${error.javaClass.simpleName})", error)
        }

    private fun loadConfiguration(bytes: ByteArray): YamlConfiguration? =
        try {
            YamlConfiguration().apply { loadFromString(bytes.toString(StandardCharsets.UTF_8).removePrefix("\uFEFF")) }
        } catch (_: InvalidConfigurationException) {
            null
        }

    private fun readMessage(
        configuration: YamlConfiguration,
        path: String,
        fileName: String,
    ): String {
        messageValidation(configuration, path)?.let { throw MessageCatalogException("$fileName: $it") }
        return configuration.getString(path).orEmpty()
    }

    private fun messageValidation(
        configuration: YamlConfiguration,
        path: String,
    ): String? {
        if (!configuration.isString(path)) return "$path must be a string"
        val value = configuration.getString(path).orEmpty()
        return when {
            value.isBlank() -> "$path must not be blank"
            value.length > MAX_MESSAGE_LENGTH -> "$path exceeds $MAX_MESSAGE_LENGTH characters"
            value.any(Char::isISOControl) -> "$path must not contain control characters"
            else -> null
        }
    }

    private fun spaced(bytes: ByteArray): ByteArray =
        when (val result = spacing.apply(bytes)) {
            is YamlDocumentEditResult.Candidate -> result.bytes
            is YamlDocumentEditResult.Rejected -> throw MessageCatalogException("language YAML spacing rejected")
        }

    private fun scanned(bytes: ByteArray): ScannedYamlDocument? = (scanner.scan(bytes) as? YamlDocumentScanResult.Scanned)?.document

    private fun writeCandidate(candidate: CatalogCandidate) {
        val temporary = Files.createTempFile(directory.toPath(), "language-${candidate.language.code}-", ".tmp")
        try {
            Files.write(temporary, candidate.bytes)
            val verified = loadConfiguration(Files.readAllBytes(temporary)) ?: throw IOException("language candidate verification failed")
            requiredMessagePaths.forEach { path -> readMessage(verified, path, candidate.file.name) }
            moveReplacing(temporary, candidate.file.toPath())
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun moveReplacing(
        source: java.nio.file.Path,
        target: java.nio.file.Path,
    ) {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    public companion object {
        public fun fromResources(
            resourceLoader: ClassLoader,
            directory: File,
            recoveryReporter: (ConfigParseRecoveryReport) -> Unit = {},
        ): BukkitMessageCatalogStore {
            val raw =
                PluginMessageLanguage.BUILT_IN.associateWith { language ->
                    val path = "config/languages/${language.code}.yml"
                    requireNotNull(resourceLoader.getResourceAsStream(path)) { "missing resource $path" }.use { it.readBytes() }
                }
            val defaults =
                raw.mapValues { (_, bytes) ->
                    YamlConfiguration().apply { loadFromString(bytes.toString(StandardCharsets.UTF_8).removePrefix("\uFEFF")) }
                }
            defaults.forEach { (language, configuration) ->
                requiredMessagePaths.forEach { path ->
                    require(configuration.isString(path)) { "embedded ${language.code} language is missing $path" }
                }
            }
            return BukkitMessageCatalogStore(directory, defaults, raw, recoveryReporter)
        }

        private const val MAX_MESSAGE_LENGTH = 512
        private const val MAX_BACKUP_NAME_ATTEMPTS = 100
        private const val DISPLAY_NO_OWNER_PATH = "display.no-owner"
        private const val DISPLAY_LIFETIME_PERMANENT_PATH = "display.lifetime-permanent"
        private const val DISPLAY_LIFETIME_UNKNOWN_PATH = "display.lifetime-unknown"
        private val requiredMessagePaths: List<String> =
            (
                ManagementMessageKey.entries.map(ManagementMessageKey::path) +
                    PickupMessageKey.entries.map(PickupMessageKey::path) +
                    listOf(DISPLAY_NO_OWNER_PATH, DISPLAY_LIFETIME_PERMANENT_PATH, DISPLAY_LIFETIME_UNKNOWN_PATH)
            ).distinct()
    }
}

private enum class BackupKind { PARSE_RECOVERY, LEGACY_MIGRATION }

private data class CatalogCandidate(
    val language: PluginMessageLanguage,
    val file: File,
    val bytes: ByteArray,
    val messages: Map<String, String>,
    val requiresWrite: Boolean,
    val created: Boolean,
    val sourceFile: File?,
    val originalBytes: ByteArray?,
    val legacyFileToDelete: File?,
    val backupKind: BackupKind?,
    val repairedPaths: List<String>,
    val documentRecreated: Boolean,
)

private class MessageCatalogException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
