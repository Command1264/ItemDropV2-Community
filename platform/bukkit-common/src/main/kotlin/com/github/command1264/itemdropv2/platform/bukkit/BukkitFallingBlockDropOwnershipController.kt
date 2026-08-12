package com.github.command1264.itemdropv2.platform.bukkit

import com.github.command1264.itemdropv2.core.ItemOwnershipAssignmentOutcome
import com.github.command1264.itemdropv2.core.ItemOwnershipAssignmentRequest
import com.github.command1264.itemdropv2.core.ItemOwnershipAssignmentService
import com.github.command1264.itemdropv2.core.ItemState
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.entity.FallingBlock
import org.bukkit.entity.Item
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.entity.EntityChangeBlockEvent
import org.bukkit.event.entity.EntityDropItemEvent
import org.bukkit.event.entity.EntitySpawnEvent
import org.bukkit.event.entity.ItemSpawnEvent
import java.util.UUID
import kotlin.math.max

internal fun fallingSourceLifetimeTicks(sources: List<FallingBlockSource>): Long {
    val maximumColumnHeight =
        sources
            .groupingBy { source -> Triple(source.worldId, source.x, source.z) }
            .eachCount()
            .values
            .maxOrNull()
            ?: return MINIMUM_FALLING_SOURCE_LIFETIME_TICKS
    return max(
        MINIMUM_FALLING_SOURCE_LIFETIME_TICKS,
        maximumColumnHeight.toLong() * FALLING_SOURCE_TICKS_PER_VERTICAL_BLOCK + FALLING_SOURCE_LIFETIME_PADDING_TICKS,
    )
}

