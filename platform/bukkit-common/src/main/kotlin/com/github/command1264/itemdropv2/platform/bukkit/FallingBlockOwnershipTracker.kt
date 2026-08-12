package com.github.command1264.itemdropv2.platform.bukkit

import java.util.UUID

internal data class FallingBlockSource(
    val worldId: UUID,
    val x: Int,
    val y: Int,
    val z: Int,
    val materialName: String,
    val ownerUuid: UUID,
)

internal data class FallingBlockSpawn(
    val entityId: UUID,
    val worldId: UUID,
    val x: Int,
    val y: Int,
    val z: Int,
    val materialName: String,
)

internal class FallingBlockOwnershipTracker(
    private val maxSources: Int = 8_192,
    private val maxFallingEntities: Int = 8_192,
) {
    private data class SourceKey(
        val worldId: UUID,
        val x: Int,
        val y: Int,
        val z: Int,
        val materialName: String,
    )

    private data class PendingSource(
        val contextId: Long,
        val ownerUuid: UUID,
    )

    private val sources = linkedMapOf<SourceKey, PendingSource>()
    private val fallingOwners = linkedMapOf<UUID, UUID>()
    private var nextContextId = 1L

    init {
        require(maxSources > 0) { "maximum falling block source count must be positive" }
        require(maxFallingEntities > 0) { "maximum falling entity count must be positive" }
    }

    fun record(candidates: List<FallingBlockSource>): Long {
        require(candidates.isNotEmpty()) { "falling block source candidates must not be empty" }
        val contextId = nextContextId++
        candidates.forEach { candidate ->
            sources[candidate.toKey()] = PendingSource(contextId, candidate.ownerUuid)
            trimOldest(sources, maxSources)
        }
        return contextId
    }

    fun bind(spawn: FallingBlockSpawn): UUID? {
        val ownerUuid = claimSource(spawn) ?: return null
        fallingOwners[spawn.entityId] = ownerUuid
        trimOldest(fallingOwners, maxFallingEntities)
        return ownerUuid
    }

    fun claimSource(spawn: FallingBlockSpawn): UUID? = sources.remove(spawn.toKey())?.ownerUuid

    fun ownerOf(entityId: UUID): UUID? = fallingOwners[entityId]

    fun complete(entityId: UUID): Boolean = fallingOwners.remove(entityId) != null

    fun expire(contextId: Long): Boolean {
        var removed = false
        val iterator = sources.iterator()
        while (iterator.hasNext()) {
            if (iterator.next().value.contextId == contextId) {
                iterator.remove()
                removed = true
            }
        }
        return removed
    }

    fun clear() {
        sources.clear()
        fallingOwners.clear()
    }

    private fun FallingBlockSource.toKey(): SourceKey = SourceKey(worldId, x, y, z, materialName)

    private fun FallingBlockSpawn.toKey(): SourceKey = SourceKey(worldId, x, y, z, materialName)

    private fun <K, V> trimOldest(
        values: LinkedHashMap<K, V>,
        maximumSize: Int,
    ) {
        while (values.size > maximumSize) values.remove(values.keys.first())
    }
}
