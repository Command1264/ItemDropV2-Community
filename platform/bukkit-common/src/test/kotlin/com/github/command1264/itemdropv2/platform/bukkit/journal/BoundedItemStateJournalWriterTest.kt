package com.github.command1264.itemdropv2.platform.bukkit.journal

import com.github.command1264.itemdropv2.core.ItemStateDurabilityOutcome
import com.github.command1264.itemdropv2.core.ItemStateJournalPresentation
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class BoundedItemStateJournalWriterTest {
    @Test
    fun `coalesces pending revisions and flushes through a bounded worker`() {
        val sink = BlockingJournalRecordSink()
        BoundedItemStateJournalWriter(capacity = 2, sink = sink).use { writer ->
            assertInstanceOf(ItemStateDurabilityOutcome.Accepted::class.java, writer.stage(sampleJournalUpsert(1)))
            sink.awaitFirstAppend()
            assertInstanceOf(ItemStateDurabilityOutcome.Accepted::class.java, writer.stage(sampleJournalUpsert(2)))
            val coalesced =
                assertInstanceOf(ItemStateDurabilityOutcome.Accepted::class.java, writer.stage(sampleJournalUpsert(3)))
            assertEquals(true, coalesced.coalesced)
            sink.release()

            assertInstanceOf(JournalFlushResult.Flushed::class.java, writer.flush(5_000))
            val shutdown = assertInstanceOf(JournalShutdownResult.Stopped::class.java, writer.shutdown(5_000))
            assertInstanceOf(JournalFlushResult.Flushed::class.java, shutdown.flushResult)
        }

        assertEquals(listOf(1L, 3L), sink.records.map { it.revision })
    }

    @Test
    fun `rejects new identities when the bounded queue is saturated`() {
        val sink = BlockingJournalRecordSink()
        BoundedItemStateJournalWriter(capacity = 1, sink = sink).use { writer ->
            writer.stage(sampleJournalUpsert(1))
            sink.awaitFirstAppend()
            writer.stage(sampleJournalUpsert(1, UUID.fromString("00000000-0000-0000-0000-000000000601")))

            val rejected =
                writer.stage(sampleJournalUpsert(1, UUID.fromString("00000000-0000-0000-0000-000000000602")))

            assertInstanceOf(ItemStateDurabilityOutcome.Rejected::class.java, rejected)
            sink.release()
        }
    }

    @Test
    fun `coalesces a safe same revision presentation refresh into the pending record`() {
        val sink = BlockingJournalRecordSink()
        BoundedItemStateJournalWriter(capacity = 2, sink = sink).use { writer ->
            writer.stage(sampleJournalUpsert(1))
            sink.awaitFirstAppend()
            val pending = sampleJournalUpsert(2)
            writer.stage(pending)
            val refreshed =
                pending.withPresentation(ItemStateJournalPresentation("current-name", true, "original", false))

            assertEquals(ItemStateDurabilityOutcome.Accepted(true), writer.refreshPresentation(refreshed))
            sink.release()
            assertInstanceOf(JournalFlushResult.Flushed::class.java, writer.flush(5_000))
        }

        assertEquals(listOf("Diamond Sword x8192", "current-name"), sink.records.map { it.presentation?.managedName })
    }

    @Test
    fun `reports the first asynchronous sink failure and rejects later mutations`() {
        val reported = AtomicReference<String?>()
        val sink =
            object : JournalRecordSink {
                override fun append(record: com.github.command1264.itemdropv2.core.ItemStateJournalRecord): JournalWriteResult =
                    JournalWriteResult.Failed("DiskFull")

                override fun flush(): JournalWriteResult = JournalWriteResult.Written
            }
        BoundedItemStateJournalWriter(capacity = 2, sink = sink, failureSink = reported::set).use { writer ->
            assertInstanceOf(ItemStateDurabilityOutcome.Accepted::class.java, writer.stage(sampleJournalUpsert(1)))
            assertInstanceOf(JournalFlushResult.Failed::class.java, writer.flush(5_000))
            assertEquals("DiskFull", reported.get())
            assertEquals(ItemStateDurabilityOutcome.Failed("DiskFull"), writer.stage(sampleJournalUpsert(2)))
        }
    }

    @Test
    fun `queue saturation permanently reports degraded state`() {
        val sink = BlockingJournalRecordSink()
        val reported = AtomicReference<String?>()
        BoundedItemStateJournalWriter(capacity = 1, sink = sink, failureSink = reported::set).use { writer ->
            writer.stage(sampleJournalUpsert(1))
            sink.awaitFirstAppend()
            writer.stage(sampleJournalUpsert(1, UUID.fromString("00000000-0000-0000-0000-000000000611")))

            assertInstanceOf(
                ItemStateDurabilityOutcome.Rejected::class.java,
                writer.stage(sampleJournalUpsert(1, UUID.fromString("00000000-0000-0000-0000-000000000612"))),
            )
            assertEquals("QueueCapacityExceeded", reported.get())
            assertEquals(
                ItemStateDurabilityOutcome.Failed("QueueCapacityExceeded"),
                writer.stage(sampleJournalUpsert(2)),
            )
            sink.release()
        }
    }

    @Test
    fun `rapid shutdown is bounded when a write is blocked`() {
        val sink = BlockingJournalRecordSink()
        val writer = BoundedItemStateJournalWriter(capacity = 1, sink = sink)
        writer.stage(sampleJournalUpsert(1))
        sink.awaitFirstAppend()

        val result = writer.shutdown(25)

        assertInstanceOf(JournalShutdownResult.TimedOut::class.java, result)
        sink.release()
    }
}

private class BlockingJournalRecordSink : JournalRecordSink {
    val records = mutableListOf<com.github.command1264.itemdropv2.core.ItemStateJournalRecord>()
    private val firstAppend = CountDownLatch(1)
    private val release = CountDownLatch(1)

    override fun append(record: com.github.command1264.itemdropv2.core.ItemStateJournalRecord): JournalWriteResult {
        firstAppend.countDown()
        check(release.await(5, TimeUnit.SECONDS))
        synchronized(records) { records += record }
        return JournalWriteResult.Written
    }

    override fun flush(): JournalWriteResult = JournalWriteResult.Written

    fun awaitFirstAppend() {
        check(firstAppend.await(5, TimeUnit.SECONDS))
    }

    fun release() {
        release.countDown()
    }
}
