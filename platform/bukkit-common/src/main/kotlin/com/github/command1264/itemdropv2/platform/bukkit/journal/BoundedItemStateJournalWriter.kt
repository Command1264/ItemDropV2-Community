package com.github.command1264.itemdropv2.platform.bukkit.journal

import com.github.command1264.itemdropv2.core.ItemStateDurabilityOutcome
import com.github.command1264.itemdropv2.core.ItemStateDurabilityPort
import com.github.command1264.itemdropv2.core.ItemStateJournalIdentity
import com.github.command1264.itemdropv2.core.ItemStateJournalRecord
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

@Suppress("TooManyFunctions")
public class BoundedItemStateJournalWriter(
    private val capacity: Int,
    private val sink: JournalRecordSink,
    private val failureSink: (String) -> Unit = {},
) : ItemStateDurabilityPort,
    AutoCloseable {
    private val lock = Any()
    private val flushLock = Any()
    private val pending = linkedMapOf<ItemStateJournalIdentity, ItemStateJournalRecord>()
    private var closing = false
    private var closed = false
    private var failureType: String? = null

    init {
        require(capacity in 1..MAXIMUM_QUEUE_CAPACITY) { "journal queue capacity is outside the supported range" }
    }

    private val commands = ArrayBlockingQueue<WriterCommand>(capacity + CONTROL_QUEUE_SLOTS)

    private val worker =
        Thread(::runWorker, THREAD_NAME).apply {
            isDaemon = true
            start()
        }

    override fun stage(record: ItemStateJournalRecord): ItemStateDurabilityOutcome = stage(record, presentationRefresh = false)

    public fun refreshPresentation(record: ItemStateJournalRecord): ItemStateDurabilityOutcome = stage(record, presentationRefresh = true)

    private fun stage(
        record: ItemStateJournalRecord,
        presentationRefresh: Boolean,
    ): ItemStateDurabilityOutcome =
        synchronized(lock) {
            failureType?.let { return ItemStateDurabilityOutcome.Failed(it) }
            if (closing || closed) return ItemStateDurabilityOutcome.Rejected("JournalClosed")
            val existing = pending[record.identity]
            if (existing != null) {
                return when {
                    record.revision < existing.revision -> ItemStateDurabilityOutcome.Rejected("StaleRevision")
                    record.revision == existing.revision &&
                        record != existing &&
                        (!presentationRefresh || !record.isPresentationRefreshOf(existing)) ->
                        ItemStateDurabilityOutcome.Rejected("RevisionConflict")
                    record.revision == existing.revision -> {
                        if (record != existing) pending[record.identity] = record
                        ItemStateDurabilityOutcome.Accepted(coalesced = true)
                    }
                    else -> {
                        pending[record.identity] = record
                        ItemStateDurabilityOutcome.Accepted(coalesced = true)
                    }
                }
            }
            if (pending.size >= capacity) {
                failureType = "QueueCapacityExceeded"
                failureSink("QueueCapacityExceeded")
                return ItemStateDurabilityOutcome.Rejected("QueueCapacityExceeded")
            }
            pending[record.identity] = record
            commands.add(WriterCommand.Record(record.identity))
            ItemStateDurabilityOutcome.Accepted(coalesced = false)
        }

    public fun flush(timeoutMillis: Long): JournalFlushResult = synchronized(flushLock) { flushLocked(timeoutMillis) }

    private fun flushLocked(timeoutMillis: Long): JournalFlushResult {
        require(timeoutMillis > 0) { "journal flush timeout must be positive" }
        val completion = CompletableFuture<JournalFlushResult>()
        val unavailable =
            synchronized(lock) {
                failureType?.let { JournalFlushResult.Failed(it) }
                    ?: if (closed) JournalFlushResult.Failed("JournalClosed") else null
            }
        if (unavailable != null) return unavailable
        synchronized(lock) {
            commands.add(WriterCommand.Flush(completion))
        }
        return try {
            completion.get(timeoutMillis, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            JournalFlushResult.TimedOut
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            JournalFlushResult.Failed(error.javaClass.simpleName)
        } catch (error: ExecutionException) {
            JournalFlushResult.Failed(error.cause?.javaClass?.simpleName ?: error.javaClass.simpleName)
        }
    }

    override fun close() {
        shutdown(CLOSE_TIMEOUT_MILLIS)
    }

    public fun shutdown(timeoutMillis: Long): JournalShutdownResult {
        require(timeoutMillis > 0) { "journal shutdown timeout must be positive" }
        synchronized(lock) {
            if (closing || closed) return JournalShutdownResult.AlreadyStopped
            closing = true
        }
        val flushResult = flush(timeoutMillis)
        synchronized(lock) {
            closed = true
            closing = false
            commands.add(WriterCommand.Stop)
        }
        return try {
            worker.join(timeoutMillis)
            if (worker.isAlive) {
                worker.interrupt()
                JournalShutdownResult.TimedOut(flushResult)
            } else {
                JournalShutdownResult.Stopped(flushResult)
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            worker.interrupt()
            JournalShutdownResult.Interrupted(flushResult)
        }
    }

    private fun runWorker() {
        try {
            var deferred: WriterCommand? = null
            while (true) {
                val command = deferred ?: commands.take()
                when (command) {
                    is WriterCommand.Record -> {
                        val identities = mutableListOf(command.identity)
                        deferred = drainRecordCommands(identities)
                        processBatch(identities)
                    }
                    is WriterCommand.Flush -> command.completion.complete(flushSink())
                    WriterCommand.Stop -> return
                }
                if (command !is WriterCommand.Record) deferred = null
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    @Suppress("ReturnCount")
    private fun drainRecordCommands(identities: MutableList<ItemStateJournalIdentity>): WriterCommand? {
        while (identities.size < MAXIMUM_BATCH_SIZE) {
            when (val next = commands.poll() ?: return null) {
                is WriterCommand.Record -> identities += next.identity
                else -> return next
            }
        }
        return null
    }

    @Suppress("TooGenericExceptionCaught")
    private fun processBatch(identities: List<ItemStateJournalIdentity>) {
        val records =
            synchronized(lock) {
                identities.mapNotNull { identity ->
                    val pendingRecord = pending.remove(identity)
                    if (failureType == null) pendingRecord else null
                }
            }
        if (records.isEmpty()) return
        val result =
            try {
                sink.appendBatch(records)
            } catch (error: InterruptedException) {
                Thread.currentThread().interrupt()
                JournalWriteResult.Failed(error.javaClass.simpleName)
            } catch (error: Exception) {
                JournalWriteResult.Failed(error.javaClass.simpleName)
            }
        if (result is JournalWriteResult.Failed) recordFailure(result.errorType)
    }

    @Suppress("TooGenericExceptionCaught")
    private fun flushSink(): JournalFlushResult {
        synchronized(lock) {
            failureType?.let { return JournalFlushResult.Failed(it) }
        }
        val result =
            try {
                sink.flush()
            } catch (error: InterruptedException) {
                Thread.currentThread().interrupt()
                JournalWriteResult.Failed(error.javaClass.simpleName)
            } catch (error: Exception) {
                JournalWriteResult.Failed(error.javaClass.simpleName)
            }
        return when (result) {
            JournalWriteResult.Written -> JournalFlushResult.Flushed
            is JournalWriteResult.Failed -> {
                recordFailure(result.errorType)
                JournalFlushResult.Failed(result.errorType)
            }
        }
    }

    private fun recordFailure(reason: String) {
        val firstFailure =
            synchronized(lock) {
                if (failureType == null) {
                    failureType = reason
                    true
                } else {
                    false
                }
            }
        if (firstFailure) failureSink(reason)
    }

    private sealed interface WriterCommand {
        data class Record(
            val identity: ItemStateJournalIdentity,
        ) : WriterCommand

        data class Flush(
            val completion: CompletableFuture<JournalFlushResult>,
        ) : WriterCommand

        data object Stop : WriterCommand
    }

    private companion object {
        private const val THREAD_NAME = "ItemDropV2-Journal"
        private const val CLOSE_TIMEOUT_MILLIS = 5_000L
        private const val CONTROL_QUEUE_SLOTS = 1
        private const val MAXIMUM_BATCH_SIZE = 256
        private const val MAXIMUM_QUEUE_CAPACITY = 1_000_000
    }
}

public sealed interface JournalFlushResult {
    public data object Flushed : JournalFlushResult

    public data object TimedOut : JournalFlushResult

    public data class Failed(
        public val errorType: String,
    ) : JournalFlushResult
}

public sealed interface JournalShutdownResult {
    public data class Stopped(
        public val flushResult: JournalFlushResult,
    ) : JournalShutdownResult

    public data class TimedOut(
        public val flushResult: JournalFlushResult,
    ) : JournalShutdownResult

    public data class Interrupted(
        public val flushResult: JournalFlushResult,
    ) : JournalShutdownResult

    public data object AlreadyStopped : JournalShutdownResult
}