@Suppress("TooManyFunctions")
public class BukkitFallingBlockDropOwnershipController internal constructor(
    private val assignmentService: ItemOwnershipAssignmentService,
    private val delayedTaskExecutor: DelayedMainThreadTaskExecutor,
    private val warningSink: DisplayWarningSink,
    private val itemRefresh: ItemOwnershipRefresh,
    private val sourceScanner: FallingBlockSourceScanner,
    private val tracker: FallingBlockOwnershipTracker,
    private val ownerStore: FallingBlockOwnerStore,
    private val transientTargetLeaseFactory: TransientItemTargetLeaseFactory =
        TransientItemTargetLeaseFactory { TransientItemTargetLease {} },
) : Listener,
    AutoCloseable {
    private val pendingReconciliationLeases = mutableMapOf<UUID, TransientItemTargetLease>()
    private val pendingSpawnOwners = mutableMapOf<UUID, UUID>()

    public constructor(
        assignmentService: ItemOwnershipAssignmentService,
        delayedTaskExecutor: DelayedMainThreadTaskExecutor,
        warningSink: DisplayWarningSink,
        itemRefresh: ItemOwnershipRefresh,
        ownerKey: NamespacedKey,
        transientTargetLeaseFactory: TransientItemTargetLeaseFactory,
    ) : this(
        assignmentService = assignmentService,
        delayedTaskExecutor = delayedTaskExecutor,
        warningSink = warningSink,
        itemRefresh = itemRefresh,
        sourceScanner = FallingBlockSourceScanner(),
        tracker = FallingBlockOwnershipTracker(),
        ownerStore = BukkitFallingBlockOwnerStore(ownerKey, warningSink),
        transientTargetLeaseFactory = transientTargetLeaseFactory,
    )

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public fun onBlockBreak(event: BlockBreakEvent) {
        if (!ensureSynchronous(event.isAsynchronous, "BlockBreakEvent")) return
        recordSources(sourceScanner.afterBreak(event.block, event.player.uniqueId))
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public fun onBlockPlace(event: BlockPlaceEvent) {
        if (!ensureSynchronous(event.isAsynchronous, "BlockPlaceEvent")) return
        recordSources(sourceScanner.afterPlace(event.blockPlaced, event.player.uniqueId))
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    @Suppress("ReturnCount", "TooGenericExceptionCaught")
    public fun onFallingBlockSpawn(event: EntitySpawnEvent) {
        if (!ensureSynchronous(event.isAsynchronous, "EntitySpawnEvent")) return
        val fallingBlock = event.entity as? FallingBlock ?: return
        val ownerUuid = tracker.bind(fallingBlock.toSpawn()) ?: return
        try {
            ownerStore.write(fallingBlock, ownerUuid)
        } catch (error: RuntimeException) {
            warningSink.warn("falling block owner persistence failed (${error.javaClass.simpleName})")
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    @Suppress("ReturnCount")
    public fun onFallingBlockChange(event: EntityChangeBlockEvent) {
        if (!ensureSynchronous(event.isAsynchronous, "EntityChangeBlockEvent")) return
        val fallingBlock = event.entity as? FallingBlock ?: return
        if (event.to == Material.AIR) return
        complete(fallingBlock)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public fun onFallingBlockDrop(event: EntityDropItemEvent) {
        if (!ensureSynchronous(event.isAsynchronous, "EntityDropItemEvent")) return
        val fallingBlock = event.entity as? FallingBlock ?: return
        val ownerUuid = readPersistedOwner(fallingBlock) ?: tracker.ownerOf(fallingBlock.uniqueId)
        complete(fallingBlock)
        if (ownerUuid != null) {
            rememberSpawnOwner(event.itemDrop.uniqueId, ownerUuid)
            assignFallingDropOwnership(event.itemDrop, ownerUuid)
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public fun onPhysicsItemSpawn(event: ItemSpawnEvent) {
        if (!ensureSynchronous(event.isAsynchronous, "ItemSpawnEvent")) return
        val item = event.entity
        val pendingOwner = pendingSpawnOwners.remove(item.uniqueId)
        if (pendingOwner != null) {
            assignItemOwnershipImmediately(
                item,
                listOf(pendingOwner),
                assignmentService,
                transientTargetLeaseFactory,
                itemRefresh,
                warningSink,
                "falling spawn",
            )
        } else if (item.itemStack.type.name in DIRECT_PHYSICS_ITEM_NAMES) {
            val ownerUuid = tracker.claimSource(item.toSpawn()) ?: return
            assignItemOwnershipImmediately(
                item,
                listOf(ownerUuid),
                assignmentService,
                transientTargetLeaseFactory,
                itemRefresh,
                warningSink,
                "falling",
            )
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun rememberSpawnOwner(
        entityId: UUID,
        ownerUuid: UUID,
    ) {
        pendingSpawnOwners[entityId] = ownerUuid
        try {
            delayedTaskExecutor.execute(SPAWN_OWNER_LIFETIME_TICKS) {
                if (pendingSpawnOwners[entityId] == ownerUuid) pendingSpawnOwners.remove(entityId)
            }
        } catch (error: RuntimeException) {
            warningSink.warn("falling item spawn handoff expiry scheduling failed (${error.javaClass.simpleName})")
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun recordSources(sources: List<FallingBlockSource>) {
        if (sources.isEmpty()) return
        val contextId = tracker.record(sources)
        try {
            delayedTaskExecutor.execute(fallingSourceLifetimeTicks(sources)) {
                tracker.expire(contextId)
            }
        } catch (error: RuntimeException) {
            tracker.expire(contextId)
            warningSink.warn("falling block source expiry scheduling failed (${error.javaClass.simpleName})")
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun assignFallingDropOwnership(
        item: Item,
        ownerUuid: UUID,
    ) {
        val lease = transientTargetLeaseFactory.acquire(item)
        pendingReconciliationLeases.put(item.uniqueId, lease)?.release()
        val request = ItemOwnershipAssignmentRequest(item.uniqueId, item.world.name, listOf(ownerUuid))
        try {
            when (val outcome = assignmentService.assign(request)) {
                is ItemOwnershipAssignmentOutcome.Assigned -> {
                    itemRefresh.refresh(item)
                    scheduleReconciliation(item, request, lease, outcome.state)
                }
                is ItemOwnershipAssignmentOutcome.Ignored -> finishReconciliation(item.uniqueId, lease)
                is ItemOwnershipAssignmentOutcome.Rejected -> {
                    finishReconciliation(item.uniqueId, lease)
                    warningSink.warn("falling item ownership rejected (${outcome.reason})")
                }
                is ItemOwnershipAssignmentOutcome.Failed ->
                    if (outcome.errorType == MISSING_TARGET) {
                        scheduleReconciliation(item, request, lease, state = null)
                    } else {
                        finishReconciliation(item.uniqueId, lease)
                        warningSink.warn("falling item ownership failed (${outcome.errorType})")
                    }
            }
        } catch (error: RuntimeException) {
            finishReconciliation(item.uniqueId, lease)
            warningSink.warn("falling item ownership assignment failed (${error.javaClass.simpleName})")
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun scheduleReconciliation(
        item: Item,
        request: ItemOwnershipAssignmentRequest,
        lease: TransientItemTargetLease,
        state: ItemState?,
    ) {
        delayedTaskExecutor.execute(CANONICAL_RECONCILIATION_DELAY_TICKS) {
            // The event-time lease deliberately shadows UUID lookup until Spigot has
            // published the dropped entity. Release it before resolving the canonical target.
            finishReconciliation(item.uniqueId, lease)
            try {
                val outcome =
                    state?.let { assigned -> assignmentService.reconcile(request.entityId, assigned) }
                        ?: assignmentService.assign(request)
                when (outcome) {
                    is ItemOwnershipAssignmentOutcome.Assigned -> itemRefresh.refresh(item)
                    is ItemOwnershipAssignmentOutcome.Ignored -> Unit
                    is ItemOwnershipAssignmentOutcome.Rejected ->
                        warningSink.warn("falling item ownership reconciliation rejected (${outcome.reason})")
                    is ItemOwnershipAssignmentOutcome.Failed ->
                        if (outcome.errorType != MISSING_TARGET) {
                            warningSink.warn("falling item ownership reconciliation failed (${outcome.errorType})")
                        }
                }
            } catch (error: RuntimeException) {
                warningSink.warn("falling item ownership reconciliation failed (${error.javaClass.simpleName})")
            }
        }
    }

    private fun finishReconciliation(
        entityId: UUID,
        lease: TransientItemTargetLease,
    ) {
        if (pendingReconciliationLeases[entityId] === lease) {
            pendingReconciliationLeases.remove(entityId)
            lease.release()
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun complete(fallingBlock: FallingBlock) {
        tracker.complete(fallingBlock.uniqueId)
        try {
            ownerStore.clear(fallingBlock)
        } catch (error: RuntimeException) {
            warningSink.warn("falling block owner cleanup failed (${error.javaClass.simpleName})")
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun readPersistedOwner(fallingBlock: FallingBlock): UUID? =
        try {
            ownerStore.read(fallingBlock)
        } catch (error: RuntimeException) {
            warningSink.warn("falling block owner recovery failed (${error.javaClass.simpleName})")
            null
        }

    private fun FallingBlock.toSpawn(): FallingBlockSpawn {
        val location = location
        return FallingBlockSpawn(
            entityId = uniqueId,
            worldId = world.uid,
            x = location.blockX,
            y = location.blockY,
            z = location.blockZ,
            materialName = blockData.material.name,
        )
    }

    private fun Item.toSpawn(): FallingBlockSpawn {
        val location = location
        return FallingBlockSpawn(
            entityId = uniqueId,
            worldId = world.uid,
            x = location.blockX,
            y = location.blockY,
            z = location.blockZ,
            materialName = itemStack.type.name,
        )
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
        tracker.clear()
        pendingReconciliationLeases.values.forEach(TransientItemTargetLease::release)
        pendingReconciliationLeases.clear()
        pendingSpawnOwners.clear()
    }

    private companion object {
        private const val CANONICAL_RECONCILIATION_DELAY_TICKS = 1L
        private const val MISSING_TARGET = "MissingTarget"
        private val DIRECT_PHYSICS_ITEM_NAMES =
            setOf(
                "POINTED_DRIPSTONE",
                "SCAFFOLDING",
                "SULFUR_SPIKE",
            )
        private const val SPAWN_OWNER_LIFETIME_TICKS = 20L
    }
}

private const val MINIMUM_FALLING_SOURCE_LIFETIME_TICKS = 20L
private const val FALLING_SOURCE_TICKS_PER_VERTICAL_BLOCK = 48L
private const val FALLING_SOURCE_LIFETIME_PADDING_TICKS = 8L
