package com.github.command1264.itemdropv2.platform.bukkit

import com.github.command1264.itemdropv2.core.ItemDisplaySettings
import com.github.command1264.itemdropv2.core.ItemDisplaySettingsManager
import com.github.command1264.itemdropv2.core.ItemDisplaySettingsUpdateResult
import com.github.command1264.itemdropv2.platform.bukkit.yaml.StrictYamlDocumentEditor
import com.github.command1264.itemdropv2.platform.bukkit.yaml.YamlDocumentCandidateValidator
import com.github.command1264.itemdropv2.platform.bukkit.yaml.YamlDocumentEditResult
import com.github.command1264.itemdropv2.platform.bukkit.yaml.YamlDocumentFileUpdater
import com.github.command1264.itemdropv2.platform.bukkit.yaml.YamlDocumentOwnedReplacementFailure
import com.github.command1264.itemdropv2.platform.bukkit.yaml.YamlDocumentRejection
import com.github.command1264.itemdropv2.platform.bukkit.yaml.YamlDocumentRejectionCategory
import com.github.command1264.itemdropv2.platform.bukkit.yaml.YamlDocumentReplacementOwnership
import com.github.command1264.itemdropv2.platform.bukkit.yaml.YamlDocumentUpdateResult
import com.github.command1264.itemdropv2.platform.bukkit.yaml.YamlDocumentValidationResult
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

public data class ConfigKeyMigrationReport(
    public val obsoletePath: String,
    public val canonicalPath: String,
    public val canonicalValueAlreadyPresent: Boolean,
    public val backupFileName: String? = null,
)

public data class ConfigParseRecoveryReport(
    public val repairedPaths: List<String>,
    public val documentRecreated: Boolean,
    public val backupFileName: String = "",
    public val fileName: String = "config.yml",
)

public sealed interface ItemDisplaySettingsPublicationPreparation {
    public data class Ready(
        public val settings: ItemDisplaySettings,
        public val commit: () -> String?,
        public val rollback: () -> Unit = {},
        public val complete: () -> Unit = {},
    ) : ItemDisplaySettingsPublicationPreparation

    public data class Rejected(
        public val reason: String,
    ) : ItemDisplaySettingsPublicationPreparation
}

