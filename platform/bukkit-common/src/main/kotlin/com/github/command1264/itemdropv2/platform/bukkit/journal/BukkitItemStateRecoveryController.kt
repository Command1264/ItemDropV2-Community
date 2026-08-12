package com.github.command1264.itemdropv2.platform.bukkit.journal

import com.github.command1264.itemdropv2.core.ItemStateDurabilityOutcome
import com.github.command1264.itemdropv2.core.ItemStateJournalIdentity
import com.github.command1264.itemdropv2.core.ItemStateRecoveryDecision
import com.github.command1264.itemdropv2.core.ItemStateRecoveryService
import com.github.command1264.itemdropv2.core.PresentationJournalSnapshotResult
import com.github.command1264.itemdropv2.core.RevisionedItemStateWriteResult
import com.github.command1264.itemdropv2.platform.bukkit.BukkitItemStateRepository
import com.github.command1264.itemdropv2.platform.bukkit.MainThreadTaskExecutor
import com.github.command1264.itemdropv2.platform.bukkit.TransientItemTargetLeaseFactory
import com.github.command1264.itemdropv2.platform.bukkit.withTransientItemTarget
import org.bukkit.Server
import org.bukkit.entity.Item
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.ItemSpawnEvent
import org.bukkit.event.world.ChunkLoadEvent

