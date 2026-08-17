package com.github.command1264.itemdropv2

import com.github.command1264.itemdropv2.capability.virtualstacking.VirtualStackingCapability
import com.github.command1264.itemdropv2.capability.virtualstacking.VirtualStackingCapabilityContext
import com.github.command1264.itemdropv2.capability.virtualstacking.VirtualStackingCapabilityProvider
import com.github.command1264.itemdropv2.capability.virtualstacking.VirtualStackingCapabilityReloadResult
import com.github.command1264.itemdropv2.core.BackendProvider
import com.github.command1264.itemdropv2.core.BackendSelectionResult
import com.github.command1264.itemdropv2.core.BackendSelectionService
import com.github.command1264.itemdropv2.core.BlockDropOwnershipService
import com.github.command1264.itemdropv2.core.DirectItemPickupFeedbackView
import com.github.command1264.itemdropv2.core.DirectItemPresentationJournalView
import com.github.command1264.itemdropv2.core.DirectItemPresentationView
import com.github.command1264.itemdropv2.core.EntityDamageAttributionService
import com.github.command1264.itemdropv2.core.ItemDisplayService
import com.github.command1264.itemdropv2.core.ItemDisplaySettings
import com.github.command1264.itemdropv2.core.ItemDisplaySettingsUpdateResult
import com.github.command1264.itemdropv2.core.ItemDisplayTextExpander
import com.github.command1264.itemdropv2.core.ItemLifetimeService
import com.github.command1264.itemdropv2.core.ItemMergeService
import com.github.command1264.itemdropv2.core.ItemNameService
import com.github.command1264.itemdropv2.core.ItemOwnershipAssignmentService
import com.github.command1264.itemdropv2.core.ItemPickupFeedbackResult
import com.github.command1264.itemdropv2.core.ItemPickupProtectionService
import com.github.command1264.itemdropv2.core.ItemProcessingWheel
import com.github.command1264.itemdropv2.core.ItemStatePersistenceMode
import com.github.command1264.itemdropv2.core.ItemStatePersistenceModeSelector
import com.github.command1264.itemdropv2.core.LoadedItemRefreshView
import com.github.command1264.itemdropv2.core.ManagementCommandService
import com.github.command1264.itemdropv2.core.MinecraftLanguageCode
import com.github.command1264.itemdropv2.core.PresentationBackend
import com.github.command1264.itemdropv2.core.PresentationBackendSettings
import com.github.command1264.itemdropv2.core.ServerFingerprint
import com.github.command1264.itemdropv2.core.VirtualItemStackingSettings
import com.github.command1264.itemdropv2.core.VirtualStackingRuntimeModeResolver
import com.github.command1264.itemdropv2.platform.bukkit.AtomicMinecraftLanguageRepository
import com.github.command1264.itemdropv2.platform.bukkit.BukkitBlockDropOwnershipController
import com.github.command1264.itemdropv2.platform.bukkit.BukkitCanonicalItemSpawnCapture
import com.github.command1264.itemdropv2.platform.bukkit.BukkitContainerDropOwnershipController
import com.github.command1264.itemdropv2.platform.bukkit.BukkitDecoratedPotDropOwnershipController
import com.github.command1264.itemdropv2.platform.bukkit.BukkitEntityDropOwnershipController
import com.github.command1264.itemdropv2.platform.bukkit.BukkitExistingItemRefreshController
import com.github.command1264.itemdropv2.platform.bukkit.BukkitFallingBlockDropOwnershipController
import com.github.command1264.itemdropv2.platform.bukkit.BukkitFishingDropOwnershipController
import com.github.command1264.itemdropv2.platform.bukkit.BukkitHarvestDropOwnershipController
import com.github.command1264.itemdropv2.platform.bukkit.BukkitInventoryMutationNotifier
import com.github.command1264.itemdropv2.platform.bukkit.BukkitItemDisplaySettingsManager
import com.github.command1264.itemdropv2.platform.bukkit.BukkitItemDisplayStateResolver
import com.github.command1264.itemdropv2.platform.bukkit.BukkitItemLifetimeController
import com.github.command1264.itemdropv2.platform.bukkit.BukkitItemMergeController
import com.github.command1264.itemdropv2.platform.bukkit.BukkitItemMergePolicyController
import com.github.command1264.itemdropv2.platform.bukkit.BukkitItemOwnershipDisplayRefresher
import com.github.command1264.itemdropv2.platform.bukkit.BukkitItemPickupProtectionController
import com.github.command1264.itemdropv2.platform.bukkit.BukkitItemRarityResolver
import com.github.command1264.itemdropv2.platform.bukkit.BukkitItemSpawnController
import com.github.command1264.itemdropv2.platform.bukkit.BukkitItemStateRepository
import com.github.command1264.itemdropv2.platform.bukkit.BukkitItemVisibilityResynchronizer
import com.github.command1264.itemdropv2.platform.bukkit.BukkitManagementCommandController
import com.github.command1264.itemdropv2.platform.bukkit.BukkitManagementMessageCatalog
import com.github.command1264.itemdropv2.platform.bukkit.BukkitMessageCatalogStore
import com.github.command1264.itemdropv2.platform.bukkit.BukkitNativeItemOwnershipReconciler
import com.github.command1264.itemdropv2.platform.bukkit.BukkitPickupMessageCatalog
import com.github.command1264.itemdropv2.platform.bukkit.BukkitPlaceholderApiTextExpander
import com.github.command1264.itemdropv2.platform.bukkit.BukkitProjectileBlockDropOwnershipController
import com.github.command1264.itemdropv2.platform.bukkit.BukkitServerFingerprintFactory
import com.github.command1264.itemdropv2.platform.bukkit.BukkitShearingDropOwnershipController
import com.github.command1264.itemdropv2.platform.bukkit.BukkitSpecialEntityDropOwnershipController
import com.github.command1264.itemdropv2.platform.bukkit.BukkitTrackedItemMergeTransaction
import com.github.command1264.itemdropv2.platform.bukkit.BukkitVehicleDropOwnershipController
import com.github.command1264.itemdropv2.platform.bukkit.BukkitVirtualItemPickupTransaction
import com.github.command1264.itemdropv2.platform.bukkit.ClasspathEnglishLanguageSource
import com.github.command1264.itemdropv2.platform.bukkit.ConfigKeyMigrationReport
import com.github.command1264.itemdropv2.platform.bukkit.ConfigParseRecoveryReport
import com.github.command1264.itemdropv2.platform.bukkit.ContainerDropItemStateCleaner
import com.github.command1264.itemdropv2.platform.bukkit.DelayedMainThreadTaskExecutor
import com.github.command1264.itemdropv2.platform.bukkit.DisplayWarningSink
import com.github.command1264.itemdropv2.platform.bukkit.HttpUrlConnectionResourceClient
import com.github.command1264.itemdropv2.platform.bukkit.ItemDisplaySettingsPublicationPreparation
import com.github.command1264.itemdropv2.platform.bukkit.ItemDropV2RuntimeReadiness
import com.github.command1264.itemdropv2.platform.bukkit.ItemOwnershipRefresh
import com.github.command1264.itemdropv2.platform.bukkit.LegacyConfigMigrationReport
import com.github.command1264.itemdropv2.platform.bukkit.MainThreadTaskExecutor
import com.github.command1264.itemdropv2.platform.bukkit.ManagementCommandInfo
import com.github.command1264.itemdropv2.platform.bukkit.MinecraftLanguageCache
import com.github.command1264.itemdropv2.platform.bukkit.MinecraftLanguageCatalogParser
import com.github.command1264.itemdropv2.platform.bukkit.MinecraftLanguageLoadCoordinator
import com.github.command1264.itemdropv2.platform.bukkit.MinecraftLanguageLoadSink
import com.github.command1264.itemdropv2.platform.bukkit.MinecraftLanguageTaskScheduler
import com.github.command1264.itemdropv2.platform.bukkit.MojangLanguageAssetResolver
import com.github.command1264.itemdropv2.platform.bukkit.OfficialMinecraftLanguageSource
import com.github.command1264.itemdropv2.platform.bukkit.PaperAttemptPickupEventBridge
import com.github.command1264.itemdropv2.platform.bukkit.RuntimeDiagnosticContext
import com.github.command1264.itemdropv2.platform.bukkit.TransientItemTargetLeaseFactory
import com.github.command1264.itemdropv2.platform.bukkit.VirtualItemCarrierNormalization
import com.github.command1264.itemdropv2.platform.bukkit.VirtualItemPickupFeedback
import com.github.command1264.itemdropv2.platform.bukkit.createBukkitItemRarityResolver
import com.github.command1264.itemdropv2.platform.bukkit.journal.BukkitItemStateJournalAdapter
import com.github.command1264.itemdropv2.platform.bukkit.journal.BukkitItemStateJournalCleanupController
import com.github.command1264.itemdropv2.platform.bukkit.journal.BukkitItemStateJournalPresentationRefresher
import com.github.command1264.itemdropv2.platform.bukkit.journal.BukkitItemStateRecoveryController
import com.github.command1264.itemdropv2.platform.bukkit.journal.BukkitJournalAwareItemPresentationView
import com.github.command1264.itemdropv2.platform.bukkit.journal.ItemStateJournalRuntime
import com.github.command1264.itemdropv2.platform.bukkit.journal.ItemStateJournalRuntimeOpenResult
import com.github.command1264.itemdropv2.platform.bukkit.journal.ItemStateJournalRuntimeShutdownResult
import org.bstats.bukkit.Metrics
import org.bukkit.NamespacedKey
import org.bukkit.entity.Item
import org.bukkit.entity.Player
import org.bukkit.plugin.ServicePriority
import org.bukkit.plugin.java.JavaPlugin
import java.util.Collections
import java.util.ServiceConfigurationError
import java.util.ServiceLoader

