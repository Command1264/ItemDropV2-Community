package com.github.command1264.itemdropv2.platform.bukkit

import com.github.command1264.itemdropv2.core.DirectItemPresentationView
import com.github.command1264.itemdropv2.core.ItemDisplayOutcome
import com.github.command1264.itemdropv2.core.ItemDisplayRequest
import com.github.command1264.itemdropv2.core.ItemDisplayService
import com.github.command1264.itemdropv2.core.ItemDisplaySettingsRepository
import com.github.command1264.itemdropv2.core.ItemLifetimeSettings
import com.github.command1264.itemdropv2.core.ItemNameRequest
import com.github.command1264.itemdropv2.core.ItemNameService
import com.github.command1264.itemdropv2.core.ItemOwnerDisplay
import com.github.command1264.itemdropv2.core.ItemPresentation
import com.github.command1264.itemdropv2.core.ItemPresentationView
import com.github.command1264.itemdropv2.core.ItemState
import com.github.command1264.itemdropv2.core.ItemStateLoadResult
import com.github.command1264.itemdropv2.core.ItemStateRepository
import com.github.command1264.itemdropv2.core.MinecraftItemRarity
import com.github.command1264.itemdropv2.core.MinecraftLanguageRepository
import com.github.command1264.itemdropv2.core.PresentationResult
import org.bukkit.Material
import org.bukkit.entity.Item
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.entity.ItemSpawnEvent

public fun interface DisplayWarningSink {
    public fun warn(message: String)

    public fun warn(
        message: String,
        context: RuntimeDiagnosticContext,
    ) {
        warn(message)
    }
}

public data class RuntimeDiagnosticContext(
    public val fields: Map<String, String> = emptyMap(),
    public val cause: Throwable? = null,
) {
    init {
        require(fields.keys.none(String::isBlank)) { "diagnostic field names must not be blank" }
    }
}

public fun interface MainThreadTaskExecutor {
    public fun execute(task: () -> Unit)
}

public class BukkitItemSpawnController(
    service: ItemDisplayService,
    taskExecutor: MainThreadTaskExecutor,
    private val warningSink: DisplayWarningSink,
    private val itemNameService: ItemNameService = fallbackItemNameService,
    private val translationKeyResolver: BukkitItemTranslationKeyResolver = BukkitItemTranslationKeyResolver(),
    private val displayStateResolver: ItemDisplayStateResolver = ItemDisplayStateResolver { ItemDisplayStateSnapshot() },
    private val rarityResolver: BukkitItemRarityResolver = BukkitItemRarityResolver { MinecraftItemRarity.COMMON },
    private val directPresentationView: DirectItemPresentationView<Item>? = null,
    private val transientTargetLeaseFactory: TransientItemTargetLeaseFactory =
        TransientItemTargetLeaseFactory { TransientItemTargetLease {} },
    private val spawnReconciliation: (Item) -> Unit = {},
) : Listener {
    private val coordinator = ScheduledItemDisplayCoordinator(service, taskExecutor, warningSink)

    @Suppress("TooGenericExceptionCaught")
    @EventHandler(ignoreCancelled = true)
    public fun onItemSpawn(event: ItemSpawnEvent) {
        if (event.isAsynchronous) {
            warningSink.warn("ignored asynchronous ItemSpawnEvent")
            return
        }

        val item = event.entity
        coordinator.submitDeferred(item, directPresentationView, transientTargetLeaseFactory) {
            spawnReconciliation(item)
            createItemDisplayRequest(
                item,
                warningSink,
                itemNameService,
                translationKeyResolver,
                displayStateResolver,
                rarityResolver,
            )
        }
    }
}

