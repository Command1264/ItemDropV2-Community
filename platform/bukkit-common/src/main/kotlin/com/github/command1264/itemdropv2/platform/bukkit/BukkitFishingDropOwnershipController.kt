package com.github.command1264.itemdropv2.platform.bukkit

import com.github.command1264.itemdropv2.core.ItemOwnershipAssignmentOutcome
import com.github.command1264.itemdropv2.core.ItemOwnershipAssignmentRequest
import com.github.command1264.itemdropv2.core.ItemOwnershipAssignmentService
import org.bukkit.entity.Item
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerFishEvent

public class BukkitFishingDropOwnershipController(
    private val assignmentService: ItemOwnershipAssignmentService,
    private val warningSink: DisplayWarningSink,
    private val itemRefresh: ItemOwnershipRefresh,
    private val transientTargetLeaseFactory: TransientItemTargetLeaseFactory =
        TransientItemTargetLeaseFactory { TransientItemTargetLease {} },
) : Listener {
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public fun onPlayerFish(event: PlayerFishEvent) {
        when {
            event.isAsynchronous -> warningSink.warn("ignored asynchronous PlayerFishEvent")
            event.state != PlayerFishEvent.State.CAUGHT_FISH -> Unit
            else -> (event.caught as? Item)?.let { item -> assign(item, event.player.uniqueId) }
        }
    }

    private fun assign(
        item: Item,
        ownerUuid: java.util.UUID,
    ) {
        assignItemOwnershipImmediately(
            item = item,
            eligibleOwnerUuids = listOf(ownerUuid),
            service = assignmentService,
            transientTargetLeaseFactory = transientTargetLeaseFactory,
            itemRefresh = itemRefresh,
            warningSink = warningSink,
            sourceName = "fishing",
        )
    }
}
