package com.github.command1264.itemdropv2.core

import java.util.UUID

public enum class CompatibilitySinkKind {
    ITEM_ENTITY,
    PLAYER_INVENTORY,
    HOPPER_INVENTORY,
    HOPPER_MINECART_INVENTORY,
}

public enum class CompatibilityBatchState {
    PREPARED,
    SOURCE_APPLIED,
    COMMITTED,
    QUARANTINED,
}

public data class CompatibilityOutput(
    public val markerIndex: Int,
    public val amount: Long,
) {
    init {
        require(markerIndex >= 0) { "compatibility output marker index must not be negative" }
        require(amount > 0) { "compatibility output amount must be positive" }
    }
}

@ConsistentCopyVisibility
public data class CompatibilityBatch private constructor(
    public val batchId: UUID,
    public val sourceEntityUuid: UUID,
    public val sourceRevision: Long,
    public val sourceBeforeAmount: Long,
    public val sourceAfterAmount: Long,
    public val transferAmount: Long,
    public val sinkKind: CompatibilitySinkKind,
    public val sinkIdentity: String,
    public val outputs: List<CompatibilityOutput>,
    public val state: CompatibilityBatchState,
    public val quarantineReason: String?,
    public val createdAtEpochSecond: Long,
    public val changedAtEpochSecond: Long,
) {
    init {
        require(batchId != ZERO_UUID) { "compatibility batch UUID must not be nil" }
        require(sourceEntityUuid != ZERO_UUID) { "compatibility source UUID must not be nil" }
        require(sourceRevision > 0) { "compatibility source revision must be positive" }
        require(sourceBeforeAmount > 0) { "compatibility source amount must be positive" }
        require(sourceAfterAmount in 0 until sourceBeforeAmount) {
            "compatibility post-source amount must be lower than the pre-source amount"
        }
        require(transferAmount == sourceBeforeAmount - sourceAfterAmount) {
            "compatibility transfer amount must equal the source delta"
        }
        require(SINK_IDENTITY.matches(sinkIdentity)) { "compatibility sink identity is not sanitized" }
        require(outputs.isNotEmpty()) { "compatibility batch must declare at least one output" }
        require(outputs.map(CompatibilityOutput::markerIndex).distinct().size == outputs.size) {
            "compatibility output marker indices must be unique"
        }
        require(outputs.conservedAmount() == transferAmount) {
            "compatibility output amount must equal the transfer amount"
        }
        require(createdAtEpochSecond > 0) { "compatibility creation timestamp must be positive" }
        require(changedAtEpochSecond >= createdAtEpochSecond) {
            "compatibility change timestamp must not precede creation"
        }
        if (state == CompatibilityBatchState.QUARANTINED) {
            require(quarantineReason != null && QUARANTINE_REASON.matches(quarantineReason)) {
                "quarantined compatibility batch requires a bounded reason"
            }
        } else {
            require(quarantineReason == null) { "non-quarantined compatibility batch cannot carry a reason" }
        }
    }

    public fun transitionTo(
        next: CompatibilityBatchState,
        changedAtEpochSecond: Long,
        quarantineReason: String? = null,
    ): CompatibilityBatch {
        require(changedAtEpochSecond > this.changedAtEpochSecond) {
            "compatibility transition timestamp must advance"
        }
        val allowed =
            when (state) {
                CompatibilityBatchState.PREPARED ->
                    next == CompatibilityBatchState.SOURCE_APPLIED || next == CompatibilityBatchState.QUARANTINED
                CompatibilityBatchState.SOURCE_APPLIED ->
                    next == CompatibilityBatchState.COMMITTED || next == CompatibilityBatchState.QUARANTINED
                CompatibilityBatchState.COMMITTED,
                CompatibilityBatchState.QUARANTINED,
                -> false
            }
        require(allowed) { "invalid compatibility batch transition: $state -> $next" }
        return copy(
            state = next,
            quarantineReason = quarantineReason,
            changedAtEpochSecond = changedAtEpochSecond,
        )
    }

    private fun List<CompatibilityOutput>.conservedAmount(): Long {
        var total = 0L
        forEach { output ->
            require(output.amount <= transferAmount - total) {
                "compatibility output amount exceeds the transfer amount"
            }
            total += output.amount
        }
        return total
    }

    public companion object {
        private val ZERO_UUID = UUID(0, 0)
        private val SINK_IDENTITY = Regex("[A-Za-z0-9._:/-]{1,256}")
        private val QUARANTINE_REASON = Regex("[A-Za-z0-9._:-]{1,256}")

        public fun create(
            batchId: UUID,
            sourceEntityUuid: UUID,
            sourceRevision: Long,
            sourceBeforeAmount: Long,
            sourceAfterAmount: Long,
            transferAmount: Long,
            sinkKind: CompatibilitySinkKind,
            sinkIdentity: String,
            outputs: List<CompatibilityOutput>,
            state: CompatibilityBatchState,
            quarantineReason: String?,
            createdAtEpochSecond: Long,
            changedAtEpochSecond: Long,
        ): CompatibilityBatch =
            CompatibilityBatch(
                batchId = batchId,
                sourceEntityUuid = sourceEntityUuid,
                sourceRevision = sourceRevision,
                sourceBeforeAmount = sourceBeforeAmount,
                sourceAfterAmount = sourceAfterAmount,
                transferAmount = transferAmount,
                sinkKind = sinkKind,
                sinkIdentity = sinkIdentity,
                outputs = outputs.toList(),
                state = state,
                quarantineReason = quarantineReason,
                createdAtEpochSecond = createdAtEpochSecond,
                changedAtEpochSecond = changedAtEpochSecond,
            )
    }
}

