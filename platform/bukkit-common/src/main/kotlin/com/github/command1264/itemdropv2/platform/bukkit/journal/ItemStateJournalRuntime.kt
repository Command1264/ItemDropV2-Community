package com.github.command1264.itemdropv2.platform.bukkit.journal

import com.github.command1264.itemdropv2.core.ItemStateDurabilityOutcome
import com.github.command1264.itemdropv2.core.ItemStateDurabilityPort
import com.github.command1264.itemdropv2.core.ItemStateJournalIdentity
import com.github.command1264.itemdropv2.core.ItemStateJournalRecord
import com.github.command1264.itemdropv2.core.ItemStateJournalRecordType
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

public class ItemStateJournalRuntime private constructor(
    private val worlds: Map<UUID, WorldJournal>,
    private val failureState: JournalRuntimeFailureState,
) : ItemStateJournalRuntimeAccess {
    override fun stage(record: ItemStateJournalRecord): ItemStateDurabilityOutcome =
        failureState.reason?.let { ItemStateDurabilityOutcome.Failed("Degraded:$it") }
            ?: worlds[record.identity.worldUuid]?.stage(record)
            ?: ItemStateDurabilityOutcome.Rejected("UnknownWorld")

    override fun get(identity: ItemStateJournalIdentity): ItemStateJournalRecord? = worlds[identity.worldUuid]?.get(identity)

    override fun refreshPresentation(record: ItemStateJournalRecord): ItemStateDurabilityOutcome =
        failureState.reason?.let { ItemStateDurabilityOutcome.Failed("Degraded:$it") }
            ?: worlds[record.identity.worldUuid]?.refreshPresentation(record)
            ?: ItemStateDurabilityOutcome.Rejected("UnknownWorld")

    override fun degrade(reason: String) {
        failureState.fail(reason, notify = false)
    }

    @Suppress("ReturnCount")
    override fun discard(identity: ItemStateJournalIdentity): ItemStateDurabilityOutcome {
        val existing = get(identity) ?: return ItemStateDurabilityOutcome.Accepted(coalesced = true)
        if (existing.type == ItemStateJournalRecordType.TOMBSTONE) {
            return ItemStateDurabilityOutcome.Accepted(coalesced = true)
        }
        if (existing.revision == Long.MAX_VALUE) return ItemStateDurabilityOutcome.Rejected("RevisionExhausted")
        return stage(existing.asTombstone(existing.revision + 1))
    }

    public fun shutdown(): ItemStateJournalRuntimeShutdownResult {
        val failures =
            worlds.entries.mapNotNull { (worldUuid, journal) ->
                journal.shutdown()?.let { failure -> "$worldUuid:$failure" }
            }
        return if (failures.isEmpty()) {
            ItemStateJournalRuntimeShutdownResult.Closed
        } else {
            ItemStateJournalRuntimeShutdownResult.Failed(failures)
        }
    }

    public companion object {
        private const val DEFAULT_QUEUE_CAPACITY = 10_000
        public const val MAXIMUM_RECORDS_PER_WORLD: Int = SqliteItemStateJournalStore.MAXIMUM_RECORDS_PER_WORLD

        public fun open(
            root: Path,
            worldUuids: Set<UUID>,
            queueCapacity: Int = DEFAULT_QUEUE_CAPACITY,
            maximumRecordsPerWorld: Int = MAXIMUM_RECORDS_PER_WORLD,
            failureSink: (String) -> Unit = {},
        ): ItemStateJournalRuntimeOpenResult {
            val normalizedRoot = root.toAbsolutePath().normalize()
            return try {
                Files.createDirectories(normalizedRoot)
                val opened = linkedMapOf<UUID, WorldJournal>()
                val failureState = JournalRuntimeFailureState(failureSink)
                for (worldUuid in worldUuids.sortedBy(UUID::toString)) {
                    when (
                        val result =
                            WorldJournal.open(
                                normalizedRoot.resolve(worldUuid.toString()),
                                worldUuid,
                                queueCapacity,
                                maximumRecordsPerWorld,
                                failureSink = { reason -> failureState.fail("Writer:$worldUuid:$reason", notify = true) },
                            )
                    ) {
                        is WorldJournalOpenResult.Opened -> opened[worldUuid] = result.journal
                        is WorldJournalOpenResult.Failed -> {
                            opened.values.forEach(WorldJournal::shutdown)
                            return ItemStateJournalRuntimeOpenResult.Failed(worldUuid, result.reason)
                        }
                    }
                }
                ItemStateJournalRuntimeOpenResult.Opened(ItemStateJournalRuntime(opened.toMap(), failureState))
            } catch (error: IOException) {
                ItemStateJournalRuntimeOpenResult.Failed(null, error.javaClass.simpleName)
            } catch (error: SecurityException) {
                ItemStateJournalRuntimeOpenResult.Failed(null, error.javaClass.simpleName)
            }
        }
    }
}

