package com.github.command1264.itemdropv2.platform.bukkit

import com.github.command1264.itemdropv2.core.BlockDropOwnershipOutcome
import com.github.command1264.itemdropv2.core.BlockDropOwnershipRequest
import com.github.command1264.itemdropv2.core.BlockDropOwnershipService
import org.bukkit.event.Event
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.entity.ItemSpawnEvent
import org.bukkit.event.player.PlayerInteractEvent
import java.util.UUID

public class BukkitHarvestDropOwnershipController internal constructor(
    private val service: BlockDropOwnershipService,
    private val delayedTaskExecutor: DelayedMainThreadTaskExecutor,
    private val warningSink: DisplayWarningSink,
    private val itemRefresh: ItemOwnershipRefresh,
    private val transientTargetLeaseFactory: TransientItemTargetLeaseFactory =
        TransientItemTargetLeaseFactory { TransientItemTargetLease {} },
    private val tracker: HarvestDropContextTracker = HarvestDropContextTracker(),
) : Listener,
    AutoCloseable {
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
        HarvestDropContextTracker(),
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
        HarvestDropContextTracker(),
    )

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    @Suppress("ReturnCount")
    public fun onPlayerInteract(event: PlayerInteractEvent) {
        if (!ensureSynchronous(event.isAsynchronous, "PlayerInteractEvent")) return
        if (event.action != Action.RIGHT_CLICK_BLOCK || event.useInteractedBlock() == Event.Result.DENY) return
        val block = event.clickedBlock ?: return
        val materialName = block.type.name
        if (!isHarvestableBlock(materialName, block.blockData.asString)) return
        val dropMaterialName = harvestDropMaterialName(materialName) ?: return
        val contextId =
            tracker.record(
                HarvestDropSource(
                    worldId = block.world.uid,
                    x = block.x,
                    y = block.y,
                    z = block.z,
                    ownerUuid = event.player.uniqueId,
                    dropMaterialName = dropMaterialName,
                    maximumAmount = MAXIMUM_HARVEST_AMOUNT,
                ),
            )
        scheduleExpiry(contextId)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public fun onItemSpawn(event: ItemSpawnEvent) {
        if (!ensureSynchronous(event.isAsynchronous, "ItemSpawnEvent")) return
        val item = event.entity
        val location = item.location
        tracker
            .claim(
                HarvestItemSpawn(
                    worldId = item.world.uid,
                    x = location.blockX,
                    y = location.blockY,
                    z = location.blockZ,
                    materialName = item.itemStack.type.name,
                    amount = item.itemStack.amount,
                ),
            )?.let { ownerUuid ->
                assignBlockOwnershipImmediately(
                    item,
                    ownerUuid,
                    service,
                    transientTargetLeaseFactory,
                    itemRefresh,
                    warningSink,
                    "harvest",
                )
            }
    }

    override fun close() {
        tracker.clear()
    }

    private fun ensureSynchronous(
        asynchronous: Boolean,
        eventName: String,
    ): Boolean {
        if (!asynchronous) return true
        warningSink.warn("ignored asynchronous $eventName for harvest ownership")
        return false
    }

    @Suppress("TooGenericExceptionCaught")
    private fun scheduleExpiry(contextId: Long) {
        try {
            delayedTaskExecutor.execute(CONTEXT_LIFETIME_TICKS) { tracker.expire(contextId) }
        } catch (error: RuntimeException) {
            tracker.expire(contextId)
            warningSink.warn("harvest ownership context scheduling failed (${error.javaClass.simpleName})")
        }
    }

    private companion object {
        private const val CONTEXT_LIFETIME_TICKS = 2L
        private const val MAXIMUM_HARVEST_AMOUNT = 64
    }
}