@Suppress("LargeClass", "LongMethod", "TooManyFunctions")
public class ItemDropV2Plugin : JavaPlugin() {
    private val consoleLog: ConsoleLogEmitter by lazy(LazyThreadSafetyMode.NONE) {
        ConsoleLogEmitter(logger)
    }
    private var activeBackend: PresentationBackend? = null
    private var metricsController: PluginMetricsController? = null
    private var existingItemRefreshController: BukkitExistingItemRefreshController? = null
    private var blockDropOwnershipController: BukkitBlockDropOwnershipController? = null
    private var containerDropOwnershipController: BukkitContainerDropOwnershipController? = null
    private var fallingBlockDropOwnershipController: BukkitFallingBlockDropOwnershipController? = null
    private var projectileBlockDropOwnershipController: BukkitProjectileBlockDropOwnershipController? = null
    private var decoratedPotDropOwnershipController: BukkitDecoratedPotDropOwnershipController? = null
    private var harvestDropOwnershipController: BukkitHarvestDropOwnershipController? = null
    private var entityDropOwnershipController: BukkitEntityDropOwnershipController? = null
    private var specialEntityDropOwnershipController: BukkitSpecialEntityDropOwnershipController? = null
    private var shearingDropOwnershipController: BukkitShearingDropOwnershipController? = null
    private var vehicleDropOwnershipController: BukkitVehicleDropOwnershipController? = null
    private var itemLifetimeController: BukkitItemLifetimeController? = null
    private var itemPickupProtectionController: BukkitItemPickupProtectionController? = null
    private var creativeNoCapacityPickupTaskId: Int? = null
    private var itemLifetimeTaskId: Int? = null
    private var virtualStackingCapabilityProvider: VirtualStackingCapabilityProvider? = null
    private var virtualStackingCapability: VirtualStackingCapability? = null
    private var entityOwnershipTaskId: Int? = null
    private var currentServerTick: Long = 0
    private var languageLoadCoordinator: MinecraftLanguageLoadCoordinator? = null
    private val retiringLanguageLoadCoordinators =
        Collections.synchronizedSet(mutableSetOf<MinecraftLanguageLoadCoordinator>())
    private var activeMinecraftLanguage: MinecraftLanguageCode? = null
    private val runtimeWarningLimiter = RuntimeWarningLimiter(MAX_RUNTIME_WARNINGS_PER_KEY)
    private val buildMetadata: PluginBuildMetadata by lazy {
        PluginBuildMetadata.load(javaClass)
    }
    private var diagnosticReports: DiagnosticReportService? = null
    private var diagnosticLog: DiagnosticLogGateway? = null
    private val itemStateJournalLifecycleLock = Any()
    private var itemStateJournalRuntime: ItemStateJournalRuntime? = null
    private var itemStateRecoveryController: BukkitItemStateRecoveryController? = null
    private var itemStateJournalCleanupTaskId: Int? = null
    private val runtimeReadiness = MutableItemDropV2RuntimeReadiness()

    @Volatile
    private var itemStateJournalActivationCancelled: Boolean = false

    @Volatile
    private var diagnosticMetadata: Map<String, String> = emptyMap()

    @Suppress("TooGenericExceptionCaught")
    override fun onEnable() {
        itemStateJournalActivationCancelled = false
        initializeDiagnostics()
        runtimeReadiness.markNotReady()
        try {
            server.servicesManager.register(
                ItemDropV2RuntimeReadiness::class.java,
                runtimeReadiness,
                this,
                ServicePriority.Normal,
            )
            when (
                val identity =
                    PluginIdentityGuard().evaluate(
                        description.name,
                        server.pluginManager.plugins.map { plugin -> plugin.description.name },
                    )
            ) {
                PluginIdentityResult.Accepted -> Unit
                is PluginIdentityResult.Rejected -> {
                    rejectStartup(
                        "duplicate ItemDropV2 plugin identity detected " +
                            "(matching plugins: ${identity.matchingPlugins})",
                        null,
                    )
                    return
                }
            }
            val fingerprint = BukkitServerFingerprintFactory().create(server)
            diagnosticMetadata =
                diagnosticMetadata +
                mapOf(
                    "server.platform" to fingerprint.platform.name,
                    "server.minecraft-version" to fingerprint.minecraftVersion,
                    "server.implementation-version" to fingerprint.implementationVersion,
                )
            when (val capability = loadVirtualStackingCapabilityProvider()) {
                is VirtualStackingCapabilityLoadResult.Loaded -> {
                    virtualStackingCapabilityProvider = capability.provider
                    val providers = ServiceLoader.load(BackendProvider::class.java, javaClass.classLoader).toList()
                    when (val result = BackendSelectionService().select(providers, fingerprint)) {
                        is BackendSelectionResult.Selected -> enable(result, fingerprint)
                        is BackendSelectionResult.Rejected ->
                            rejectStartup(result.diagnostic, result.recommendedArtifactClassifier)
                    }
                }
                is VirtualStackingCapabilityLoadResult.Rejected ->
                    rejectStartup("virtual stacking capability ${capability.diagnostic}", null)
            }
        } catch (error: ServiceConfigurationError) {
            rejectStartup("backend provider loading failed (${error.javaClass.simpleName})", null, error)
        } catch (error: LinkageError) {
            rejectStartup("backend linkage failed (${error.javaClass.simpleName})", null, error)
        } catch (error: RuntimeException) {
            diagnostics().error(
                "ItemDropV2 startup failed (${error.javaClass.simpleName}); disabling plugin safely.",
                RuntimeDiagnosticContext(cause = error),
            )
            server.pluginManager.disablePlugin(this)
        }
    }

    @Suppress("TooGenericExceptionCaught")
    override fun onDisable() {
        runtimeReadiness.markNotReady()
        server.servicesManager.unregister(ItemDropV2RuntimeReadiness::class.java, runtimeReadiness)
        val journalRuntime =
            synchronized(itemStateJournalLifecycleLock) {
                itemStateJournalActivationCancelled = true
                itemStateRecoveryController = null
                itemStateJournalRuntime.also { itemStateJournalRuntime = null }
            }
        itemStateJournalCleanupTaskId?.let(server.scheduler::cancelTask)
        itemStateJournalCleanupTaskId = null
        languageLoadCoordinator?.close()
        languageLoadCoordinator = null
        synchronized(retiringLanguageLoadCoordinators) {
            retiringLanguageLoadCoordinators.toList()
        }.forEach(::closeRetiringLanguageLoader)
        retiringLanguageLoadCoordinators.clear()
        metricsController?.close()
        metricsController = null
        diagnostics().resetWarningLimiter()
        existingItemRefreshController?.close()
        existingItemRefreshController = null
        blockDropOwnershipController?.close()
        blockDropOwnershipController = null
        containerDropOwnershipController?.close()
        containerDropOwnershipController = null
        fallingBlockDropOwnershipController?.close()
        fallingBlockDropOwnershipController = null
        projectileBlockDropOwnershipController?.close()
        projectileBlockDropOwnershipController = null
        decoratedPotDropOwnershipController?.close()
        decoratedPotDropOwnershipController = null
        harvestDropOwnershipController?.close()
        harvestDropOwnershipController = null
        entityOwnershipTaskId?.let(server.scheduler::cancelTask)
        entityOwnershipTaskId = null
        entityDropOwnershipController?.close()
        entityDropOwnershipController = null
        specialEntityDropOwnershipController?.close()
        specialEntityDropOwnershipController = null
        shearingDropOwnershipController?.close()
        shearingDropOwnershipController = null
        vehicleDropOwnershipController?.close()
        vehicleDropOwnershipController = null
        currentServerTick = 0
        virtualStackingCapability?.close()
        virtualStackingCapability = null
        virtualStackingCapabilityProvider = null
        itemLifetimeTaskId?.let(server.scheduler::cancelTask)
        itemLifetimeTaskId = null
        itemLifetimeController?.close()
        itemLifetimeController = null
        creativeNoCapacityPickupTaskId?.let(server.scheduler::cancelTask)
        creativeNoCapacityPickupTaskId = null
        itemPickupProtectionController?.close()
        itemPickupProtectionController = null
        activeMinecraftLanguage = null
        val backend = activeBackend
        activeBackend = null
        if (backend != null) {
            try {
                backend.close()
            } catch (error: RuntimeException) {
                diagnostics().error(
                    "ItemDropV2 backend shutdown failed (${error.javaClass.simpleName}).",
                    RuntimeDiagnosticContext(cause = error),
                )
            }
        }
        if (journalRuntime != null) {
            when (val result = journalRuntime.shutdown()) {
                ItemStateJournalRuntimeShutdownResult.Closed -> Unit
                is ItemStateJournalRuntimeShutdownResult.Failed ->
                    diagnostics().error(
                        "ItemDropV2 item-state journal shutdown failed; " +
                            "failures=${safe(result.failures.joinToString(","))}.",
                    )
            }
        }
        diagnosticReports?.close()
        diagnosticReports = null
        diagnosticLog = null
        diagnosticMetadata = emptyMap()
    }