public class BukkitItemStateRecoveryController(
    private val repository: BukkitItemStateRepository,
    private val runtime: ItemStateJournalRuntimeAccess,
    private val entityPort: BukkitItemStateJournalEntityPort,
    private val taskExecutor: MainThreadTaskExecutor,
    private val service: ItemStateRecoveryService = ItemStateRecoveryService(),
    private val journalRecoveryAllowed: Boolean = true,
    private val failureSink: (String) -> Unit = {},
    private val transientTargetLeaseFactory: TransientItemTargetLeaseFactory =
        TransientItemTargetLeaseFactory(repository::leaseTransientTarget),
    private val observedItemSink: (Item) -> Unit = {},
) : Listener {
    @EventHandler(priority = EventPriority.LOWEST)
    public fun onChunkLoad(event: ChunkLoadEvent) {
        val items = event.chunk.entities.filterIsInstance<Item>()
        recoverChunkItems(items)
        if (items.isEmpty()) {
            scheduleLoadedChunkRetry(event.chunk, LOADED_CHUNK_ENTITY_VISIBILITY_ATTEMPTS)
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public fun onItemSpawn(event: ItemSpawnEvent) {
        observedItemSink(event.entity)
    }

    public fun recoverLoadedItems(server: Server): ItemStateRecoveryBatchResult {
        var inspected = 0
        var restored = 0
        val failures = mutableListOf<String>()
        server.worlds
            .asSequence()
            .flatMap { world -> world.loadedChunks.asSequence() }
            .flatMap { chunk -> chunk.entities.asSequence() }
            .filterIsInstance<Item>()
            .forEach { item ->
                observedItemSink(item)
                inspected++
                when (val result = recover(item)) {
                    ItemStateEntityRecoveryResult.Restored -> restored++
                    is ItemStateEntityRecoveryResult.Failed -> failures += "${item.uniqueId}:${result.reason}"
                    else -> Unit
                }
            }
        failures.forEach(::degrade)
        return ItemStateRecoveryBatchResult(inspected, restored, failures)
    }

    public fun recover(item: Item): ItemStateEntityRecoveryResult =
        withTransientItemTarget(item, transientTargetLeaseFactory) {
            val fingerprint =
                when (val result = entityPort.fingerprint(item)) {
                    is BukkitItemStateJournalFingerprintResult.Created -> result.fingerprint
                    is BukkitItemStateJournalFingerprintResult.Failed ->
                        return@withTransientItemTarget ItemStateEntityRecoveryResult.Failed(
                            "Fingerprint:${result.errorType}",
                        )
                }
            val identity = ItemStateJournalIdentity(item.world.uid, item.uniqueId)
            when (
                val decision =
                    service.decide(
                        repository.load(item.uniqueId),
                        runtime[identity],
                        fingerprint,
                        journalRecoveryAllowed,
                    )
            ) {
                ItemStateRecoveryDecision.NoAction -> ItemStateEntityRecoveryResult.Unchanged
                is ItemStateRecoveryDecision.AlreadySynchronized -> ItemStateEntityRecoveryResult.Unchanged
                is ItemStateRecoveryDecision.PublishPdc -> decision.publish(item)
                is ItemStateRecoveryDecision.Restore -> decision.restore(item)
                is ItemStateRecoveryDecision.Conflict -> ItemStateEntityRecoveryResult.Failed(decision.reason)
                is ItemStateRecoveryDecision.Failed -> ItemStateEntityRecoveryResult.Failed(decision.errorType)
            }
        }

    private fun ItemStateRecoveryDecision.PublishPdc.publish(item: Item): ItemStateEntityRecoveryResult =
        when (val result = entityPort.publish(item, state, revision)) {
            is ItemStateDurabilityOutcome.Accepted -> ItemStateEntityRecoveryResult.Published
            is ItemStateDurabilityOutcome.Rejected -> ItemStateEntityRecoveryResult.Failed("Journal:${result.reason}")
            is ItemStateDurabilityOutcome.Failed -> ItemStateEntityRecoveryResult.Failed("Journal:${result.errorType}")
        }

    @Suppress("ReturnCount")
    private fun ItemStateRecoveryDecision.Restore.restore(item: Item): ItemStateEntityRecoveryResult {
        val state = requireNotNull(record.state)
        when (val presentation = entityPort.restorePresentation(item, requireNotNull(record.presentation))) {
            PresentationJournalSnapshotResult.Applied -> Unit
            is PresentationJournalSnapshotResult.Rejected ->
                return ItemStateEntityRecoveryResult.Failed("Presentation:${presentation.reason}")
            is PresentationJournalSnapshotResult.Failed ->
                return ItemStateEntityRecoveryResult.Failed("Presentation:${presentation.errorType}")
            else -> return ItemStateEntityRecoveryResult.Failed("Presentation:UnexpectedResult")
        }
        return when (val restored = repository.restoreRevisioned(item, state, record.revision)) {
            is RevisionedItemStateWriteResult.Applied -> ItemStateEntityRecoveryResult.Restored
            RevisionedItemStateWriteResult.MissingTarget -> ItemStateEntityRecoveryResult.Failed("MissingTarget")
            is RevisionedItemStateWriteResult.Rejected -> ItemStateEntityRecoveryResult.Failed(restored.reason)
            is RevisionedItemStateWriteResult.Failed -> ItemStateEntityRecoveryResult.Failed(restored.errorType)
        }
    }

    private fun recoverOrDegrade(item: Item) {
        val result = recover(item)
        if (result is ItemStateEntityRecoveryResult.Failed) degrade("${item.uniqueId}:${result.reason}")
    }

    private fun recoverChunkItems(items: List<Item>) {
        items.forEach {
            observedItemSink(it)
            recoverOrDegrade(it)
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun scheduleLoadedChunkRetry(
        chunk: org.bukkit.Chunk,
        attemptsRemaining: Int,
    ) {
        try {
            taskExecutor.execute {
                try {
                    if (!chunk.isLoaded) return@execute
                    val items = chunk.entities.filterIsInstance<Item>()
                    recoverChunkItems(items)
                    if (items.isEmpty() && attemptsRemaining > 1) {
                        scheduleLoadedChunkRetry(chunk, attemptsRemaining - 1)
                    }
                } catch (error: RuntimeException) {
                    degrade("ChunkRecovery:${error.javaClass.simpleName}")
                }
            }
        } catch (error: RuntimeException) {
            degrade("ChunkRecoveryScheduling:${error.javaClass.simpleName}")
        }
    }

    private fun degrade(reason: String) {
        runtime.degrade(reason)
        failureSink(reason)
    }

    private companion object {
        private const val LOADED_CHUNK_ENTITY_VISIBILITY_ATTEMPTS = 100
    }
}

public sealed interface ItemStateEntityRecoveryResult {
    public data object Unchanged : ItemStateEntityRecoveryResult

    public data object Published : ItemStateEntityRecoveryResult

    public data object Restored : ItemStateEntityRecoveryResult

    public data class Failed(
        public val reason: String,
    ) : ItemStateEntityRecoveryResult
}

public data class ItemStateRecoveryBatchResult(
    public val inspected: Int,
    public val restored: Int,
    public val failures: List<String>,
)
