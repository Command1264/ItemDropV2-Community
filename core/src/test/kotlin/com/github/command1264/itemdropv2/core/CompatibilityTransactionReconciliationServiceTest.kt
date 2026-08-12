package com.github.command1264.itemdropv2.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.util.UUID

class CompatibilityTransactionReconciliationServiceTest {
    private val service = CompatibilityTransactionReconciliationService()

    @ParameterizedTest
    @EnumSource(CompatibilitySinkKind::class)
    fun `prepared source with no outputs keeps source for every sink kind`(sinkKind: CompatibilitySinkKind) {
        val decision =
            service.reconcile(
                batch(sinkKind, CompatibilityBatchState.PREPARED),
                CompatibilityAuthoritySnapshot(
                    source = CompatibilitySourceObservation.MATCHES_BEFORE,
                    outputs = CompatibilityOutputObservation.NONE,
                ),
            )

        assertEquals(CompatibilityReconciliationDecision.KeepSource, decision)
    }

    @ParameterizedTest
    @EnumSource(CompatibilitySinkKind::class)
    fun `complete post-source snapshot commits for every sink kind`(sinkKind: CompatibilitySinkKind) {
        val decision =
            service.reconcile(
                batch(sinkKind, CompatibilityBatchState.SOURCE_APPLIED),
                CompatibilityAuthoritySnapshot(
                    source = CompatibilitySourceObservation.MATCHES_AFTER,
                    outputs = CompatibilityOutputObservation.COMPLETE,
                ),
            )

        assertEquals(CompatibilityReconciliationDecision.Commit, decision)
    }

    @ParameterizedTest
    @EnumSource(CompatibilitySinkKind::class)
    fun `unavailable source defers every sink kind without guessing`(sinkKind: CompatibilitySinkKind) {
        val decision =
            service.reconcile(
                batch(sinkKind, CompatibilityBatchState.SOURCE_APPLIED),
                CompatibilityAuthoritySnapshot(
                    source = CompatibilitySourceObservation.UNAVAILABLE,
                    outputs = CompatibilityOutputObservation.COMPLETE,
                ),
            )

        assertEquals(CompatibilityReconciliationDecision.Defer("SourceUnavailable"), decision)
    }

    @Test
    fun `unavailable output scope defers even when source matches post state`() {
        val decision =
            service.reconcile(
                batch(CompatibilitySinkKind.PLAYER_INVENTORY, CompatibilityBatchState.SOURCE_APPLIED),
                CompatibilityAuthoritySnapshot(
                    source = CompatibilitySourceObservation.MATCHES_AFTER,
                    outputs = CompatibilityOutputObservation.UNAVAILABLE,
                ),
            )

        assertEquals(CompatibilityReconciliationDecision.Defer("OutputsUnavailable"), decision)
    }

    @Test
    fun `partial outputs quarantine instead of accepting duplication risk`() {
        val decision =
            service.reconcile(
                batch(CompatibilitySinkKind.HOPPER_INVENTORY, CompatibilityBatchState.SOURCE_APPLIED),
                CompatibilityAuthoritySnapshot(
                    source = CompatibilitySourceObservation.MATCHES_AFTER,
                    outputs = CompatibilityOutputObservation.PARTIAL,
                ),
            )

        assertEquals(CompatibilityReconciliationDecision.Quarantine("PartialOutputs"), decision)
    }

    @Test
    fun `revision mismatch quarantines before considering output evidence`() {
        val decision =
            service.reconcile(
                batch(CompatibilitySinkKind.ITEM_ENTITY, CompatibilityBatchState.PREPARED),
                CompatibilityAuthoritySnapshot(
                    source = CompatibilitySourceObservation.REVISION_MISMATCH,
                    outputs = CompatibilityOutputObservation.NONE,
                ),
            )

        assertEquals(CompatibilityReconciliationDecision.Quarantine("SourceRevisionMismatch"), decision)
    }

    @Test
    fun `contradictory source and output evidence quarantines`() {
        val decision =
            service.reconcile(
                batch(CompatibilitySinkKind.HOPPER_MINECART_INVENTORY, CompatibilityBatchState.SOURCE_APPLIED),
                CompatibilityAuthoritySnapshot(
                    source = CompatibilitySourceObservation.MATCHES_BEFORE,
                    outputs = CompatibilityOutputObservation.COMPLETE,
                ),
            )

        assertEquals(CompatibilityReconciliationDecision.Quarantine("ContradictoryAuthority"), decision)
    }

    @Test
    fun `terminal states return idempotent no change decisions`() {
        assertEquals(
            CompatibilityReconciliationDecision.NoChange(CompatibilityBatchState.COMMITTED),
            service.reconcile(
                batch(CompatibilitySinkKind.ITEM_ENTITY, CompatibilityBatchState.COMMITTED),
                CompatibilityAuthoritySnapshot(
                    source = CompatibilitySourceObservation.UNAVAILABLE,
                    outputs = CompatibilityOutputObservation.UNAVAILABLE,
                ),
            ),
        )
        assertEquals(
            CompatibilityReconciliationDecision.NoChange(CompatibilityBatchState.QUARANTINED),
            service.reconcile(
                batch(CompatibilitySinkKind.ITEM_ENTITY, CompatibilityBatchState.QUARANTINED),
                CompatibilityAuthoritySnapshot(
                    source = CompatibilitySourceObservation.UNAVAILABLE,
                    outputs = CompatibilityOutputObservation.UNAVAILABLE,
                ),
            ),
        )
    }

    private fun batch(
        sinkKind: CompatibilitySinkKind,
        state: CompatibilityBatchState,
    ): CompatibilityBatch =
        CompatibilityBatch.create(
            batchId = UUID.fromString("00000000-0000-0000-0000-000000000011"),
            sourceEntityUuid = UUID.fromString("00000000-0000-0000-0000-000000000012"),
            sourceRevision = 9,
            sourceBeforeAmount = 128,
            sourceAfterAmount = 64,
            transferAmount = 64,
            sinkKind = sinkKind,
            sinkIdentity = "sink:00000000-0000-0000-0000-000000000013",
            outputs = listOf(CompatibilityOutput(0, 64)),
            state = state,
            quarantineReason = if (state == CompatibilityBatchState.QUARANTINED) "ExistingConflict" else null,
            createdAtEpochSecond = 1,
            changedAtEpochSecond = if (state == CompatibilityBatchState.PREPARED) 1 else 2,
        )
}
