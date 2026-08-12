package com.github.command1264.itemdropv2.platform.bukkit

import java.util.UUID

internal data class SugarCaneCollapseContext(
    val worldId: UUID,
    val x: Int,
    val z: Int,
    val minY: Int,
    val maxY: Int,
    val ownerUuid: UUID,
) {
    init {
        require(minY <= maxY) { "minimum sugar cane height must not exceed maximum height" }
    }
}

internal data class SugarCaneItemSpawn(
    val entityId: UUID,
    val worldId: UUID,
    val x: Int,
    val z: Int,
    val y: Int,
)

internal data class SugarCaneClaim(
    val ownerUuid: UUID,
)

internal class SugarCaneCollapseTracker(
    private val maxContexts: Int = 1_024,
    private val maxClaimedEntities: Int = 8_192,
) {
    private data class ActiveContext(
        val context: SugarCaneCollapseContext,
        val pendingYs: MutableSet<Int>,
    )

    private val contexts = linkedMapOf<Long, ActiveContext>()
    private val claimedEntityContexts = linkedMapOf<UUID, Long>()
    private var nextId = 1L

    init {
        require(maxContexts > 0) { "maximum context count must be positive" }
        require(maxClaimedEntities > 0) { "maximum claimed entity count must be positive" }
    }

    fun record(context: SugarCaneCollapseContext): Long {
        removePositionsClaimedByNewerBreak(context)
        while (contexts.size >= maxContexts) {
            removeContext(contexts.keys.first())
        }
        val id = nextId++
        contexts[id] = ActiveContext(context, (context.minY..context.maxY).toMutableSet())
        return id
    }

    fun claim(spawn: SugarCaneItemSpawn): SugarCaneClaim? {
        val match =
            if (spawn.entityId in claimedEntityContexts) {
                null
            } else {
                contexts.entries.lastOrNull { (_, active) ->
                    val context = active.context
                    context.worldId == spawn.worldId &&
                        context.x == spawn.x &&
                        context.z == spawn.z &&
                        spawn.y in active.pendingYs
                }
            }
        return match?.let { entry ->
            claimedEntityContexts[spawn.entityId] = entry.key
            while (claimedEntityContexts.size > maxClaimedEntities) {
                claimedEntityContexts.remove(claimedEntityContexts.keys.first())
            }
            entry.value.pendingYs.remove(spawn.y)
            val claim = SugarCaneClaim(entry.value.context.ownerUuid)
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

    private fun removePositionsClaimedByNewerBreak(context: SugarCaneCollapseContext) {
        val iterator = contexts.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            val active = entry.value
            val existing = active.context
            if (existing.worldId != context.worldId || existing.x != context.x || existing.z != context.z) continue
            active.pendingYs.removeAll(context.minY..context.maxY)
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
