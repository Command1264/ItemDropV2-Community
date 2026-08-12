package com.github.command1264.itemdropv2.platform.bukkit

import com.github.command1264.itemdropv2.core.BlockDropOwnershipOutcome
import com.github.command1264.itemdropv2.core.BlockDropOwnershipRequest
import com.github.command1264.itemdropv2.core.BlockDropOwnershipService
import org.bukkit.Material
import org.bukkit.entity.Item
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.ItemSpawnEvent
import org.bukkit.event.entity.ProjectileHitEvent
import java.util.UUID

public class BukkitProjectileBlockDropOwnershipController internal constructor(
    private val service: BlockDropOwnershipService,
    private val delayedTaskExecutor: DelayedMainThreadTaskExecutor,
    private val warningSink: DisplayWarningSink,
    private val itemRefresh: ItemOwnershipRefresh,
    private val transientTargetLeaseFactory: TransientItemTargetLeaseFactory =
        TransientItemTargetLeaseFactory { TransientItemTargetLease {} },
    private val tracker: ChorusFlowerProjectileTracker = ChorusFlowerProjectileTracker(),
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
        ChorusFlowerProjectileTracker(),
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
        ChorusFlowerProjectileTracker(),
    )

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    @Suppress("ReturnCount")
    public fun onProjectileHit(event: ProjectileHitEvent) {
        if (event.isAsynchronous) {
            warningSink.warn("ignored asynchronous ProjectileHitEvent")
            return
        }
        val block = event.hitBlock ?: return
        val materialName = block.type.name
        if (materialName != "CHORUS_FLOWER") {
            if (!supportsProjectileBlockDrop(materialName, event.entity.type.name)) return
        }
        val ownerUuid = resolvePlayerContributor(event.entity) ?: return
        val contextId =
            tracker.record(
                ChorusFlowerProjectileContext(
                    worldId = block.world.uid,
                    x = block.x,
                    y = block.y,
                    z = block.z,
                    ownerUuid = ownerUuid,
                    materialName = materialName,
                ),
            )
        scheduleExpiry(contextId)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public fun onItemSpawn(event: ItemSpawnEvent) {
        if (event.isAsynchronous) {
            warningSink.warn("ignored asynchronous ItemSpawnEvent for chorus flower ownership")
            return
        }
        val item = event.entity
        val materialName = item.itemStack.type.name
        if (materialName !in PROJECTILE_DROP_MATERIAL_NAMES) return
        val location = item.location
        tracker
            .claim(
                ChorusFlowerItemSpawn(
                    worldId = item.world.uid,
                    x = location.blockX,
                    y = location.blockY,
                    z = location.blockZ,
                    materialName = materialName,
                ),
            )?.let { ownerUuid ->
                assignBlockOwnershipImmediately(
                    item,
                    ownerUuid,
                    service,
                    transientTargetLeaseFactory,
                    itemRefresh,
                    warningSink,
                    "projectile block",
                )
            }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun scheduleExpiry(contextId: Long) {
        try {
            delayedTaskExecutor.execute(CONTEXT_LIFETIME_TICKS) { tracker.expire(contextId) }
        } catch (error: RuntimeException) {
            tracker.expire(contextId)
            warningSink.warn("projectile block ownership context scheduling failed (${error.javaClass.simpleName})")
        }
    }

    override fun close() {
        tracker.clear()
    }

    private companion object {
        private const val CONTEXT_LIFETIME_TICKS = 2L
        private val PROJECTILE_DROP_MATERIAL_NAMES = SPELEOTHEM_MATERIAL_NAMES + "CHORUS_FLOWER"
    }
}

private const val TRIDENT_ENTITY_TYPE_NAME = "TRIDENT"
private val SPELEOTHEM_MATERIAL_NAMES =
    setOf(
        "POINTED_DRIPSTONE",
        "SULFUR_SPIKE",
    )

internal fun supportsProjectileBlockDrop(
    materialName: String,
    projectileTypeName: String,
): Boolean =
    materialName == "CHORUS_FLOWER" ||
        (materialName in SPELEOTHEM_MATERIAL_NAMES && projectileTypeName == TRIDENT_ENTITY_TYPE_NAME)