    @Suppress("ThrowsCount", "TooGenericExceptionCaught")
    private fun enable(
        result: BackendSelectionResult.Selected,
        fingerprint: ServerFingerprint,
    ) {
        var switchableBackend: SwitchablePresentationBackend? = null
        val runtimeConfiguration =
            try {
                RuntimeConfigurationFactory(
                    resourceLoader = javaClass.classLoader,
                    dataFolder = dataFolder,
                    migrationReporter = ::reportLegacyConfigMigration,
                    configKeyMigrationReporter = ::reportConfigKeyMigration,
                    configParseRecoveryReporter = ::reportConfigParseRecovery,
                    paperClientSideTranslationSettingSupported =
                        result.provider.supportsPaperClientSideTranslationSetting,
                    virtualStackingSettingSupported =
                        checkNotNull(virtualStackingCapabilityProvider).creationCapabilityAvailable,
                    configurationFragmentResources =
                        listOfNotNull(
                            result.provider.configurationFragmentResource,
                            checkNotNull(virtualStackingCapabilityProvider).configurationFragmentResource,
                        ),
                    additionalPublicationPreparer = { _, candidate ->
                        val switcher = switchableBackend
                        if (switcher == null) {
                            ItemDisplaySettingsPublicationPreparation.Ready(candidate, commit = { null })
                        } else {
                            prepareBackendSwitch(result, fingerprint, switcher, candidate)
                        }
                    },
                ).create()
            } catch (error: RuntimeException) {
                closeAfterFailedActivation(result.backend, error)
                throw error
            }
        val settingsManager = runtimeConfiguration.settingsManager
        val settingsResult = settingsManager.reload()
        if (settingsResult is ItemDisplaySettingsUpdateResult.Failed) {
            closeRejectedBackend(result.backend)
            rejectConfiguration(listOf(settingsResult.reason))
            return
        }
        val settings = (settingsResult as ItemDisplaySettingsUpdateResult.Applied).settings
        settingsManager.consumeYamlRecoveryReports()
        runtimeConfiguration.messageCatalog.consumeRecoveryReports()
        runtimeConfiguration.messageCatalog.consumeGeneratedFallbackLanguage()?.let { language ->
            diagnostics().warning(
                "Created languages/${safe(language.code)}.yml with English fallback text; " +
                    "translate it and reload ItemDropV2.",
            )
        }
        val configuredBackend =
            if (settings.paperClientSideTranslationEnabled != false) {
                result.backend
            } else {
                closeRejectedBackend(result.backend)
                result.provider.createBackend(
                    fingerprint,
                    PresentationBackendSettings(paperClientSideTranslationEnabled = false),
                )
            }
        try {
            configuredBackend.activate()
        } catch (error: RuntimeException) {
            closeAfterFailedActivation(configuredBackend, error)
            throw error
        } catch (error: LinkageError) {
            closeAfterFailedActivation(configuredBackend, error)
            throw error
        }
        val backendFacade = SwitchablePresentationBackend(configuredBackend)
        switchableBackend = backendFacade
        val activeResult = result.copy(backend = backendFacade)
        activeBackend = backendFacade
        diagnosticMetadata =
            diagnosticMetadata +
            mapOf(
                "backend.provider-id" to result.providerId,
                "backend.id" to backendFacade.id,
                "artifact.classifier" to result.artifactClassifier,
            )
        val persistenceMode = ItemStatePersistenceModeSelector().select(fingerprint)
        diagnosticMetadata = diagnosticMetadata + ("item-state.persistence-mode" to persistenceMode.name)
        consoleLog.info(StartupSelectionMessage.format(fingerprint, backendFacade.id, persistenceMode))
        when (persistenceMode) {
            ItemStatePersistenceMode.ENTITY_PDC ->
                completeActivation(
                    activeResult,
                    fingerprint,
                    settingsManager,
                    runtimeConfiguration.messageCatalog,
                    BukkitItemStateRepository(
                        revisionGate = null,
                        loadedEntityCanonicalFallback = requiresLoadedEntityCanonicalFallback(fingerprint.minecraftVersion),
                    ),
                    settings.enabled,
                )
            ItemStatePersistenceMode.ENTITY_PDC_WITH_JOURNAL ->
                startJournalActivation(
                    activeResult,
                    fingerprint,
                    settingsManager,
                    runtimeConfiguration.messageCatalog,
                    settings.enabled,
                )
            ItemStatePersistenceMode.JOURNAL_ONLY,
            ItemStatePersistenceMode.UNSUPPORTED,
            -> {
                rejectStartup("unsupported item-state persistence endpoint", null)
                return
            }
        }
    }

    private fun prepareBackendSwitch(
        result: BackendSelectionResult.Selected,
        fingerprint: ServerFingerprint,
        switcher: SwitchablePresentationBackend,
        candidateSettings: ItemDisplaySettings,
    ): ItemDisplaySettingsPublicationPreparation {
        val enabled = candidateSettings.paperClientSideTranslationEnabled ?: true
        val candidate =
            result.provider.createBackend(
                fingerprint,
                PresentationBackendSettings(paperClientSideTranslationEnabled = enabled),
            )
        if (candidate.id == switcher.id) {
            closeRejectedBackend(candidate)
            return ItemDisplaySettingsPublicationPreparation.Ready(candidateSettings, commit = { null })
        }
        val transaction =
            switcher.prepare(candidate) { error ->
                diagnostics().warning(
                    "Retired presentation backend shutdown failed (${error.javaClass.simpleName}).",
                    RuntimeDiagnosticContext(cause = error),
                )
            }
        return ItemDisplaySettingsPublicationPreparation.Ready(
            settings = candidateSettings,
            commit = transaction::commit,
            rollback = transaction::rollback,
            complete = {
                transaction.complete()
                diagnosticMetadata = diagnosticMetadata + ("backend.id" to switcher.id)
                consoleLog.info(
                    "ItemDropV2 presentation backend changed to ${safe(switcher.id)} after configuration reload.",
                )
            },
        )
    }

    private fun completeActivation(
        result: BackendSelectionResult.Selected,
        fingerprint: ServerFingerprint,
        settingsManager: BukkitItemDisplaySettingsManager,
        messageCatalog: BukkitMessageCatalogStore,
        itemStateRepository: BukkitItemStateRepository,
        itemDisplayEnabled: Boolean,
        journalPresentationApplied: ((Item) -> Unit)? = null,
        loadedItemReadiness: (Item) -> Boolean = { true },
    ) {
        enableItemDisplay(
            settingsManager,
            messageCatalog,
            result.backend,
            fingerprint,
            result.artifactClassifier,
            itemStateRepository,
            journalPresentationApplied,
            loadedItemReadiness,
        )
        startMetrics()
        runtimeReadiness.markReady()
        consoleLog.info(
            "ItemDropV2 startup complete; ItemDropV2 backend enabled: ${safe(result.providerId)}; " +
                "artifact: ${safe(result.artifactClassifier)}; build: ${safe(buildMetadata.shortLabel())}; " +
                "item display: " +
                if (itemDisplayEnabled) "enabled" else "disabled by configuration",
        )
    }

