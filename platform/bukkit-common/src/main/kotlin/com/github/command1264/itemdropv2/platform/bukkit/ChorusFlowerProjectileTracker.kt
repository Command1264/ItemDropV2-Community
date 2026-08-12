package com.github.command1264.itemdropv2.platform.bukkit

import java.util.UUID

internal data class ChorusFlowerProjectileContext(
    val worldId: UUID,
    val x: Int,
    val y: Int,
    val z: Int,
    val ownerUuid: UUID,
    val materialName: String = "CHORUS_FLOWER",
)

internal data class ChorusFlowerItemSpawn(
    val worldId: UUID,
    val x: Int,
    val y: Int,
    val z: Int,
    val materialName: String = "CHORUS_FLOWER",
)

internal class ChorusFlowerProjectileTracker(
    private val maxContexts: Int = DEFAULT_MAX_CONTEXTS,
) {
    private data class Entry(
        val id: Long,
        val context: ChorusFlowerProjectileContext,
    )

    private val entries = ArrayDeque<Entry>()
    private var nextId = 0L

    init {
        require(maxContexts > 0) { "maxContexts must be positive" }
    }

    fun record(context: ChorusFlowerProjectileContext): Long {
        val id = ++nextId
        entries.removeAll { entry ->
            entry.context.worldId == context.worldId &&
                entry.context.x == context.x &&
                entry.context.y == context.y &&
                entry.context.z == context.z &&
                entry.context.materialName == context.materialName
        }
        entries.addLast(Entry(id, context))
        while (entries.size > maxContexts) entries.removeFirst()
        return id
    }

    fun claim(spawn: ChorusFlowerItemSpawn): UUID? {
        val match =
            entries.lastOrNull { entry ->
                entry.context.worldId == spawn.worldId &&
                    entry.context.x == spawn.x &&
                    entry.context.y == spawn.y &&
                    entry.context.z == spawn.z &&
                    entry.context.materialName == spawn.materialName
            } ?: return null
        entries.remove(match)
        return match.context.ownerUuid
    }

    fun expire(id: Long) {
        entries.removeAll { entry -> entry.id == id }
    }

    fun clear() {
        entries.clear()
    }

    private companion object {
        private const val DEFAULT_MAX_CONTEXTS = 1_024
    }
}
