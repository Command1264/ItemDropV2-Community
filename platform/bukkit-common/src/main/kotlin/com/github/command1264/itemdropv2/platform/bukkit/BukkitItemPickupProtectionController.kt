package com.github.command1264.itemdropv2.platform.bukkit

import com.github.command1264.itemdropv2.core.ItemDisplaySettingsRepository
import com.github.command1264.itemdropv2.core.ItemPickupOutcome
import com.github.command1264.itemdropv2.core.ItemPickupProtectionService
import com.github.command1264.itemdropv2.core.ItemPickupRequest
import com.github.command1264.itemdropv2.core.ItemStateWriteResult
import com.github.command1264.itemdropv2.core.PickupActor
import com.github.command1264.itemdropv2.core.PickupDeniedReason
import com.github.command1264.itemdropv2.core.PickupWarningMessageType
import com.github.command1264.itemdropv2.core.PluginMessageLanguage
import net.md_5.bungee.api.ChatMessageType
import net.md_5.bungee.api.chat.TextComponent
import org.bukkit.ChatColor
import org.bukkit.GameMode
import org.bukkit.Material
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Item
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityPickupItemEvent
import org.bukkit.event.inventory.InventoryPickupItemEvent
import org.bukkit.event.player.PlayerQuitEvent
import java.io.InputStreamReader
import java.util.UUID

public fun interface VirtualItemPickupFeedback {
    public fun pickedUp(
        player: Player,
        item: Item,
        consumedAmount: Long,
        remainingAmount: Long,
    )
}

public fun interface PlayerInventorySynchronizer {
    public fun synchronize(player: Player)
}

public enum class PickupMessageKey(
    public val path: String,
) {
    NO_PERMISSION("pickup.no-permission"),
    OTHER_OWNER("pickup.other-owner"),
}

public class BukkitPickupMessageCatalog internal constructor(
    private val renderer: (PluginMessageLanguage, PickupMessageKey, Map<String, String>) -> String,
) {
    internal constructor(messages: Map<PluginMessageLanguage, Map<PickupMessageKey, String>>) : this(
        renderer = { language, key, placeholders ->
            val template = requireNotNull(messages[language]?.get(key)) { "missing message ${language.code}:${key.path}" }
            ChatColor.translateAlternateColorCodes(
                '&',
                placeholders.entries.fold(template) { text, (name, value) -> text.replace("%$name%", value) },
            )
        },
    )

    public fun render(
        language: PluginMessageLanguage,
        key: PickupMessageKey,
        placeholders: Map<String, String> = emptyMap(),
    ): String = renderer(language, key, placeholders)

    public companion object {
        public fun fromStore(store: BukkitMessageCatalogStore): BukkitPickupMessageCatalog =
            BukkitPickupMessageCatalog { language, key, placeholders ->
                store.render(language, key.path, placeholders)
            }

        public fun load(resourceLoader: ClassLoader): BukkitPickupMessageCatalog {
            val catalogs =
                PluginMessageLanguage.BUILT_IN.associateWith { language ->
                    val path = "config/languages/${language.code}.yml"
                    val stream = requireNotNull(resourceLoader.getResourceAsStream(path)) { "missing resource $path" }
                    stream.use {
                        val yaml = YamlConfiguration.loadConfiguration(InputStreamReader(it, Charsets.UTF_8))
                        PickupMessageKey.entries.associateWith { key ->
                            requireNotNull(yaml.getString(key.path)) { "missing message ${key.path} in $path" }
                        }
                    }
                }
            return BukkitPickupMessageCatalog(catalogs)
        }
    }
}