    @Suppress("TooGenericExceptionCaught")
    private fun startJournalActivation(
        result: BackendSelectionResult.Selected,
        fingerprint: ServerFingerprint,
        settingsManager: BukkitItemDisplaySettingsManager,
        messageCatalog: BukkitMessageCatalogStore,
        itemDisplayEnabled: Boolean,
    ) {
        val journalRoot = dataFolder.toPath().resolve(ITEM_STATE_JOURNAL_DIRECTORY)
        val loadedWorldUuids = server.worlds.map { it.uid }.toSet()
        server.scheduler.runTaskAsynchronously(
            this,
            Runnable {
                when (
                    val opened =
                        ItemStateJournalRuntime.open(
                            journalRoot,
                            loadedWorldUuids,
                            failureSink = { reason ->
                                diagnosticLog?.error(
                                    "ItemDropV2 item-state journal writer entered degraded mode; reason=${safe(reason)}.",
                                )
                            },
                        )
                ) {
                    is ItemStateJournalRuntimeOpenResult.Failed ->
                        scheduleJournalActivationCompletion {
                            rejectStartup(
                                "item-state journal failed to open " +
                                    "(world=${safe(opened.worldUuid?.toString() ?: "unknown")}, " +
                                    "reason=${safe(opened.reason)})",
                                null,
                            )
                        }
                    is ItemStateJournalRuntimeOpenResult.Opened -> {
                        val accepted =
                            synchronized(itemStateJournalLifecycleLock) {
                                if (itemStateJournalActivationCancelled) {
                                    false
                                } else {
                                    itemStateJournalRuntime = opened.runtime
                                    true
                                }
                            }
                        if (!accepted) {
                            opened.runtime.shutdown()
                            return@Runnable
                        }
                        val scheduled =
                            scheduleJournalActivationCompletion {
                                finishJournalActivation(
                                    result,
                                    fingerprint,
                                    settingsManager,
                                    messageCatalog,
                                    itemDisplayEnabled,
                                    opened.runtime,
                                )
                            }
                        if (!scheduled) {
                            val owned =
                                synchronized(itemStateJournalLifecycleLock) {
                                    if (itemStateJournalRuntime === opened.runtime) {
                                        itemStateJournalRuntime = null
                                        true
                                    } else {
                                        false
                                    }
                                }
                            if (owned) opened.runtime.shutdown()
                        }
                    }
                }
            },
        )
    }

    @Suppress("TooGenericExceptionCaught")
    private fun scheduleJournalActivationCompletion(operation: () -> Unit): Boolean =
        if (itemStateJournalActivationCancelled) {
            false
        } else {
            try {
                server.scheduler.runTask(this, Runnable(operation))
                true
            } catch (_: RuntimeException) {
                false
            }
        }

    @Suppress("UNCHECKED_CAST", "TooGenericExceptionCaught")
    private fun finishJournalActivation(
        result: BackendSelectionResult.Selected,
        fingerprint: ServerFingerprint,
        settingsManager: BukkitItemDisplaySettingsManager,
        messageCatalog: BukkitMessageCatalogStore,
        itemDisplayEnabled: Boolean,
        runtime: ItemStateJournalRuntime,
    ) {
        val activationAllowed =
            synchronized(itemStateJournalLifecycleLock) {
                !itemStateJournalActivationCancelled && itemStateJournalRuntime === runtime
            }
        if (!activationAllowed || !isEnabled) return
        try {
            val presentation =
                result.backend as? DirectItemPresentationJournalView<Item>
                    ?: error("selected presentation backend does not support item-state journal snapshots")
            val adapter =
                BukkitItemStateJournalAdapter(
                    runtime = runtime,
                    presentation = presentation,
                )
            val repository =
                BukkitItemStateRepository(
                    revisionGate = adapter,
                    loadedEntityCanonicalFallback = requiresLoadedEntityCanonicalFallback(fingerprint.minecraftVersion),
                )
            val presentationRefresher =
                BukkitItemStateJournalPresentationRefresher(
                    runtime = runtime,
                    adapter = adapter,
                    failureSink = { reason ->
                        diagnostics().error(
                            "ItemDropV2 item-state presentation journal entered degraded mode; " +
                                "reason=${safe(reason)}.",
                        )
                    },
                )
            val cleanupController =
                BukkitItemStateJournalCleanupController(
                    runtime = runtime,
                    failureSink = { reason ->
                        diagnostics().error(
                            "ItemDropV2 item-state journal cleanup entered degraded mode; reason=${safe(reason)}.",
                        )
                    },
                )
            val recoveryController =
                BukkitItemStateRecoveryController(
                    repository = repository,
                    runtime = runtime,
                    entityPort = adapter,
                    taskExecutor =
                        MainThreadTaskExecutor { task ->
                            server.scheduler.runTaskLater(this, Runnable { task() }, 1L)
                        },
                    failureSink = { reason ->
                        diagnostics().error(
                            "ItemDropV2 item-state journal entered degraded mode; reason=${safe(reason)}.",
                        )
                    },
                    observedItemSink = cleanupController::observe,
                )
            val recovered = recoveryController.recoverLoadedItems(server)
            if (recovered.failures.isNotEmpty()) {
                diagnostics().error(
                    "ItemDropV2 item-state recovery failed for ${recovered.failures.size} loaded entities; " +
                        "disabling plugin before runtime listeners are registered.",
                )
                server.pluginManager.disablePlugin(this)
                return
            }
            synchronized(itemStateJournalLifecycleLock) {
                itemStateRecoveryController = recoveryController
            }
            server.pluginManager.registerEvents(recoveryController, this)
            recoveryController.scheduleLoadedChunkDiscoveryRetries(server)
            itemStateJournalCleanupTaskId =
                server.scheduler
                    .runTaskTimer(
                        this,
                        Runnable { cleanupController.process(server) },
                        ITEM_STATE_JOURNAL_CLEANUP_PERIOD_TICKS,
                        ITEM_STATE_JOURNAL_CLEANUP_PERIOD_TICKS,
                    ).taskId
            consoleLog.info(
                "ItemDropV2 item-state journal ready; inspected=${recovered.inspected}, " +
                    "restored=${recovered.restored}; maximum records per world=" +
                    "${ItemStateJournalRuntime.MAXIMUM_RECORDS_PER_WORLD}.",
            )
            completeActivation(
                result,
                fingerprint,
                settingsManager,
                messageCatalog,
                repository,
                itemDisplayEnabled,
                presentationRefresher::refresh,
                recoveryController::prepareForLifetimeRegistration,
            )
        } catch (error: RuntimeException) {
            diagnostics().error(
                "ItemDropV2 item-state journal activation failed (${error.javaClass.simpleName}); " +
                    "disabling plugin safely.",
                RuntimeDiagnosticContext(cause = error),
            )
            server.pluginManager.disablePlugin(this)
        } catch (error: LinkageError) {
            diagnostics().error(
                "ItemDropV2 item-state journal linkage failed (${error.javaClass.simpleName}); " +
                    "disabling plugin safely.",
                RuntimeDiagnosticContext(cause = error),
            )
            server.pluginManager.disablePlugin(this)
        }
    }

    private fun reportLegacyConfigMigration(report: LegacyConfigMigrationReport) {
        val ignored =
            report.ignoredPaths
                .joinToString(", ")
                .ifEmpty { "none" }
        consoleLog.info(
            "Migrated legacy ${safe(report.source.fileLabel)} config; backup: " +
                "${safe(report.backupFileName ?: "unknown")}; ignored obsolete keys: ${safe(ignored)}.",
        )
    }

    private fun reportConfigKeyMigration(report: ConfigKeyMigrationReport) {
        consoleLog.info(
            "Migrated config key ${safe(report.obsoletePath)} to ${safe(report.canonicalPath)}; " +
                "canonical value already present: ${report.canonicalValueAlreadyPresent}; backup: " +
                "${safe(report.backupFileName ?: "unknown")}.",
        )
    }

    private fun reportConfigParseRecovery(report: ConfigParseRecoveryReport) {
        val repaired = if (report.documentRecreated) report.fileName else report.repairedPaths.joinToString(", ")
        diagnostics().warning(
            "Recovered invalid ItemDropV2 YAML ${safe(report.fileName)}; repaired=${safe(repaired)}; " +
                "backup=${safe(report.backupFileName)}.",
        )
    }

    private fun startMetrics() {
        check(metricsController == null) { "bStats metrics controller is already initialized" }
        metricsController =
            PluginMetricsController(
                createSession = {
                    val metrics = Metrics(this, BSTATS_PLUGIN_ID)
                    MetricsSession(metrics::shutdown)
                },
                warningSink = { message, cause ->
                    diagnostics().warning(message, RuntimeDiagnosticContext(cause = cause))
                },
            ).also(PluginMetricsController::start)
    }

