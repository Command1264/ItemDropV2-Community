package com.github.command1264.itemdropv2.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.util.UUID

class CompatibilityTransactionTest {
    @Test
    fun `batch preserves the complete transfer contract`() {
        val batch = batch()

        assertEquals(BATCH_ID, batch.batchId)
        assertEquals(SOURCE_ID, batch.sourceEntityUuid)
        assertEquals(128, batch.sourceBeforeAmount)
        assertEquals(64, batch.sourceAfterAmount)
        assertEquals(64, batch.transferAmount)
        assertEquals(listOf(CompatibilityOutput(0, 32), CompatibilityOutput(1, 32)), batch.outputs)
    }

    @Test
    fun `batch snapshots output records instead of retaining caller mutation`() {
        val mutableOutputs = mutableListOf(CompatibilityOutput(0, 32), CompatibilityOutput(1, 32))
        val batch = batch(outputs = mutableOutputs)

        mutableOutputs.clear()

        assertEquals(listOf(CompatibilityOutput(0, 32), CompatibilityOutput(1, 32)), batch.outputs)
    }

    @Test
    fun `batch rejects nil identities nonpositive revision and timestamp`() {
        assertThrows(IllegalArgumentException::class.java) { batch(batchId = UUID(0, 0)) }
        assertThrows(IllegalArgumentException::class.java) { batch(sourceEntityUuid = UUID(0, 0)) }
        assertThrows(IllegalArgumentException::class.java) { batch(sourceRevision = 0) }
        assertThrows(IllegalArgumentException::class.java) { batch(createdAtEpochSecond = 0) }
        assertThrows(IllegalArgumentException::class.java) { batch(changedAtEpochSecond = 0) }
    }

    @Test
    fun `batch requires conserved positive amounts`() {
        assertThrows(IllegalArgumentException::class.java) {
            batch(sourceBeforeAmount = 128, sourceAfterAmount = 96, transferAmount = 31)
        }
        assertThrows(IllegalArgumentException::class.java) {
            batch(sourceBeforeAmount = 128, sourceAfterAmount = 129, transferAmount = 1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            batch(outputs = listOf(CompatibilityOutput(0, 63)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            CompatibilityOutput(markerIndex = 0, amount = 0)
        }
    }

    @Test
    fun `batch rejects duplicate output indices and unsafe sink identity`() {
        assertThrows(IllegalArgumentException::class.java) {
            batch(outputs = listOf(CompatibilityOutput(0, 32), CompatibilityOutput(0, 32)))
        }
        assertThrows(IllegalArgumentException::class.java) { batch(sinkIdentity = "world\nsecret") }
        assertThrows(IllegalArgumentException::class.java) { batch(sinkIdentity = "") }
    }

    @Test
    fun `batch only follows forward durable transitions`() {
        val prepared = batch()
        val sourceApplied = prepared.transitionTo(CompatibilityBatchState.SOURCE_APPLIED, 2)
        val committed = sourceApplied.transitionTo(CompatibilityBatchState.COMMITTED, 3)

        assertEquals(CompatibilityBatchState.SOURCE_APPLIED, sourceApplied.state)
        assertEquals(CompatibilityBatchState.COMMITTED, committed.state)
        assertThrows(IllegalArgumentException::class.java) {
            prepared.transitionTo(CompatibilityBatchState.COMMITTED, 2)
        }
        assertThrows(IllegalArgumentException::class.java) {
            committed.transitionTo(CompatibilityBatchState.QUARANTINED, 4, "late-conflict")
        }
    }

    @Test
    fun `quarantine requires bounded reason and terminal state is immutable`() {
        val quarantined = batch().transitionTo(CompatibilityBatchState.QUARANTINED, 2, "PartialOutputs")

        assertEquals("PartialOutputs", quarantined.quarantineReason)
        assertThrows(IllegalArgumentException::class.java) {
            batch().transitionTo(CompatibilityBatchState.QUARANTINED, 2)
        }
        assertThrows(IllegalArgumentException::class.java) {
            batch().transitionTo(CompatibilityBatchState.QUARANTINED, 2, "x".repeat(257))
        }
        assertThrows(IllegalArgumentException::class.java) {
            quarantined.transitionTo(CompatibilityBatchState.COMMITTED, 3)
        }
    }

    @Test
    fun `transition timestamp must advance`() {
        assertThrows(IllegalArgumentException::class.java) {
            batch().transitionTo(CompatibilityBatchState.SOURCE_APPLIED, 1)
        }
    }

    private fun batch(
        batchId: UUID = BATCH_ID,
        sourceEntityUuid: UUID = SOURCE_ID,
        sourceRevision: Long = 7,
        sourceBeforeAmount: Long = 128,
        sourceAfterAmount: Long = 64,
        transferAmount: Long = 64,
        sinkIdentity: String = "world:00000000-0000-0000-0000-000000000003:0:64:0",
        outputs: List<CompatibilityOutput> = listOf(CompatibilityOutput(0, 32), CompatibilityOutput(1, 32)),
        createdAtEpochSecond: Long = 1,
        changedAtEpochSecond: Long = createdAtEpochSecond,
    ): CompatibilityBatch =
        CompatibilityBatch.create(
            batchId = batchId,
            sourceEntityUuid = sourceEntityUuid,
            sourceRevision = sourceRevision,
            sourceBeforeAmount = sourceBeforeAmount,
            sourceAfterAmount = sourceAfterAmount,
            transferAmount = transferAmount,
            sinkKind = CompatibilitySinkKind.HOPPER_INVENTORY,
            sinkIdentity = sinkIdentity,
            outputs = outputs,
            state = CompatibilityBatchState.PREPARED,
            quarantineReason = null,
            createdAtEpochSecond = createdAtEpochSecond,
            changedAtEpochSecond = changedAtEpochSecond,
        )

    private companion object {
        val BATCH_ID: UUID = UUID.fromString("00000000-0000-0000-0000-000000000001")
        val SOURCE_ID: UUID = UUID.fromString("00000000-0000-0000-0000-000000000002")
    }
}
