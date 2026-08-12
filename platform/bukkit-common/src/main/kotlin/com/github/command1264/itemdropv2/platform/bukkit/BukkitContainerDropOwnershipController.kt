package com.github.command1264.itemdropv2.platform.bukkit

import com.github.command1264.itemdropv2.core.BlockDropOwnershipOutcome
import com.github.command1264.itemdropv2.core.BlockDropOwnershipRequest
import com.github.command1264.itemdropv2.core.BlockDropOwnershipService
import com.github.command1264.itemdropv2.core.ItemDisplaySettingsRepository
import com.github.command1264.itemdropv2.core.ItemStateWriteResult
import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.block.Chest
import org.bukkit.entity.Item
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.inventory.BlockInventoryHolder
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack
import org.bukkit.util.Vector
import java.util.UUID
import kotlin.random.Random

internal data class ContainerDropSpawnPlan(
    val offsetX: Double,
    val offsetY: Double,
    val offsetZ: Double,
    val velocityX: Double,
    val velocityY: Double,
    val velocityZ: Double,
)

internal fun interface ContainerDropSpawnPlanner {
    fun next(): ContainerDropSpawnPlan
}

internal class VanillaLikeContainerDropSpawnPlanner(
    private val nextDouble: () -> Double = { Random.Default.nextDouble() },
) : ContainerDropSpawnPlanner {
    override fun next(): ContainerDropSpawnPlan =
        ContainerDropSpawnPlan(
            offsetX = nextOffset(),
            offsetY = nextOffset(),
            offsetZ = nextOffset(),
            velocityX = nextTriangularVelocity(),
            velocityY = BASE_UPWARD_VELOCITY + nextTriangularVelocity(),
            velocityZ = nextTriangularVelocity(),
        )

    private fun nextOffset(): Double = MINIMUM_OFFSET + nextRandom() * OFFSET_RANGE

    private fun nextTriangularVelocity(): Double = (nextRandom() - nextRandom()) * VELOCITY_SPREAD

    private fun nextRandom(): Double =
        nextDouble().also { value ->
            require(value in 0.0..1.0) { "container drop random value must be between zero and one" }
        }

    private companion object {
        private const val MINIMUM_OFFSET = 0.125
        private const val OFFSET_RANGE = 0.75
        private const val BASE_UPWARD_VELOCITY = 0.2

        // Intentionally gentler than vanilla's typical container-drop spread. The resulting drops
        // stay slightly more concentrated, and that visual behavior was explicitly accepted.
        private const val VELOCITY_SPREAD = 0.05
    }
}

internal interface ContainerDropTarget {
    fun snapshot(): Array<ItemStack?>

    fun clear()

    fun restore(snapshot: Array<ItemStack?>)

    fun spawn(
        itemStack: ItemStack,
        plan: ContainerDropSpawnPlan,
    ): Item
}

internal fun interface ContainerDropTargetResolver {
    fun resolve(block: Block): ContainerDropTarget?
}

public fun interface ContainerDropItemStateCleaner {
    fun clear(item: Item): ItemStateWriteResult
}

