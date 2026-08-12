package com.github.command1264.itemdropv2.platform.bukkit

import com.github.command1264.itemdropv2.core.BlockDropOwnershipOutcome
import com.github.command1264.itemdropv2.core.BlockDropOwnershipRequest
import com.github.command1264.itemdropv2.core.BlockDropOwnershipService
import com.github.command1264.itemdropv2.core.ItemOwnershipAssignmentOutcome
import com.github.command1264.itemdropv2.core.ItemOwnershipAssignmentRequest
import com.github.command1264.itemdropv2.core.ItemOwnershipAssignmentService
import com.github.command1264.itemdropv2.core.ItemState
import org.bukkit.entity.Item
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockDropItemEvent
import org.bukkit.event.entity.ItemMergeEvent
import org.bukkit.event.entity.ItemSpawnEvent
import java.util.UUID

public fun interface DelayedMainThreadTaskExecutor {
    public fun execute(
        delayTicks: Long,
        task: () -> Unit,
    )
}

public fun interface ItemOwnershipRefresh {
    public fun refresh(entityId: UUID)

    public fun refresh(item: Item) {
        refresh(item.uniqueId)
    }
}

public fun interface TransientItemTargetLease {
    public fun release()
}

public fun interface TransientItemTargetLeaseFactory {
    public fun acquire(item: Item): TransientItemTargetLease
}

internal fun <T> withTransientItemTarget(
    item: Item,
    factory: TransientItemTargetLeaseFactory,
    operation: () -> T,
): T {
    val lease = factory.acquire(item)
    return try {
        operation()
    } finally {
        lease.release()
    }
}

internal fun <T> withTransientItemTargets(
    first: Item,
    second: Item,
    factory: TransientItemTargetLeaseFactory,
    operation: () -> T,
): T =
    withTransientItemTarget(first, factory) {
        withTransientItemTarget(second, factory, operation)
    }

@Suppress("TooGenericExceptionCaught")
internal fun assignItemOwnershipImmediately(
    item: Item,
    eligibleOwnerUuids: List<UUID>,
    service: ItemOwnershipAssignmentService,
    transientTargetLeaseFactory: TransientItemTargetLeaseFactory,
    itemRefresh: ItemOwnershipRefresh,
    warningSink: DisplayWarningSink,
    sourceName: String,
) {
    try {
        withTransientItemTarget(item, transientTargetLeaseFactory) {
            val request = ItemOwnershipAssignmentRequest(item.uniqueId, item.world.name, eligibleOwnerUuids)
            when (val outcome = service.assign(request)) {
                is ItemOwnershipAssignmentOutcome.Assigned -> itemRefresh.refresh(item)
                is ItemOwnershipAssignmentOutcome.Ignored -> Unit
                is ItemOwnershipAssignmentOutcome.Rejected ->
                    warningSink.warn("$sourceName item ownership rejected (${outcome.reason})")
                is ItemOwnershipAssignmentOutcome.Failed ->
                    warningSink.warn("$sourceName item ownership failed (${outcome.errorType})")
            }
        }
    } catch (error: RuntimeException) {
        warningSink.warn("$sourceName item ownership assignment failed (${error.javaClass.simpleName})")
    }
}

@Suppress("TooGenericExceptionCaught")
internal fun assignBlockOwnershipImmediately(
    item: Item,
    ownerUuid: UUID,
    service: BlockDropOwnershipService,
    transientTargetLeaseFactory: TransientItemTargetLeaseFactory,
    itemRefresh: ItemOwnershipRefresh,
    warningSink: DisplayWarningSink,
    sourceName: String,
) {
    try {
        withTransientItemTarget(item, transientTargetLeaseFactory) {
            val request = BlockDropOwnershipRequest(item.uniqueId, item.world.name, ownerUuid)
            when (val outcome = service.assign(request)) {
                is BlockDropOwnershipOutcome.Assigned -> itemRefresh.refresh(item)
                is BlockDropOwnershipOutcome.Ignored -> Unit
                is BlockDropOwnershipOutcome.Rejected ->
                    warningSink.warn("$sourceName item ownership rejected (${outcome.reason})")
                is BlockDropOwnershipOutcome.Failed ->
                    warningSink.warn("$sourceName item ownership failed (${outcome.errorType})")
            }
        }
    } catch (error: RuntimeException) {
        warningSink.warn("$sourceName item ownership assignment failed (${error.javaClass.simpleName})")
    }
}