public interface ItemStateJournalRuntimeAccess : ItemStateDurabilityPort {
    public operator fun get(identity: ItemStateJournalIdentity): ItemStateJournalRecord?

    public fun refreshPresentation(record: ItemStateJournalRecord): ItemStateDurabilityOutcome

    public fun discard(identity: ItemStateJournalIdentity): ItemStateDurabilityOutcome =
        ItemStateDurabilityOutcome.Accepted(coalesced = true)

    public fun degrade(reason: String)
}

private class WorldJournal(
    private val index: ItemStateJournalIndex,
    private val writer: BoundedItemStateJournalWriter,
    private val store: SqliteItemStateJournalStore,
    private val maximumRecords: Int,
    private var activeRecordCount: Int,
) {
    @Synchronized
    @Suppress("ReturnCount")
    fun stage(record: ItemStateJournalRecord): ItemStateDurabilityOutcome {
        val existing = index[record.identity]
        if (existing != null) {
            when {
                record.revision < existing.revision -> return ItemStateDurabilityOutcome.Rejected("StaleRevision")
                record.revision == existing.revision && record != existing ->
                    return ItemStateDurabilityOutcome.Rejected("RevisionConflict")
                record == existing -> return ItemStateDurabilityOutcome.Accepted(coalesced = true)
            }
        }
        val createsRecord = createsRecord(record, existing)
        if (createsRecord && activeRecordCount >= maximumRecords) {
            return ItemStateDurabilityOutcome.Rejected("RecordCapacityExceeded:$maximumRecords")
        }
        return when (val staged = writer.stage(record)) {
            is ItemStateDurabilityOutcome.Accepted -> {
                check(index.apply(record) !is JournalIndexUpdate.Conflict)
                updateActiveRecordCount(existing, record, createsRecord)
                staged
            }
            else -> staged
        }
    }

    private fun createsRecord(
        record: ItemStateJournalRecord,
        existing: ItemStateJournalRecord?,
    ): Boolean =
        record.type == ItemStateJournalRecordType.UPSERT &&
            (existing == null || existing.type == ItemStateJournalRecordType.TOMBSTONE)

    private fun updateActiveRecordCount(
        existing: ItemStateJournalRecord?,
        record: ItemStateJournalRecord,
        createsRecord: Boolean,
    ) {
        if (createsRecord) {
            activeRecordCount++
        } else if (existing?.type == ItemStateJournalRecordType.UPSERT && record.type == ItemStateJournalRecordType.TOMBSTONE) {
            activeRecordCount--
        }
    }

    @Synchronized
    operator fun get(identity: ItemStateJournalIdentity): ItemStateJournalRecord? = index[identity]

    @Synchronized
    fun refreshPresentation(record: ItemStateJournalRecord): ItemStateDurabilityOutcome {
        val existing = index[record.identity] ?: return ItemStateDurabilityOutcome.Rejected("MissingStateRevision")
        return if (!record.isPresentationRefreshOf(existing)) {
            ItemStateDurabilityOutcome.Rejected("PresentationStateMismatch")
        } else {
            when (val staged = writer.refreshPresentation(record)) {
                is ItemStateDurabilityOutcome.Accepted -> {
                    check(index.apply(record) !is JournalIndexUpdate.Conflict)
                    staged
                }
                else -> staged
            }
        }
    }

    fun shutdown(): String? {
        val stopped = writer.shutdown(SHUTDOWN_TIMEOUT_MILLIS)
        if (stopped !is JournalShutdownResult.Stopped || stopped.flushResult != JournalFlushResult.Flushed) {
            return "WriterShutdown:$stopped"
        }
        return try {
            store.close()
            null
        } catch (error: java.sql.SQLException) {
            "DatabaseClose:${error.javaClass.simpleName}"
        }
    }

    companion object {
        private const val SHUTDOWN_TIMEOUT_MILLIS = 5_000L

        @Suppress("ReturnCount")
        fun open(
            directory: Path,
            worldUuid: UUID,
            queueCapacity: Int,
            maximumRecords: Int,
            failureSink: (String) -> Unit,
        ): WorldJournalOpenResult {
            val index = ItemStateJournalIndex()
            val store =
                when (
                    val opened =
                        SqliteItemStateJournalStore.openWithLegacyMigration(
                            directory,
                            worldUuid,
                            maximumRecords,
                        )
                ) {
                    is SqliteJournalOpenResult.Opened -> opened.store
                    is SqliteJournalOpenResult.Failed -> return WorldJournalOpenResult.Failed("Sqlite:${opened.reason}")
                }
            var revisionConflict = false
            val readResult =
                store.readRecords { record ->
                    if (index.apply(record) is JournalIndexUpdate.Conflict) revisionConflict = true
                }
            if (readResult is SqliteJournalReadResult.Failed) {
                store.close()
                return WorldJournalOpenResult.Failed("Sqlite:${readResult.reason}")
            }
            if (revisionConflict) {
                store.close()
                return WorldJournalOpenResult.Failed("RevisionConflict")
            }
            val loadedCount = (readResult as SqliteJournalReadResult.Loaded).recordCount
            return WorldJournalOpenResult.Opened(
                WorldJournal(
                    index,
                    BoundedItemStateJournalWriter(queueCapacity, store, failureSink),
                    store,
                    maximumRecords,
                    loadedCount,
                ),
            )
        }
    }
}

