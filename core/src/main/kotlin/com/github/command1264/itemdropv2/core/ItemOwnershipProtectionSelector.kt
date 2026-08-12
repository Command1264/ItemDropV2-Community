package com.github.command1264.itemdropv2.core

internal object ItemOwnershipProtectionSelector {
    fun select(
        strategy: MergeOwnershipStrategy,
        source: ItemOwnership?,
        target: ItemOwnership?,
        resetProtectionSeconds: Long,
    ): ItemOwnership? {
        require(resetProtectionSeconds >= 0) { "reset protection seconds must not be negative" }
        require(ownershipMatches(source, target)) { "ownership groups must match" }
        source ?: return null
        val targetProtection = requireNotNull(target).protectionSecondsRemaining
        val selectedProtection =
            when (strategy) {
                MergeOwnershipStrategy.AVERAGE -> roundedAverage(source.protectionSecondsRemaining, targetProtection)
                MergeOwnershipStrategy.MAXIMUM -> maxOf(source.protectionSecondsRemaining, targetProtection)
                MergeOwnershipStrategy.MINIMUM -> minOf(source.protectionSecondsRemaining, targetProtection)
                MergeOwnershipStrategy.RESET -> resetProtectionSeconds
            }
        return selectedProtection
            .takeIf { it > 0 }
            ?.let { source.copy(protectionSecondsRemaining = it) }
    }

    private fun ownershipMatches(
        source: ItemOwnership?,
        target: ItemOwnership?,
    ): Boolean = source?.ownerUuid == target?.ownerUuid && source?.eligibleOwnerUuids == target?.eligibleOwnerUuids

    private fun roundedAverage(
        first: Long,
        second: Long,
    ): Long = first / 2 + second / 2 + (first % 2 + second % 2 + 1) / 2
}
