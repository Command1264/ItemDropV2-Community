package com.github.command1264.itemdropv2.platform.bukkit

import org.bukkit.inventory.ItemStack
import java.util.UUID

internal data class EntityDeathDropContext(
    val worldUuid: UUID,
    val x: Double,
    val y: Double,
    val z: Double,
    val expectedDrops: List<ItemStack>,
    val eligibleOwnerUuids: List<UUID>,
)

internal data class EntitySpawnedDrop(
    val worldUuid: UUID,
    val x: Double,
    val y: Double,
    val z: Double,
    val itemStack: ItemStack,
)

internal class EntityDeathDropTracker(
    private val itemMatcher: (ItemStack, ItemStack) -> Boolean = ItemStack::isSimilar,
) {
    private val contexts = linkedMapOf<Long, MutableContext>()
    private var nextId = 1L

    fun record(context: EntityDeathDropContext): Long {
        if (contexts.size >= MAX_ACTIVE_CONTEXTS) {
            contexts.keys.firstOrNull()?.let(contexts::remove)
        }
        val id = nextId++
        contexts[id] =
            MutableContext(
                context.worldUuid,
                context.x,
                context.y,
                context.z,
                context.expectedDrops
                    .take(MAX_EXPECTED_DROPS_PER_CONTEXT)
                    .map(ItemStack::clone)
                    .toMutableList(),
                context.eligibleOwnerUuids,
            )
        return id
    }

    fun claim(drop: EntitySpawnedDrop): List<UUID>? {
        val match =
            contexts.entries
                .asSequence()
                .map { (id, context) -> ContextDistance(id, context, context.distanceSquared(drop)) }
                .filter { candidate ->
                    candidate.context.worldUuid == drop.worldUuid &&
                        candidate.distanceSquared <= MAX_DISTANCE_SQUARED
                }.sortedWith(
                    compareBy<ContextDistance> { it.distanceSquared }
                        .thenByDescending { it.id },
                ).mapNotNull { candidate ->
                    candidate.context
                        .matchingDrop(drop.itemStack, itemMatcher)
                        ?.let { index -> Triple(candidate.id, candidate.context, index) }
                }.firstOrNull() ?: return null
        val (id, context, index) = match
        val expected = context.expectedDrops[index]
        if (drop.itemStack.amount >= expected.amount) {
            context.expectedDrops.removeAt(index)
        } else {
            expected.amount -= drop.itemStack.amount
        }
        if (context.expectedDrops.isEmpty()) contexts.remove(id)
        return context.eligibleOwnerUuids
    }

    fun expire(id: Long) {
        contexts.remove(id)
    }

    fun clear() {
        contexts.clear()
    }

    private data class ContextDistance(
        val id: Long,
        val context: MutableContext,
        val distanceSquared: Double,
    )

    private data class MutableContext(
        val worldUuid: UUID,
        val x: Double,
        val y: Double,
        val z: Double,
        val expectedDrops: MutableList<ItemStack>,
        val eligibleOwnerUuids: List<UUID>,
    ) {
        fun distanceSquared(drop: EntitySpawnedDrop): Double = square(drop.x - x) + square(drop.y - y) + square(drop.z - z)

        fun matchingDrop(
            actual: ItemStack,
            matcher: (ItemStack, ItemStack) -> Boolean,
        ): Int? = expectedDrops.indices.firstOrNull { index -> matcher(expectedDrops[index], actual) }
    }

    private companion object {
        private const val MAX_DISTANCE_SQUARED = 16.0
        private const val MAX_ACTIVE_CONTEXTS = 1_024
        private const val MAX_EXPECTED_DROPS_PER_CONTEXT = 128

        private fun square(value: Double): Double = value * value
    }
}
