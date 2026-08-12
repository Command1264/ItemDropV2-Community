package com.github.command1264.itemdropv2.platform.bukkit

import com.github.command1264.itemdropv2.core.ItemState
import com.github.command1264.itemdropv2.core.ItemStateLoadResult
import com.github.command1264.itemdropv2.core.ItemStateRepository
import org.bukkit.Bukkit
import org.bukkit.entity.Item

/** Classifies existing virtual state as Legacy Drain and never performs a virtual merge. */
public class BukkitLegacyDrainVirtualItemMergeOperations internal constructor(
    private val stateRepository: ItemStateRepository,
    private val primaryThreadCheck: () -> Boolean,
) : VirtualItemMergeOperations {
    public constructor(stateRepository: ItemStateRepository) : this(stateRepository, Bukkit::isPrimaryThread)

    override fun isVirtualPair(
        source: Item,
        target: Item,
    ): Boolean = mode(source, target) != VirtualItemMergePairMode.NotVirtual

    @Suppress("ReturnCount", "TooGenericExceptionCaught")
    override fun mode(
        source: Item,
        target: Item,
    ): VirtualItemMergePairMode {
        if (!primaryThreadCheck()) return VirtualItemMergePairMode.Rejected("AsyncBukkitAccess")
        if (source.isUnavailable() || target.isUnavailable()) {
            return VirtualItemMergePairMode.Rejected("InvalidEntityPair")
        }
        return try {
            classify(stateRepository.load(source.uniqueId), stateRepository.load(target.uniqueId))
        } catch (error: RuntimeException) {
            VirtualItemMergePairMode.Rejected(error.javaClass.simpleName)
        }
    }

    override fun canAttempt(
        source: Item,
        target: Item,
    ): Boolean = false

    override fun merge(
        source: Item,
        target: Item,
    ): VirtualItemMergeTransactionOutcome =
        when (val pairMode = mode(source, target)) {
            VirtualItemMergePairMode.NotVirtual -> VirtualItemMergeTransactionOutcome.NotVirtual
            VirtualItemMergePairMode.LegacyDrain -> VirtualItemMergeTransactionOutcome.Rejected("LegacyDrain")
            is VirtualItemMergePairMode.Rejected -> VirtualItemMergeTransactionOutcome.Rejected(pairMode.reason)
            VirtualItemMergePairMode.Active ->
                VirtualItemMergeTransactionOutcome.Rejected("UnexpectedCommunityActiveMode")
        }

    @Suppress("ReturnCount")
    private fun classify(
        sourceResult: ItemStateLoadResult,
        targetResult: ItemStateLoadResult,
    ): VirtualItemMergePairMode {
        val source = sourceResult.toLegacyPairState()
        val target = targetResult.toLegacyPairState()
        val rejected = sequenceOf(source, target).filterIsInstance<LegacyPairState.Rejected>().firstOrNull()
        if (rejected != null) return VirtualItemMergePairMode.Rejected(rejected.reason)

        val sourceState = (source as? LegacyPairState.Loaded)?.state
        val targetState = (target as? LegacyPairState.Loaded)?.state
        if (sourceState?.virtualAmount == null && targetState?.virtualAmount == null) {
            return VirtualItemMergePairMode.NotVirtual
        }
        if (sourceState == null || targetState == null) {
            return VirtualItemMergePairMode.Rejected("MixedVirtualState")
        }
        return VirtualItemMergePairMode.LegacyDrain
    }
}

private fun Item.isUnavailable(): Boolean = !isValid || isDead

private sealed interface LegacyPairState {
    data class Loaded(
        val state: ItemState,
    ) : LegacyPairState

    data object Absent : LegacyPairState

    data class Rejected(
        val reason: String,
    ) : LegacyPairState
}

private fun ItemStateLoadResult.toLegacyPairState(): LegacyPairState =
    when (this) {
        is ItemStateLoadResult.Loaded -> LegacyPairState.Loaded(state)
        ItemStateLoadResult.Absent -> LegacyPairState.Absent
        is ItemStateLoadResult.Legacy -> LegacyPairState.Rejected("LegacyStateRequiresMigration")
        is ItemStateLoadResult.Invalid -> LegacyPairState.Rejected("InvalidState")
        is ItemStateLoadResult.UnsupportedSchema -> LegacyPairState.Rejected("UnsupportedSchema:$actualVersion")
        ItemStateLoadResult.MissingTarget -> LegacyPairState.Rejected("StateNotReady")
        is ItemStateLoadResult.Failed -> LegacyPairState.Rejected(errorType)
    }