@Suppress("ReturnCount")
internal fun createItemDisplayRequest(
    entity: Item,
    warningSink: DisplayWarningSink,
    itemNameService: ItemNameService = fallbackItemNameService,
    translationKeyResolver: BukkitItemTranslationKeyResolver = BukkitItemTranslationKeyResolver(),
    displayStateResolver: ItemDisplayStateResolver = ItemDisplayStateResolver { ItemDisplayStateSnapshot() },
    rarityResolver: BukkitItemRarityResolver = BukkitItemRarityResolver { MinecraftItemRarity.COMMON },
): ItemDisplayRequest? {
    if (!entity.isItemDropLifecycleEligible()) return null
    val stack = entity.itemStack
    if (stack.type == Material.AIR || stack.amount <= 0) return null
    val meta = stack.itemMeta
    val customName =
        if (meta != null && meta.hasDisplayName()) {
            meta.displayName.takeIf { name ->
                name.isNotBlank() && name.length <= MAX_ITEM_NAME_LENGTH && name.none(Char::isISOControl)
            }
        } else {
            null
        }
    if (meta != null && meta.hasDisplayName() && customName == null) {
        warningSink.warn("invalid item display name replaced with Material fallback")
    }
    val fallbackName = MaterialDisplayNameFormatter.format(stack.type.name)
    val translationKey = translationKeyResolver.resolve(stack)
    val itemName =
        itemNameService.resolve(
            ItemNameRequest(
                customName = customName,
                translationKey = translationKey,
                fallbackName = fallbackName,
            ),
        )
    val displayState = displayStateResolver.resolve(entity.uniqueId)
    val ownerDisplay = displayState.owner
    return ItemDisplayRequest(
        entityId = entity.uniqueId,
        worldName = entity.world.name,
        itemName = itemName,
        amount = displayState.virtualAmount ?: stack.amount.toLong(),
        ownerName = ownerDisplay?.playerName,
        additionalOwnerCount = ownerDisplay?.additionalOwnerCount ?: 0,
        placeholderPlayerId = ownerDisplay?.playerId,
        rarity = rarityResolver.resolve(stack),
        customNameHasColor = customName?.let(::hasExplicitItemNameColor) == true,
        protectionSecondsRemaining = displayState.protectionSecondsRemaining,
        lifetimeSecondsElapsed = displayState.lifetimeSecondsElapsed,
        lifetimeSecondsRemaining = displayState.lifetimeSecondsRemaining,
        translationKey = translationKey.takeIf { customName == null },
    )
}

internal fun hasExplicitItemNameColor(name: String): Boolean =
    LEGACY_ITEM_NAME_COLOR.containsMatchIn(name) || LEGACY_HEX_ITEM_NAME_COLOR.containsMatchIn(name)

public data class ItemDisplayStateSnapshot(
    public val owner: ItemOwnerDisplay? = null,
    public val protectionSecondsRemaining: Long = 0,
    public val lifetimeSecondsRemaining: Long? = null,
    public val virtualAmount: Long? = null,
    public val lifetimeSecondsElapsed: Long? = null,
) {
    init {
        require(protectionSecondsRemaining >= 0) { "protection seconds must not be negative" }
        require(owner != null || protectionSecondsRemaining == 0L) {
            "protection seconds require an owner"
        }
        require(lifetimeSecondsElapsed == null || lifetimeSecondsElapsed >= 0) {
            "elapsed lifetime must be zero, positive, or absent"
        }
        require(
            lifetimeSecondsRemaining == null ||
                lifetimeSecondsRemaining == ItemLifetimeSettings.NEVER_EXPIRES ||
                lifetimeSecondsRemaining >= 0,
        ) {
            "remaining lifetime must be -1, zero, positive, or absent"
        }
        require(virtualAmount == null || virtualAmount > 0) {
            "virtual amount must be positive or absent"
        }
    }
}

public fun interface ItemDisplayStateResolver {
    public fun resolve(entityId: java.util.UUID): ItemDisplayStateSnapshot
}

