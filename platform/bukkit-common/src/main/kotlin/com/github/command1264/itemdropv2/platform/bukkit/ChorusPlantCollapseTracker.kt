package com.github.command1264.itemdropv2.platform.bukkit

import java.util.UUID

internal data class ChorusBlockPosition(
    val x: Int,
    val y: Int,
    val z: Int,
)

internal data class ChorusPlantCollapseContext(
    val worldId: UUID,
    val positions: Set<ChorusBlockPosition>,
    val ownerUuid: UUID,
) {
    init {
        require(positions.isNotEmpty()) { "chorus plant topology must not be empty" }
    }
}

internal data class ChorusFruitItemSpawn(
    val entityId: UUID,
    val worldId: UUID,
    val position: ChorusBlockPosition,
)

internal data class ChorusPlantClaim(
    val ownerUuid: UUID,
)

internal class ChorusPlantCollapseTracker(
    private val maxContexts: Int = 1_024,
    private val maxClaimedEntities: Int = 8_192,
) {
    private data class ActiveContext(
        val context: ChorusPlantCollapseContext,
        val pendingPositions: MutableSet<ChorusBlockPosition>,
    )

    private val contexts = linkedMapOf<Long, ActiveContext>()
    private val claimedEntities = mutableSetOf<UUID>()
    private var nextId = 1L

    init {
        require(maxContexts > 0) { "maximum context count must be positive" }
        require(maxClaimedEntities > 0) { "maximum claimed entity count must be positive" }
    }

    fun record(context: ChorusPlantCollapseContext): Long {
        removePositionsClaimedByNewerBreak(context)
        while (contexts.size >= maxContexts) {
            contexts.remove(contexts.keys.first())
        }
        val id = nextId++
        contexts[id] = ActiveContext(context, context.positions.toMutableSet())
        return id
    }

    fun claim(spawn: ChorusFruitItemSpawn): ChorusPlantClaim? {
        val match =
            if (spawn.entityId in claimedEntities) {
                null
            } else {
                contexts.entries.lastOrNull { (_, active) ->
                    active.context.worldId == spawn.worldId &&
                        spawn.position in active.pendingPositions
                }
            }
        return match?.let { entry ->
            claimedEntities += spawn.entityId
            while (claimedEntities.size > maxClaimedEntities) {
                claimedEntities.remove(claimedEntities.first())
            }
            entry.value.pendingPositions.remove(spawn.position)
            val claim = ChorusPlantClaim(entry.value.context.ownerUuid)
            if (entry.value.pendingPositions.isEmpty()) contexts.remove(entry.key)
            claim
        }
    }

    fun wasClaimed(entityId: UUID): Boolean = entityId in claimedEntities

    fun expire(contextId: Long): Boolean = contexts.remove(contextId) != null

    fun clear() {
        contexts.clear()
        claimedEntities.clear()
    }

    private fun removePositionsClaimedByNewerBreak(context: ChorusPlantCollapseContext) {
        val iterator = contexts.iterator()
        while (iterator.hasNext()) {
            val active = iterator.next().value
            if (active.context.worldId != context.worldId) continue
            active.pendingPositions.removeAll(context.positions)
            if (active.pendingPositions.isEmpty()) iterator.remove()
        }
    }
}