public class BukkitBlockDropOwnershipController internal constructor(
    private val service: BlockDropOwnershipService,
    private val delayedTaskExecutor: DelayedMainThreadTaskExecutor,
    private val warningSink: DisplayWarningSink,
    private val itemRefresh: ItemOwnershipRefresh,
    private val transientTargetLeaseFactory: TransientItemTargetLeaseFactory =
        TransientItemTargetLeaseFactory { TransientItemTargetLease {} },
    private val plantTracker: PlantCollapseOwnershipTracker =
        PlantCollapseOwnershipTracker(delayedTaskExecutor, warningSink),
) : Listener,
    AutoCloseable {
    private val pendingPlantReconciliationLeases = mutableMapOf<UUID, TransientItemTargetLease>()

    public constructor(
        service: BlockDropOwnershipService,
        delayedTaskExecutor: DelayedMainThreadTaskExecutor,
        warningSink: DisplayWarningSink,
        itemRefresh: ItemOwnershipRefresh,
    ) : this(
        service,
        delayedTaskExecutor,
        warningSink,
        itemRefresh,
        TransientItemTargetLeaseFactory { TransientItemTargetLease {} },
        PlantCollapseOwnershipTracker(delayedTaskExecutor, warningSink),
    )

    public constructor(
        service: BlockDropOwnershipService,
        delayedTaskExecutor: DelayedMainThreadTaskExecutor,
        warningSink: DisplayWarningSink,
        itemRefresh: ItemOwnershipRefresh,
        transientTargetLeaseFactory: TransientItemTargetLeaseFactory,
    ) : this(
        service,
        delayedTaskExecutor,
        warningSink,
        itemRefresh,
        transientTargetLeaseFactory,
        PlantCollapseOwnershipTracker(delayedTaskExecutor, warningSink),
    )

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public fun onBlockDrop(event: BlockDropItemEvent) {
        if (!ensureSynchronous(event.isAsynchronous, "BlockDropItemEvent")) return
        val ownerUuid = event.player.uniqueId
        event.items
            .filterNot { item -> plantTracker.wasClaimed(item.uniqueId) }
            .forEach { item -> assignOwnership(item, ownerUuid) }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public fun onBlockBreak(event: BlockBreakEvent) {
        if (!ensureSynchronous(event.isAsynchronous, "BlockBreakEvent")) return
        plantTracker.record(event.block, event.player.uniqueId)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public fun onPlantItemSpawn(event: ItemSpawnEvent) {
        if (!ensureSynchronous(event.isAsynchronous, "ItemSpawnEvent")) return
        val item = event.entity
        plantTracker.claim(item)?.let { ownerUuid -> assignPlantOwnership(item, ownerUuid) }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public fun onPendingOwnershipMerge(event: ItemMergeEvent) {
        if (!ensureSynchronous(event.isAsynchronous, "ItemMergeEvent")) {
            event.isCancelled = true
            return
        }
        val collapseActive =
            plantTracker.isMergeProtected(event.entity.uniqueId) ||
                plantTracker.isMergeProtected(event.target.uniqueId)
        if (collapseActive) {
            event.isCancelled = true
        }
    }

    private fun assignOwnership(
        item: Item,
        ownerUuid: UUID,
    ) {
        assignBlockOwnershipImmediately(
            item,
            ownerUuid,
            service,
            transientTargetLeaseFactory,
            itemRefresh,
            warningSink,
            "block",
        )
    }

    @Suppress("TooGenericExceptionCaught")
    private fun assignPlantOwnership(
        item: Item,
        ownerUuid: UUID,
    ) {
        val lease = transientTargetLeaseFactory.acquire(item)
        pendingPlantReconciliationLeases.put(item.uniqueId, lease)?.release()
        val request = BlockDropOwnershipRequest(item.uniqueId, item.world.name, ownerUuid)
        try {
            when (val outcome = service.assign(request)) {
                is BlockDropOwnershipOutcome.Assigned -> {
                    itemRefresh.refresh(item)
                    schedulePlantReconciliation(item, request, lease, outcome.state)
                }
                is BlockDropOwnershipOutcome.Ignored -> finishPlantReconciliation(item.uniqueId, lease)
                is BlockDropOwnershipOutcome.Rejected -> {
                    finishPlantReconciliation(item.uniqueId, lease)
                    warningSink.warn("plant item ownership rejected (${outcome.reason})")
                }
                is BlockDropOwnershipOutcome.Failed ->
                    if (outcome.errorType == MISSING_TARGET) {
                        schedulePlantReconciliation(item, request, lease, state = null)
                    } else {
                        finishPlantReconciliation(item.uniqueId, lease)
                        warningSink.warn("plant item ownership failed (${outcome.errorType})")
                    }
            }
        } catch (error: RuntimeException) {
            finishPlantReconciliation(item.uniqueId, lease)
            warningSink.warn("plant item ownership assignment failed (${error.javaClass.simpleName})")
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun schedulePlantReconciliation(
        item: Item,
        request: BlockDropOwnershipRequest,
        lease: TransientItemTargetLease,
        state: ItemState?,
    ) {
        delayedTaskExecutor.execute(CANONICAL_RECONCILIATION_DELAY_TICKS) {
            // The event-time lease deliberately shadows UUID lookup until legacy Paper has
            // published the dropped entity. Release it before resolving the canonical target.
            finishPlantReconciliation(item.uniqueId, lease)
            try {
                val outcome =
                    state?.let { assigned -> service.reconcile(request.entityId, assigned) }
                        ?: service.assign(request)
                when (outcome) {
                    is BlockDropOwnershipOutcome.Assigned -> itemRefresh.refresh(item)
                    is BlockDropOwnershipOutcome.Ignored -> Unit
                    is BlockDropOwnershipOutcome.Rejected ->
                        warningSink.warn("plant item ownership reconciliation rejected (${outcome.reason})")
                    is BlockDropOwnershipOutcome.Failed ->
                        if (outcome.errorType != MISSING_TARGET) {
                            warningSink.warn("plant item ownership reconciliation failed (${outcome.errorType})")
                        }
                }
            } catch (error: RuntimeException) {
                warningSink.warn("plant item ownership reconciliation failed (${error.javaClass.simpleName})")
            }
        }
    }

    private fun finishPlantReconciliation(
        entityId: UUID,
        lease: TransientItemTargetLease,
    ) {
        if (pendingPlantReconciliationLeases[entityId] === lease) {
            pendingPlantReconciliationLeases.remove(entityId)
            lease.release()
        }
    }

    private fun ensureSynchronous(
        asynchronous: Boolean,
        eventName: String,
    ): Boolean {
        if (!asynchronous) return true
        warningSink.warn("ignored asynchronous $eventName")
        return false
    }

    override fun close() {
        plantTracker.clear()
        pendingPlantReconciliationLeases.values.forEach(TransientItemTargetLease::release)
        pendingPlantReconciliationLeases.clear()
    }

    private companion object {
        private const val CANONICAL_RECONCILIATION_DELAY_TICKS = 1L
        private const val MISSING_TARGET = "MissingTarget"
    }
}