public class BukkitItemDisplayStateResolver(
    private val repository: ItemStateRepository,
    private val settingsRepository: ItemDisplaySettingsRepository,
    private val playerNameResolver: (java.util.UUID) -> String?,
    private val warningSink: DisplayWarningSink,
) : ItemDisplayStateResolver {
    override fun resolve(entityId: java.util.UUID): ItemDisplayStateSnapshot =
        when (val result = repository.load(entityId)) {
            is ItemStateLoadResult.Loaded -> resolveLoaded(result.state)
            is ItemStateLoadResult.Legacy -> resolveLegacy(result)
            ItemStateLoadResult.Absent,
            ItemStateLoadResult.MissingTarget,
            -> ItemDisplayStateSnapshot()
            is ItemStateLoadResult.Invalid -> warn("invalid item display state")
            is ItemStateLoadResult.UnsupportedSchema -> warn("unsupported item display state schema ${result.actualVersion}")
            is ItemStateLoadResult.Failed -> warn("item display state read failed (${result.errorType})")
        }

    private fun resolveLoaded(state: ItemState): ItemDisplayStateSnapshot {
        val settings = settingsRepository.settings()
        val owner =
            state.ownership?.let { ownership ->
                resolveOwner(
                    ownership.eligibleOwnerUuids,
                    (settings.ownership.protectionSeconds - ownership.protectionSecondsRemaining).coerceAtLeast(0),
                )
            }
        val protection = if (owner == null) 0 else requireNotNull(state.ownership).protectionSecondsRemaining
        return ItemDisplayStateSnapshot(
            owner = owner,
            protectionSecondsRemaining = protection,
            lifetimeSecondsElapsed = state.elapsedLifetimeSeconds.takeIf { state.originalLifetimeSeconds != null },
            lifetimeSecondsRemaining = state.remainingLifetimeSeconds,
            virtualAmount = state.virtualAmount?.value,
        )
    }

    private fun resolveLegacy(result: ItemStateLoadResult.Legacy): ItemDisplayStateSnapshot {
        val ownerId = result.state.ownerUuid
        val owner =
            ownerId
                ?.let(::resolvePlayerName)
                ?.let { ItemOwnerDisplay(it, 0, ownerId) }
        return ItemDisplayStateSnapshot(
            owner = owner,
            protectionSecondsRemaining = if (owner == null) 0 else result.state.ownerTime ?: 0,
            lifetimeSecondsRemaining = null,
        )
    }

    private fun resolveOwner(
        owners: List<java.util.UUID>,
        elapsedProtectionSeconds: Long,
    ): ItemOwnerDisplay? {
        val rotationSeconds =
            settingsRepository
                .settings()
                .ownership.display.rotationSeconds
        val start = ((elapsedProtectionSeconds / rotationSeconds) % owners.size).toInt()
        return owners.indices
            .asSequence()
            .map { offset -> owners[(start + offset) % owners.size] }
            .mapNotNull { ownerId ->
                resolvePlayerName(ownerId)?.let { ownerName ->
                    ItemOwnerDisplay(ownerName, owners.size - 1, ownerId)
                }
            }.firstOrNull()
    }

    private fun resolvePlayerName(ownerUuid: java.util.UUID): String? =
        playerNameResolver(ownerUuid)?.takeIf { name ->
            name.isNotBlank() && name.length <= MAX_PLAYER_NAME_LENGTH && name.none(Char::isISOControl)
        }

    private fun warn(message: String): ItemDisplayStateSnapshot {
        warningSink.warn(message)
        return ItemDisplayStateSnapshot()
    }

    private companion object {
        private const val MAX_PLAYER_NAME_LENGTH = 64
    }
}

public class BukkitItemOwnershipDisplayRefresher(
    service: ItemDisplayService,
    private val warningSink: DisplayWarningSink,
    private val itemNameService: ItemNameService = fallbackItemNameService,
    private val displayStateResolver: ItemDisplayStateResolver = ItemDisplayStateResolver { ItemDisplayStateSnapshot() },
    private val translationKeyResolver: BukkitItemTranslationKeyResolver = BukkitItemTranslationKeyResolver(),
    private val rarityResolver: BukkitItemRarityResolver = BukkitItemRarityResolver { MinecraftItemRarity.COMMON },
    private val itemResolver: (java.util.UUID) -> Item? = ::resolveBukkitItem,
    private val directPresentationView: DirectItemPresentationView<Item>? = null,
) : ItemOwnershipRefresh {
    private val processor = ItemDisplayProcessor(service, warningSink)

    @Suppress("TooGenericExceptionCaught")
    override fun refresh(entityId: java.util.UUID) {
        try {
            itemResolver(entityId)?.let(::refresh)
        } catch (error: RuntimeException) {
            warningSink.warn("item ownership display refresh failed (${error.javaClass.simpleName})")
        }
    }

    @Suppress("TooGenericExceptionCaught")
    override fun refresh(item: Item) {
        try {
            createItemDisplayRequest(
                item,
                warningSink,
                itemNameService,
                translationKeyResolver,
                displayStateResolver,
                rarityResolver,
            )?.let { request ->
                if (directPresentationView == null) {
                    processor.display(request)
                } else {
                    processor.display(item, request, directPresentationView)
                }
            }
        } catch (error: RuntimeException) {
            warningSink.warn("item ownership display refresh failed (${error.javaClass.simpleName})")
        }
    }
}