    private fun enableItemDisplay(
        settingsManager: BukkitItemDisplaySettingsManager,
        messageCatalog: BukkitMessageCatalogStore,
        backend: PresentationBackend,
        fingerprint: ServerFingerprint,
        artifactClassifier: String,
        itemStateRepository: BukkitItemStateRepository,
        journalPresentationApplied: ((Item) -> Unit)?,
        loadedItemReadiness: (Item) -> Boolean,
    ) {
        val settings = settingsManager.settings()
        val taskExecutor =
            MainThreadTaskExecutor { task ->
                server.scheduler.runTaskLater(this, Runnable { task() }, 1L)
            }
        val warningSink =
            object : DisplayWarningSink {
                override fun warn(message: String) {
                    diagnostics().runtimeWarning(message)
                }

                override fun warn(
                    message: String,
                    context: RuntimeDiagnosticContext,
                ) {
                    diagnostics().runtimeWarning(message, context)
                }
            }
        val textExpander =
            if (server.pluginManager.isPluginEnabled(PLACEHOLDER_API_PLUGIN_NAME)) {
                BukkitPlaceholderApiTextExpander(
                    offlinePlayerResolver = server::getOfflinePlayer,
                    warningSink = warningSink,
                )
            } else {
                ItemDisplayTextExpander { _, text -> text }
            }

        @Suppress("UNCHECKED_CAST")
        val backendDirectPresentationView =
            backend as? DirectItemPresentationView<Item>
                ?: error("selected presentation backend does not support direct Item presentation")
        val journalAwarePresentationView =
            journalPresentationApplied?.let { callback ->
                BukkitJournalAwareItemPresentationView(
                    view = backend,
                    directView = backendDirectPresentationView,
                    presentationApplied = callback,
                )
            }
        val presentationView = journalAwarePresentationView ?: backend
        val directPresentationView: DirectItemPresentationView<Item> =
            journalAwarePresentationView ?: backendDirectPresentationView
        val service = ItemDisplayService(settingsManager, presentationView, textExpander)
        val canonicalItemSpawnCapture = BukkitCanonicalItemSpawnCapture()
        server.pluginManager.registerEvents(canonicalItemSpawnCapture, this)

        @Suppress("UNCHECKED_CAST")
        val directPickupFeedbackView =
            backend as? DirectItemPickupFeedbackView<Player, Item>
                ?: error("selected presentation backend does not support direct Item pickup feedback")
        val rarityResolver = createBukkitItemRarityResolver(fingerprint.minecraftVersion, warningSink)
        val languageRepository = AtomicMinecraftLanguageRepository()
        val itemNameService = ItemNameService(languageRepository)
        val displayStateResolver =
            BukkitItemDisplayStateResolver(
                repository = itemStateRepository,
                settingsRepository = settingsManager,
                playerNameResolver = { ownerUuid ->
                    server.getPlayer(ownerUuid)?.name ?: server.getOfflinePlayer(ownerUuid).name
                },
                warningSink = warningSink,
            )
        val ownershipDisplayRefresher =
            BukkitItemOwnershipDisplayRefresher(
                service,
                warningSink,
                itemNameService,
                displayStateResolver,
                rarityResolver = rarityResolver,
                directPresentationView = directPresentationView,
            )
        val capabilityProvider =
            checkNotNull(virtualStackingCapabilityProvider) {
                "virtual stacking capability provider is not selected"
            }
        val virtualStackingModeResolver =
            VirtualStackingRuntimeModeResolver(
                creationCapabilityAvailable = capabilityProvider.creationCapabilityAvailable,
            )
        val capability =
            capabilityProvider.create(
                VirtualStackingCapabilityContext(
                    plugin = this,
                    settingsManager = settingsManager,
                    itemStateRepository = itemStateRepository,
                    displayRefresh = ownershipDisplayRefresher,
                    warningSink = warningSink,
                    modeResolver = virtualStackingModeResolver,
                ),
            )
        virtualStackingCapability = capability
        diagnosticMetadata =
            diagnosticMetadata +
            mapOf(
                "virtual-stacking.capability-provider" to capabilityProvider.id,
            )
        consoleLog.info("ItemDropV2 virtual stacking capability provider: ${safe(capabilityProvider.id)}.")
        publishVirtualStackingRuntimeMode(settings.virtualStacking, virtualStackingModeResolver)
        registerItemLifetime(
            settingsManager,
            itemStateRepository,
            ownershipDisplayRefresher,
            warningSink,
            taskExecutor,
            capability.carrierNormalization,
            loadedItemReadiness,
        )
        val nativeItemOwnershipReconciler =
            registerOwnership(
                settingsManager,
                itemStateRepository,
                warningSink,
                ownershipDisplayRefresher,
                fingerprint.minecraftVersion,
                canonicalItemSpawnCapture,
            )
        registerPickupProtection(
            settingsManager,
            itemStateRepository,
            messageCatalog,
            warningSink,
            ownershipDisplayRefresher,
            directPickupFeedbackView,
            canonicalItemSpawnCapture,
            virtualStackingModeResolver,
        )
        registerItemMergeThenActivate(
            registerItemMerge = {
                server.pluginManager.registerEvents(
                    BukkitItemMergePolicyController(
                        ItemMergeService(itemStateRepository, settingsManager),
                        warningSink,
                        BukkitTrackedItemMergeTransaction(
                            itemStateRepository,
                            ownershipDisplayRefresher,
                            warningSink,
                        ),
                        capability.mergeOperations,
                        ownershipDisplayRefresher,
                        capability.mergeDispatchContext,
                        TransientItemTargetLeaseFactory(itemStateRepository::leaseTransientTarget),
                    ),
                    this,
                )
            },
            activateCapability = capability::activate,
        )
        val refreshController =
            registerDisplayListeners(
                service,
                taskExecutor,
                warningSink,
                itemNameService,
                displayStateResolver,
                rarityResolver,
                directPresentationView,
                TransientItemTargetLeaseFactory(itemStateRepository::leaseTransientTarget),
                nativeItemOwnershipReconciler::reconcile,
            )
        restartLanguageLoader(languageRepository, refreshController, fingerprint, settings.minecraftLanguage)
        configureManagement(
            settingsManager,
            messageCatalog,
            languageRepository,
            refreshController,
            fingerprint,
            backend,
            virtualStackingModeResolver,
            artifactClassifier,
        )
    }

    private fun registerItemLifetime(
        settingsManager: BukkitItemDisplaySettingsManager,
        itemStateRepository: BukkitItemStateRepository,
        ownershipRefresh: ItemOwnershipRefresh,
        warningSink: DisplayWarningSink,
        taskExecutor: MainThreadTaskExecutor,
        carrierNormalization: VirtualItemCarrierNormalization,
        loadedItemReadiness: (Item) -> Boolean,
    ) {
        val wheel =
            ItemProcessingWheel(
                maximumItemsPerTick = {
                    settingsManager.settings().processing.maximumItemsPerTick
                },
            )
        val lifetimeService = ItemLifetimeService(itemStateRepository, settingsManager)
        val lifetimeController =
            BukkitItemLifetimeController(
                server,
                lifetimeService,
                wheel,
                ownershipRefresh,
                warningSink,
                taskExecutor,
                carrierNormalization,
                TransientItemTargetLeaseFactory(itemStateRepository::leaseTransientTarget),
                loadedItemReadiness = loadedItemReadiness,
            )
        itemLifetimeController = lifetimeController
        server.pluginManager.registerEvents(lifetimeController, this)
        server.scheduler.runTaskLater(
            this,
            Runnable(lifetimeController::registerLoadedItems),
            STARTUP_ITEM_RECOVERY_DELAY_TICKS,
        )
        itemLifetimeTaskId =
            server.scheduler
                .runTaskTimer(
                    this,
                    Runnable(lifetimeController::processNextSlot),
                    1L,
                    1L,
                ).taskId
    }

