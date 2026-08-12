package com.github.command1264.itemdropv2.platform.bukkit

import org.bukkit.entity.Item

public interface VirtualItemMergeOperations {
    public fun mode(
        source: Item,
        target: Item,
    ): VirtualItemMergePairMode =
        if (isVirtualPair(source, target)) VirtualItemMergePairMode.Active else VirtualItemMergePairMode.NotVirtual

    public fun isVirtualPair(
        source: Item,
        target: Item,
    ): Boolean

    public fun canAttempt(
        source: Item,
        target: Item,
    ): Boolean

    public fun merge(
        source: Item,
        target: Item,
    ): VirtualItemMergeTransactionOutcome
}

public sealed interface VirtualItemMergePairMode {
    public data object NotVirtual : VirtualItemMergePairMode

    public data object Active : VirtualItemMergePairMode

    public data object LegacyDrain : VirtualItemMergePairMode

    public data class Rejected(
        public val reason: String,
    ) : VirtualItemMergePairMode
}

public sealed interface VirtualItemMergeTransactionOutcome {
    public data object NotVirtual : VirtualItemMergeTransactionOutcome

    public data object OwnershipMismatch : VirtualItemMergeTransactionOutcome

    public data class Merged(
        public val movedAmount: Long,
        public val sourceRemoved: Boolean,
    ) : VirtualItemMergeTransactionOutcome

    public data class Rejected(
        public val reason: String,
        public val diagnosticFields: Map<String, String> = emptyMap(),
    ) : VirtualItemMergeTransactionOutcome

    public data class Failed(
        public val errorType: String,
        public val diagnosticFields: Map<String, String> = emptyMap(),
    ) : VirtualItemMergeTransactionOutcome
}
