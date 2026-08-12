package com.github.command1264.itemdropv2.platform.bukkit.journal

import com.github.command1264.itemdropv2.core.ItemStateDurabilityOutcome
import com.github.command1264.itemdropv2.core.ItemStateJournalChunk
import com.github.command1264.itemdropv2.core.ItemStateJournalIdentity
import com.github.command1264.itemdropv2.core.ItemStateJournalRecordType
import org.bukkit.Server
import org.bukkit.entity.Item

/**
 * Removes durable rows only after an item observed in this server session is absent twice while its last chunk is loaded.
 */
public class BukkitItemStateJournalCleanupController(
    private val runtime: ItemStateJournalRuntime,
    private val failureSink: (String) -> Unit = {},
) {
    private val tracked = linkedMapOf<ItemStateJournalIdentity, TrackedItem>()

    public fun observe(item: Item) {
        val identity = ItemStateJournalIdentity(item.world.uid, item.uniqueId)
        tracked[identity] =
            TrackedItem(
                ItemStateJournalChunk(item.location.blockX shr CHUNK_COORDINATE_SHIFT, item.location.blockZ shr CHUNK_COORDINATE_SHIFT),
                INITIAL_MISSING_CHECKS,
            )
    }

    public fun process(server: Server): ItemStateJournalCleanupResult {
        var inspected = 0
        var removed = 0
        val identities = tracked.keys.take(MAXIMUM_ITEMS_PER_RUN)
        val candidates = identities.toSet()
        val loadedItems =
            server.worlds
                .asSequence()
                .flatMap { it.loadedChunks.asSequence() }
                .flatMap { it.entities.asSequence() }
                .filterIsInstance<Item>()
                .filter { ItemStateJournalIdentity(it.world.uid, it.uniqueId) in candidates }
                .associateBy { ItemStateJournalIdentity(it.world.uid, it.uniqueId) }
        identities.forEach { identity ->
            val trackedItem = tracked.remove(identity) ?: return@forEach
            inspected++
            val record = runtime[identity]
            if (record == null || record.type == ItemStateJournalRecordType.TOMBSTONE) return@forEach
            val world = server.getWorld(identity.worldUuid)
            val entity = loadedItems[identity]
            if (entity is Item && entity.isValid) {
                observe(entity)
                return@forEach
            }
            if (world == null || !world.isChunkLoaded(trackedItem.chunk.x, trackedItem.chunk.z)) {
                tracked[identity] = trackedItem.copy(missingLoadedChunkChecks = 0)
                return@forEach
            }
            if (trackedItem.missingLoadedChunkChecks < REQUIRED_MISSING_CHECKS - 1) {
                tracked[identity] = trackedItem.copy(missingLoadedChunkChecks = trackedItem.missingLoadedChunkChecks + 1)
                return@forEach
            }
            when (val discarded = runtime.discard(identity)) {
                is ItemStateDurabilityOutcome.Accepted -> removed++
                is ItemStateDurabilityOutcome.Rejected -> tracked[identity] = trackedItem
                is ItemStateDurabilityOutcome.Failed -> {
                    tracked[identity] = trackedItem
                    failureSink("${identity.entityUuid}:${discarded.errorType}")
                }
            }
        }
        return ItemStateJournalCleanupResult(inspected, removed, tracked.size)
    }

    private data class TrackedItem(
        val chunk: ItemStateJournalChunk,
        val missingLoadedChunkChecks: Int,
    )

    private companion object {
        private const val MAXIMUM_ITEMS_PER_RUN = 128
        private const val REQUIRED_MISSING_CHECKS = 2
        private const val CHUNK_COORDINATE_SHIFT = 4
        private const val INITIAL_MISSING_CHECKS = 0
    }
}

public data class ItemStateJournalCleanupResult(
    public val inspected: Int,
    public val removed: Int,
    public val remainingTracked: Int,
)