private class JournalRuntimeFailureState(
    private val externalFailureSink: (String) -> Unit,
) {
    @Volatile
    var reason: String? = null
        private set

    fun fail(
        reason: String,
        notify: Boolean,
    ) {
        val bounded = reason.take(MAXIMUM_REASON_LENGTH)
        val firstFailure =
            synchronized(this) {
                if (this.reason == null) {
                    this.reason = bounded
                    true
                } else {
                    false
                }
            }
        if (firstFailure && notify) externalFailureSink(bounded)
    }

    private companion object {
        private const val MAXIMUM_REASON_LENGTH = 200
    }
}

private sealed interface WorldJournalOpenResult {
    data class Opened(
        val journal: WorldJournal,
    ) : WorldJournalOpenResult

    data class Failed(
        val reason: String,
    ) : WorldJournalOpenResult
}

public sealed interface ItemStateJournalRuntimeOpenResult {
    public data class Opened(
        public val runtime: ItemStateJournalRuntime,
    ) : ItemStateJournalRuntimeOpenResult

    public data class Failed(
        public val worldUuid: UUID?,
        public val reason: String,
    ) : ItemStateJournalRuntimeOpenResult
}

public sealed interface ItemStateJournalRuntimeShutdownResult {
    public data object Closed : ItemStateJournalRuntimeShutdownResult

    public data class Failed(
        public val failures: List<String>,
    ) : ItemStateJournalRuntimeShutdownResult
}