private const val MAX_ITEM_NAME_LENGTH = 256
private val LEGACY_ITEM_NAME_COLOR = Regex("(?i)[§&][0-9a-f]")
private val LEGACY_HEX_ITEM_NAME_COLOR = Regex("(?i)§x(?:§[0-9a-f]){6}")
internal val fallbackItemNameService = ItemNameService(MinecraftLanguageRepository { null })

internal class ScheduledItemDisplayCoordinator(
    service: ItemDisplayService,
    private val taskExecutor: MainThreadTaskExecutor,
    private val warningSink: DisplayWarningSink,
) {
    private val processor = ItemDisplayProcessor(service, warningSink)

    fun submit(request: ItemDisplayRequest) {
        schedule { processor.display(request) }
    }

    @Suppress("TooGenericExceptionCaught")
    fun submitDeferred(requestResolver: () -> ItemDisplayRequest?) {
        schedule {
            val request =
                try {
                    requestResolver()
                } catch (error: RuntimeException) {
                    warningSink.warn("item presentation refresh failed (${error.javaClass.simpleName})")
                    null
                }
            request?.let(processor::display)
        }
    }

    @Suppress("TooGenericExceptionCaught")
    fun submitDeferred(
        item: Item,
        directView: DirectItemPresentationView<Item>?,
        transientTargetLeaseFactory: TransientItemTargetLeaseFactory,
        requestResolver: () -> ItemDisplayRequest?,
    ) {
        schedule {
            withTransientItemTarget(item, transientTargetLeaseFactory) {
                val request =
                    try {
                        requestResolver()
                    } catch (error: RuntimeException) {
                        warningSink.warn("item presentation refresh failed (${error.javaClass.simpleName})")
                        null
                    }
                request?.let { resolved ->
                    if (directView == null) {
                        processor.display(resolved)
                    } else {
                        processor.display(item, resolved, directView)
                        submitCanonicalReconciliation(resolved, MAX_CANONICAL_RECONCILIATION_ATTEMPTS)
                    }
                }
            }
        }
    }

    private fun submitCanonicalReconciliation(
        request: ItemDisplayRequest,
        attemptsRemaining: Int,
    ) {
        schedule {
            val result = processor.display(request)
            if (result == PresentationResult.MissingTarget && attemptsRemaining > 1) {
                submitCanonicalReconciliation(request, attemptsRemaining - 1)
            }
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun schedule(task: () -> Unit) {
        try {
            taskExecutor.execute(task)
        } catch (error: RuntimeException) {
            warningSink.warn("item presentation scheduling failed (${error.javaClass.simpleName})")
        }
    }

    private companion object {
        private const val MAX_CANONICAL_RECONCILIATION_ATTEMPTS = 20
    }
}

internal class ItemDisplayProcessor(
    private val service: ItemDisplayService,
    private val warningSink: DisplayWarningSink,
) {
    fun display(request: ItemDisplayRequest): PresentationResult? = handle(service.display(request))

    fun display(
        item: Item,
        request: ItemDisplayRequest,
        directView: DirectItemPresentationView<Item>,
    ): PresentationResult? {
        val itemView =
            object : ItemPresentationView {
                override fun present(presentation: ItemPresentation): PresentationResult = directView.present(item, presentation)

                override fun clear(entityId: java.util.UUID): PresentationResult = directView.clear(item)
            }
        return handle(service.display(request, itemView))
    }

    private fun handle(outcome: ItemDisplayOutcome): PresentationResult? =
        when (outcome) {
            is ItemDisplayOutcome.Ignored -> null
            is ItemDisplayOutcome.Cleared -> outcome.result.also(::handlePresentationResult)
            is ItemDisplayOutcome.Rejected -> null.also { warningSink.warn(outcome.reason) }
            is ItemDisplayOutcome.Presented -> outcome.result.also(::handlePresentationResult)
        }

    private fun handlePresentationResult(result: PresentationResult) {
        when (result) {
            PresentationResult.Applied,
            PresentationResult.MissingTarget,
            -> Unit
            is PresentationResult.Failed ->
                warningSink.warn("item presentation failed (${result.errorType})")
        }
    }
}