@Suppress("LargeClass", "TooManyFunctions")
public class BukkitItemDisplaySettingsManager(
    initialSettings: ItemDisplaySettings,
    private val configFile: File,
    private val defaultConfiguration: YamlConfiguration? = null,
    defaultDocumentBytes: ByteArray? = null,
    private val loader: BukkitDisplaySettingsLoader = BukkitDisplaySettingsLoader(),
    private val lifetimeConfigFile: File? = null,
    private val defaultLifetimeConfiguration: YamlConfiguration? = null,
    defaultLifetimeDocumentBytes: ByteArray? = null,
    private val lifetimeLoader: BukkitItemLifetimeSettingsLoader = BukkitItemLifetimeSettingsLoader(),
    private val settingsValidator: (ItemDisplaySettings) -> String? = { null },
    private val settingsTransformer: (ItemDisplaySettings) -> ItemDisplaySettings = { it },
    private val publicationPreparer: ((ItemDisplaySettings, ItemDisplaySettings) -> ItemDisplaySettingsPublicationPreparation)? = null,
    private val migrationReporter: (LegacyConfigMigrationReport) -> Unit = {},
    private val configKeyMigrationReporter: (ConfigKeyMigrationReport) -> Unit = {},
    private val configParseRecoveryReporter: (ConfigParseRecoveryReport) -> Unit = {},
    private val migrationTimestamp: () -> String = DEFAULT_MIGRATION_TIMESTAMP,
    private val migrationFileReplacer: (Path, Path) -> Unit = ::moveReplacing,
    configFileReplacer: ((Path, Path) -> Unit)? = null,
    private val configBackupCreator: ((Path, ByteArray) -> Path)? = null,
) : ItemDisplaySettingsManager {
    private val defaultDocumentBytes = defaultDocumentBytes?.copyOf()
    private val defaultLifetimeDocumentBytes = defaultLifetimeDocumentBytes?.copyOf()
    private val configDocumentAdapter: BukkitConfigDocumentAdapter?
    private val configFileReplacer = configFileReplacer ?: ::moveReplacing
    private var configCandidateWriter: ((Path, ByteArray) -> Unit)? = null

    init {
        require((defaultConfiguration == null) == (this.defaultDocumentBytes == null)) {
            "embedded semantic defaults and raw document bytes must be supplied together"
        }
        require(lifetimeConfigFile == null || defaultLifetimeConfiguration != null) {
            "lifetime config file requires embedded defaults"
        }
        require(defaultLifetimeDocumentBytes == null || defaultLifetimeConfiguration != null) {
            "raw lifetime defaults require embedded semantic defaults"
        }
        configDocumentAdapter =
            defaultConfiguration?.let { defaults ->
                BukkitConfigDocumentAdapter(
                    templateBytes = requireNotNull(this.defaultDocumentBytes),
                    defaults = defaults,
                    loader = loader,
                    editor = StrictYamlDocumentEditor(),
                )
            }
        check((defaultConfiguration == null) == (configDocumentAdapter == null)) {
            "embedded config adapter initialization is inconsistent"
        }
    }

    internal constructor(
        initialSettings: ItemDisplaySettings,
        configFile: File,
        defaultConfiguration: YamlConfiguration,
        defaultDocumentBytes: ByteArray,
        configCandidateWriter: (Path, ByteArray) -> Unit,
        migrationReporter: (LegacyConfigMigrationReport) -> Unit = {},
    ) : this(
        initialSettings = initialSettings,
        configFile = configFile,
        defaultConfiguration = defaultConfiguration,
        defaultDocumentBytes = defaultDocumentBytes,
        migrationReporter = migrationReporter,
    ) {
        this.configCandidateWriter = configCandidateWriter
    }

    private val current = AtomicReference(settingsTransformer(initialSettings))
    private val validationCandidate = ThreadLocal<ItemDisplaySettings?>()
    private val transitionValidator = AtomicReference<(ItemDisplaySettings, ItemDisplaySettings) -> String?> { _, _ -> null }
    private val mutationInProgress = AtomicBoolean(false)
    private val pendingConfigParseRecoveries = AtomicReference<List<ConfigParseRecoveryReport>>(emptyList())
    private val initialLifetimeSettings = initialSettings.lifetime
    private val legacyMigrator = defaultConfiguration?.let { BukkitLegacyConfigMigrator(it, loader) }
    private val lifetimeFileStore: BukkitItemLifetimeSettingsFile? by lazy {
        lifetimeConfigFile?.let { file ->
            BukkitItemLifetimeSettingsFile(
                file,
                defaultLifetimeConfiguration,
                lifetimeLoader,
                defaultLifetimeDocumentBytes,
                migrationTimestamp,
            )
        }
    }

    override fun settings(): ItemDisplaySettings = validationCandidate.get() ?: current.get()

    public fun consumeConfigParseRecoveryReport(): ConfigParseRecoveryReport? =
        pendingConfigParseRecoveries.getAndSet(emptyList()).firstOrNull()

    public fun consumeYamlRecoveryReports(): List<ConfigParseRecoveryReport> = pendingConfigParseRecoveries.getAndSet(emptyList())

    public fun installTransitionValidator(validator: (ItemDisplaySettings, ItemDisplaySettings) -> String?) {
        transitionValidator.set(validator)
    }

    override fun setEnabled(enabled: Boolean): ItemDisplaySettingsUpdateResult = runMutation { setEnabledTransaction(enabled) }

    @Suppress("ReturnCount")
    private fun setEnabledTransaction(enabled: Boolean): ItemDisplaySettingsUpdateResult {
        val adapter =
            configDocumentAdapter
                ?: return ItemDisplaySettingsUpdateResult.Failed("embedded config is unavailable")
        val originalBytes = if (configFile.isFile) readDocument(configFile) ?: return readFailure() else null
        val toggle =
            when (val prepared = prepareToggle(adapter, originalBytes, enabled)) {
                is TogglePreparation.Failed -> return ItemDisplaySettingsUpdateResult.Failed(prepared.reason)
                is TogglePreparation.Ready -> prepared
            }
        return when (val updated = replaceCandidate(toggle.edit, originalBytes, toggle.replacement)) {
            is FileLoadResult.Loaded -> publish(updated)
            is FileLoadResult.Failed -> ItemDisplaySettingsUpdateResult.Failed(updated.reason)
        }
    }

    private fun prepareToggle(
        adapter: BukkitConfigDocumentAdapter,
        originalBytes: ByteArray?,
        enabled: Boolean,
    ): TogglePreparation =
        when {
            originalBytes == null ->
                TogglePreparation.Ready(adapter.createMissingAndSetEnabledCandidate(enabled), standardReplacement())
            else ->
                loadConfiguration(originalBytes)?.let { configuration ->
                    if (configuration.getInt(SCHEMA_VERSION_PATH) == SCHEMA_VERSION) {
                        prepareCurrentToggle(adapter, originalBytes, enabled)
                    } else {
                        prepareLegacyToggle(adapter, originalBytes, configuration, enabled)
                    }
                } ?: prepareMalformedToggle(adapter, enabled)
        }

    private fun prepareMalformedToggle(
        adapter: BukkitConfigDocumentAdapter,
        enabled: Boolean,
    ): TogglePreparation =
        when (val replacement = parseRecoveryReplacement(emptyList(), documentRecreated = true)) {
            is ReplacementPreparation.Failed -> TogglePreparation.Failed(replacement.reason)
            is ReplacementPreparation.Ready ->
                TogglePreparation.Ready(adapter.createMissingAndSetEnabledCandidate(enabled), replacement.options)
        }

    private fun prepareCurrentToggle(
        adapter: BukkitConfigDocumentAdapter,
        originalBytes: ByteArray,
        enabled: Boolean,
    ): TogglePreparation {
        val repair = adapter.repairAndSetEnabled(originalBytes, enabled)
        val replacement =
            if (repair.parseRecoveryPaths.isNotEmpty()) {
                parseRecoveryReplacement(repair.parseRecoveryPaths, documentRecreated = false)
            } else {
                repair.configKeyMigration?.let(::configKeyReplacement)
            }
        return when (val prepared = replacement) {
            is ReplacementPreparation.Failed -> TogglePreparation.Failed(prepared.reason)
            is ReplacementPreparation.Ready -> TogglePreparation.Ready(repair.edit, prepared.options)
            null -> TogglePreparation.Ready(repair.edit, standardReplacement())
        }
    }

    private fun prepareLegacyToggle(
        adapter: BukkitConfigDocumentAdapter,
        originalBytes: ByteArray,
        configuration: YamlConfiguration,
        enabled: Boolean,
    ): TogglePreparation =
        when (val migration = legacyMigrator?.migrate(configuration, originalBytes) ?: LegacyConfigMigrationResult.NotLegacy) {
            LegacyConfigMigrationResult.NotLegacy ->
                TogglePreparation.Ready(adapter.setEnabled(originalBytes, enabled), standardReplacement())
            is LegacyConfigMigrationResult.Rejected -> TogglePreparation.Failed(migration.reason)
            is LegacyConfigMigrationResult.Migrated -> prepareMigratedLegacyToggle(adapter, originalBytes, migration, enabled)
        }

    private fun prepareMigratedLegacyToggle(
        adapter: BukkitConfigDocumentAdapter,
        originalBytes: ByteArray,
        migration: LegacyConfigMigrationResult.Migrated,
        enabled: Boolean,
    ): TogglePreparation {
        val edit =
            when (val migrated = adapter.migrateLegacy(originalBytes, migration)) {
                is YamlDocumentEditResult.Rejected -> migrated
                is YamlDocumentEditResult.Candidate -> adapter.setEnabled(migrated.bytes, enabled)
            }
        return when (edit) {
            is YamlDocumentEditResult.Rejected -> TogglePreparation.Failed(edit.rejection.asFailureReason())
            is YamlDocumentEditResult.Candidate ->
                when (val prepared = legacyReplacement(migration.report, originalBytes)) {
                    is ReplacementPreparation.Failed -> TogglePreparation.Failed(prepared.reason)
                    is ReplacementPreparation.Ready -> TogglePreparation.Ready(edit, prepared.options)
                }
        }
    }

    private fun readFailure(): ItemDisplaySettingsUpdateResult.Failed = ItemDisplaySettingsUpdateResult.Failed("config read failed")

    override fun reload(): ItemDisplaySettingsUpdateResult =
        runMutation {
            when (val loaded = loadAndRepair()) {
                is FileLoadResult.Loaded -> publish(loaded)
                is FileLoadResult.Failed -> ItemDisplaySettingsUpdateResult.Failed(loaded.reason)
            }
        }

    private fun runMutation(action: () -> ItemDisplaySettingsUpdateResult): ItemDisplaySettingsUpdateResult {
        if (!mutationInProgress.compareAndSet(false, true)) {
            return ItemDisplaySettingsUpdateResult.Failed("config mutation is already in progress")
        }
        return try {
            action()
        } finally {
            mutationInProgress.set(false)
        }
    }

    @Suppress("ReturnCount", "TooGenericExceptionCaught")
    private fun publish(loaded: FileLoadResult.Loaded): ItemDisplaySettingsUpdateResult {
        val previousSettings = current.get()
        val publication =
            loaded.publication ?: when (val prepared = preparePublication(previousSettings, loaded.settings)) {
                is PublicationPreparation.Failed -> return ItemDisplaySettingsUpdateResult.Failed(prepared.reason)
                is PublicationPreparation.Prepared -> prepared.publication
            }
        val commitFailure =
            try {
                publication.commit()
            } catch (error: IOException) {
                "settings publication failed (${error.javaClass.simpleName})"
            } catch (error: RuntimeException) {
                "settings publication failed (${error.javaClass.simpleName})"
            }
        if (commitFailure != null) {
            return rollbackFailedPublication(loaded, publication, commitFailure)
        }
        val publishedSettings =
            try {
                settingsTransformer(publication.settings)
            } catch (error: RuntimeException) {
                return rollbackFailedPublication(
                    loaded,
                    publication,
                    "settings transformation failed (${error.javaClass.simpleName})",
                )
            }
        current.set(publishedSettings)
        try {
            loaded.onPublished?.invoke()
        } catch (error: Exception) {
            current.set(previousSettings)
            return rollbackFailedPublication(
                loaded,
                publication,
                "migration report failed (${error.javaClass.simpleName})",
            )
        }
        try {
            publication.complete()
        } catch (error: Exception) {
            current.set(previousSettings)
            return rollbackFailedPublication(
                loaded,
                publication,
                "settings publication completion failed (${error.javaClass.simpleName})",
            )
        }
        return ItemDisplaySettingsUpdateResult.Applied(publishedSettings)
    }

    @Suppress("ReturnCount")
    private fun preparePublication(
        previous: ItemDisplaySettings,
        candidate: ItemDisplaySettings,
    ): PublicationPreparation {
        settingsValidator(candidate)?.let { return PublicationPreparation.Failed(it) }
        val publication =
            when (val prepared = publicationPreparer?.invoke(previous, candidate)) {
                null ->
                    ItemDisplaySettingsPublicationPreparation.Ready(
                        settings = candidate,
                        commit = { null },
                    )
                is ItemDisplaySettingsPublicationPreparation.Rejected ->
                    return PublicationPreparation.Failed(prepared.reason)
                is ItemDisplaySettingsPublicationPreparation.Ready -> prepared
            }
        val transitionFailure = withValidationCandidate(candidate) { transitionValidator.get().invoke(current.get(), candidate) }
        if (transitionFailure != null) {
            publication.rollback()
            return PublicationPreparation.Failed(transitionFailure)
        }
        return PublicationPreparation.Prepared(publication)
    }

    @Suppress("TooGenericExceptionCaught")
    private fun rollbackFailedPublication(
        loaded: FileLoadResult.Loaded,
        publication: ItemDisplaySettingsPublicationPreparation.Ready,
        reason: String,
    ): ItemDisplaySettingsUpdateResult.Failed {
        var detail = reason
        try {
            publication.rollback()
        } catch (error: Exception) {
            detail += "; settings publication rollback failed (${error.javaClass.simpleName})"
        }
        try {
            loaded.documentRollback?.let { restoreOriginalDocument(it.bytes, it.expectedCurrentBytes) }
        } catch (error: Exception) {
            detail += "; config rollback failed (${error.javaClass.simpleName})"
        }
        return ItemDisplaySettingsUpdateResult.Failed(detail)
    }

    private fun <T> withValidationCandidate(
        candidate: ItemDisplaySettings,
        action: () -> T,
    ): T {
        val previous = validationCandidate.get()
        validationCandidate.set(candidate)
        return try {
            action()
        } finally {
            if (previous == null) {
                validationCandidate.remove()
            } else {
                validationCandidate.set(previous)
            }
        }
    }

    private fun loadAndRepair(): FileLoadResult =
        if (configFile.isFile) {
            loadExistingAndRepair()
        } else {
            configDocumentAdapter
                ?.let { adapter -> replaceCandidate(adapter.createMissingCandidate(), null) }
                ?: FileLoadResult.Failed("config read failed")
        }

    @Suppress("ReturnCount")
    private fun loadExistingAndRepair(): FileLoadResult {
        val originalBytes = readDocument(configFile) ?: return FileLoadResult.Failed("config read failed")
        val configuration =
            loadConfiguration(originalBytes)
                ?: return recoverMalformedDocument(originalBytes)
        migrateLegacyIfNeeded(configuration, originalBytes)?.let { return it }
        if (!configuration.isInt(SCHEMA_VERSION_PATH) || configuration.getInt(SCHEMA_VERSION_PATH) != SCHEMA_VERSION) {
            return loadFile(configFile)
        }
        return loadCurrentSchema(originalBytes, configuration)
    }

    private fun recoverMalformedDocument(originalBytes: ByteArray): FileLoadResult {
        val adapter = configDocumentAdapter ?: return FileLoadResult.Failed("embedded config is unavailable")
        return when (val replacement = parseRecoveryReplacement(emptyList(), documentRecreated = true)) {
            is ReplacementPreparation.Failed -> FileLoadResult.Failed(replacement.reason)
            is ReplacementPreparation.Ready ->
                replaceCandidate(adapter.createMissingCandidate(), originalBytes, replacement.options)
        }
    }

    private fun loadCurrentSchema(
        originalBytes: ByteArray,
        configuration: YamlConfiguration,
    ): FileLoadResult {
        val adapter = configDocumentAdapter ?: return loadCurrentDocument(configuration, originalBytes)
        val repair = adapter.prepareCurrentSchemaRepair(originalBytes)
        return when (val edit = repair.edit) {
            is YamlDocumentEditResult.Rejected -> persistCurrentKeyRepair(adapter, originalBytes, repair)
            is YamlDocumentEditResult.Candidate ->
                if (edit.bytes.contentEquals(originalBytes)) {
                    loadCurrentDocument(configuration, originalBytes)
                } else {
                    persistCurrentKeyRepair(adapter, originalBytes, repair)
                }
        }
    }

    private fun persistCurrentKeyRepair(
        adapter: BukkitConfigDocumentAdapter,
        originalBytes: ByteArray,
        repair: BukkitCurrentSchemaDocumentRepair,
    ): FileLoadResult {
        if (repair.edit is YamlDocumentEditResult.Candidate) {
            val validation = adapter.validate(repair.edit.bytes)
            if (validation is BukkitConfigCandidateResult.Rejected) return FileLoadResult.Failed(validation.reason)
        }
        val replacement =
            if (repair.parseRecoveryPaths.isNotEmpty()) {
                parseRecoveryReplacement(repair.parseRecoveryPaths, documentRecreated = false)
            } else {
                repair.configKeyMigration?.let(::configKeyReplacement)
            }
        return when (replacement) {
            is ReplacementPreparation.Failed -> FileLoadResult.Failed(replacement.reason)
            is ReplacementPreparation.Ready ->
                replaceCandidate(
                    repair.edit,
                    originalBytes,
                    replacement.options,
                )
            null ->
                replaceCandidate(
                    repair.edit,
                    originalBytes,
                )
        }
    }

    private fun loadCurrentDocument(
        configuration: YamlConfiguration,
        documentBytes: ByteArray,
    ): FileLoadResult =
        when (val validation = loader.load(configuration)) {
            is BukkitDisplaySettingsLoadResult.Invalid -> FileLoadResult.Failed(validation.errors.joinToString("; "))
            is BukkitDisplaySettingsLoadResult.Loaded ->
                combineLifetime(validation.settings, configuration, documentBytes)
        }

    private fun migrateLegacyIfNeeded(
        configuration: YamlConfiguration,
        originalBytes: ByteArray,
    ): FileLoadResult? {
        if (configuration.contains(SCHEMA_VERSION_PATH)) return null
        return when (val migration = legacyMigrator?.migrate(configuration, originalBytes) ?: LegacyConfigMigrationResult.NotLegacy) {
            LegacyConfigMigrationResult.NotLegacy -> loadFile(configFile)
            is LegacyConfigMigrationResult.Rejected -> FileLoadResult.Failed(migration.reason)
            is LegacyConfigMigrationResult.Migrated -> migrateLegacyFile(migration, originalBytes)
        }
    }

    @Suppress("ReturnCount")
    private fun migrateLegacyFile(
        migration: LegacyConfigMigrationResult.Migrated,
        originalBytes: ByteArray,
    ): FileLoadResult {
        val adapter = configDocumentAdapter ?: return FileLoadResult.Failed("embedded config is unavailable")
        val edit = adapter.migrateLegacy(originalBytes, migration)
        if (edit is YamlDocumentEditResult.Rejected) return FileLoadResult.Failed(edit.rejection.asFailureReason())
        val replacement =
            when (val prepared = legacyReplacement(migration.report, originalBytes)) {
                is ReplacementPreparation.Failed -> return FileLoadResult.Failed(prepared.reason)
                is ReplacementPreparation.Ready -> prepared.options
            }
        return replaceCandidate(
            edit,
            originalBytes,
            replacement,
        )
    }

    private fun standardReplacement(): ConfigReplacement = ConfigReplacement(fileReplacer = configFileReplacer)

    private fun configKeyReplacement(report: ConfigKeyMigrationReport): ReplacementPreparation =
        migrationReplacement(
            label = "key-migration",
            failureMode = ConfigReplacementFailureMode.CONFIG_KEY_MIGRATION,
            onPublished = { backupFileName ->
                configKeyMigrationReporter(report.copy(backupFileName = backupFileName))
            },
        )

    private fun parseRecoveryReplacement(
        repairedPaths: List<String>,
        documentRecreated: Boolean,
    ): ReplacementPreparation =
        migrationReplacement(
            label = "parse-recovery",
            failureMode = ConfigReplacementFailureMode.PARSE_RECOVERY,
            onPublished = { backupFileName ->
                val report =
                    ConfigParseRecoveryReport(
                        repairedPaths = repairedPaths,
                        documentRecreated = documentRecreated,
                        backupFileName = backupFileName,
                    )
                configParseRecoveryReporter(report)
                appendRecoveryReport(report)
            },
        )

    private fun legacyReplacement(
        report: LegacyConfigMigrationReport,
        originalBytes: ByteArray,
    ): ReplacementPreparation {
        val replacement =
            when (
                val prepared =
                    migrationReplacement(
                        label = report.source.fileLabel,
                        failureMode = ConfigReplacementFailureMode.LEGACY_MIGRATION,
                        onPublished = { backupFileName ->
                            migrationReporter(report.copy(backupFileName = backupFileName))
                        },
                    )
            ) {
                is ReplacementPreparation.Failed -> return prepared
                is ReplacementPreparation.Ready -> prepared.options
            }
        return stageLegacyBackup(replacement, originalBytes)
    }

    @Suppress("TooGenericExceptionCaught")
    private fun stageLegacyBackup(
        replacement: ConfigReplacement,
        originalBytes: ByteArray,
    ): ReplacementPreparation {
        val parent =
            configFile
                .toPath()
                .toAbsolutePath()
                .normalize()
                .parent
                ?: return ReplacementPreparation.Failed("config parent is unavailable")
        return try {
            Files.createDirectories(parent)
            val backupSpec = requireNotNull(replacement.backup)
            val backup = configBackupCreator?.invoke(parent, originalBytes) ?: createBackup(parent, backupSpec, originalBytes)
            ReplacementPreparation.Ready(replacement.copy(backup = null, preparedBackup = backup))
        } catch (error: IOException) {
            ReplacementPreparation.Failed(replacement.backupFailure(error))
        } catch (error: RuntimeException) {
            ReplacementPreparation.Failed(replacement.backupFailure(error))
        }
    }

    private fun migrationReplacement(
        label: String,
        failureMode: ConfigReplacementFailureMode,
        onPublished: (String) -> Unit,
    ): ReplacementPreparation {
        val timestamp = migrationTimestamp()
        if (!timestamp.matches(SAFE_BACKUP_TIMESTAMP)) {
            return ReplacementPreparation.Failed(failureMode.invalidTimestampReason())
        }
        return ReplacementPreparation.Ready(
            ConfigReplacement(
                fileReplacer = migrationFileReplacer,
                backup = ConfigBackup(label, timestamp),
                failureMode = failureMode,
                onPublished = onPublished,
            ),
        )
    }

    private fun backupPath(
        parent: Path,
        label: String,
        timestamp: String,
        suffix: Int,
    ): Path {
        val base = "config.yml.$label-$timestamp"
        return parent.resolve(if (suffix == 0) "$base.bak" else "$base-$suffix.bak")
    }

    private fun loadFile(file: File): FileLoadResult {
        val documentBytes = readDocument(file) ?: return FileLoadResult.Failed("config read failed")
        return loadConfiguration(documentBytes)
            ?.let { configuration -> loadCurrentDocument(configuration, documentBytes) }
            ?: FileLoadResult.Failed("config read failed")
    }

    private fun combineLifetime(
        settings: ItemDisplaySettings,
        configuration: YamlConfiguration,
        documentBytes: ByteArray? = null,
    ): FileLoadResult =
        when (val lifetime = lifetimeFileStore?.loadAndRepair() ?: LifetimeFileLoadResult.Loaded(initialLifetimeSettings)) {
            is LifetimeFileLoadResult.Loaded -> {
                lifetime.recoveryReport?.let { report ->
                    configParseRecoveryReporter(report)
                    appendRecoveryReport(report)
                }
                FileLoadResult.Loaded(
                    settings = settings.copy(lifetime = lifetime.settings),
                    configuration = configuration,
                    documentBytes = documentBytes?.copyOf(),
                )
            }
            is LifetimeFileLoadResult.Failed -> FileLoadResult.Failed(lifetime.reason)
        }

    private fun appendRecoveryReport(report: ConfigParseRecoveryReport) {
        pendingConfigParseRecoveries.updateAndGet { reports -> reports + report }
    }

    @Suppress("TooGenericExceptionCaught", "ReturnCount", "LongMethod")
    private fun replaceCandidate(
        edit: YamlDocumentEditResult,
        originalBytes: ByteArray?,
        replacement: ConfigReplacement = standardReplacement(),
    ): FileLoadResult {
        if (edit is YamlDocumentEditResult.Rejected) return FileLoadResult.Failed(edit.rejection.asFailureReason())
        val candidateBytes = (edit as YamlDocumentEditResult.Candidate).bytes
        val parent = configFile.toPath().toAbsolutePath().parent ?: return FileLoadResult.Failed("config parent is unavailable")
        var validatedCandidate: FileLoadResult.Loaded? = null
        var backup: Path? = replacement.preparedBackup
        return try {
            Files.createDirectories(parent)
            val updater = candidateFileUpdater(replacement, parent, originalBytes) { backup = it }
            when (
                val update =
                    updater.update(
                        target = configFile.toPath(),
                        candidate = candidateBytes,
                        validator =
                            YamlDocumentCandidateValidator { bytes ->
                                when (val validated = validateDocument(bytes, validatePublication = true)) {
                                    is FileLoadResult.Loaded -> {
                                        validatedCandidate = validated
                                        YamlDocumentValidationResult.Valid
                                    }
                                    is FileLoadResult.Failed -> invalidDocument(validated.reason)
                                }
                            },
                    )
            ) {
                is YamlDocumentUpdateResult.Rejected -> {
                    rollbackUnownedCandidateFailure(
                        validatedCandidate?.publication,
                        originalBytes,
                        update.rejection.asFailureReason(),
                    )
                }
                is YamlDocumentUpdateResult.Updated ->
                    verifyPersistedCandidate(
                        persistedBytes = update.bytes,
                        candidateBytes = candidateBytes,
                        validatedCandidate = validatedCandidate,
                        originalBytes = originalBytes,
                        ownership = update.ownership,
                        replacement = replacement,
                        backup = backup,
                    )
            }
        } catch (error: ConfigBackupException) {
            rollbackUnownedCandidateFailure(
                validatedCandidate?.publication,
                originalBytes,
                replacement.backupFailure(error.cause ?: error),
            )
        } catch (error: YamlDocumentOwnedReplacementFailure) {
            rollbackCandidateFailure(
                publication = validatedCandidate?.publication,
                originalBytes = originalBytes,
                ownership = error.ownership,
                reason = replacement.writeFailure(error.failure),
            )
        } catch (error: IOException) {
            rollbackUnownedCandidateFailure(
                validatedCandidate?.publication,
                originalBytes,
                replacement.writeFailure(error),
            )
        } catch (error: RuntimeException) {
            rollbackUnownedCandidateFailure(
                validatedCandidate?.publication,
                originalBytes,
                replacement.writeFailure(error),
            )
        }
    }

    private fun rollbackUnownedCandidateFailure(
        publication: ItemDisplaySettingsPublicationPreparation.Ready?,
        originalBytes: ByteArray?,
        reason: String,
    ): FileLoadResult.Failed =
        rollbackCandidateFailure(
            publication = publication,
            originalBytes = originalBytes,
            ownership = null,
            reason = reason,
        )

    @Suppress("TooGenericExceptionCaught")
    private fun candidateFileUpdater(
        replacement: ConfigReplacement,
        parent: Path,
        originalBytes: ByteArray?,
        backupCreated: (Path) -> Unit,
    ): YamlDocumentFileUpdater {
        val fileReplacer: (Path, Path) -> Unit = { source, target ->
            replacement.backup?.let { backupSpec ->
                try {
                    val original = requireNotNull(originalBytes)
                    val backup = configBackupCreator?.invoke(parent, original) ?: createBackup(parent, backupSpec, original)
                    backupCreated(backup)
                } catch (error: IOException) {
                    throw ConfigBackupException(error)
                } catch (error: RuntimeException) {
                    throw ConfigBackupException(error)
                }
            }
            replacement.fileReplacer(source, target)
        }
        return configCandidateWriter?.let { writer ->
            YamlDocumentFileUpdater(fileReplacer, writer)
        } ?: YamlDocumentFileUpdater(fileReplacer)
    }

    @Suppress("TooGenericExceptionCaught")
    private fun createBackup(
        parent: Path,
        backup: ConfigBackup,
        bytes: ByteArray,
    ): Path {
        repeat(MAX_BACKUP_COLLISIONS) { suffix ->
            val candidate = backupPath(parent, backup.label, backup.timestamp, suffix)
            val channel =
                try {
                    FileChannel.open(candidate, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
                } catch (_: FileAlreadyExistsException) {
                    return@repeat
                }
            try {
                channel.use { output ->
                    val buffer = ByteBuffer.wrap(bytes)
                    while (buffer.hasRemaining()) {
                        if (output.write(buffer) <= 0) throw IOException("config backup write made no progress")
                    }
                    output.force(true)
                }
            } catch (error: IOException) {
                deletePartialBackup(candidate, error)
            } catch (error: RuntimeException) {
                deletePartialBackup(candidate, error)
            }
            return candidate
        }
        throw IOException("config backup name allocation exhausted")
    }

    @Suppress("TooGenericExceptionCaught")
    private fun deletePartialBackup(
        backup: Path,
        error: Exception,
    ): Nothing {
        try {
            Files.deleteIfExists(backup)
        } catch (cleanupFailure: IOException) {
            error.addSuppressed(cleanupFailure)
        } catch (cleanupFailure: RuntimeException) {
            error.addSuppressed(cleanupFailure)
        }
        throw error
    }

    @Suppress("ReturnCount")
    private fun verifyPersistedCandidate(
        persistedBytes: ByteArray,
        candidateBytes: ByteArray,
        validatedCandidate: FileLoadResult.Loaded?,
        originalBytes: ByteArray?,
        ownership: YamlDocumentReplacementOwnership,
        replacement: ConfigReplacement,
        backup: Path?,
    ): FileLoadResult {
        val expected =
            validatedCandidate
                ?: return rollbackPersistedFailure(
                    originalBytes,
                    null,
                    ownership,
                    replacement,
                    "candidate validation was incomplete",
                )
        if (!persistedBytes.contentEquals(candidateBytes)) {
            return rollbackPersistedFailure(
                originalBytes,
                expected.publication,
                ownership,
                replacement,
                "persisted config differs from the validated candidate",
            )
        }
        val persisted = validateDocument(persistedBytes, validatePublication = false)
        if (persisted is FileLoadResult.Failed) {
            return rollbackPersistedFailure(
                originalBytes,
                expected.publication,
                ownership,
                replacement,
                persisted.reason,
            )
        }
        persisted as FileLoadResult.Loaded
        if (persisted.settings.lifetime != expected.settings.lifetime) {
            return rollbackPersistedFailure(
                originalBytes,
                expected.publication,
                ownership,
                replacement,
                "lifetime settings changed during config replacement",
            )
        }
        return persisted.copy(
            publication = expected.publication,
            documentRollback = DocumentRollback(originalBytes?.copyOf(), ownership.expectedCurrentBytes),
            onPublished =
                replacement.onPublished?.let { reporter ->
                    val backupFileName = requireNotNull(backup).fileName.toString()
                    ({ reporter(backupFileName) })
                },
        )
    }

    private fun validateDocument(
        bytes: ByteArray,
        validatePublication: Boolean,
    ): FileLoadResult =
        configDocumentAdapter
            ?.validate(bytes)
            ?.let { candidate -> validateDocument(candidate, validatePublication) }
            ?: FileLoadResult.Failed("embedded config is unavailable")

    private fun validateDocument(
        candidate: BukkitConfigCandidateResult,
        validatePublication: Boolean,
    ): FileLoadResult =
        when (candidate) {
            is BukkitConfigCandidateResult.Rejected -> FileLoadResult.Failed(candidate.reason)
            is BukkitConfigCandidateResult.Valid ->
                validateCombinedCandidate(
                    combineLifetime(candidate.settings, candidate.configuration, candidate.bytes),
                    validatePublication,
                )
        }

    private fun validateCombinedCandidate(
        combined: FileLoadResult,
        validatePublication: Boolean,
    ): FileLoadResult =
        when {
            combined is FileLoadResult.Failed || !validatePublication -> combined
            else -> {
                combined as FileLoadResult.Loaded
                when (val prepared = preparePublication(current.get(), combined.settings)) {
                    is PublicationPreparation.Failed -> FileLoadResult.Failed(prepared.reason)
                    is PublicationPreparation.Prepared -> combined.copy(publication = prepared.publication)
                }
            }
        }

    private fun rollbackPersistedFailure(
        originalBytes: ByteArray?,
        publication: ItemDisplaySettingsPublicationPreparation.Ready?,
        ownership: YamlDocumentReplacementOwnership,
        replacement: ConfigReplacement,
        reason: String,
    ): FileLoadResult.Failed =
        rollbackCandidateFailure(
            publication = publication,
            originalBytes = originalBytes,
            ownership = ownership,
            reason = replacement.verificationFailure(reason),
        )

    @Suppress("TooGenericExceptionCaught")
    private fun rollbackCandidateFailure(
        publication: ItemDisplaySettingsPublicationPreparation.Ready?,
        originalBytes: ByteArray?,
        ownership: YamlDocumentReplacementOwnership?,
        reason: String,
    ): FileLoadResult.Failed {
        var detail = reason
        try {
            publication?.rollback()
        } catch (error: Exception) {
            detail += "; settings publication rollback failed (${error.javaClass.simpleName})"
        }
        if (ownership != null) {
            try {
                restoreOriginalDocument(originalBytes, ownership.expectedCurrentBytes)
            } catch (error: Exception) {
                detail += "; config rollback failed (${error.javaClass.simpleName})"
            }
        }
        return FileLoadResult.Failed(detail)
    }

    private fun restoreOriginalDocument(
        originalBytes: ByteArray?,
        expectedCurrentBytes: ByteArray? = null,
    ) {
        val target = configFile.toPath().toAbsolutePath().normalize()
        if (expectedCurrentBytes != null) {
            val currentBytes = if (Files.isRegularFile(target)) Files.readAllBytes(target) else null
            if (currentBytes == null || !currentBytes.contentEquals(expectedCurrentBytes)) {
                throw ConfigRollbackOwnershipException()
            }
        }
        if (originalBytes == null) {
            Files.deleteIfExists(target)
            return
        }
        if (Files.isRegularFile(target) && Files.readAllBytes(target).contentEquals(originalBytes)) return
        val parent = checkNotNull(target.parent) { "config rollback parent is unavailable" }
        val temporary = Files.createTempFile(parent, ".config-restore-", ".tmp")
        try {
            Files.write(temporary, originalBytes)
            moveReplacing(temporary, target)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun invalidDocument(reason: String): YamlDocumentValidationResult.Invalid =
        YamlDocumentValidationResult.Invalid(
            YamlDocumentRejection(
                category = YamlDocumentRejectionCategory.VALIDATION,
                detail = reason.take(MAX_REJECTION_DETAIL_LENGTH),
            ),
        )

    @Suppress("TooGenericExceptionCaught")
    private fun loadConfiguration(bytes: ByteArray): YamlConfiguration? =
        try {
            YamlConfiguration().apply { loadFromString(bytes.toString(StandardCharsets.UTF_8)) }
        } catch (_: Exception) {
            null
        }

    @Suppress("TooGenericExceptionCaught")
    private fun readDocument(file: File): ByteArray? =
        try {
            Files.readAllBytes(file.toPath())
        } catch (_: Exception) {
            null
        }

    private companion object {
        private val DEFAULT_MIGRATION_TIMESTAMP: () -> String = AdministratorTimestampFormatter::formatNow
        private val SAFE_BACKUP_TIMESTAMP = Regex("[0-9]{4}(?:-[0-9]{2}){5}-UTC[+-][0-9]{2}-[0-9]{2}")
        private const val MAX_BACKUP_COLLISIONS = 10_000
        private const val MAX_REJECTION_DETAIL_LENGTH = 160
        private const val SCHEMA_VERSION_PATH = "schema-version"
        private const val SCHEMA_VERSION = 1
    }
}

private data class ConfigBackup(
    val label: String,
    val timestamp: String,
)

private data class ConfigReplacement(
    val fileReplacer: (Path, Path) -> Unit,
    val backup: ConfigBackup? = null,
    val preparedBackup: Path? = null,
    val failureMode: ConfigReplacementFailureMode = ConfigReplacementFailureMode.STANDARD,
    val onPublished: ((String) -> Unit)? = null,
) {
    fun backupFailure(error: Throwable): String = failureMode.backupFailure(error)

    fun writeFailure(error: Throwable): String = failureMode.writeFailure(error)

    fun verificationFailure(reason: String): String = failureMode.verificationFailure(reason)
}

private enum class ConfigReplacementFailureMode {
    STANDARD,
    CONFIG_KEY_MIGRATION,
    LEGACY_MIGRATION,
    PARSE_RECOVERY,
    ;

    fun invalidTimestampReason(): String =
        when (this) {
            STANDARD -> "config timestamp is invalid"
            CONFIG_KEY_MIGRATION -> "config key migration timestamp is invalid"
            LEGACY_MIGRATION -> "legacy config migration timestamp is invalid"
            PARSE_RECOVERY -> "config parse recovery timestamp is invalid"
        }

    fun backupFailure(error: Throwable): String =
        when (this) {
            STANDARD -> "config backup failed (${error.javaClass.simpleName})"
            CONFIG_KEY_MIGRATION -> "config key migration backup failed (${error.javaClass.simpleName})"
            LEGACY_MIGRATION -> "legacy config migration backup failed (${error.javaClass.simpleName})"
            PARSE_RECOVERY -> "config parse recovery backup failed (${error.javaClass.simpleName})"
        }

    fun writeFailure(error: Throwable): String =
        when (this) {
            STANDARD -> "config write failed (${error.javaClass.simpleName})"
            CONFIG_KEY_MIGRATION ->
                "config key migration failed; original restored (config write failed (${error.javaClass.simpleName}))"
            LEGACY_MIGRATION ->
                "legacy config migration write failed (${error.javaClass.simpleName}); original restored"
            PARSE_RECOVERY ->
                "config parse recovery write failed (${error.javaClass.simpleName}); original restored"
        }

    fun verificationFailure(reason: String): String =
        when (this) {
            STANDARD -> "persisted config verification failed ($reason)"
            CONFIG_KEY_MIGRATION -> "config key migration verification failed; original restored ($reason)"
            LEGACY_MIGRATION -> "legacy config migration verification failed; original restored ($reason)"
            PARSE_RECOVERY -> "config parse recovery verification failed; original restored ($reason)"
        }
}

private sealed interface ReplacementPreparation {
    data class Ready(
        val options: ConfigReplacement,
    ) : ReplacementPreparation

    data class Failed(
        val reason: String,
    ) : ReplacementPreparation
}

private sealed interface TogglePreparation {
    data class Ready(
        val edit: YamlDocumentEditResult,
        val replacement: ConfigReplacement,
    ) : TogglePreparation

    data class Failed(
        val reason: String,
    ) : TogglePreparation
}

private class ConfigBackupException(
    cause: Throwable,
) : RuntimeException(cause)

private class ConfigRollbackOwnershipException : IllegalStateException("config rollback ownership was lost")

private fun moveReplacing(
    source: Path,
    target: Path,
) {
    try {
        Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    } catch (_: AtomicMoveNotSupportedException) {
        Files.move(source, target, StandardCopyOption.REPLACE_EXISTING)
    }
}

private sealed interface FileLoadResult {
    data class Loaded(
        val settings: ItemDisplaySettings,
        val configuration: YamlConfiguration,
        val documentBytes: ByteArray? = null,
        val publication: ItemDisplaySettingsPublicationPreparation.Ready? = null,
        val documentRollback: DocumentRollback? = null,
        val onPublished: (() -> Unit)? = null,
    ) : FileLoadResult

    data class Failed(
        val reason: String,
    ) : FileLoadResult
}

private sealed interface PublicationPreparation {
    data class Prepared(
        val publication: ItemDisplaySettingsPublicationPreparation.Ready,
    ) : PublicationPreparation

    data class Failed(
        val reason: String,
    ) : PublicationPreparation
}

private data class DocumentRollback(
    val bytes: ByteArray?,
    val expectedCurrentBytes: ByteArray,
)

private fun YamlDocumentRejection.asFailureReason(): String = "${category.name.lowercase()}: $detail"
