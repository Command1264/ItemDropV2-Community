package com.github.command1264.itemdropv2.platform.bukkit

import java.util.UUID

internal data class BambooCollapseContext(
    val worldId: UUID,
    val x: Int,
    val z: Int,
    val minY: Int,
    val maxY: Int,
    val ownerUuid: UUID,
) {
    init {
        require(minY <= maxY) { "minimum bamboo height must not exceed maximum height" }
    }
}

internal data class BambooItemSpawn(
    val entityId: UUID,
    val worldId: UUID,
    val x: Int,
    val z: Int,
    val y: Int,
)

internal data class BambooClaim(
    val ownerUuid: UUID,
)

internal class BambooCollapseTracker(
    private val maxContexts: Int = 1_024,
    private val maxClaimedEntities: Int = 8_192,
) {
    private data class ActiveContext(
        val context: BambooCollapseContext,
        val pendingYs: MutableSet<Int>,
        var trackedMaxY: Int,
        var sourceOffsetY: Int? = null,
    )

    private data class MatchedContext(
        val contextId: Long,
        val active: ActiveContext,
        val sourceY: Int,
    )

    private val contexts = linkedMapOf<Long, ActiveContext>()
    private val claimedEntityContexts = linkedMapOf<UUID, Long>()
    private var nextId = 1L

    init {
        require(maxContexts > 0) { "maximum context count must be positive" }
        require(maxClaimedEntities > 0) { "maximum claimed entity count must be positive" }
    }

    fun record(context: BambooCollapseContext): Long {
        removePositionsClaimedByNewerBreak(context)
        while (contexts.size >= maxContexts) {
            removeContext(contexts.keys.first())
        }
        val id = nextId++
        contexts[id] = ActiveContext(context, (context.minY..context.maxY).toMutableSet(), context.maxY)
        return id
    }

    fun claim(spawn: BambooItemSpawn): BambooClaim? {
        val match =
            if (spawn.entityId in claimedEntityContexts) {
                null
            } else {
                findMatch(spawn)
            }
        return match?.let { matched ->
            claimedEntityContexts[spawn.entityId] = matched.contextId
            while (claimedEntityContexts.size > maxClaimedEntities) {
                claimedEntityContexts.remove(claimedEntityContexts.keys.first())
            }
            matched.active.pendingYs.remove(matched.sourceY)
            if (matched.active.sourceOffsetY == null) {
                matched.active.sourceOffsetY = matched.sourceY - spawn.y
            }
            if (matched.sourceY > matched.active.trackedMaxY) {
                matched.active.trackedMaxY = matched.sourceY
            }
            val claim = BambooClaim(matched.active.context.ownerUuid)
            claim
        }
    }

    private fun findMatch(spawn: BambooItemSpawn): MatchedContext? =
        contexts.entries
            .toList()
            .asReversed()
            .firstNotNullOfOrNull { (contextId, active) ->
                val context = active.context
                if (context.worldId != spawn.worldId || context.x != spawn.x || context.z != spawn.z) {
                    null
                } else {
                    sourceYCandidates(spawn.y, active)
                        .firstOrNull(active.pendingYs::contains)
                        ?.let { sourceY -> MatchedContext(contextId, active, sourceY) }
                        ?: sourceYCandidates(spawn.y, active)
                            .firstOrNull { sourceY ->
                                active.pendingYs.isEmpty() &&
                                    active.trackedMaxY < Int.MAX_VALUE &&
                                    sourceY == active.trackedMaxY + 1
                            }?.let { sourceY -> MatchedContext(contextId, active, sourceY) }
                }
            }

    fun wasClaimed(entityId: UUID): Boolean = entityId in claimedEntityContexts

    fun isMergeProtected(entityId: UUID): Boolean = claimedEntityContexts[entityId]?.let(contexts::containsKey) == true

    fun expire(contextId: Long): Boolean = removeContext(contextId)

    fun clear() {
        contexts.clear()
        claimedEntityContexts.clear()
    }

    private fun removePositionsClaimedByNewerBreak(context: BambooCollapseContext) {
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

    private fun sourceYCandidates(
        spawnY: Int,
        active: ActiveContext,
    ): List<Int> =
        active.sourceOffsetY?.let { offset -> listOf(spawnY + offset) }
            ?: if (spawnY == active.context.minY) listOf(spawnY) else listOf(spawnY - 1, spawnY)
}
