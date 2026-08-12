package com.github.command1264.itemdropv2.platform.bukkit

import java.util.UUID

internal data class VerticalPlantCollapseContext(
    val worldId: UUID,
    val x: Int,
    val z: Int,
    val ownerUuid: UUID,
    val expectedItemNamesByY: Map<Int, Set<String>>,
) {
    init {
        require(expectedItemNamesByY.isNotEmpty()) {
            "vertical plant context must contain at least one position"
        }
        require(expectedItemNamesByY.values.all { names -> names.isNotEmpty() && names.none(String::isBlank) }) {
            "expected vertical plant item names must not be empty"
        }
    }
}

internal data class VerticalPlantItemSpawn(
    val entityId: UUID,
    val worldId: UUID,
    val x: Int,
    val z: Int,
    val y: Int,
    val materialName: String,
)

internal data class VerticalPlantClaim(
    val ownerUuid: UUID,
)

internal class VerticalPlantCollapseTracker(
    private val maxContexts: Int = 1_024,
    private val maxClaimedEntities: Int = 8_192,
) {
    private data class ActiveContext(
        val context: VerticalPlantCollapseContext,
        val pendingYs: MutableSet<Int>,
    )

    private val contexts = linkedMapOf<Long, ActiveContext>()
    private val claimedEntityContexts = linkedMapOf<UUID, Long>()
    private var nextId = 1L

    init {
        require(maxContexts > 0) { "maximum context count must be positive" }
        require(maxClaimedEntities > 0) { "maximum claimed entity count must be positive" }
    }

    fun record(context: VerticalPlantCollapseContext): Long {
        removePositionsClaimedByNewerBreak(context)
        while (contexts.size >= maxContexts) {
            removeContext(contexts.keys.first())
        }
        val id = nextId++
        contexts[id] = ActiveContext(context, context.expectedItemNamesByY.keys.toMutableSet())
        return id
    }

    fun claim(spawn: VerticalPlantItemSpawn): VerticalPlantClaim? {
        if (spawn.entityId in claimedEntityContexts) return null
        val match =
            contexts.entries.lastOrNull { (_, active) ->
                val context = active.context
                context.worldId == spawn.worldId &&
                    context.x == spawn.x &&
                    context.z == spawn.z &&
                    spawn.y in active.pendingYs &&
                    spawn.materialName in context.expectedItemNamesByY.getValue(spawn.y)
            }
        return match?.let { entry ->
            claimedEntityContexts[spawn.entityId] = entry.key
            while (claimedEntityContexts.size > maxClaimedEntities) {
                claimedEntityContexts.remove(claimedEntityContexts.keys.first())
            }
            entry.value.pendingYs.remove(spawn.y)
            val claim = VerticalPlantClaim(entry.value.context.ownerUuid)
            claim
        }
    }

    fun wasClaimed(entityId: UUID): Boolean = entityId in claimedEntityContexts

    fun isMergeProtected(entityId: UUID): Boolean = claimedEntityContexts[entityId]?.let(contexts::containsKey) == true

    fun expire(contextId: Long): Boolean = removeContext(contextId)

    fun clear() {
        contexts.clear()
        claimedEntityContexts.clear()
    }

    private fun removePositionsClaimedByNewerBreak(context: VerticalPlantCollapseContext) {
        val iterator = contexts.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            val active = entry.value
            val existing = active.context
            if (existing.worldId != context.worldId || existing.x != context.x || existing.z != context.z) continue
            active.pendingYs.removeAll(context.expectedItemNamesByY.keys)
            if (active.pendingYs.isEmpty()) {
                iterator.remove()
                claimedEntityContexts.entries.removeIf { (_, contextId) -> contextId == entry.key }
            }
        }
    }

    private fun removeContext(contextId: Long): Boolean {
        val removed = contexts.remove(contextId) != null
        if (removed) {
            claimedEntityContexts.entries.removeIf { (_, claimedContextId) -> claimedContextId == contextId }
        }
        return removed
    }
}
