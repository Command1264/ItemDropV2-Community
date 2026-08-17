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
    private val loadedChunkRecoveryScheduler =
        LoadedChunkRecoveryScheduler(runtime, taskExecutor, ::recoverChunkItems, ::degrade)

    @EventHandler(priority = EventPriority.LOWEST)
    public fun onChunkLoad(event: ChunkLoadEvent) {
        val items = event.chunk.entities.filterIsInstance<Item>()
        recoverChunkItems(items)
        loadedChunkRecoveryScheduler.scheduleEventRetry(
            event.chunk,
            items.associateByTo(mutableMapOf(), Item::getUniqueId),
        )
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
            .forEach { chunk ->
                val items = chunk.entities.filterIsInstance<Item>()
                items.forEach { item ->
                    observedItemSink(item)
                    inspected++
                    when (val result = recover(item)) {
                        ItemStateEntityRecoveryResult.Restored -> restored++
                        is ItemStateEntityRecoveryResult.Failed -> failures += "${item.uniqueId}:${result.reason}"
                        else -> Unit
                    }
                }
                loadedChunkRecoveryScheduler.scheduleStartupRetry(
                    chunk,
                    items.associateByTo(mutableMapOf(), Item::getUniqueId),
                )
            }
        failures.forEach(::degrade)
        return ItemStateRecoveryBatchResult(inspected, restored, failures)
    }

    /** Closes the activation handoff when old servers publish a loaded chunk after the initial scan. */
    public fun scheduleLoadedChunkDiscoveryRetries(server: Server) {
        loadedChunkRecoveryScheduler.scheduleDiscovery(server)
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

    /** Prevents startup lifetime registration from publishing defaults over a durable journal record. */
    @Suppress("TooGenericExceptionCaught")
    public fun prepareForLifetimeRegistration(item: Item): Boolean {
        val result =
            try {
                recover(item)
            } catch (error: RuntimeException) {
                ItemStateEntityRecoveryResult.Failed("StartupRecovery:${error.javaClass.simpleName}")
            }
        return if (result is ItemStateEntityRecoveryResult.Failed) {
            degrade("${item.uniqueId}:${result.reason}")
            false
        } else {
            true
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

    private fun degrade(reason: String) {
        runtime.degrade(reason)
        failureSink(reason)
    }
}

private data class LoadedChunkIdentity(
    val worldUuid: java.util.UUID,
    val x: Int,
    val z: Int,
)

private class LoadedChunkRecoveryScheduler(
    private val runtime: ItemStateJournalRuntimeAccess,
    private val taskExecutor: MainThreadTaskExecutor,
    private val recoverItems: (List<Item>) -> Unit,
    private val degrade: (String) -> Unit,
) {
    private val retryStates = linkedMapOf<LoadedChunkIdentity, LoadedChunkRecoveryState>()
    private val retryQueue = java.util.ArrayDeque<LoadedChunkIdentity>()
    private val discoveredChunks = linkedSetOf<LoadedChunkIdentity>()
    private var recoveryTick = 0L
    private var recoveryTaskScheduled = false
    private var capacityFailureReported = false
    private var discoveryServer: Server? = null
    private var discoveryWorldIndex = 0
    private var discoveryChunkIndex = 0
    private var discoverySnapshotWorld: org.bukkit.World? = null
    private var discoveryChunkSnapshot = emptyArray<org.bukkit.Chunk>()
    private val startupDeadlineTick = RECOVERY_ATTEMPTS.toLong()

    fun scheduleDiscovery(server: Server) {
        discoveryServer = server
        scheduleRecoveryTask()
    }

    fun scheduleEventRetry(
        chunk: org.bukkit.Chunk,
        observedItems: MutableMap<java.util.UUID, Item>,
    ) {
        enqueue(chunk, observedItems, EVENT_RECOVERY_ATTEMPTS)
    }

    fun scheduleStartupRetry(
        chunk: org.bukkit.Chunk,
        observedItems: MutableMap<java.util.UUID, Item>,
    ) {
        val identity = chunk.toLoadedChunkIdentity()
        if (!canTrackDiscoveredChunk(identity)) return
        enqueue(chunk, observedItems, startupDeadlineTick)
    }

    private fun enqueue(
        chunk: org.bukkit.Chunk,
        observedItems: MutableMap<java.util.UUID, Item>,
        lifetimeTicks: Int,
    ) {
        enqueue(chunk, observedItems, recoveryTick + lifetimeTicks)
    }

    private fun enqueue(
        chunk: org.bukkit.Chunk,
        observedItems: MutableMap<java.util.UUID, Item>,
        expiresAtTick: Long,
    ) {
        val identity = chunk.toLoadedChunkIdentity()
        val existing = retryStates[identity]
        if (existing != null) {
            existing.chunk = chunk
            existing.observedItems.putAll(observedItems)
            existing.expiresAtTick = maxOf(existing.expiresAtTick, expiresAtTick)
            return
        }
        if (retryStates.size >= MAXIMUM_ACTIVE_CHUNKS) {
            reportCapacityFailure()
            return
        }
        retryStates[identity] =
            LoadedChunkRecoveryState(
                chunk = chunk,
                observedItems = observedItems,
                expiresAtTick = expiresAtTick,
                nextRevalidationTick = recoveryTick + 1,
            )
        retryQueue.addLast(identity)
        scheduleRecoveryTask()
    }

    @Suppress("TooGenericExceptionCaught")
    private fun scheduleRecoveryTask() {
        if (recoveryTaskScheduled) return
        recoveryTaskScheduled = true
        try {
            taskExecutor.execute {
                recoveryTaskScheduled = false
                try {
                    processRecoveryQueue()
                } catch (error: RuntimeException) {
                    degrade("ChunkRecovery:${error.javaClass.simpleName}")
                }
            }
        } catch (error: RuntimeException) {
            recoveryTaskScheduled = false
            degrade("ChunkRecoveryScheduling:${error.javaClass.simpleName}")
        }
    }

    private fun processRecoveryQueue() {
        recoveryTick++
        var remainingChunkBudget = MAXIMUM_CHUNKS_PER_TICK
        remainingChunkBudget -= processDiscovery(minOf(remainingChunkBudget, MAXIMUM_DISCOVERY_CHUNKS_PER_TICK))
        repeat(minOf(remainingChunkBudget, retryQueue.size)) {
            val identity = retryQueue.removeFirst()
            val state = retryStates[identity] ?: return@repeat
            if (recoveryTick > state.expiresAtTick) {
                retryStates.remove(identity)
                return@repeat
            }
            if (!state.chunk.isLoaded) {
                retryQueue.addLast(identity)
                return@repeat
            }
            val revalidateDurableItems = recoveryTick >= state.nextRevalidationTick
            val items =
                state.chunk.entities
                    .filterIsInstance<Item>()
                    .filter { item ->
                        val previous = state.observedItems.put(item.uniqueId, item)
                        previous == null ||
                            previous !== item ||
                            revalidateDurableItems &&
                            runtime[ItemStateJournalIdentity(item.world.uid, item.uniqueId)] != null
                    }
            recoverItems(items)
            if (revalidateDurableItems) {
                state.nextRevalidationTick = recoveryTick + DURABLE_ITEM_REVALIDATION_INTERVAL
            }
            retryQueue.addLast(identity)
        }
        if (retryQueue.isNotEmpty() || discoveryServer != null) scheduleRecoveryTask()
    }

    @Suppress("ReturnCount")
    private fun processDiscovery(chunkBudget: Int): Int {
        val server = discoveryServer ?: return 0
        if (recoveryTick > startupDeadlineTick) {
            stopDiscovery()
            return 0
        }
        val worlds = server.worlds
        if (worlds.isEmpty()) return 0
        if (discoveryWorldIndex >= worlds.size) discoveryWorldIndex = 0
        var processed = 0
        var worldsWithoutChunk = 0
        while (processed < chunkBudget && worldsWithoutChunk < worlds.size) {
            val world = worlds[discoveryWorldIndex]
            if (discoverySnapshotWorld !== world) {
                val snapshot = world.loadedChunks
                if (snapshot.size > MAXIMUM_ACTIVE_CHUNKS) {
                    reportCapacityFailure()
                    stopDiscovery()
                    return processed
                }
                discoverySnapshotWorld = world
                discoveryChunkSnapshot = snapshot
                discoveryChunkIndex = 0
            }
            if (discoveryChunkIndex >= discoveryChunkSnapshot.size) {
                discoveryWorldIndex = (discoveryWorldIndex + 1) % worlds.size
                discoveryChunkIndex = 0
                discoverySnapshotWorld = null
                discoveryChunkSnapshot = emptyArray()
                worldsWithoutChunk++
                continue
            }
            val chunk = discoveryChunkSnapshot[discoveryChunkIndex++]
            val identity = chunk.toLoadedChunkIdentity()
            if (canTrackDiscoveredChunk(identity)) enqueue(chunk, mutableMapOf(), startupDeadlineTick)
            processed++
        }
        return processed
    }

    private fun stopDiscovery() {
        discoveryServer = null
        discoverySnapshotWorld = null
        discoveryChunkSnapshot = emptyArray()
        discoveredChunks.clear()
    }

    @Suppress("ReturnCount")
    private fun canTrackDiscoveredChunk(identity: LoadedChunkIdentity): Boolean {
        if (identity in discoveredChunks) return true
        if (discoveredChunks.size >= MAXIMUM_ACTIVE_CHUNKS) {
            reportCapacityFailure()
            return false
        }
        discoveredChunks += identity
        return true
    }

    private fun reportCapacityFailure() {
        if (capacityFailureReported) return
        capacityFailureReported = true
        degrade("ChunkRecoveryCapacityExceeded:$MAXIMUM_ACTIVE_CHUNKS")
    }

    private companion object {
        private const val DURABLE_ITEM_REVALIDATION_INTERVAL = 200
        private const val EVENT_RECOVERY_ATTEMPTS = 100
        private const val MAXIMUM_ACTIVE_CHUNKS = SqliteItemStateJournalStore.MAXIMUM_RECORDS_PER_WORLD
        private const val MAXIMUM_CHUNKS_PER_TICK = 128
        private const val MAXIMUM_DISCOVERY_CHUNKS_PER_TICK = MAXIMUM_CHUNKS_PER_TICK / 2
        private const val RECOVERY_ATTEMPTS = 1_200
    }
}

private fun org.bukkit.Chunk.toLoadedChunkIdentity(): LoadedChunkIdentity = LoadedChunkIdentity(world.uid, x, z)

private data class LoadedChunkRecoveryState(
    var chunk: org.bukkit.Chunk,
    val observedItems: MutableMap<java.util.UUID, Item>,
    var expiresAtTick: Long,
    var nextRevalidationTick: Long,
)

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