public interface CompatibilityTransactionRepository : AutoCloseable {
    public fun create(batch: CompatibilityBatch): CompatibilityTransactionWriteResult

    public fun find(batchId: UUID): CompatibilityTransactionReadResult

    public fun unresolved(limit: Int): CompatibilityTransactionReadManyResult

    public fun transition(
        batchId: UUID,
        expected: CompatibilityBatchState,
        next: CompatibilityBatchState,
        changedAtEpochSecond: Long,
        quarantineReason: String? = null,
    ): CompatibilityTransactionWriteResult
}

public sealed interface CompatibilityTransactionWriteResult {
    public data object Created : CompatibilityTransactionWriteResult

    public data class Updated(
        public val state: CompatibilityBatchState,
    ) : CompatibilityTransactionWriteResult

    public data class Conflict(
        public val actualState: CompatibilityBatchState,
    ) : CompatibilityTransactionWriteResult

    public data object Missing : CompatibilityTransactionWriteResult

    public data object CapacityReached : CompatibilityTransactionWriteResult

    public data class Rejected(
        public val reason: String,
    ) : CompatibilityTransactionWriteResult

    public data class Failed(
        public val errorType: String,
    ) : CompatibilityTransactionWriteResult
}

public sealed interface CompatibilityTransactionReadResult {
    public data class Found(
        public val batch: CompatibilityBatch,
    ) : CompatibilityTransactionReadResult

    public data object Missing : CompatibilityTransactionReadResult

    public data class Rejected(
        public val reason: String,
    ) : CompatibilityTransactionReadResult

    public data class Failed(
        public val errorType: String,
    ) : CompatibilityTransactionReadResult
}

public sealed interface CompatibilityTransactionReadManyResult {
    public data class Loaded(
        public val batches: List<CompatibilityBatch>,
    ) : CompatibilityTransactionReadManyResult

    public data class Rejected(
        public val reason: String,
    ) : CompatibilityTransactionReadManyResult

    public data class Failed(
        public val errorType: String,
    ) : CompatibilityTransactionReadManyResult
}