    private fun registerOwnership(
        settingsManager: BukkitItemDisplaySettingsManager,
        itemStateRepository: BukkitItemStateRepository,
        warningSink: DisplayWarningSink,
        itemRefresh: ItemOwnershipRefresh,
        minecraftVersion: String,
        canonicalItemSpawnCapture: BukkitCanonicalItemSpawnCapture,
    ): BukkitNativeItemOwnershipReconciler {
        val delayedTaskExecutor =
            DelayedMainThreadTaskExecutor { delayTicks, task ->
                server.scheduler.runTaskLater(this, Runnable { task() }, delayTicks)
            }
        val assignmentService = ItemOwnershipAssignmentService(itemStateRepository, settingsManager)
        val nativeItemOwnershipReconciler =
            BukkitNativeItemOwnershipReconciler(
                assignmentService = assignmentService,
                warningSink = warningSink,
            )
        registerBlockOwnership(
            settingsManager,
            itemStateRepository,
            delayedTaskExecutor,
            warningSink,
            itemRefresh,
            canonicalItemSpawnCapture,
        )
        registerFallingBlockOwnership(
            assignmentService,
            itemStateRepository,
            delayedTaskExecutor,
            warningSink,
            itemRefresh,
        )

        val entityController =
            BukkitEntityDropOwnershipController(
                attributionService = EntityDamageAttributionService(),
                assignmentService = assignmentService,
                settingsRepository = settingsManager,
                delayedTaskExecutor = delayedTaskExecutor,
                currentTick = { currentServerTick },
                warningSink = warningSink,
                itemRefresh = itemRefresh,
                transientTargetLeaseFactory =
                    TransientItemTargetLeaseFactory(itemStateRepository::leaseTransientTarget),
            )
        entityDropOwnershipController = entityController
        server.pluginManager.registerEvents(entityController, this)
        val specialEntityController =
            BukkitSpecialEntityDropOwnershipController(
                assignmentService = assignmentService,
                settingsRepository = settingsManager,
                delayedTaskExecutor = delayedTaskExecutor,
                warningSink = warningSink,
                itemRefresh = itemRefresh,
                minecraftVersion = minecraftVersion,
                transientTargetLeaseFactory =
                    TransientItemTargetLeaseFactory(itemStateRepository::leaseTransientTarget),
            )
        specialEntityDropOwnershipController = specialEntityController
        server.pluginManager.registerEvents(specialEntityController, this)
        server.pluginManager.registerEvents(
            BukkitFishingDropOwnershipController(
                assignmentService = assignmentService,
                warningSink = warningSink,
                itemRefresh = itemRefresh,
                transientTargetLeaseFactory =
                    TransientItemTargetLeaseFactory(itemStateRepository::leaseTransientTarget),
            ),
            this,
        )
        val shearingController =
            BukkitShearingDropOwnershipController(
                assignmentService = assignmentService,
                delayedTaskExecutor = delayedTaskExecutor,
                warningSink = warningSink,
                itemRefresh = itemRefresh,
                transientTargetLeaseFactory =
                    TransientItemTargetLeaseFactory(itemStateRepository::leaseTransientTarget),
            )
        shearingDropOwnershipController = shearingController
        server.pluginManager.registerEvents(shearingController, this)
        registerVehicleOwnership(
            assignmentService,
            itemStateRepository,
            delayedTaskExecutor,
            warningSink,
            itemRefresh,
        )
        entityOwnershipTaskId =
            server.scheduler
                .runTaskTimer(
                    this,
                    Runnable {
                        currentServerTick++
                        if (currentServerTick % ENTITY_COMBAT_PURGE_INTERVAL_TICKS == 0L) {
                            entityController.purgeExpiredCombats()
                        }
                    },
                    1L,
                    1L,
                ).taskId
        return nativeItemOwnershipReconciler
    }

    private fun registerBlockOwnership(
        settingsManager: BukkitItemDisplaySettingsManager,
        itemStateRepository: BukkitItemStateRepository,
        delayedTaskExecutor: DelayedMainThreadTaskExecutor,
        warningSink: DisplayWarningSink,
        itemRefresh: ItemOwnershipRefresh,
        canonicalItemSpawnCapture: BukkitCanonicalItemSpawnCapture,
    ) {
        val service = BlockDropOwnershipService(itemStateRepository, settingsManager)
        val ownershipController =
            BukkitBlockDropOwnershipController(
                service = service,
                delayedTaskExecutor = delayedTaskExecutor,
                warningSink = warningSink,
                itemRefresh = itemRefresh,
                transientTargetLeaseFactory = itemStateRepository::leaseTransientTarget,
            )
        blockDropOwnershipController = ownershipController
        server.pluginManager.registerEvents(ownershipController, this)
        val containerController =
            BukkitContainerDropOwnershipController(
                service = service,
                settingsRepository = settingsManager,
                warningSink = warningSink,
                itemRefresh = itemRefresh,
                transientTargetLeaseFactory =
                    TransientItemTargetLeaseFactory(itemStateRepository::leaseTransientTarget),
                itemStateCleaner = ContainerDropItemStateCleaner(itemStateRepository::clearItemState),
                canonicalItemSpawnCapture = canonicalItemSpawnCapture,
            )
        containerDropOwnershipController = containerController
        server.pluginManager.registerEvents(containerController, this)
        val projectileBlockController =
            BukkitProjectileBlockDropOwnershipController(
                service = service,
                delayedTaskExecutor = delayedTaskExecutor,
                warningSink = warningSink,
                itemRefresh = itemRefresh,
                transientTargetLeaseFactory =
                    TransientItemTargetLeaseFactory(itemStateRepository::leaseTransientTarget),
            )
        projectileBlockDropOwnershipController = projectileBlockController
        server.pluginManager.registerEvents(projectileBlockController, this)
        val decoratedPotController =
            BukkitDecoratedPotDropOwnershipController(
                service = service,
                delayedTaskExecutor = delayedTaskExecutor,
                warningSink = warningSink,
                itemRefresh = itemRefresh,
                transientTargetLeaseFactory =
                    TransientItemTargetLeaseFactory(itemStateRepository::leaseTransientTarget),
            )
        decoratedPotDropOwnershipController = decoratedPotController
        server.pluginManager.registerEvents(decoratedPotController, this)
        val harvestController =
            BukkitHarvestDropOwnershipController(
                service = service,
                delayedTaskExecutor = delayedTaskExecutor,
                warningSink = warningSink,
                itemRefresh = itemRefresh,
                transientTargetLeaseFactory =
                    TransientItemTargetLeaseFactory(itemStateRepository::leaseTransientTarget),
            )
        harvestDropOwnershipController = harvestController
        server.pluginManager.registerEvents(harvestController, this)
    }

    private fun registerVehicleOwnership(
        assignmentService: ItemOwnershipAssignmentService,
        itemStateRepository: BukkitItemStateRepository,
        delayedTaskExecutor: DelayedMainThreadTaskExecutor,
        warningSink: DisplayWarningSink,
        itemRefresh: ItemOwnershipRefresh,
    ) {
        val controller =
            BukkitVehicleDropOwnershipController(
                assignmentService = assignmentService,
                delayedTaskExecutor = delayedTaskExecutor,
                warningSink = warningSink,
                itemRefresh = itemRefresh,
                transientTargetLeaseFactory =
                    TransientItemTargetLeaseFactory(itemStateRepository::leaseTransientTarget),
            )
        vehicleDropOwnershipController = controller
        server.pluginManager.registerEvents(controller, this)
    }

    private fun registerFallingBlockOwnership(
        assignmentService: ItemOwnershipAssignmentService,
        itemStateRepository: BukkitItemStateRepository,
        delayedTaskExecutor: DelayedMainThreadTaskExecutor,
        warningSink: DisplayWarningSink,
        itemRefresh: ItemOwnershipRefresh,
    ) {
        val controller =
            BukkitFallingBlockDropOwnershipController(
                assignmentService = assignmentService,
                delayedTaskExecutor = delayedTaskExecutor,
                warningSink = warningSink,
                itemRefresh = itemRefresh,
                ownerKey = NamespacedKey(this, FALLING_BLOCK_OWNER_KEY),
                transientTargetLeaseFactory =
                    TransientItemTargetLeaseFactory(itemStateRepository::leaseTransientTarget),
            )
        fallingBlockDropOwnershipController = controller
        server.pluginManager.registerEvents(controller, this)
    }

