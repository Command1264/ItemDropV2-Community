package com.github.command1264.itemdropv2.platform.bukkit.journal

import com.github.command1264.itemdropv2.core.ItemStateDurabilityOutcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.UUID

class ItemStateJournalRuntimeTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `persists accepted records and rebuilds the per-world index after restart`() {
        val record = sampleJournalUpsert(revision = 3)
        val opened = ItemStateJournalRuntime.open(directory, setOf(record.identity.worldUuid))
        val runtime = assertInstanceOf(ItemStateJournalRuntimeOpenResult.Opened::class.java, opened).runtime

        assertEquals(ItemStateDurabilityOutcome.Accepted(false), runtime.stage(record))
        assertEquals(record, runtime[record.identity])
        assertInstanceOf(ItemStateJournalRuntimeShutdownResult.Closed::class.java, runtime.shutdown())

        val reopened =
            assertInstanceOf(
                ItemStateJournalRuntimeOpenResult.Opened::class.java,
                ItemStateJournalRuntime.open(directory, setOf(record.identity.worldUuid)),
            ).runtime
        assertEquals(record, reopened[record.identity])
        reopened.shutdown()
    }

    @Test
    fun `rejects unknown worlds stale revisions and equal revision conflicts`() {
        val record = sampleJournalUpsert(revision = 3)
        val runtime =
            assertInstanceOf(
                ItemStateJournalRuntimeOpenResult.Opened::class.java,
                ItemStateJournalRuntime.open(directory, setOf(record.identity.worldUuid)),
            ).runtime

        assertEquals(ItemStateDurabilityOutcome.Accepted(false), runtime.stage(record))
        assertEquals(ItemStateDurabilityOutcome.Rejected("StaleRevision"), runtime.stage(sampleJournalUpsert(2)))
        assertEquals(
            ItemStateDurabilityOutcome.Rejected("RevisionConflict"),
            runtime.stage(record.asTombstone(record.revision)),
        )
        val unknownWorld =
            com.github.command1264.itemdropv2.core.ItemStateJournalRecord.upsert(
                identity = record.identity.copy(worldUuid = UUID.fromString("00000000-0000-0000-0000-000000000599")),
                revision = 1,
                chunk = record.chunk,
                sessionId = record.sessionId,
                fingerprint = record.fingerprint,
                state = requireNotNull(record.state),
                presentation = requireNotNull(record.presentation),
            )
        assertEquals(ItemStateDurabilityOutcome.Rejected("UnknownWorld"), runtime.stage(unknownWorld))
        runtime.degrade("test-failure")
        assertEquals(
            ItemStateDurabilityOutcome.Failed("Degraded:test-failure"),
            runtime.stage(sampleJournalUpsert(4)),
        )
        runtime.shutdown()
    }

    @Test
    fun `accepts same revision presentation refresh but rejects state mutation`() {
        val record = sampleJournalUpsert(revision = 3)
        val runtime =
            assertInstanceOf(
                ItemStateJournalRuntimeOpenResult.Opened::class.java,
                ItemStateJournalRuntime.open(directory, setOf(record.identity.worldUuid)),
            ).runtime
        assertEquals(ItemStateDurabilityOutcome.Accepted(false), runtime.stage(record))
        val refreshed =
            record.withPresentation(
                requireNotNull(record.presentation).copy(managedName = "Diamond Sword x8192 [00:01]"),
            )

        assertInstanceOf(ItemStateDurabilityOutcome.Accepted::class.java, runtime.refreshPresentation(refreshed))
        assertEquals(refreshed, runtime[record.identity])
        assertEquals(
            ItemStateDurabilityOutcome.Rejected("PresentationStateMismatch"),
            runtime.refreshPresentation(
                com.github.command1264.itemdropv2.core.ItemStateJournalRecord.upsert(
                    refreshed.identity,
                    refreshed.revision,
                    refreshed.chunk,
                    refreshed.sessionId,
                    refreshed.fingerprint,
                    requireNotNull(refreshed.state).copy(elapsedLifetimeSeconds = 4),
                    requireNotNull(refreshed.presentation),
                ),
            ),
        )
        assertInstanceOf(ItemStateJournalRuntimeShutdownResult.Closed::class.java, runtime.shutdown())

        val reopened =
            assertInstanceOf(
                ItemStateJournalRuntimeOpenResult.Opened::class.java,
                ItemStateJournalRuntime.open(directory, setOf(record.identity.worldUuid)),
            ).runtime
        assertEquals(refreshed, reopened[record.identity])
        reopened.shutdown()
    }

    @Test
    fun `asynchronously discards an existing durable row with its next revision`() {
        val record = sampleJournalUpsert(revision = 3)
        val runtime =
            assertInstanceOf(
                ItemStateJournalRuntimeOpenResult.Opened::class.java,
                ItemStateJournalRuntime.open(directory, setOf(record.identity.worldUuid)),
            ).runtime
        assertEquals(ItemStateDurabilityOutcome.Accepted(false), runtime.stage(record))

        assertInstanceOf(ItemStateDurabilityOutcome.Accepted::class.java, runtime.discard(record.identity))
        assertInstanceOf(ItemStateJournalRuntimeShutdownResult.Closed::class.java, runtime.shutdown())

        val reopened =
            assertInstanceOf(
                ItemStateJournalRuntimeOpenResult.Opened::class.java,
                ItemStateJournalRuntime.open(directory, setOf(record.identity.worldUuid)),
            ).runtime
        assertEquals(null, reopened[record.identity])
        reopened.shutdown()
    }

    @Test
    fun `discarding a missing durable row is idempotent`() {
        val identity = sampleJournalUpsert().identity
        val runtime =
            assertInstanceOf(
                ItemStateJournalRuntimeOpenResult.Opened::class.java,
                ItemStateJournalRuntime.open(directory, setOf(identity.worldUuid)),
            ).runtime

        assertEquals(ItemStateDurabilityOutcome.Accepted(true), runtime.discard(identity))

        assertInstanceOf(ItemStateJournalRuntimeShutdownResult.Closed::class.java, runtime.shutdown())
    }

    @Test
    fun `repeated reload cycles close every journal worker and preserve the latest revision`() {
        val record = sampleJournalUpsert(revision = 1)
        repeat(10) { cycle ->
            val runtime =
                assertInstanceOf(
                    ItemStateJournalRuntimeOpenResult.Opened::class.java,
                    ItemStateJournalRuntime.open(directory, setOf(record.identity.worldUuid)),
                ).runtime
            assertInstanceOf(
                ItemStateDurabilityOutcome.Accepted::class.java,
                runtime.stage(sampleJournalUpsert(revision = cycle + 1L)),
            )
            assertInstanceOf(ItemStateJournalRuntimeShutdownResult.Closed::class.java, runtime.shutdown())
        }

        val journalThreads = Thread.getAllStackTraces().keys.filter { it.isAlive && it.name == "ItemDropV2-Journal" }
        assertEquals(emptyList<Thread>(), journalThreads)
        val reopened =
            assertInstanceOf(
                ItemStateJournalRuntimeOpenResult.Opened::class.java,
                ItemStateJournalRuntime.open(directory, setOf(record.identity.worldUuid)),
            ).runtime
        assertEquals(10, reopened[record.identity]?.revision)
        reopened.shutdown()
    }
}