@Suppress("TooManyFunctions")
public class BukkitItemPickupProtectionController internal constructor(
    private val service: ItemPickupProtectionService,
    private val settingsRepository: ItemDisplaySettingsRepository,
    private val messages: BukkitPickupMessageCatalog,
    private val ownerNameResolver: (UUID) -> String?,
    private val warningSink: DisplayWarningSink,
    private val nanoTime: () -> Long,
    private val itemRefresh: ItemOwnershipRefresh,
    private val delayedTaskExecutor: DelayedMainThreadTaskExecutor,
    private val pickedUpStateCleaner: (Item) -> ItemStateWriteResult = { ItemStateWriteResult.Applied },
    private val virtualPickupHandling: VirtualItemPickupHandling = NoVirtualItemPickupHandling,
    private val inventoryMutationNotifier: InventoryMutationNotifier =
        InventoryMutationNotifier {
            InventoryMutationNotificationResult.NotRequired
        },
    private val virtualPickupFeedback: VirtualItemPickupFeedback = VirtualItemPickupFeedback { _, _, _, _ -> },
    private val inventorySynchronizer: PlayerInventorySynchronizer = PlayerInventorySynchronizer(Player::updateInventory),
    private val creativeNoCapacityPickupCandidates: (Player) -> List<Item> =
        ::resolveCreativeNoCapacityPickupCandidates,
    private val transientTargetLeaseFactory: TransientItemTargetLeaseFactory =
        TransientItemTargetLeaseFactory { TransientItemTargetLease {} },
) : Listener,
    AutoCloseable {
    public constructor(
        service: ItemPickupProtectionService,
        settingsRepository: ItemDisplaySettingsRepository,
        messages: BukkitPickupMessageCatalog,
        ownerNameResolver: (UUID) -> String?,
        warningSink: DisplayWarningSink,
        itemRefresh: ItemOwnershipRefresh,
        delayedTaskExecutor: DelayedMainThreadTaskExecutor,
        pickedUpStateCleaner: (Item) -> ItemStateWriteResult = { ItemStateWriteResult.Applied },
        virtualPickupHandling: VirtualItemPickupHandling = NoVirtualItemPickupHandling,
        inventoryMutationNotifier: InventoryMutationNotifier =
            InventoryMutationNotifier {
                InventoryMutationNotificationResult.NotRequired
            },
        virtualPickupFeedback: VirtualItemPickupFeedback = VirtualItemPickupFeedback { _, _, _, _ -> },
        inventorySynchronizer: PlayerInventorySynchronizer = PlayerInventorySynchronizer(Player::updateInventory),
        creativeNoCapacityPickupCandidates: (Player) -> List<Item> =
            ::resolveCreativeNoCapacityPickupCandidates,
        transientTargetLeaseFactory: TransientItemTargetLeaseFactory =
            TransientItemTargetLeaseFactory { TransientItemTargetLease {} },
    ) : this(
        service,
        settingsRepository,
        messages,
        ownerNameResolver,
        warningSink,
        System::nanoTime,
        itemRefresh,
        delayedTaskExecutor,
        pickedUpStateCleaner,
        virtualPickupHandling,
        inventoryMutationNotifier,
        virtualPickupFeedback,
        inventorySynchronizer,
        creativeNoCapacityPickupCandidates,
        transientTargetLeaseFactory,
    )

    private val warningThrottle = PickupWarningThrottle(nanoTime)
    private val ownerWarningFormatter = PickupOwnerWarningFormatter(ownerNameResolver)
    private val pendingCreativeInventorySynchronizations = mutableSetOf<UUID>()

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public fun onEntityPickup(event: EntityPickupItemEvent) {
        if (event.isAsynchronous) {
            event.isCancelled = true
            warningSink.warn("cancelled asynchronous EntityPickupItemEvent")
            return
        }
        withPickupEventItem(
            event.item,
            transientTargetLeaseFactory,
            event::setCancelled,
            "EntityPickupItemEvent",
            warningSink,
        ) {
            val player = event.entity as? Player
            val actor =
                player?.let {
                    PickupActor.Player(
                        playerUuid = it.uniqueId,
                        hasPickupPermission = it.hasPermission(PICKUP_PERMISSION),
                        hasOtherPickupPermission = it.hasPermission(PICKUP_OTHER_PERMISSION),
                    )
                } ?: PickupActor.NonPlayerEntity
            applyOutcome(
                service.evaluate(ItemPickupRequest(event.item.uniqueId, event.item.world.name, actor)),
                event.item,
                player,
                event::setCancelled,
                nativePickupRemaining = event.remaining,
                virtualPickup = {
                    if (player == null) {
                        virtualPickupHandling.pickupByNonPlayer(event.item, event.remaining)
                    } else {
                        virtualPickupHandling
                            .pickupByPlayer(event.item, player) { consumedAmount, remainingAmount ->
                                deliverVirtualPickupFeedback(player, event.item, consumedAmount, remainingAmount)
                            }.also { virtual ->
                                if (
                                    player.gameMode == GameMode.CREATIVE &&
                                    virtual is VirtualItemPickupOutcome.Inserted &&
                                    virtual.insertedAmount > 0L
                                ) {
                                    scheduleCreativeInventorySynchronization(player)
                                }
                            }
                    }
                },
            )
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public fun onInventoryPickup(event: InventoryPickupItemEvent) {
        if (event.isAsynchronous) {
            event.isCancelled = true
            warningSink.warn("cancelled asynchronous InventoryPickupItemEvent")
            return
        }
        withPickupEventItem(
            event.item,
            transientTargetLeaseFactory,
            event::setCancelled,
            "InventoryPickupItemEvent",
            warningSink,
        ) {
            applyOutcome(
                service.evaluate(ItemPickupRequest(event.item.uniqueId, event.item.world.name, PickupActor.Inventory)),
                event.item,
                player = null,
                cancel = event::setCancelled,
                nativePickupRemaining = 0,
                virtualPickup = {
                    virtualPickupHandling.pickupByInventory(event.item, event.inventory)
                },
                committedPickup = {
                    scheduleInventoryMutationNotification(event.inventory)
                },
            )
        }
    }

    /**
     * Covers the CraftBukkit zero-capacity path where no pickup event is dispatched. The resolver
     * only returns collision-range Items for Creative players whose storage has no empty slots;
     * the transaction performs the authoritative compatible-stack capacity check before mutation.
     */
    @Suppress("TooGenericExceptionCaught")
    public fun processCreativeNoCapacityPickups(players: Iterable<Player>) {
        players.forEach { player ->
            val candidates =
                try {
                    creativeNoCapacityPickupCandidates(player)
                } catch (error: RuntimeException) {
                    warningSink.warn("creative no-capacity pickup scan failed (${error.javaClass.simpleName})")
                    emptyList()
                }
            candidates.forEach { item -> processCreativeNoCapacityPickupAttempt(player, item) }
        }
    }

    /**
     * Handles an exact Creative collision reported by a platform-specific attempt event. Returning
     * true means ItemDropV2 either committed the zero-capacity policy or failed closed, so the
     * platform must cancel its native pickup. False leaves a capacity-bearing/non-virtual pickup to
     * the standard Bukkit event path.
     */
    public fun processCreativeNoCapacityPickupAttempt(
        player: Player,
        item: Item,
    ): Boolean {
        if (player.gameMode != GameMode.CREATIVE) return false
        var handled = true
        withPickupEventItem(
            item,
            transientTargetLeaseFactory,
            cancel = {},
            eventName = "CreativeNoCapacityPickupCollision",
            warningSink = warningSink,
        ) {
            handled =
                when (
                    val outcome =
                        service.evaluate(
                            ItemPickupRequest(
                                item.uniqueId,
                                item.world.name,
                                PickupActor.Player(
                                    player.uniqueId,
                                    player.hasPermission(PICKUP_PERMISSION),
                                    player.hasPermission(PICKUP_OTHER_PERMISSION),
                                ),
                            ),
                        )
                ) {
                    ItemPickupOutcome.Allowed -> applyCreativeNoCapacityVirtualPickup(player, item)
                    is ItemPickupOutcome.Denied -> {
                        warnPlayer(player, outcome)
                        true
                    }
                    is ItemPickupOutcome.Failed -> {
                        warningSink.warn("creative no-capacity pickup protection failed (${outcome.errorType})")
                        true
                    }
                }
        }
        return handled
    }

    @EventHandler
    public fun onPlayerQuit(event: PlayerQuitEvent) {
        warningThrottle.remove(event.player.uniqueId)
        pendingCreativeInventorySynchronizations.remove(event.player.uniqueId)
    }

    override fun close() {
        warningThrottle.clear()
        pendingCreativeInventorySynchronizations.clear()
    }

    private fun applyOutcome(
        outcome: ItemPickupOutcome,
        item: Item,
        player: Player?,
        cancel: (Boolean) -> Unit,
        nativePickupRemaining: Int,
        virtualPickup: () -> VirtualItemPickupOutcome,
        committedPickup: () -> Unit = {},
    ) {
        when (outcome) {
            ItemPickupOutcome.Allowed ->
                applyAllowedPickup(item, cancel, nativePickupRemaining, virtualPickup, committedPickup)
            is ItemPickupOutcome.Denied -> {
                cancel(true)
                if (player != null) warnPlayer(player, outcome)
            }
            is ItemPickupOutcome.Failed -> {
                cancel(true)
                warningSink.warn("item pickup protection failed (${outcome.errorType})")
            }
        }
    }

    @Suppress("CyclomaticComplexMethod")
    private fun applyAllowedPickup(
        item: Item,
        cancel: (Boolean) -> Unit,
        nativePickupRemaining: Int,
        virtualPickup: () -> VirtualItemPickupOutcome,
        committedPickup: () -> Unit,
    ) {
        when (val virtual = virtualPickup()) {
            VirtualItemPickupOutcome.NotApplicable -> Unit
            VirtualItemPickupOutcome.NotVirtual ->
                when (val cleared = pickedUpStateCleaner(item)) {
                    ItemStateWriteResult.Applied,
                    ItemStateWriteResult.MissingTarget,
                    -> {
                        if (nativePickupRemaining > 0) scheduleRefresh(item.uniqueId)
                    }
                    is ItemStateWriteResult.Rejected -> {
                        cancel(true)
                        warningSink.warn("picked-up item state cleanup rejected (${cleared.reason})")
                    }
                    is ItemStateWriteResult.Failed -> {
                        cancel(true)
                        warningSink.warn("picked-up item state cleanup failed (${cleared.errorType})")
                    }
                }
            VirtualItemPickupOutcome.NoCapacity -> cancel(true)
            is VirtualItemPickupOutcome.Inserted -> {
                cancel(true)
                committedPickup()
                if (virtual.remainingAmount > 0) refreshDisplay { refresh(item) }
            }
            is VirtualItemPickupOutcome.NativePickupPrepared -> {
                virtual.itemsToRefresh.forEach { preparedItem ->
                    refreshDisplay { refresh(preparedItem) }
                }
                if (virtual.reconcileAfterNativePickup) {
                    scheduleNonPlayerPickupReconciliation(virtual.sourceEntityId)
                }
            }
            is VirtualItemPickupOutcome.Rejected -> {
                cancel(true)
                warningSink.warn("virtual item pickup rejected (${virtual.reason})")
            }
            is VirtualItemPickupOutcome.Failed -> {
                cancel(true)
                warningSink.warn("virtual item pickup failed (${virtual.errorType})")
            }
        }
    }

    private fun applyCreativeNoCapacityVirtualPickup(
        player: Player,
        item: Item,
    ): Boolean =
        when (
            val virtual =
                virtualPickupHandling.pickupByCreativeNoCapacityCollision(item, player) { consumed, remaining ->
                    deliverVirtualPickupFeedback(player, item, consumed, remaining)
                }
        ) {
            VirtualItemPickupOutcome.NotApplicable,
            VirtualItemPickupOutcome.NotVirtual,
            -> false
            VirtualItemPickupOutcome.NoCapacity -> true
            is VirtualItemPickupOutcome.Inserted -> {
                if (virtual.insertedAmount > 0L) scheduleCreativeInventorySynchronization(player)
                if (virtual.remainingAmount > 0L) refreshDisplay { refresh(item) }
                true
            }
            is VirtualItemPickupOutcome.NativePickupPrepared -> {
                warningSink.warn("creative no-capacity pickup returned an invalid native preparation")
                true
            }
            is VirtualItemPickupOutcome.Rejected -> {
                warningSink.warn("creative no-capacity virtual pickup rejected (${virtual.reason})")
                true
            }
            is VirtualItemPickupOutcome.Failed -> {
                warningSink.warn("creative no-capacity virtual pickup failed (${virtual.errorType})")
                true
            }
        }

    @Suppress("TooGenericExceptionCaught")
    private fun scheduleNonPlayerPickupReconciliation(sourceEntityId: UUID) {
        try {
            delayedTaskExecutor.execute(1L) {
                when (val reconciled = virtualPickupHandling.reconcileNonPlayerPickup(sourceEntityId)) {
                    NonPlayerPickupReconciliationOutcome.NotRequired,
                    NonPlayerPickupReconciliationOutcome.MissingTarget,
                    -> Unit
                    is NonPlayerPickupReconciliationOutcome.Applied ->
                        refreshDisplay { refresh(reconciled.item) }
                    is NonPlayerPickupReconciliationOutcome.Failed ->
                        warningSink.warn(
                            "non-player virtual pickup reconciliation failed (${reconciled.errorType})",
                        )
                }
            }
        } catch (error: RuntimeException) {
            warningSink.warn(
                "non-player virtual pickup reconciliation scheduling failed (${error.javaClass.simpleName})",
            )
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun deliverVirtualPickupFeedback(
        player: Player,
        item: Item,
        consumedAmount: Long,
        remainingAmount: Long,
    ) {
        if (consumedAmount <= 0) return
        try {
            virtualPickupFeedback.pickedUp(player, item, consumedAmount, remainingAmount)
        } catch (error: RuntimeException) {
            warningSink.warn("virtual item pickup feedback failed (${error.javaClass.simpleName})")
        }
    }

    /**
     * A Creative inventory clear already emits a burst of client slot packets. Deferring and
     * coalescing the compensating full sync prevents old Spigot and protocol bridges from turning
     * simultaneous virtual pickups into an inventory packet feedback loop.
     */
    @Suppress("TooGenericExceptionCaught")
    private fun scheduleCreativeInventorySynchronization(player: Player) {
        val playerId = player.uniqueId
        if (!pendingCreativeInventorySynchronizations.add(playerId)) return
        try {
            delayedTaskExecutor.execute(1L) {
                if (!pendingCreativeInventorySynchronizations.remove(playerId)) return@execute
                try {
                    inventorySynchronizer.synchronize(player)
                } catch (error: RuntimeException) {
                    warningSink.warn("creative inventory synchronization failed (${error.javaClass.simpleName})")
                }
            }
        } catch (error: RuntimeException) {
            pendingCreativeInventorySynchronizations.remove(playerId)
            warningSink.warn("creative inventory synchronization scheduling failed (${error.javaClass.simpleName})")
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun scheduleRefresh(entityId: UUID) {
        try {
            delayedTaskExecutor.execute(1L) { refreshDisplay { refresh(entityId) } }
        } catch (error: RuntimeException) {
            warningSink.warn("partial item pickup display refresh scheduling failed (${error.javaClass.simpleName})")
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun scheduleInventoryMutationNotification(inventory: org.bukkit.inventory.Inventory) {
        try {
            delayedTaskExecutor.execute(1L) {
                when (val notified = inventoryMutationNotifier.notifyMutation(inventory)) {
                    InventoryMutationNotificationResult.Applied,
                    InventoryMutationNotificationResult.NotRequired,
                    -> Unit
                    is InventoryMutationNotificationResult.Failed ->
                        warningSink.warn("inventory mutation notification failed (${notified.errorType})")
                }
            }
        } catch (error: RuntimeException) {
            warningSink.warn("inventory mutation notification scheduling failed (${error.javaClass.simpleName})")
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun refreshDisplay(refresh: ItemOwnershipRefresh.() -> Unit) {
        try {
            itemRefresh.refresh()
        } catch (error: RuntimeException) {
            warningSink.warn("partial item pickup display refresh failed (${error.javaClass.simpleName})")
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun warnPlayer(
        player: Player,
        denied: ItemPickupOutcome.Denied,
    ) {
        val settings = settingsRepository.settings()
        if (!warningThrottle.acquire(player.uniqueId, settings.ownership.pickup.warningCooldownSeconds)) return
        val key =
            when (denied.reason) {
                PickupDeniedReason.NO_PICKUP_PERMISSION -> PickupMessageKey.NO_PERMISSION
                PickupDeniedReason.OTHER_OWNER -> PickupMessageKey.OTHER_OWNER
            }
        val message =
            ownerWarningFormatter.format(denied).let { ownerPlaceholders ->
                messages.render(
                    settings.messageLanguage,
                    key,
                    ownerPlaceholders +
                        mapOf(
                            "permission" to PICKUP_PERMISSION,
                            "seconds" to denied.protectionSecondsRemaining?.toString().orEmpty(),
                        ),
                )
            }
        try {
            when (settings.ownership.pickup.warningMessageType) {
                PickupWarningMessageType.CHAT -> player.sendMessage(message)
                PickupWarningMessageType.ACTION_BAR ->
                    player.spigot().sendMessage(ChatMessageType.ACTION_BAR, TextComponent(message))
            }
        } catch (error: RuntimeException) {
            warningSink.warn("pickup warning delivery failed (${error.javaClass.simpleName})")
        }
    }

    private companion object {
        private const val PICKUP_PERMISSION = "itemdrop.event.pickup"
        private const val PICKUP_OTHER_PERMISSION = "itemdrop.event.pickup.other"
    }
}

internal fun resolveCreativeNoCapacityPickupCandidates(player: Player): List<Item> =
    when {
        player.gameMode != GameMode.CREATIVE -> emptyList()
        player.inventory.storageContents.any { stack -> stack == null || stack.type == Material.AIR } -> emptyList()
        else ->
            player
                .getNearbyEntities(CREATIVE_PICKUP_HORIZONTAL_RANGE, CREATIVE_PICKUP_VERTICAL_RANGE, CREATIVE_PICKUP_HORIZONTAL_RANGE)
                .filterIsInstance<Item>()
                .filter { item -> item.isValid && !item.isDead && item.pickupDelay <= 0 }
    }

private const val CREATIVE_PICKUP_HORIZONTAL_RANGE = 1.0
private const val CREATIVE_PICKUP_VERTICAL_RANGE = 0.5

internal class PickupOwnerWarningFormatter(
    private val ownerNameResolver: (UUID) -> String?,
) {
    fun format(denied: ItemPickupOutcome.Denied): Map<String, String> {
        val ownerIds =
            denied.eligibleOwnerUuids.ifEmpty {
                denied.ownerUuid?.let(::listOf).orEmpty()
            }
        val resolvedNames =
            ownerIds
                .asSequence()
                .mapNotNull(::resolveOwnerName)
                .take(MAX_WARNING_OWNER_NAMES)
                .toList()
        val hiddenOwnerCount = (ownerIds.size - resolvedNames.size).coerceAtLeast(0)
        val primaryName =
            denied.ownerUuid
                ?.let(::resolveOwnerName)
                ?: resolvedNames.firstOrNull()
                ?: UNKNOWN_OWNER_NAME
        val ownerSummary =
            buildString {
                append(resolvedNames.ifEmpty { listOf(UNKNOWN_OWNER_NAME) }.joinToString(OWNER_NAME_SEPARATOR))
                if (hiddenOwnerCount > 0) append(" +$hiddenOwnerCount")
            }
        return mapOf(
            "player_name" to primaryName,
            "owner_names" to ownerSummary,
            "additional_owner_count" to hiddenOwnerCount.toString(),
        )
    }

    private fun resolveOwnerName(ownerUuid: UUID): String? =
        ownerNameResolver(ownerUuid)?.takeIf { name ->
            name.isNotBlank() &&
                name.length <= MAX_WARNING_OWNER_NAME_LENGTH &&
                name.none(Char::isISOControl)
        }

    private companion object {
        private const val MAX_WARNING_OWNER_NAMES = 3
        private const val MAX_WARNING_OWNER_NAME_LENGTH = 64
        private const val OWNER_NAME_SEPARATOR = ", "
        private const val UNKNOWN_OWNER_NAME = "?"
    }
}

private object NoVirtualItemPickupHandling : VirtualItemPickupHandling {
    override fun pickupByPlayer(
        item: Item,
        player: Player,
        beforeCarrierMutation: (consumedAmount: Long, remainingAmount: Long) -> Unit,
    ): VirtualItemPickupOutcome = VirtualItemPickupOutcome.NotVirtual

    override fun pickupByInventory(
        item: Item,
        inventory: org.bukkit.inventory.Inventory,
    ): VirtualItemPickupOutcome = VirtualItemPickupOutcome.NotVirtual

    override fun pickupByNonPlayer(
        item: Item,
        nativePickupRemaining: Int,
    ): VirtualItemPickupOutcome = VirtualItemPickupOutcome.NotVirtual
}

@Suppress("TooGenericExceptionCaught")
private fun withPickupEventItem(
    item: Item,
    transientTargetLeaseFactory: TransientItemTargetLeaseFactory,
    cancel: (Boolean) -> Unit,
    eventName: String,
    warningSink: DisplayWarningSink,
    operation: () -> Unit,
) {
    try {
        withTransientItemTarget(item, transientTargetLeaseFactory, operation)
    } catch (error: RuntimeException) {
        cancel(true)
        val origin =
            error.stackTrace.firstOrNull { frame ->
                frame.className.startsWith("com.github.command1264.itemdropv2")
            }
        val location =
            origin
                ?.let { frame ->
                    " at ${frame.className.substringAfterLast('.')}.${frame.methodName}:${frame.lineNumber}"
                }.orEmpty()
        warningSink.warn("$eventName direct item processing failed (${error.javaClass.simpleName}$location)")
    }
}

internal class PickupWarningThrottle(
    private val nanoTime: () -> Long,
) {
    private val lastWarningNanos = mutableMapOf<UUID, Long>()

    fun acquire(
        playerUuid: UUID,
        cooldownSeconds: Long,
    ): Boolean {
        if (cooldownSeconds == 0L) return true
        val now = nanoTime()
        val previous = lastWarningNanos[playerUuid]
        val acquired = previous == null || now - previous >= cooldownSeconds * NANOS_PER_SECOND
        if (acquired) lastWarningNanos[playerUuid] = now
        return acquired
    }

    fun remove(playerUuid: UUID) {
        lastWarningNanos.remove(playerUuid)
    }

    fun clear() {
        lastWarningNanos.clear()
    }

    private companion object {
        private const val NANOS_PER_SECOND = 1_000_000_000L
    }
}
