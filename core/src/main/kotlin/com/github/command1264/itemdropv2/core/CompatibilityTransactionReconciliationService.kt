package com.github.command1264.itemdropv2.core

public enum class CompatibilitySourceObservation {
    MATCHES_BEFORE,
    MATCHES_AFTER,
    UNAVAILABLE,
    REVISION_MISMATCH,
    AMBIGUOUS,
}

public enum class CompatibilityOutputObservation {
    NONE,
    COMPLETE,
    PARTIAL,
    UNAVAILABLE,
    AMOUNT_MISMATCH,
}

public data class CompatibilityAuthoritySnapshot(
    public val source: CompatibilitySourceObservation,
    public val outputs: CompatibilityOutputObservation,
)

public sealed interface CompatibilityReconciliationDecision {
    public data object KeepSource : CompatibilityReconciliationDecision

    public data object Commit : CompatibilityReconciliationDecision

    public data class Defer(
        public val reason: String,
    ) : CompatibilityReconciliationDecision

    public data class Quarantine(
        public val reason: String,
    ) : CompatibilityReconciliationDecision

    public data class NoChange(
        public val state: CompatibilityBatchState,
    ) : CompatibilityReconciliationDecision
}

public class CompatibilityTransactionReconciliationService {
    public fun reconcile(
        batch: CompatibilityBatch,
        snapshot: CompatibilityAuthoritySnapshot,
    ): CompatibilityReconciliationDecision =
        when {
            batch.state == CompatibilityBatchState.COMMITTED || batch.state == CompatibilityBatchState.QUARANTINED ->
                CompatibilityReconciliationDecision.NoChange(batch.state)
            snapshot.source == CompatibilitySourceObservation.UNAVAILABLE ->
                CompatibilityReconciliationDecision.Defer("SourceUnavailable")
            snapshot.outputs == CompatibilityOutputObservation.UNAVAILABLE ->
                CompatibilityReconciliationDecision.Defer("OutputsUnavailable")
            snapshot.source == CompatibilitySourceObservation.REVISION_MISMATCH ->
                CompatibilityReconciliationDecision.Quarantine("SourceRevisionMismatch")
            snapshot.source == CompatibilitySourceObservation.AMBIGUOUS ->
                CompatibilityReconciliationDecision.Quarantine("SourceAmbiguous")
            snapshot.outputs == CompatibilityOutputObservation.PARTIAL ->
                CompatibilityReconciliationDecision.Quarantine("PartialOutputs")
            snapshot.outputs == CompatibilityOutputObservation.AMOUNT_MISMATCH ->
                CompatibilityReconciliationDecision.Quarantine("OutputAmountMismatch")
            snapshot ==
                CompatibilityAuthoritySnapshot(
                    CompatibilitySourceObservation.MATCHES_BEFORE,
                    CompatibilityOutputObservation.NONE,
                ) -> CompatibilityReconciliationDecision.KeepSource
            snapshot ==
                CompatibilityAuthoritySnapshot(
                    CompatibilitySourceObservation.MATCHES_AFTER,
                    CompatibilityOutputObservation.COMPLETE,
                ) -> CompatibilityReconciliationDecision.Commit
            else -> CompatibilityReconciliationDecision.Quarantine("ContradictoryAuthority")
        }
}
