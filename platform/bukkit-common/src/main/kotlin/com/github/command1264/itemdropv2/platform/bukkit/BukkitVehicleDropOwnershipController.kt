package com.github.command1264.itemdropv2.platform.bukkit

import com.github.command1264.itemdropv2.core.ItemOwnershipAssignmentOutcome
import com.github.command1264.itemdropv2.core.ItemOwnershipAssignmentRequest
import com.github.command1264.itemdropv2.core.ItemOwnershipAssignmentService
import org.bukkit.Material
import org.bukkit.entity.Item
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDropItemEvent
import org.bukkit.event.entity.ItemSpawnEvent
import org.bukkit.event.vehicle.VehicleDestroyEvent
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemStack
import java.util.UUID

public class BukkitVehicleDropOwnershipController internal constructor(
    private val assignmentService: ItemOwnershipAssignmentService,
    private val delayedTaskExecutor: DelayedMainThreadTaskExecutor,
    private val warningSink: DisplayWarningSink,
    private val itemRefresh: ItemOwnershipRefresh,
    private val transientTargetLeaseFactory: TransientItemTargetLeaseFactory =
        TransientItemTargetLeaseFactory { TransientItemTargetLease {} },
    private val contexts: VehicleDropContextTracker,
) : Listener,
    AutoCloseable {
    public constructor(
        assignmentService: ItemOwnershipAssignmentService,
        delayedTaskExecutor: DelayedMainThreadTaskExecutor,
        warningSink: DisplayWarningSink,
        itemRefresh: ItemOwnershipRefresh,
    ) : this(
        assignmentService,
        delayedTaskExecutor,
        warningSink,
        itemRefresh,
        TransientItemTargetLeaseFactory { TransientItemTargetLease {} },
        VehicleDropContextTracker(VehicleItemStackMatcher(ItemStack::isSimilar)),
    )

    public constructor(
        assignmentService: ItemOwnershipAssignmentService,
        delayedTaskExecutor: DelayedMainThreadTaskExecutor,
        warningSink: DisplayWarningSink,
        itemRefresh: ItemOwnershipRefresh,
        transientTargetLeaseFactory: TransientItemTargetLeaseFactory,
    ) : this(
        assignmentService,
        delayedTaskExecutor,
        warningSink,
        itemRefresh,
        transientTargetLeaseFactory,
        VehicleDropContextTracker(VehicleItemStackMatcher(ItemStack::isSimilar)),
    )

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public fun onVehicleDestroy(event: VehicleDestroyEvent) {
        if (!ensureSynchronous(event.isAsynchronous, "VehicleDestroyEvent") || event.isCancelled) return
        val ownerUuid = event.attacker?.let(::resolvePlayerContributor) ?: return
        val vehicle = event.vehicle
        val location = vehicle.location
        val inventoryContents =
            (vehicle as? InventoryHolder)
                ?.inventory
                ?.contents
                ?.filterNotNull()
                .orEmpty()
        val contextId =
            contexts.record(
                vehicleId = vehicle.uniqueId,
                worldName = vehicle.world.name,
                x = location.x,
                y = location.y,
                z = location.z,
                ownerUuid = ownerUuid,
                inventoryContents = inventoryContents,
            )
        scheduleExpiry(vehicle.uniqueId, contextId)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public fun onEntityDropItem(event: EntityDropItemEvent) {
        if (!ensureSynchronous(event.isAsynchronous, "EntityDropItemEvent") || event.isCancelled) return
        val source = event.entity
        val item = event.itemDrop
        contexts
            .claimDirect(source.uniqueId, source.world.name, item.world.name, item.uniqueId)
            ?.let { ownerUuid -> scheduleAssignment(item, ownerUuid) }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public fun onItemSpawn(event: ItemSpawnEvent) {
        if (!ensureSynchronous(event.isAsynchronous, "ItemSpawnEvent") || event.isCancelled) return
        val item = event.entity
        val location = item.location
        contexts
            .claimInventory(
                itemId = item.uniqueId,
                worldName = item.world.name,
                x = location.x,
                y = location.y,
                z = location.z,
                itemStack = item.itemStack,
            )?.let { ownerUuid -> scheduleAssignment(item, ownerUuid) }
    }

    override fun close() {
        contexts.clear()
    }

    private fun ensureSynchronous(
        asynchronous: Boolean,
        eventName: String,
    ): Boolean {
        if (!asynchronous) return true
        warningSink.warn("ignored asynchronous $eventName")
        return false
    }

    @Suppress("TooGenericExceptionCaught")
    private fun scheduleExpiry(
        vehicleId: UUID,
        contextId: Long,
    ) {
        try {
            delayedTaskExecutor.execute(CONTEXT_EXPIRY_DELAY_TICKS) { contexts.expire(vehicleId, contextId) }
        } catch (error: RuntimeException) {
            contexts.expire(vehicleId, contextId)
            warningSink.warn("vehicle context expiry scheduling failed (${error.javaClass.simpleName})")
        }
    }

    private fun scheduleAssignment(
        item: Item,
        ownerUuid: UUID,
    ) {
        assignItemOwnershipImmediately(
            item,
            listOf(ownerUuid),
            assignmentService,
            transientTargetLeaseFactory,
            itemRefresh,
            warningSink,
            "vehicle",
        )
    }

    private companion object {
        private const val CONTEXT_EXPIRY_DELAY_TICKS = 1L
    }
}

internal fun interface VehicleItemStackMatcher {
    fun isSimilar(
        expected: ItemStack,
        actual: ItemStack,
    ): Boolean
}

internal class VehicleDropContextTracker(
    private val itemStackMatcher: VehicleItemStackMatcher,
) {
    private val contexts = linkedMapOf<UUID, VehicleDropContext>()
    private val directItemClaims = mutableMapOf<UUID, Long>()
    private var nextContextId = 1L

    fun record(
        vehicleId: UUID,
        worldName: String,
        x: Double,
        y: Double,
        z: Double,
        ownerUuid: UUID,
        inventoryContents: List<ItemStack>,
    ): Long {
        remove(vehicleId)
        val contextId = nextContextId++
        contexts[vehicleId] =
            VehicleDropContext(
                id = contextId,
                worldName = worldName,
                x = x,
                y = y,
                z = z,
                ownerUuid = ownerUuid,
                inventory = aggregateInventory(inventoryContents),
                itemStackMatcher = itemStackMatcher,
            )
        while (contexts.size > MAX_CONTEXTS) remove(contexts.keys.first())
        return contextId
    }

    fun claimDirect(
        vehicleId: UUID,
        sourceWorldName: String,
        itemWorldName: String,
        itemId: UUID,
    ): UUID? {
        val context = contexts[vehicleId]
        return if (context?.canClaimDirect(sourceWorldName, itemWorldName) == true) {
            context.directDropCount++
            context.directItemIds += itemId
            directItemClaims[itemId] = context.id
            context.ownerUuid
        } else {
            null
        }
    }

    fun claimInventory(
        itemId: UUID,
        worldName: String,
        x: Double,
        y: Double,
        z: Double,
        itemStack: ItemStack,
    ): UUID? {
        if (directItemClaims.remove(itemId) != null) return null
        val context =
            contexts.values
                .asSequence()
                .filter { candidate -> candidate.matches(worldName, x, y, z, itemStack) }
                .minWithOrNull(compareBy<VehicleDropContext> { it.distanceSquared(x, y, z) }.thenByDescending { it.id })
        return context?.claim(itemStack)
    }

    fun expire(
        vehicleId: UUID,
        contextId: Long,
    ) {
        if (contexts[vehicleId]?.id == contextId) remove(vehicleId)
    }

    fun clear() {
        contexts.clear()
        directItemClaims.clear()
    }

    private fun remove(vehicleId: UUID) {
        val removed = contexts.remove(vehicleId) ?: return
        removed.directItemIds.forEach(directItemClaims::remove)
    }

    private fun aggregateInventory(contents: List<ItemStack>): MutableList<VehicleInventoryExpectation> {
        val aggregated = mutableListOf<VehicleInventoryExpectation>()
        contents
            .asSequence()
            .filter { stack -> stack.type != Material.AIR && stack.amount > 0 }
            .forEach { stack ->
                val existing = aggregated.firstOrNull { expectation -> expectation.isSimilar(stack, itemStackMatcher) }
                if (existing == null) {
                    val template = stack.clone().apply { amount = 1 }
                    aggregated += VehicleInventoryExpectation(template, stack.amount)
                } else {
                    existing.remainingAmount = saturatingAdd(existing.remainingAmount, stack.amount)
                }
            }
        return aggregated
    }

    private fun saturatingAdd(
        left: Int,
        right: Int,
    ): Int = if (left > Int.MAX_VALUE - right) Int.MAX_VALUE else left + right

    private companion object {
        private const val MAX_CONTEXTS = 128
    }
}

private data class VehicleDropContext(
    val id: Long,
    val worldName: String,
    val x: Double,
    val y: Double,
    val z: Double,
    val ownerUuid: UUID,
    val inventory: MutableList<VehicleInventoryExpectation>,
    val itemStackMatcher: VehicleItemStackMatcher,
    val directItemIds: MutableSet<UUID> = mutableSetOf(),
    var directDropCount: Int = 0,
) {
    fun canClaimDirect(
        sourceWorldName: String,
        itemWorldName: String,
    ): Boolean =
        worldName == sourceWorldName &&
            worldName == itemWorldName &&
            directDropCount < MAX_DIRECT_DROPS_PER_CONTEXT

    fun matches(
        candidateWorldName: String,
        candidateX: Double,
        candidateY: Double,
        candidateZ: Double,
        itemStack: ItemStack,
    ): Boolean =
        worldName == candidateWorldName &&
            distanceSquared(candidateX, candidateY, candidateZ) <= MAX_DISTANCE_SQUARED &&
            inventory.any { expectation -> expectation.canClaim(itemStack, itemStackMatcher) }

    fun distanceSquared(
        candidateX: Double,
        candidateY: Double,
        candidateZ: Double,
    ): Double {
        val dx = x - candidateX
        val dy = y - candidateY
        val dz = z - candidateZ
        return dx * dx + dy * dy + dz * dz
    }

    fun claim(itemStack: ItemStack): UUID? {
        val expectation = inventory.firstOrNull { candidate -> candidate.canClaim(itemStack, itemStackMatcher) } ?: return null
        expectation.remainingAmount -= itemStack.amount
        if (expectation.remainingAmount == 0) inventory.remove(expectation)
        return ownerUuid
    }

    private companion object {
        private const val MAX_DISTANCE_SQUARED = 2.25
        private const val MAX_DIRECT_DROPS_PER_CONTEXT = 64
    }
}

private data class VehicleInventoryExpectation(
    val template: ItemStack,
    var remainingAmount: Int,
) {
    fun isSimilar(
        itemStack: ItemStack,
        matcher: VehicleItemStackMatcher,
    ): Boolean = matcher.isSimilar(template, itemStack)

    fun canClaim(
        itemStack: ItemStack,
        matcher: VehicleItemStackMatcher,
    ): Boolean = itemStack.amount > 0 && itemStack.amount <= remainingAmount && isSimilar(itemStack, matcher)
}