    private fun registerPickupProtection(
        settingsManager: BukkitItemDisplaySettingsManager,
        itemStateRepository: BukkitItemStateRepository,
        messageCatalog: BukkitMessageCatalogStore,
        warningSink: DisplayWarningSink,
        itemRefresh: ItemOwnershipRefresh,
        pickupFeedbackView: DirectItemPickupFeedbackView<Player, Item>,
        canonicalItemSpawnCapture: BukkitCanonicalItemSpawnCapture,
        virtualStackingModeResolver: VirtualStackingRuntimeModeResolver,
    ) {
        val virtualPickupTransaction =
            BukkitVirtualItemPickupTransaction(
                itemStateRepository,
                settingsManager,
                itemStateRepository::clearItemStackFallback,
                transientTargetLeaseFactory =
                    TransientItemTargetLeaseFactory(itemStateRepository::leaseTransientTarget),
                canonicalItemSpawnCapture = canonicalItemSpawnCapture,
                modeResolver = virtualStackingModeResolver,
            )
        val delayedTaskExecutor =
            DelayedMainThreadTaskExecutor { delayTicks, task ->
                server.scheduler.runTaskLater(this, Runnable { task() }, delayTicks)
            }
        val visibilityResynchronizer =
            BukkitItemVisibilityResynchronizer(
                plugin = this,
                delayedTaskExecutor = delayedTaskExecutor,
                warningSink = warningSink,
            )
        val controller =
            BukkitItemPickupProtectionController(
                service = ItemPickupProtectionService(itemStateRepository, settingsManager),
                settingsRepository = settingsManager,
                messages = BukkitPickupMessageCatalog.fromStore(messageCatalog),
                ownerNameResolver = { ownerUuid ->
                    server.getPlayer(ownerUuid)?.name ?: server.getOfflinePlayer(ownerUuid).name
                },
                warningSink = warningSink,
                pickedUpStateCleaner = itemStateRepository::clearItemStackFallback,
                virtualPickupHandling = virtualPickupTransaction,
                inventoryMutationNotifier = BukkitInventoryMutationNotifier(),
                virtualPickupFeedback =
                    VirtualItemPickupFeedback { player, item, consumedAmount, remainingAmount ->
                        val feedbackResult =
                            pickupFeedbackView.playPickupFeedback(
                                player,
                                item,
                                consumedAmount.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                                carrierRemains = remainingAmount > 0L,
                                carrierResynchronizationSupported =
                                    remainingAmount > 0L && visibilityResynchronizer.isSupported(player),
                            )
                        if (feedbackResult == ItemPickupFeedbackResult.CarrierResynchronizationRequired) {
                            visibilityResynchronizer.resynchronize(player, item)
                        }
                    },
                itemRefresh = itemRefresh,
                delayedTaskExecutor = delayedTaskExecutor,
                transientTargetLeaseFactory =
                    TransientItemTargetLeaseFactory(itemStateRepository::leaseTransientTarget),
            )
        itemPickupProtectionController = controller
        server.pluginManager.registerEvents(controller, this)
        val paperAttemptBridgeRegistered =
            PaperAttemptPickupEventBridge.registerIfAvailable(this, controller, warningSink)
        consoleLog.info(
            "ItemDropV2 Creative no-capacity pickup path: " +
                if (paperAttemptBridgeRegistered) {
                    "paper-attempt-event + collision-fallback"
                } else {
                    "collision-fallback"
                },
        )
        creativeNoCapacityPickupTaskId =
            server.scheduler
                .runTaskTimer(
                    this,
                    Runnable { controller.processCreativeNoCapacityPickups(server.onlinePlayers) },
                    1L,
                    1L,
                ).taskId
    }

    private fun registerDisplayListeners(
        service: ItemDisplayService,
        taskExecutor: MainThreadTaskExecutor,
        warningSink: DisplayWarningSink,
        itemNameService: ItemNameService,
        displayStateResolver: BukkitItemDisplayStateResolver,
        rarityResolver: BukkitItemRarityResolver,
        directPresentationView: DirectItemPresentationView<Item>,
        transientTargetLeaseFactory: TransientItemTargetLeaseFactory,
        spawnReconciliation: (Item) -> Unit,
    ): BukkitExistingItemRefreshController {
        server.pluginManager.registerEvents(
            BukkitItemSpawnController(
                service,
                taskExecutor,
                warningSink,
                itemNameService,
                displayStateResolver = displayStateResolver,
                rarityResolver = rarityResolver,
                directPresentationView = directPresentationView,
                transientTargetLeaseFactory = transientTargetLeaseFactory,
                spawnReconciliation = spawnReconciliation,
            ),
            this,
        )
        server.pluginManager.registerEvents(
            BukkitItemMergeController(
                service,
                taskExecutor,
                warningSink,
                itemNameService,
                displayStateResolver = displayStateResolver,
                rarityResolver = rarityResolver,
            ),
            this,
        )
        val refreshController =
            BukkitExistingItemRefreshController(
                server,
                service,
                taskExecutor,
                warningSink,
                itemNameService,
                displayStateResolver = displayStateResolver,
                rarityResolver = rarityResolver,
            )
        existingItemRefreshController = refreshController
        server.pluginManager.registerEvents(refreshController, this)
        refreshController.refreshLoadedChunks()
        return refreshController
    }

    private fun configureManagement(
        settingsManager: BukkitItemDisplaySettingsManager,
        messageCatalog: BukkitMessageCatalogStore,
        languageRepository: AtomicMinecraftLanguageRepository,
        refreshController: BukkitExistingItemRefreshController,
        fingerprint: ServerFingerprint,
        backend: PresentationBackend,
        virtualStackingModeResolver: VirtualStackingRuntimeModeResolver,
        artifactClassifier: String,
    ) {
        val managementService =
            ManagementCommandService(
                settingsManager,
                LoadedItemRefreshView { updatedSettings ->
                    runtimeWarningLimiter.reset()
                    if (activeMinecraftLanguage != updatedSettings.minecraftLanguage) {
                        restartLanguageLoader(
                            languageRepository,
                            refreshController,
                            fingerprint,
                            updatedSettings.minecraftLanguage,
                        )
                    }
                    when (val capabilityResult = virtualStackingCapability?.reload(updatedSettings.virtualStacking)) {
                        VirtualStackingCapabilityReloadResult.Applied -> Unit
                        is VirtualStackingCapabilityReloadResult.Failed ->
                            error("virtual stacking capability reload failed (${capabilityResult.errorType})")
                        null -> error("virtual stacking capability is not active")
                    }
                    publishVirtualStackingRuntimeMode(updatedSettings.virtualStacking, virtualStackingModeResolver)
                    refreshController.refreshLoadedChunks()
                },
            )
        val commandController =
            BukkitManagementCommandController(
                service = managementService,
                language = { settingsManager.settings().messageLanguage },
                messages = BukkitManagementMessageCatalog.fromStore(messageCatalog),
                pluginName = managementInformationValue(description.name),
                version = buildMetadata.displayVersion(description.version),
                infoProvider = {
                    val currentSettings = settingsManager.settings()
                    ManagementCommandInfo(
                        edition = managementInformationValue(editionDisplayName(artifactClassifier)),
                        server =
                            managementInformationValue(
                                "${fingerprint.platform.name.lowercase().replaceFirstChar(Char::uppercase)} " +
                                    fingerprint.minecraftVersion,
                            ),
                        backend = managementInformationValue(backend.id),
                        persistence =
                            managementInformationValue(
                                ItemStatePersistenceModeSelector()
                                    .select(fingerprint)
                                    .name
                                    .lowercase()
                                    .replace('_', '-'),
                            ),
                        virtualStacking =
                            managementInformationValue(
                                virtualStackingRuntimeModeName(
                                    currentSettings.virtualStacking,
                                    virtualStackingModeResolver,
                                ),
                            ),
                        pluginLanguage = managementInformationValue(currentSettings.messageLanguage.code),
                        minecraftLanguage = managementInformationValue(currentSettings.minecraftLanguage.value),
                    )
                },
                failureLogger = { reason ->
                    diagnostics().warning("ItemDropV2 management command failed: ${safe(reason)}")
                },
                additionalYamlRecoveryConsumer = {
                    settingsManager.consumeYamlRecoveryReports() + messageCatalog.consumeRecoveryReports()
                },
            )
        val command = requireNotNull(getCommand("itemdrop")) { "plugin.yml does not declare itemdrop command" }
        command.setExecutor(commandController)
        command.tabCompleter = commandController
    }

    private fun publishVirtualStackingRuntimeMode(
        settings: VirtualItemStackingSettings,
        modeResolver: VirtualStackingRuntimeModeResolver,
    ) {
        val mode = virtualStackingRuntimeModeName(settings, modeResolver)
        diagnosticMetadata = diagnosticMetadata + ("virtual-stacking.runtime-mode" to mode)
        consoleLog.info("ItemDropV2 virtual stacking runtime mode: $mode; materialization=disabled.")
    }

    private fun editionDisplayName(artifactClassifier: String): String =
        when (artifactClassifier) {
            "community" -> "Community"
            "pro" -> "Pro"
            "single-runtime" -> "Runtime"
            else -> artifactClassifier
        }

    private fun managementInformationValue(value: String): String {
        val normalized =
            value
                .map { character ->
                    when {
                        character == '&' -> '＆'
                        character == '§' ||
                            character.isISOControl() ||
                            Character.getType(character) == Character.FORMAT.toInt() ||
                            Character.getType(character) == Character.LINE_SEPARATOR.toInt() ||
                            Character.getType(character) == Character.PARAGRAPH_SEPARATOR.toInt() -> ' '
                        else -> character
                    }
                }.joinToString("")
                .trim()
                .replace(Regex("\\s+"), " ")
                .take(MAX_MANAGEMENT_INFORMATION_VALUE_LENGTH)
        return normalized.ifEmpty { UNKNOWN_MANAGEMENT_INFORMATION_VALUE }
    }

    private fun loadVirtualStackingCapabilityProvider(): VirtualStackingCapabilityLoadResult =
        VirtualStackingCapabilityLoader {
            ServiceLoader
                .load(VirtualStackingCapabilityProvider::class.java, javaClass.classLoader)
                .toList()
        }.load()

