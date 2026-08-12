package com.github.command1264.itemdropv2.platform.bukkit

import com.github.command1264.itemdropv2.core.BlockDropOwnershipOutcome
import com.github.command1264.itemdropv2.core.BlockDropOwnershipRequest
import com.github.command1264.itemdropv2.core.BlockDropOwnershipService
import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.entity.Item
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.ItemSpawnEvent
import org.bukkit.event.entity.ProjectileHitEvent
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemStack
import java.lang.reflect.Method
import java.util.UUID

public class BukkitDecoratedPotDropOwnershipController internal constructor(
    private val service: BlockDropOwnershipService,
    private val delayedTaskExecutor: DelayedMainThreadTaskExecutor,
    private val warningSink: DisplayWarningSink,
    private val itemRefresh: ItemOwnershipRefresh,
    private val transientTargetLeaseFactory: TransientItemTargetLeaseFactory =
        TransientItemTargetLeaseFactory { TransientItemTargetLease {} },
    private val tracker: DecoratedPotDropContextTracker =
        DecoratedPotDropContextTracker(DecoratedPotItemStackMatcher(ItemStack::isSimilar)),
    private val snapshotReader: DecoratedPotSnapshotReader = ReflectiveDecoratedPotSnapshotReader,
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
        DecoratedPotDropContextTracker(DecoratedPotItemStackMatcher(ItemStack::isSimilar)),
        ReflectiveDecoratedPotSnapshotReader,
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
        DecoratedPotDropContextTracker(DecoratedPotItemStackMatcher(ItemStack::isSimilar)),
        ReflectiveDecoratedPotSnapshotReader,
    )

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    @Suppress("ReturnCount")
    public fun onProjectileHit(event: ProjectileHitEvent) {
        if (!ensureSynchronous(event.isAsynchronous, "ProjectileHitEvent")) return
        val block = event.hitBlock ?: return
        if (block.type.name != DECORATED_POT_MATERIAL_NAME) return
        val ownerUuid = resolvePlayerContributor(event.entity) ?: return
        val expectedDrops = snapshotReader.read(block)
        if (expectedDrops == null) {
            warningSink.warn("decorated pot snapshot unavailable; projectile ownership skipped")
            return
        }
        val contextId =
            tracker.record(
                DecoratedPotDropSource(
                    worldId = block.world.uid,
                    x = block.x,
                    y = block.y,
                    z = block.z,
                    ownerUuid = ownerUuid,
                    expectedDrops = expectedDrops,
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
                DecoratedPotItemSpawn(
                    worldId = item.world.uid,
                    x = location.blockX,
                    y = location.blockY,
                    z = location.blockZ,
                    itemStack = item.itemStack,
                ),
            )?.let { ownerUuid ->
                assignBlockOwnershipImmediately(
                    item,
                    ownerUuid,
                    service,
                    transientTargetLeaseFactory,
                    itemRefresh,
                    warningSink,
                    "decorated pot",
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
        warningSink.warn("ignored asynchronous $eventName for decorated pot ownership")
        return false
    }

    @Suppress("TooGenericExceptionCaught")
    private fun scheduleExpiry(contextId: Long) {
        try {
            delayedTaskExecutor.execute(CONTEXT_LIFETIME_TICKS) { tracker.expire(contextId) }
        } catch (error: RuntimeException) {
            tracker.expire(contextId)
            warningSink.warn("decorated pot ownership context scheduling failed (${error.javaClass.simpleName})")
        }
    }

    private companion object {
        private const val DECORATED_POT_MATERIAL_NAME = "DECORATED_POT"
        private const val CONTEXT_LIFETIME_TICKS = 2L
    }
}

internal fun interface DecoratedPotSnapshotReader {
    fun read(block: Block): List<ItemStack>?
}

internal object ReflectiveDecoratedPotSnapshotReader : DecoratedPotSnapshotReader {
    override fun read(block: Block): List<ItemStack>? {
        if (block.type.name != "DECORATED_POT") return null
        return readDecoratedPotExpectedDrops(block.state)
    }
}

internal fun readDecoratedPotExpectedDrops(state: Any): List<ItemStack>? =
    try {
        val sideMaterials = readDecoratedPotSideMaterials(state)
        if (sideMaterials.size != DECORATED_POT_SIDE_COUNT) {
            null
        } else {
            val inventoryContents =
                (state as? InventoryHolder)
                    ?.inventory
                    ?.contents
                    ?.filterNotNull()
                    .orEmpty()
            sideMaterials.map(::ItemStack) + inventoryContents.map(ItemStack::clone)
        }
    } catch (_: ReflectiveOperationException) {
        null
    } catch (_: LinkageError) {
        null
    } catch (_: ClassCastException) {
        null
    }

@Suppress("ReturnCount")
private fun readDecoratedPotSideMaterials(state: Any): List<Material> {
    return when (val capability = decoratedPotSideCapabilityByClass.get(state.javaClass)) {
        is DecoratedPotSideCapability.Modern -> {
            val map = capability.method.invoke(state) as? Map<*, *> ?: return emptyList()
            map.values.filterIsInstance<Material>()
        }
        is DecoratedPotSideCapability.Legacy -> {
            val values = capability.method.invoke(state) as? Iterable<*> ?: return emptyList()
            values.filterIsInstance<Material>()
        }
        DecoratedPotSideCapability.Unsupported -> emptyList()
    }
}

private val decoratedPotSideCapabilityByClass =
    RuntimeClassCapabilityCache<DecoratedPotSideCapability>(::resolveDecoratedPotSideCapability)

private fun resolveDecoratedPotSideCapability(type: Class<*>): DecoratedPotSideCapability =
    try {
        type.methods
            .firstOrNull { method -> method.name == "getSherds" && method.parameterCount == 0 }
            ?.let(DecoratedPotSideCapability::Modern)
            ?: DecoratedPotSideCapability.Legacy(type.getMethod("getShards"))
    } catch (_: NoSuchMethodException) {
        DecoratedPotSideCapability.Unsupported
    } catch (_: SecurityException) {
        DecoratedPotSideCapability.Unsupported
    } catch (_: LinkageError) {
        DecoratedPotSideCapability.Unsupported
    }

private sealed interface DecoratedPotSideCapability {
    data class Modern(
        val method: Method,
    ) : DecoratedPotSideCapability

    data class Legacy(
        val method: Method,
    ) : DecoratedPotSideCapability

    data object Unsupported : DecoratedPotSideCapability
}

private const val DECORATED_POT_SIDE_COUNT = 4
