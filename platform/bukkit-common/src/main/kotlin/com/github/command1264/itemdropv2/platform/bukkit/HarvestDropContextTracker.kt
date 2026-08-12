package com.github.command1264.itemdropv2.platform.bukkit

import java.util.UUID

internal data class HarvestDropSource(
    val worldId: UUID,
    val x: Int,
    val y: Int,
    val z: Int,
    val ownerUuid: UUID,
    val dropMaterialName: String,
    val maximumAmount: Int,
) {
    init {
        require(dropMaterialName.isNotBlank()) { "harvest drop material must not be blank" }
        require(maximumAmount > 0) { "harvest maximum amount must be positive" }
    }
}

internal data class HarvestItemSpawn(
    val worldId: UUID,
    val x: Int,
    val y: Int,
    val z: Int,
    val materialName: String,
    val amount: Int,
)

internal class HarvestDropContextTracker(
    private val maximumContexts: Int = 1_024,
) {
    private val contexts = linkedMapOf<HarvestPosition, HarvestDropContext>()
    private var nextContextId = 1L

    init {
        require(maximumContexts > 0) { "maximum harvest context count must be positive" }
    }

    fun record(source: HarvestDropSource): Long {
        val id = nextContextId++
        val position = source.position()
        contexts[position] = HarvestDropContext(id, source, source.maximumAmount)
        while (contexts.size > maximumContexts) contexts.remove(contexts.keys.first())
        return id
    }

    @Suppress("ReturnCount")
    fun claim(spawn: HarvestItemSpawn): UUID? {
        if (spawn.amount <= 0) return null
        val position = spawn.position()
        val context = contexts[position] ?: return null
        if (spawn.materialName != context.source.dropMaterialName || spawn.amount > context.remainingAmount) return null
        context.remainingAmount -= spawn.amount
        if (context.remainingAmount == 0) contexts.remove(position)
        return context.source.ownerUuid
    }

    fun expire(contextId: Long) {
        val position = contexts.entries.firstOrNull { (_, context) -> context.id == contextId }?.key ?: return
        contexts.remove(position)
    }

    fun clear() {
        contexts.clear()
    }

    private fun HarvestDropSource.position(): HarvestPosition = HarvestPosition(worldId, x, y, z)

    private fun HarvestItemSpawn.position(): HarvestPosition = HarvestPosition(worldId, x, y, z)
}

private data class HarvestPosition(
    val worldId: UUID,
    val x: Int,
    val y: Int,
    val z: Int,
)

private data class HarvestDropContext(
    val id: Long,
    val source: HarvestDropSource,
    var remainingAmount: Int,
)

internal fun isHarvestableBlock(
    materialName: String,
    blockData: String,
): Boolean =
    when (materialName) {
        "SWEET_BERRY_BUSH" -> parseBlockDataProperty(blockData, "age")?.toIntOrNull()?.let { age -> age >= 2 } == true
        "CAVE_VINES", "CAVE_VINES_PLANT" -> parseBlockDataProperty(blockData, "berries") == "true"
        else -> false
    }

internal fun harvestDropMaterialName(materialName: String): String? =
    when (materialName) {
        "SWEET_BERRY_BUSH" -> "SWEET_BERRIES"
        "CAVE_VINES", "CAVE_VINES_PLANT" -> "GLOW_BERRIES"
        else -> null
    }

private fun parseBlockDataProperty(
    blockData: String,
    propertyName: String,
): String? =
    blockData
        .substringAfter('[', missingDelimiterValue = "")
        .substringBeforeLast(']', missingDelimiterValue = "")
        .split(',')
        .asSequence()
        .map { property -> property.split('=', limit = 2) }
        .firstOrNull { parts -> parts.size == 2 && parts[0].trim() == propertyName }
        ?.get(1)
        ?.trim()