    private fun restartLanguageLoader(
        repository: AtomicMinecraftLanguageRepository,
        refreshController: BukkitExistingItemRefreshController,
        fingerprint: ServerFingerprint,
        language: MinecraftLanguageCode,
    ) {
        languageLoadCoordinator?.let(::retireLanguageLoader)
        languageLoadCoordinator =
            createLanguageLoadCoordinator(repository, refreshController::refreshLoadedChunks).also { coordinator ->
                activeMinecraftLanguage = language
                coordinator.start(fingerprint.minecraftVersion, language)
            }
    }

    private fun retireLanguageLoader(coordinator: MinecraftLanguageLoadCoordinator) {
        coordinator.cancel()
        retiringLanguageLoadCoordinators += coordinator
        var scheduled = false
        scheduleMinecraftLanguageTask(::isEnabled) {
            server.scheduler.runTaskAsynchronously(
                this,
                Runnable { closeRetiringLanguageLoader(coordinator) },
            )
            scheduled = true
        }
        if (!scheduled) closeRetiringLanguageLoader(coordinator)
    }

    @Suppress("TooGenericExceptionCaught")
    private fun closeRetiringLanguageLoader(coordinator: MinecraftLanguageLoadCoordinator) {
        try {
            coordinator.close()
        } catch (error: RuntimeException) {
            diagnostics().warning(
                "Retired Minecraft language loader shutdown failed (${error.javaClass.simpleName}).",
                RuntimeDiagnosticContext(cause = error),
            )
        } finally {
            retiringLanguageLoadCoordinators -= coordinator
        }
    }

    private fun createLanguageLoadCoordinator(
        repository: AtomicMinecraftLanguageRepository,
        refreshExistingItems: () -> Unit,
    ): MinecraftLanguageLoadCoordinator {
        val parser = MinecraftLanguageCatalogParser()
        val serverClassLoader = server.javaClass.classLoader
        val source =
            OfficialMinecraftLanguageSource(
                ClasspathEnglishLanguageSource { path ->
                    serverClassLoader.getResourceAsStream(path)
                        ?: org.bukkit.Server.Spigot::class.java.getResourceAsStream("/$path")
                },
                MojangLanguageAssetResolver(HttpUrlConnectionResourceClient()),
            )
        val scheduler =
            object : MinecraftLanguageTaskScheduler {
                override fun executeAsync(task: () -> Unit) {
                    scheduleMinecraftLanguageTask(this@ItemDropV2Plugin::isEnabled) {
                        server.scheduler.runTaskAsynchronously(this@ItemDropV2Plugin, Runnable { task() })
                    }
                }

                override fun executeMain(task: () -> Unit) {
                    scheduleMinecraftLanguageTask(this@ItemDropV2Plugin::isEnabled) {
                        server.scheduler.runTask(this@ItemDropV2Plugin, Runnable { task() })
                    }
                }
            }
        val sink =
            object : MinecraftLanguageLoadSink {
                override fun loaded(
                    source: String,
                    translationCount: Int,
                ) {
                    consoleLog.info(
                        "Minecraft language loaded from ${safe(source)} with $translationCount item translations.",
                    )
                    refreshExistingItems()
                }

                override fun warn(diagnostic: String) {
                    warnRuntimeFailure("Minecraft language: $diagnostic; using Material fallback names")
                }
            }
        return MinecraftLanguageLoadCoordinator(
            cache = MinecraftLanguageCache(dataFolder.toPath().resolve("minecraft-languages"), parser),
            source = source,
            parser = parser,
            repository = repository,
            scheduler = scheduler,
            sink = sink,
        )
    }

    @Suppress("TooGenericExceptionCaught")
    private fun closeRejectedBackend(backend: PresentationBackend) {
        try {
            backend.close()
        } catch (error: RuntimeException) {
            diagnostics().error(
                "Rejected backend shutdown failed (${error.javaClass.simpleName}).",
                RuntimeDiagnosticContext(cause = error),
            )
        } catch (error: LinkageError) {
            diagnostics().error(
                "Rejected backend linkage shutdown failed (${error.javaClass.simpleName}).",
                RuntimeDiagnosticContext(cause = error),
            )
        }
    }

    private fun rejectConfiguration(errors: List<String>) {
        errors.take(MAX_CONFIG_ERRORS).forEach { error ->
            diagnostics().error("ItemDropV2 configuration rejected: ${safe(error)}")
        }
        if (errors.size > MAX_CONFIG_ERRORS) {
            diagnostics().error("ItemDropV2 configuration has ${errors.size - MAX_CONFIG_ERRORS} additional errors.")
        }
        server.pluginManager.disablePlugin(this)
    }

    private fun warnRuntimeFailure(message: String) {
        diagnostics().runtimeWarning(message)
    }

    @Suppress("TooGenericExceptionCaught")
    private fun closeAfterFailedActivation(
        backend: PresentationBackend,
        activationFailure: Throwable,
    ) {
        try {
            backend.close()
        } catch (closeFailure: RuntimeException) {
            activationFailure.addSuppressed(closeFailure)
        } catch (closeFailure: LinkageError) {
            activationFailure.addSuppressed(closeFailure)
        }
    }

    private fun rejectStartup(
        diagnostic: String,
        recommendation: String?,
        cause: Throwable? = null,
    ) {
        val artifact =
            recommendation?.let {
                "ItemDropV2-${description.version}-${safe(it)}.jar"
            } ?: "a correctly packaged single-backend ItemDropV2 artifact"
        diagnostics().error(
            "ItemDropV2 backend compatibility check failed; ItemDropV2 startup rejected: " +
                "${safe(diagnostic)}; recommended artifact: $artifact",
            RuntimeDiagnosticContext(cause = cause),
        )
        server.pluginManager.disablePlugin(this)
    }

    private fun initializeDiagnostics() {
        val enabled =
            try {
                DiagnosticReportSwitch.isEnabled(System.getProperty(DiagnosticReportSwitch.PROPERTY_NAME))
            } catch (_: SecurityException) {
                false
            }
        diagnosticMetadata =
            mapOf(
                "plugin.version" to description.version,
                "java.version" to System.getProperty("java.version", "unknown"),
                "java.vendor" to System.getProperty("java.vendor", "unknown"),
                "os.name" to System.getProperty("os.name", "unknown"),
                "os.arch" to System.getProperty("os.arch", "unknown"),
            ) + buildMetadata.diagnosticFields()
        val reports =
            DiagnosticReportService(
                enabled = enabled,
                dataDirectory = dataFolder.toPath(),
                relativeDataDirectory = "plugins/${dataFolder.name}",
                metadataProvider = { diagnosticMetadata },
                failureSink = diagnosticReportFailureSink(logger),
            )
        diagnosticReports = reports
        diagnosticLog = DiagnosticLogGateway(logger, reports, runtimeWarningLimiter)
        if (enabled) {
            consoleLog.info(
                "ItemDropV2 diagnostic reports enabled; WARN and ERROR details will be stored under " +
                    "plugins/${dataFolder.name}/reports/.",
            )
        }
    }

    private fun diagnostics(): DiagnosticLogGateway = checkNotNull(diagnosticLog) { "ItemDropV2 diagnostics are not initialized" }

    private fun safe(value: String): String =
        value
            .replace(unsafeLogCharacters, " ")
            .take(MAX_LOG_VALUE_LENGTH)

    private companion object {
        private val unsafeLogCharacters = Regex("[\\r\\n\\t]")
        private const val MAX_LOG_VALUE_LENGTH = 500
        private const val MAX_MANAGEMENT_INFORMATION_VALUE_LENGTH = 120
        private const val UNKNOWN_MANAGEMENT_INFORMATION_VALUE = "unknown"
        private const val MAX_CONFIG_ERRORS = 20
        private const val MAX_RUNTIME_WARNINGS_PER_KEY = 5
        private const val ITEM_STATE_JOURNAL_DIRECTORY = "state-journal"
        private const val ITEM_STATE_JOURNAL_CLEANUP_PERIOD_TICKS = 100L
        private const val FALLING_BLOCK_OWNER_KEY = "falling-block-owner"
        private const val STARTUP_ITEM_RECOVERY_DELAY_TICKS = 20L
        private const val ENTITY_COMBAT_PURGE_INTERVAL_TICKS = 20L
        private const val PLACEHOLDER_API_PLUGIN_NAME = "PlaceholderAPI"
    }
}

internal fun requiresLoadedEntityCanonicalFallback(minecraftVersion: String): Boolean {
    val release = minecraftVersion.substringBefore('-').split('.')
    return release.size >= 2 &&
        release[0].toIntOrNull() == LEGACY_CANONICAL_FALLBACK_MAJOR &&
        release[1].toIntOrNull() == LEGACY_CANONICAL_FALLBACK_MINOR
}

private const val LEGACY_CANONICAL_FALLBACK_MAJOR = 1
private const val LEGACY_CANONICAL_FALLBACK_MINOR = 14
