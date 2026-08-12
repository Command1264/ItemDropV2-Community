package com.github.command1264.itemdropv2.core

public data class VirtualItemMergeRequest(
    public val sourceState: ItemState,
    public val targetState: ItemState,
) {
    init {
        requireNotNull(sourceState.virtualAmount) { "source virtual amount is required" }
        requireNotNull(targetState.virtualAmount) { "target virtual amount is required" }
    }
}

public enum class VirtualItemMergeRejection {
    OWNERSHIP_PRESENCE_MISMATCH,
    OWNER_MISMATCH,
    AMOUNT_EXCEEDS_MAXIMUM,
    TARGET_AT_CAPACITY,
}

public sealed interface VirtualItemMergeOutcome {
    public data class Merged(
        public val targetState: ItemState,
        public val sourceRemainderState: ItemState?,
        public val movedAmount: VirtualItemAmount,
    ) : VirtualItemMergeOutcome

    public data class Rejected(
        public val reason: VirtualItemMergeRejection,
    ) : VirtualItemMergeOutcome
}

public class VirtualItemMergeService(
    private val maximumAmountPerEntity: VirtualItemAmount,
    private val lifetimeStrategy: MergeLifetimeStrategy,
    private val ownershipStrategy: MergeOwnershipStrategy = MergeOwnershipStrategy.AVERAGE,
    private val resetProtectionSeconds: Long = ItemOwnershipSettings.DEFAULT_PROTECTION_SECONDS,
) {
    @Suppress("ReturnCount")
    public fun merge(request: VirtualItemMergeRequest): VirtualItemMergeOutcome {
        val source = request.sourceState
        val target = request.targetState
        if ((source.ownership == null) != (target.ownership == null)) {
            return VirtualItemMergeOutcome.Rejected(VirtualItemMergeRejection.OWNERSHIP_PRESENCE_MISMATCH)
        }
        if (!ownershipMatches(source.ownership, target.ownership)) {
            return VirtualItemMergeOutcome.Rejected(VirtualItemMergeRejection.OWNER_MISMATCH)
        }

        val sourceAmount = requireNotNull(source.virtualAmount)
        val targetAmount = requireNotNull(target.virtualAmount)
        val maximum = maximumAmountPerEntity.value
        if (sourceAmount.value > maximum || targetAmount.value > maximum) {
            return VirtualItemMergeOutcome.Rejected(VirtualItemMergeRejection.AMOUNT_EXCEEDS_MAXIMUM)
        }

        val available = maximum - targetAmount.value
        if (available == 0L) {
            return VirtualItemMergeOutcome.Rejected(VirtualItemMergeRejection.TARGET_AT_CAPACITY)
        }

        val movedValue = minOf(sourceAmount.value, available)
        val movedAmount = VirtualItemAmount.of(movedValue)
        val targetResultAmount = VirtualItemAmount.of(targetAmount.value + movedValue)
        val sourceRemainder =
            (sourceAmount.value - movedValue)
                .takeIf { it > 0 }
                ?.let(VirtualItemAmount::of)

        val selectedElapsed =
            ItemElapsedLifetimeSelector.select(
                strategy = lifetimeStrategy,
                source = source.elapsedLifetimeSeconds,
                target = target.elapsedLifetimeSeconds,
            )
        val targetOwnership =
            ItemOwnershipProtectionSelector.select(
                strategy = ownershipStrategy,
                source = source.ownership,
                target = target.ownership,
                resetProtectionSeconds = resetProtectionSeconds,
            )
        val targetState =
            target.copy(
                ownership = targetOwnership,
                elapsedLifetimeSeconds =
                    selectedElapsed.coerceBeforeExpiry(target.originalLifetimeSeconds),
                virtualAmount = targetResultAmount,
            )
        val sourceState = sourceRemainder?.let { source.copy(virtualAmount = it) }
        return VirtualItemMergeOutcome.Merged(targetState, sourceState, movedAmount)
    }

    private fun ownershipMatches(
        source: ItemOwnership?,
        target: ItemOwnership?,
    ): Boolean = source?.ownerUuid == target?.ownerUuid && source?.eligibleOwnerUuids == target?.eligibleOwnerUuids
}

private fun Long.coerceBeforeExpiry(originalLifetimeSeconds: Long?): Long =
    when {
        originalLifetimeSeconds == null ||
            originalLifetimeSeconds == ItemLifetimeSettings.NEVER_EXPIRES ->
            this
        this >= originalLifetimeSeconds -> originalLifetimeSeconds - 1
        else -> this
    }