public class BukkitContainerDropOwnershipController internal constructor(
    private val service: BlockDropOwnershipService,
    private val settingsRepository: ItemDisplaySettingsRepository,
    private val warningSink: DisplayWarningSink,
    private val itemRefresh: ItemOwnershipRefresh,
    private val transientTargetLeaseFactory: TransientItemTargetLeaseFactory =
        TransientItemTargetLeaseFactory { TransientItemTargetLease {} },
    private val itemStateCleaner: ContainerDropItemStateCleaner =
        ContainerDropItemStateCleaner { ItemStateWriteResult.Applied },
    private val targetResolver: ContainerDropTargetResolver = BukkitContainerDropTargetResolver,
    private val canonicalItemSpawnCapture: CanonicalItemSpawnCapture = DirectItemSpawnCapture,
    private val spawnPlanner: ContainerDropSpawnPlanner = VanillaLikeContainerDropSpawnPlanner(),
) : Listener,
    AutoCloseable {
    public constructor(
        service: BlockDropOwnershipService,
        settingsRepository: ItemDisplaySettingsRepository,
        warningSink: DisplayWarningSink,
        itemRefresh: ItemOwnershipRefresh,
        transientTargetLeaseFactory: TransientItemTargetLeaseFactory,
        itemStateCleaner: ContainerDropItemStateCleaner,
        canonicalItemSpawnCapture: CanonicalItemSpawnCapture = DirectItemSpawnCapture,
    ) : this(
        service,
        settingsRepository,
        warningSink,
        itemRefresh,
        transientTargetLeaseFactory,
        itemStateCleaner,
        BukkitContainerDropTargetResolver,
        canonicalItemSpawnCapture,
        VanillaLikeContainerDropSpawnPlanner(),
    )

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    @Suppress("ReturnCount")
    public fun onBlockBreak(event: BlockBreakEvent) {
        if (!ensureSynchronous(event.isAsynchronous, "BlockBreakEvent") || event.isCancelled) return
        if (!event.isDropItems || !supportsReplacement(event.block.type.name)) return
        val settings = settingsRepository.settings()
        if (!settings.ownership.enabled || event.block.world.name in settings.blockedWorlds) return
        val target = targetResolver.resolve(event.block) ?: return
        replaceContents(target, event.player.uniqueId)
    }

    @Suppress("ReturnCount", "TooGenericExceptionCaught")
    private fun replaceContents(
        target: ContainerDropTarget,
        ownerUuid: UUID,
    ) {
        val snapshot =
            try {
                target.snapshot().map { it?.clone() }.toTypedArray()
            } catch (error: RuntimeException) {
                warningSink.warn("container inventory snapshot failed (${error.javaClass.simpleName})")
                return
            }
        val contents =
            snapshot.filterNotNull().filter { itemStack ->
                itemStack.type != Material.AIR && itemStack.amount > 0
            }
        if (contents.isEmpty()) return

        val spawnedItems = mutableListOf<Item>()
        try {
            target.clear()
            contents.forEach { itemStack ->
                val plan = spawnPlanner.next()
                val item =
                    canonicalItemSpawnCapture.capture(
                        operation = { target.spawn(itemStack.clone(), plan) },
                        beforePublication = { spawned -> scheduleAssignment(spawned, ownerUuid) },
                    )
                spawnedItems += item
                item.velocity = Vector(plan.velocityX, plan.velocityY, plan.velocityZ)
            }
        } catch (error: RuntimeException) {
            spawnedItems.forEach { item ->
                when (val cleared = itemStateCleaner.clear(item)) {
                    ItemStateWriteResult.Applied -> Unit
                    ItemStateWriteResult.MissingTarget -> warningSink.warn("container rollback state target was missing")
                    is ItemStateWriteResult.Rejected ->
                        warningSink.warn("container rollback state clear rejected (${cleared.reason})")
                    is ItemStateWriteResult.Failed ->
                        warningSink.warn("container rollback state clear failed (${cleared.errorType})")
                }
                item.remove()
            }
            runCatching { target.restore(snapshot) }
                .onFailure { restoreError ->
                    warningSink.warn(
                        "container inventory rollback failed (${restoreError.javaClass.simpleName})",
                    )
                }
            warningSink.warn("container drop replacement rolled back (${error.javaClass.simpleName})")
            return
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun scheduleAssignment(
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
            "container",
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

    override fun close(): Unit = Unit

    public companion object {
        private val REPLACED_CONTAINER_TYPES =
            setOf(
                "BARREL",
                "BLAST_FURNACE",
                "BREWING_STAND",
                "CHEST",
                "CHISELED_BOOKSHELF",
                "CRAFTER",
                "DISPENSER",
                "DROPPER",
                "FURNACE",
                "HOPPER",
                "LECTERN",
                "SHELF",
                "SMOKER",
                "TRAPPED_CHEST",
            )

        internal fun supportsReplacement(materialName: String): Boolean = materialName in REPLACED_CONTAINER_TYPES
    }
}

internal object BukkitContainerDropTargetResolver : ContainerDropTargetResolver {
    override fun resolve(block: Block): ContainerDropTarget? {
        val state = block.state
        val inventory =
            when (state) {
                is Chest -> state.blockInventory
                is BlockInventoryHolder -> state.inventory
                else -> null
            } ?: return null
        return BukkitContainerDropTarget(block, inventory)
    }
}

private class BukkitContainerDropTarget(
    private val block: Block,
    private val inventory: Inventory,
) : ContainerDropTarget {
    override fun snapshot(): Array<ItemStack?> = inventory.contents.map { it?.clone() }.toTypedArray()

    override fun clear() {
        inventory.clear()
    }

    override fun restore(snapshot: Array<ItemStack?>) {
        inventory.contents = snapshot.map { it?.clone() }.toTypedArray()
    }

    override fun spawn(
        itemStack: ItemStack,
        plan: ContainerDropSpawnPlan,
    ): Item =
        block.world.dropItem(
            block.location.clone().add(plan.offsetX, plan.offsetY, plan.offsetZ),
            itemStack,
        )
}
