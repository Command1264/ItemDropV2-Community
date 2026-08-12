package com.github.command1264.itemdropv2.platform.bukkit

import com.github.command1264.itemdropv2.core.ItemMergeOutcome
import com.github.command1264.itemdropv2.core.ItemMergeService
import com.github.command1264.itemdropv2.core.ItemState
import com.github.command1264.itemdropv2.core.ItemStateRepository
import com.github.command1264.itemdropv2.core.ItemStateWriteResult
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.ItemMergeEvent
import kotlin.math.min

public fun interface TrackedItemMergeTransaction {
    public fun merge(
        source: org.bukkit.entity.Item,
        target: org.bukkit.entity.Item,
        state: ItemState,
    ): Boolean
}

public class BukkitTrackedItemMergeTransaction(
    private val repository: ItemStateRepository,
    private val itemRefresh: ItemOwnershipRefresh,
    private val warningSink: DisplayWarningSink,
) : TrackedItemMergeTransaction {
    @Suppress("TooGenericExceptionCaught")
    override fun merge(
        source: org.bukkit.entity.Item,
        target: org.bukkit.entity.Item,
        state: ItemState,
    ): Boolean =
        try {
            mergeSafely(source, target, state)
        } catch (error: RuntimeException) {
            warningSink.warn("tracked item merge failed (${error.javaClass.simpleName})")
            false
        }

    private fun mergeSafely(
        source: org.bukkit.entity.Item,
        target: org.bukkit.entity.Item,
        state: ItemState,
    ): Boolean {
        val sourceStack = source.itemStack
        val targetStack = target.itemStack
        val transfer = min(sourceStack.amount, targetStack.maxStackSize - targetStack.amount)
        if (transfer <= 0) return false
        val updatedTarget = targetStack.clone().apply { setAmount(targetStack.amount + transfer) }
        target.setItemStack(updatedTarget)
        return when (val saved = repository.save(target.uniqueId, state)) {
            ItemStateWriteResult.Applied -> complete(source, target, sourceStack, transfer)
            ItemStateWriteResult.MissingTarget -> failed(target, targetStack, "MissingTarget")
            is ItemStateWriteResult.Rejected -> failed(target, targetStack, saved.reason)
            is ItemStateWriteResult.Failed -> failed(target, targetStack, saved.errorType)
        }
    }

    private fun complete(
        source: org.bukkit.entity.Item,
        target: org.bukkit.entity.Item,
        sourceStack: org.bukkit.inventory.ItemStack,
        transfer: Int,
    ): Boolean {
        val remaining = sourceStack.amount - transfer
        if (remaining == 0) {
            source.remove()
        } else {
            source.setItemStack(sourceStack.clone().apply { setAmount(remaining) })
            itemRefresh.refresh(source)
        }
        itemRefresh.refresh(target)
        return true
    }

    private fun failed(
        target: org.bukkit.entity.Item,
        originalTargetStack: org.bukkit.inventory.ItemStack,
        reason: String,
    ): Boolean {
        target.setItemStack(originalTargetStack)
        warningSink.warn("tracked item merge failed ($reason)")
        return false
    }
}

