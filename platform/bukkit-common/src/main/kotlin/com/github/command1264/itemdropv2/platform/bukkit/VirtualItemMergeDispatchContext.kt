package com.github.command1264.itemdropv2.platform.bukkit

import java.util.UUID

public class VirtualItemMergeDispatchContext : AutoCloseable {
    private var activePair: MergePair? = null
    private var completedOutcome: VirtualItemMergeTransactionOutcome? = null

    public fun dispatch(
        sourceId: UUID,
        targetId: UUID,
        eventDispatch: () -> Unit,
    ): VirtualItemMergeTransactionOutcome? {
        check(activePair == null) { "nested virtual merge dispatch is not allowed" }
        activePair = MergePair(sourceId, targetId)
        completedOutcome = null
        return try {
            eventDispatch()
            completedOutcome
        } finally {
            activePair = null
            completedOutcome = null
        }
    }

    public fun complete(
        sourceId: UUID,
        targetId: UUID,
        outcome: VirtualItemMergeTransactionOutcome,
    ) {
        if (activePair == MergePair(sourceId, targetId)) {
            completedOutcome = outcome
        }
    }

    public fun isDispatching(
        sourceId: UUID,
        targetId: UUID,
    ): Boolean = activePair == MergePair(sourceId, targetId)

    override fun close() {
        activePair = null
        completedOutcome = null
    }

    private data class MergePair(
        val sourceId: UUID,
        val targetId: UUID,
    )
}
