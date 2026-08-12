package com.github.command1264.itemdropv2.platform.bukkit

import org.bukkit.Material
import org.bukkit.inventory.ItemStack
import java.util.UUID

internal fun interface DecoratedPotItemStackMatcher {
    fun isSimilar(
        expected: ItemStack,
        actual: ItemStack,
    ): Boolean
}

internal data class DecoratedPotDropSource(
    val worldId: UUID,
    val x: Int,
    val y: Int,
    val z: Int,
    val ownerUuid: UUID,
    val expectedDrops: List<ItemStack>,
)

internal data class DecoratedPotItemSpawn(
    val worldId: UUID,
    val x: Int,
    val y: Int,
    val z: Int,
    val itemStack: ItemStack,
)

internal class DecoratedPotDropContextTracker(
    private val itemStackMatcher: DecoratedPotItemStackMatcher,
    private val maximumContexts: Int = 128,
) {
    private val contexts = linkedMapOf<DecoratedPotPosition, DecoratedPotDropContext>()
    private var nextContextId = 1L

    init {
        require(maximumContexts > 0) { "maximum decorated pot context count must be positive" }
    }

    fun record(source: DecoratedPotDropSource): Long {
        val id = nextContextId++
        val position = source.position()
        contexts[position] =
            DecoratedPotDropContext(
                id = id,
                ownerUuid = source.ownerUuid,
                expectedDrops = aggregate(source.expectedDrops),
            )
        while (contexts.size > maximumContexts) contexts.remove(contexts.keys.first())
        return id
    }

    @Suppress("ReturnCount")
    fun claim(spawn: DecoratedPotItemSpawn): UUID? {
        val position = spawn.position()
        val context = contexts[position] ?: return null
        val expectation =
            context.expectedDrops.firstOrNull { candidate ->
                candidate.canClaim(spawn.itemStack, itemStackMatcher)
            } ?: return null
        expectation.remainingAmount -= spawn.itemStack.amount
        if (expectation.remainingAmount == 0) context.expectedDrops.remove(expectation)
        if (context.expectedDrops.isEmpty()) contexts.remove(position)
        return context.ownerUuid
    }

    fun expire(contextId: Long) {
        val position = contexts.entries.firstOrNull { (_, context) -> context.id == contextId }?.key ?: return
        contexts.remove(position)
    }

    fun clear() {
        contexts.clear()
    }

    private fun aggregate(stacks: List<ItemStack>): MutableList<DecoratedPotDropExpectation> {
        val aggregated = mutableListOf<DecoratedPotDropExpectation>()
        stacks
            .asSequence()
            .filter { stack -> stack.type != Material.AIR && stack.amount > 0 }
            .forEach { stack ->
                val existing =
                    aggregated.firstOrNull { expectation ->
                        itemStackMatcher.isSimilar(expectation.template, stack)
                    }
                if (existing == null) {
                    aggregated += DecoratedPotDropExpectation(stack.clone().apply { amount = 1 }, stack.amount)
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

    private fun DecoratedPotDropSource.position(): DecoratedPotPosition = DecoratedPotPosition(worldId, x, y, z)

    private fun DecoratedPotItemSpawn.position(): DecoratedPotPosition = DecoratedPotPosition(worldId, x, y, z)
}

private data class DecoratedPotPosition(
    val worldId: UUID,
    val x: Int,
    val y: Int,
    val z: Int,
)

private data class DecoratedPotDropContext(
    val id: Long,
    val ownerUuid: UUID,
    val expectedDrops: MutableList<DecoratedPotDropExpectation>,
)

private data class DecoratedPotDropExpectation(
    val template: ItemStack,
    var remainingAmount: Int,
) {
    fun canClaim(
        itemStack: ItemStack,
        matcher: DecoratedPotItemStackMatcher,
    ): Boolean =
        itemStack.amount > 0 &&
            itemStack.amount <= remainingAmount &&
            matcher.isSimilar(template, itemStack)
}