public class BukkitItemMergePolicyController(
    private val service: ItemMergeService,
    private val warningSink: DisplayWarningSink,
    private val trackedTransaction: TrackedItemMergeTransaction,
    private val virtualTransaction: VirtualItemMergeOperations? = null,
    private val virtualMergeRefresh: ItemOwnershipRefresh = ItemOwnershipRefresh {},
    private val dispatchContext: VirtualItemMergeDispatchContext? = null,
    private val transientTargetLeaseFactory: TransientItemTargetLeaseFactory =
        TransientItemTargetLeaseFactory { TransientItemTargetLease {} },
) : Listener {
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public fun onItemMerge(event: ItemMergeEvent) {
        if (event.isAsynchronous) {
            event.isCancelled = true
            warningSink.warn("cancelled asynchronous ItemMergeEvent")
            return
        }
        runWithEventItems(event) {
            val scheduledPair = dispatchContext?.isDispatching(event.entity.uniqueId, event.target.uniqueId) == true
            if (
                scheduledPair ||
                virtualTransaction
                    ?.mode(event.entity, event.target)
                    ?.let { it != VirtualItemMergePairMode.NotVirtual } == true
            ) {
                return@runWithEventItems
            }
            when (val outcome = service.merge(event.entity.uniqueId, event.target.uniqueId)) {
                is ItemMergeOutcome.Merged -> {
                    event.isCancelled = true
                    trackedTransaction.merge(event.entity, event.target, outcome.state)
                }
                ItemMergeOutcome.Untracked -> Unit
                is ItemMergeOutcome.Rejected -> event.isCancelled = true
                is ItemMergeOutcome.Failed -> {
                    event.isCancelled = true
                    warningSink.warn("item merge failed (${outcome.errorType})")
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public fun onVirtualItemMerge(event: ItemMergeEvent) {
        runWithEventItems(event) {
            val transaction = virtualTransaction ?: return@runWithEventItems
            val scheduledPair = dispatchContext?.isDispatching(event.entity.uniqueId, event.target.uniqueId) == true
            val pairMode = transaction.mode(event.entity, event.target)
            if (pairMode == VirtualItemMergePairMode.NotVirtual) return@runWithEventItems
            event.isCancelled = true
            if (pairMode == VirtualItemMergePairMode.LegacyDrain) {
                dispatchContext?.complete(
                    event.entity.uniqueId,
                    event.target.uniqueId,
                    VirtualItemMergeTransactionOutcome.Rejected("LegacyDrain"),
                )
                return@runWithEventItems
            }
            if (pairMode is VirtualItemMergePairMode.Rejected) {
                val outcome = VirtualItemMergeTransactionOutcome.Rejected(pairMode.reason)
                dispatchContext?.complete(event.entity.uniqueId, event.target.uniqueId, outcome)
                warningSink.warn(
                    "virtual item merge rejected (${pairMode.reason})",
                    virtualMergeContext(event, scheduledPair, emptyMap()),
                )
                return@runWithEventItems
            }
            val outcome = transaction.merge(event.entity, event.target)
            dispatchContext?.complete(event.entity.uniqueId, event.target.uniqueId, outcome)
            when (outcome) {
                VirtualItemMergeTransactionOutcome.NotVirtual ->
                    warningSink.warn("virtual item merge lost its managed state before commit")
                VirtualItemMergeTransactionOutcome.OwnershipMismatch -> Unit
                is VirtualItemMergeTransactionOutcome.Merged -> {
                    virtualMergeRefresh.refresh(event.target)
                    if (!outcome.sourceRemoved) virtualMergeRefresh.refresh(event.entity)
                }
                is VirtualItemMergeTransactionOutcome.Rejected ->
                    warningSink.warn(
                        "virtual item merge rejected (${outcome.reason})",
                        virtualMergeContext(event, scheduledPair, outcome.diagnosticFields),
                    )
                is VirtualItemMergeTransactionOutcome.Failed ->
                    warningSink.warn(
                        "virtual item merge failed (${outcome.errorType})",
                        virtualMergeContext(event, scheduledPair, outcome.diagnosticFields),
                    )
            }
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun runWithEventItems(
        event: ItemMergeEvent,
        operation: () -> Unit,
    ) {
        try {
            withTransientItemTargets(
                event.entity,
                event.target,
                transientTargetLeaseFactory,
                operation,
            )
        } catch (error: RuntimeException) {
            event.isCancelled = true
            warningSink.warn("ItemMergeEvent direct item processing failed (${error.javaClass.simpleName})")
        }
    }

    private fun virtualMergeContext(
        event: ItemMergeEvent,
        scheduledPair: Boolean,
        transactionFields: Map<String, String>,
    ): RuntimeDiagnosticContext =
        RuntimeDiagnosticContext(
            fields =
                mapOf("merge.scheduled-pair" to scheduledPair.toString()) +
                    event.entity.diagnosticFields("source") +
                    event.target.diagnosticFields("target") +
                    transactionFields,
        )
}

@Suppress("TooGenericExceptionCaught")
private fun org.bukkit.entity.Item.diagnosticFields(role: String): Map<String, String> =
    try {
        val location = location
        mapOf(
            "merge.$role.entity-id" to uniqueId.toString(),
            "merge.$role.valid" to isValid.toString(),
            "merge.$role.dead" to isDead.toString(),
            "merge.$role.material" to itemStack.type.name,
            "merge.$role.physical-amount" to itemStack.amount.toString(),
            "merge.$role.pickup-delay" to pickupDelay.toString(),
            "merge.$role.world" to location.world?.name.orEmpty(),
            "merge.$role.block-position" to "${location.blockX},${location.blockY},${location.blockZ}",
        )
    } catch (error: RuntimeException) {
        mapOf(
            "merge.$role.entity-id" to uniqueId.toString(),
            "merge.$role.snapshot-error" to error.javaClass.simpleName,
        )
    }
