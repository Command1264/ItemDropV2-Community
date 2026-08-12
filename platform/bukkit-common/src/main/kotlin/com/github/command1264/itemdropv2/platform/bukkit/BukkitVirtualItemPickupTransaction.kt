package com.github.command1264.itemdropv2.platform.bukkit

import com.github.command1264.itemdropv2.core.CreativeNoCapacityPickupMode
import com.github.command1264.itemdropv2.core.InventoryInsertionPlan
import com.github.command1264.itemdropv2.core.InventoryInsertionPlanner
import com.github.command1264.itemdropv2.core.InventorySlotCapacity
import com.github.command1264.itemdropv2.core.ItemDisplaySettingsRepository
import com.github.command1264.itemdropv2.core.ItemState
import com.github.command1264.itemdropv2.core.ItemStateLoadResult
import com.github.command1264.itemdropv2.core.ItemStateRepository
import com.github.command1264.itemdropv2.core.ItemStateWriteResult
import com.github.command1264.itemdropv2.core.VirtualItemAmount
import com.github.command1264.itemdropv2.core.VirtualStackingRuntimeModeResolver
import org.bukkit.Bukkit
import org.bukkit.GameMode
import org.bukkit.Material
import org.bukkit.entity.Item
import org.bukkit.entity.Player
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack
import java.util.UUID

public interface VirtualItemPickupHandling {
    /**
     * [beforeCarrierMutation] 只會在 inventory 與 PDC 已成功提交後呼叫一次，並提供本次
     * 消耗量與剩餘量；此時 [item] 尚未被移除或改量。
     */
    public fun pickupByPlayer(
        item: Item,
        player: Player,
        beforeCarrierMutation: (consumedAmount: Long, remainingAmount: Long) -> Unit = { _, _ -> },
    ): VirtualItemPickupOutcome

    /**
     * CraftBukkit does not dispatch a pickup event when the native inventory reports zero capacity.
     * This entry point is therefore restricted to an independently detected Creative collision and
     * refuses to mutate anything when legal inventory capacity still exists.
     */
    public fun pickupByCreativeNoCapacityCollision(
        item: Item,
        player: Player,
        beforeCarrierMutation: (consumedAmount: Long, remainingAmount: Long) -> Unit = { _, _ -> },
    ): VirtualItemPickupOutcome = VirtualItemPickupOutcome.NotApplicable

    public fun pickupByInventory(
        item: Item,
        inventory: Inventory,
    ): VirtualItemPickupOutcome

    public fun pickupByNonPlayer(
        item: Item,
        nativePickupRemaining: Int,
    ): VirtualItemPickupOutcome

    public fun reconcileNonPlayerPickup(sourceEntityId: UUID): NonPlayerPickupReconciliationOutcome =
        NonPlayerPickupReconciliationOutcome.NotRequired
}

@Suppress("TooManyFunctions")
public class BukkitVirtualItemPickupTransaction internal constructor(
    private val stateRepository: ItemStateRepository,
    private val settingsRepository: ItemDisplaySettingsRepository,
    private val pickedUpStateCleaner: (Item) -> ItemStateWriteResult,
    private val primaryThreadCheck: () -> Boolean,
    private val remainderSpawner: (Item, ItemStack) -> Item = { source, stack ->
        source.world.dropItem(source.location, stack).also { remainder ->
            remainder.velocity = source.velocity
            remainder.pickupDelay = source.pickupDelay
        }
    },
    private val itemResolver: (UUID) -> Item? = ::resolveBukkitItem,
    private val transientTargetLeaseFactory: TransientItemTargetLeaseFactory =
        TransientItemTargetLeaseFactory { TransientItemTargetLease {} },
    private val planner: InventoryInsertionPlanner = InventoryInsertionPlanner(),
    private val modeResolver: VirtualStackingRuntimeModeResolver =
        VirtualStackingRuntimeModeResolver(creationCapabilityAvailable = true),
) : VirtualItemPickupHandling {
    public constructor(
        stateRepository: ItemStateRepository,
        settingsRepository: ItemDisplaySettingsRepository,
        pickedUpStateCleaner: (Item) -> ItemStateWriteResult,
        transientTargetLeaseFactory: TransientItemTargetLeaseFactory,
        canonicalItemSpawnCapture: CanonicalItemSpawnCapture = DirectItemSpawnCapture,
        modeResolver: VirtualStackingRuntimeModeResolver =
            VirtualStackingRuntimeModeResolver(creationCapabilityAvailable = true),
    ) : this(
        stateRepository,
        settingsRepository,
        pickedUpStateCleaner,
        Bukkit::isPrimaryThread,
        remainderSpawner = { source, stack ->
            canonicalItemSpawnCapture
                .capture { source.world.dropItem(source.location, stack) }
                .also { remainder ->
                    remainder.velocity = source.velocity
                    remainder.pickupDelay = source.pickupDelay
                }
        },
        transientTargetLeaseFactory = transientTargetLeaseFactory,
        modeResolver = modeResolver,
    )

    override fun pickupByPlayer(
        item: Item,
        player: Player,
        beforeCarrierMutation: (consumedAmount: Long, remainingAmount: Long) -> Unit,
    ): VirtualItemPickupOutcome =
        pickupByPlayer(
            item,
            player,
            requireZeroCapacity = false,
            beforeCarrierMutation,
        )

    override fun pickupByCreativeNoCapacityCollision(
        item: Item,
        player: Player,
        beforeCarrierMutation: (consumedAmount: Long, remainingAmount: Long) -> Unit,
    ): VirtualItemPickupOutcome =
        when {
            !primaryThreadCheck() -> VirtualItemPickupOutcome.Failed("AsyncBukkitAccess")
            player.gameMode != GameMode.CREATIVE -> VirtualItemPickupOutcome.NotApplicable
            else ->
                pickupByPlayer(
                    item,
                    player,
                    requireZeroCapacity = true,
                    beforeCarrierMutation,
                )
        }

    private fun pickupByPlayer(
        item: Item,
        player: Player,
        requireZeroCapacity: Boolean,
        beforeCarrierMutation: (consumedAmount: Long, remainingAmount: Long) -> Unit,
    ): VirtualItemPickupOutcome =
        if (!primaryThreadCheck()) {
            VirtualItemPickupOutcome.Failed("AsyncBukkitAccess")
        } else {
            val key = PlayerItemTransactionKey(player.uniqueId, item.uniqueId)
            if (!activePlayerTransactions.add(key)) {
                VirtualItemPickupOutcome.Rejected("PickupTransactionAlreadyActive")
            } else {
                try {
                    val creative = player.gameMode == GameMode.CREATIVE
                    pickup(
                        item,
                        player.inventory,
                        Long.MAX_VALUE,
                        discardOverflow = creative,
                        denyWhenNoCapacity =
                            !creative ||
                                settingsRepository
                                    .settings()
                                    .ownership
                                    .pickup
                                    .creativeNoCapacityPickupMode == CreativeNoCapacityPickupMode.DENY,
                        requireZeroCapacity = requireZeroCapacity,
                        beforeCarrierMutation = beforeCarrierMutation,
                    )
                } finally {
                    activePlayerTransactions -= key
                }
            }
        }

    override fun pickupByInventory(
        item: Item,
        inventory: Inventory,
    ): VirtualItemPickupOutcome =
        pickup(
            item,
            inventory,
            item.itemStack.maxStackSize.toLong(),
            discardOverflow = false,
            denyWhenNoCapacity = true,
        )

    override fun pickupByNonPlayer(
        item: Item,
        nativePickupRemaining: Int,
    ): VirtualItemPickupOutcome {
        if (!primaryThreadCheck()) return VirtualItemPickupOutcome.Failed("AsyncBukkitAccess")
        return when (val resolution = resolveVirtualState(item, trustDirectEventTarget = true)) {
            is VirtualStateResolution.Virtual -> prepareNativeNonPlayerPickup(item, nativePickupRemaining, resolution.state)
            VirtualStateResolution.NotVirtual -> VirtualItemPickupOutcome.NotVirtual
            is VirtualStateResolution.Rejected -> VirtualItemPickupOutcome.Rejected(resolution.reason)
            is VirtualStateResolution.Failed -> VirtualItemPickupOutcome.Failed(resolution.errorType)
        }
    }

    @Suppress("CyclomaticComplexMethod", "LongMethod", "ReturnCount", "TooGenericExceptionCaught")
    private fun prepareNativeNonPlayerPickup(
        item: Item,
        nativePickupRemaining: Int,
        state: ItemState,
    ): VirtualItemPickupOutcome {
        val carrier = item.itemStack
        if (nativePickupRemaining !in 0..carrier.amount) {
            return VirtualItemPickupOutcome.Rejected("InvalidNativePickupRemaining")
        }
        val virtualAmount = requireNotNull(state.virtualAmount)
        if (virtualAmount.value < carrier.amount.toLong()) {
            return VirtualItemPickupOutcome.Rejected("VirtualAmountBelowCarrierAmount")
        }
        val excessAmount = virtualAmount.value - carrier.amount
        if (excessAmount == 0L) {
            return VirtualItemPickupOutcome.NativePickupPrepared(
                sourceEntityId = item.uniqueId,
                itemsToRefresh = emptyList(),
                reconcileAfterNativePickup = nativePickupRemaining > 0,
            )
        }

        var remainder: Item? = null
        var sourceStateChanged = false
        return try {
            val spawned =
                remainderSpawner(
                    item,
                    carrier.clone().apply { amount = 1 },
                )
            remainder = spawned
            val remainderState = state.copy(virtualAmount = VirtualItemAmount.of(excessAmount))
            val remainderWrite =
                withTransientItemTarget(spawned, transientTargetLeaseFactory) {
                    stateRepository.save(spawned.uniqueId, remainderState)
                }
            when (remainderWrite) {
                ItemStateWriteResult.Applied -> Unit
                ItemStateWriteResult.MissingTarget ->
                    return rollbackPreparedNonPlayerPickup(
                        item,
                        state,
                        spawned,
                        sourceStateChanged = false,
                        reason = "RemainderMissingTarget",
                    )
                is ItemStateWriteResult.Rejected ->
                    return rollbackPreparedNonPlayerPickup(
                        item,
                        state,
                        spawned,
                        sourceStateChanged = false,
                        reason = remainderWrite.reason,
                        rejected = true,
                    )
                is ItemStateWriteResult.Failed ->
                    return rollbackPreparedNonPlayerPickup(
                        item,
                        state,
                        spawned,
                        sourceStateChanged = false,
                        reason = remainderWrite.errorType,
                    )
            }
            val virtualSettings =
                settingsRepository
                    .settings()
                    .virtualStacking
            spawned.applyVirtualCarrierAmount(
                VirtualItemAmount.of(excessAmount),
                virtualSettings,
                modeResolver,
            )

            val sourceState =
                state.copy(
                    virtualAmount = VirtualItemAmount.of(carrier.amount.toLong()),
                )
            when (val saved = stateRepository.save(item.uniqueId, sourceState)) {
                ItemStateWriteResult.Applied -> sourceStateChanged = true
                ItemStateWriteResult.MissingTarget ->
                    return rollbackPreparedNonPlayerPickup(item, state, spawned, false, "SourceMissingTarget")
                is ItemStateWriteResult.Rejected ->
                    return rollbackPreparedNonPlayerPickup(item, state, spawned, false, saved.reason, rejected = true)
                is ItemStateWriteResult.Failed ->
                    return rollbackPreparedNonPlayerPickup(item, state, spawned, false, saved.errorType)
            }
            VirtualItemPickupOutcome.NativePickupPrepared(
                sourceEntityId = item.uniqueId,
                itemsToRefresh = listOf(item, spawned),
                reconcileAfterNativePickup = nativePickupRemaining > 0,
            )
        } catch (error: RuntimeException) {
            remainder?.let { spawned ->
                rollbackPreparedNonPlayerPickup(
                    item,
                    state,
                    spawned,
                    sourceStateChanged,
                    error.javaClass.simpleName,
                )
            } ?: VirtualItemPickupOutcome.Failed(error.javaClass.simpleName)
        }
    }

    @Suppress("ReturnCount")
    override fun reconcileNonPlayerPickup(sourceEntityId: UUID): NonPlayerPickupReconciliationOutcome {
        if (!primaryThreadCheck()) return NonPlayerPickupReconciliationOutcome.Failed("AsyncBukkitAccess")
        val item = itemResolver(sourceEntityId) ?: return NonPlayerPickupReconciliationOutcome.MissingTarget
        if (!item.isValid) return NonPlayerPickupReconciliationOutcome.MissingTarget
        return when (val loaded = stateRepository.load(sourceEntityId)) {
            is ItemStateLoadResult.Loaded -> reconcileNativeCarrier(item, loaded.state)
            ItemStateLoadResult.Absent -> NonPlayerPickupReconciliationOutcome.NotRequired
            ItemStateLoadResult.MissingTarget -> NonPlayerPickupReconciliationOutcome.MissingTarget
            is ItemStateLoadResult.Legacy ->
                NonPlayerPickupReconciliationOutcome.Failed("LegacyStateRequiresMigration")
            is ItemStateLoadResult.Invalid -> NonPlayerPickupReconciliationOutcome.Failed("InvalidState")
            is ItemStateLoadResult.UnsupportedSchema ->
                NonPlayerPickupReconciliationOutcome.Failed("UnsupportedSchema:${loaded.actualVersion}")
            is ItemStateLoadResult.Failed -> NonPlayerPickupReconciliationOutcome.Failed(loaded.errorType)
        }
    }

    @Suppress("ReturnCount", "TooGenericExceptionCaught")
    private fun reconcileNativeCarrier(
        item: Item,
        state: ItemState,
    ): NonPlayerPickupReconciliationOutcome {
        if (state.virtualAmount == null) return NonPlayerPickupReconciliationOutcome.NotRequired
        val nativeRemaining = item.itemStack.amount
        if (nativeRemaining <= 0) return NonPlayerPickupReconciliationOutcome.Failed("NonPositiveNativeRemainder")
        val remaining = VirtualItemAmount.of(nativeRemaining.toLong())
        return when (
            val saved =
                stateRepository.save(
                    item.uniqueId,
                    state.copy(virtualAmount = remaining),
                )
        ) {
            ItemStateWriteResult.Applied ->
                try {
                    val virtualSettings =
                        settingsRepository
                            .settings()
                            .virtualStacking
                    item.applyVirtualCarrierAmount(remaining, virtualSettings, modeResolver)
                    NonPlayerPickupReconciliationOutcome.Applied(item)
                } catch (error: RuntimeException) {
                    NonPlayerPickupReconciliationOutcome.Failed("Carrier${error.javaClass.simpleName}")
                }
            ItemStateWriteResult.MissingTarget -> NonPlayerPickupReconciliationOutcome.MissingTarget
            is ItemStateWriteResult.Rejected -> NonPlayerPickupReconciliationOutcome.Failed(saved.reason)
            is ItemStateWriteResult.Failed -> NonPlayerPickupReconciliationOutcome.Failed(saved.errorType)
        }
    }

    private fun rollbackPreparedNonPlayerPickup(
        source: Item,
        originalState: ItemState,
        remainder: Item,
        sourceStateChanged: Boolean,
        reason: String,
        rejected: Boolean = false,
    ): VirtualItemPickupOutcome {
        val sourceRestored =
            !sourceStateChanged ||
                try {
                    stateRepository.save(source.uniqueId, originalState) == ItemStateWriteResult.Applied
                } catch (_: RuntimeException) {
                    false
                }
        val replacementRemoved =
            try {
                remainder.remove()
                true
            } catch (_: RuntimeException) {
                false
            }
        if (!sourceRestored || !replacementRemoved) {
            return VirtualItemPickupOutcome.Failed("NonPlayerPickupRollbackFailed")
        }
        return if (rejected) {
            VirtualItemPickupOutcome.Rejected(reason)
        } else {
            VirtualItemPickupOutcome.Failed(reason)
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun pickup(
        item: Item,
        inventory: Inventory,
        transactionLimit: Long,
        discardOverflow: Boolean,
        denyWhenNoCapacity: Boolean,
        requireZeroCapacity: Boolean = false,
        beforeCarrierMutation: (consumedAmount: Long, remainingAmount: Long) -> Unit = { _, _ -> },
    ): VirtualItemPickupOutcome =
        if (!primaryThreadCheck()) {
            VirtualItemPickupOutcome.Failed("AsyncBukkitAccess")
        } else {
            when (val resolution = resolveVirtualState(item)) {
                is VirtualStateResolution.Virtual ->
                    try {
                        executeTransaction(
                            item,
                            inventory,
                            transactionLimit,
                            discardOverflow,
                            denyWhenNoCapacity,
                            requireZeroCapacity,
                            resolution.state,
                            beforeCarrierMutation,
                        )
                    } catch (error: RuntimeException) {
                        VirtualItemPickupOutcome.Failed(error.javaClass.simpleName)
                    }
                else -> resolution.toPickupOutcome()
            }
        }

    // 每個 early return 都會在 inventory mutation 前停止，或進入明確 rollback；保留交易邊界可讀性。
    @Suppress("CyclomaticComplexMethod", "ReturnCount", "TooGenericExceptionCaught")
    private fun executeTransaction(
        item: Item,
        inventory: Inventory,
        transactionLimit: Long,
        discardOverflow: Boolean,
        denyWhenNoCapacity: Boolean,
        requireZeroCapacity: Boolean,
        state: ItemState,
        beforeCarrierMutation: (consumedAmount: Long, remainingAmount: Long) -> Unit,
    ): VirtualItemPickupOutcome {
        val carrier = item.itemStack
        val original = inventory.storageContents.map { it?.clone() }.toTypedArray()
        val plan =
            planner.plan(
                virtualAmount = requireNotNull(state.virtualAmount),
                transactionLimit = transactionLimit,
                slots = capacities(original, carrier, inventory.maxStackSize),
            )
        if (requireZeroCapacity && plan.insertedAmount > 0L) return VirtualItemPickupOutcome.NotApplicable
        if (plan.insertedAmount == 0L && denyWhenNoCapacity) return VirtualItemPickupOutcome.NoCapacity
        when (val cleaned = pickedUpStateCleaner(item)) {
            ItemStateWriteResult.Applied,
            ItemStateWriteResult.MissingTarget,
            -> Unit
            is ItemStateWriteResult.Rejected -> return VirtualItemPickupOutcome.Rejected(cleaned.reason)
            is ItemStateWriteResult.Failed -> return VirtualItemPickupOutcome.Failed(cleaned.errorType)
        }
        val cleanedCarrier = item.itemStack
        val remainingAmount = if (discardOverflow) 0L else plan.remainingAmount
        val discardedAmount = if (discardOverflow) plan.remainingAmount else 0L

        val updated = original.map { it?.clone() }.toTypedArray()
        plan.mutations.forEach { mutation ->
            val existing = updated[mutation.index]
            updated[mutation.index] =
                (existing ?: cleanedCarrier.clone()).apply {
                    amount = mutation.newAmount
                }
        }
        return try {
            if (plan.mutations.isNotEmpty()) inventory.storageContents = updated
            val persisted =
                if (remainingAmount == 0L) {
                    ItemStateWriteResult.Applied
                } else {
                    stateRepository.save(
                        item.uniqueId,
                        state.copy(virtualAmount = VirtualItemAmount.of(remainingAmount)),
                    )
                }
            when (persisted) {
                ItemStateWriteResult.Applied ->
                    finishAppliedTransaction(
                        item,
                        inventory,
                        original,
                        state,
                        plan,
                        remainingAmount,
                        discardedAmount,
                        beforeCarrierMutation,
                    )
                ItemStateWriteResult.MissingTarget -> rollback(inventory, original, "MissingTarget")
                is ItemStateWriteResult.Rejected -> rollback(inventory, original, persisted.reason, rejected = true)
                is ItemStateWriteResult.Failed -> rollback(inventory, original, persisted.errorType)
            }
        } catch (error: RuntimeException) {
            rollback(inventory, original, error.javaClass.simpleName)
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun finishAppliedTransaction(
        item: Item,
        inventory: Inventory,
        original: Array<ItemStack?>,
        state: ItemState,
        plan: InventoryInsertionPlan,
        remainingAmount: Long,
        discardedAmount: Long,
        beforeCarrierMutation: (consumedAmount: Long, remainingAmount: Long) -> Unit,
    ): VirtualItemPickupOutcome =
        try {
            beforeCarrierMutation(plan.insertedAmount + discardedAmount, remainingAmount)
            if (remainingAmount == 0L) {
                item.remove()
            } else {
                val virtualSettings =
                    settingsRepository
                        .settings()
                        .virtualStacking
                item.applyVirtualCarrierAmount(
                    VirtualItemAmount.of(remainingAmount),
                    virtualSettings,
                    modeResolver,
                )
            }
            VirtualItemPickupOutcome.Inserted(plan.insertedAmount, remainingAmount, discardedAmount)
        } catch (error: RuntimeException) {
            if (
                remainingAmount != 0L &&
                stateRepository.save(item.uniqueId, state) != ItemStateWriteResult.Applied
            ) {
                rollback(inventory, original, "CarrierRollbackFailed")
            } else {
                rollback(inventory, original, "Carrier${error.javaClass.simpleName}")
            }
        }

    private fun capacities(
        contents: Array<ItemStack?>,
        carrier: ItemStack,
        inventoryMaximum: Int,
    ): List<InventorySlotCapacity> {
        val nativeMaximum = minOf(carrier.maxStackSize, inventoryMaximum)
        return contents.mapIndexed { index, existing ->
            when {
                existing == null || existing.type == Material.AIR ->
                    InventorySlotCapacity(index, 0, nativeMaximum, acceptsItem = true)
                existing.isSimilar(carrier) ->
                    InventorySlotCapacity(
                        index,
                        existing.amount,
                        maxOf(existing.amount, minOf(existing.maxStackSize, inventoryMaximum)),
                        acceptsItem = true,
                    )
                else ->
                    InventorySlotCapacity(
                        index,
                        existing.amount,
                        maxOf(existing.amount, existing.maxStackSize),
                        acceptsItem = false,
                    )
            }
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun rollback(
        inventory: Inventory,
        original: Array<ItemStack?>,
        reason: String,
        rejected: Boolean = false,
    ): VirtualItemPickupOutcome =
        try {
            inventory.storageContents = original
            if (rejected) VirtualItemPickupOutcome.Rejected(reason) else VirtualItemPickupOutcome.Failed(reason)
        } catch (error: RuntimeException) {
            VirtualItemPickupOutcome.Failed("Rollback${error.javaClass.simpleName}")
        }

    private fun resolveVirtualState(
        item: Item,
        trustDirectEventTarget: Boolean = false,
    ): VirtualStateResolution {
        if (!trustDirectEventTarget && !item.isValid) return VirtualStateResolution.Failed("InvalidItemEntity")
        return when (val loaded = stateRepository.load(item.uniqueId)) {
            is ItemStateLoadResult.Loaded -> {
                val amount = loaded.state.virtualAmount
                when {
                    amount == null -> VirtualStateResolution.NotVirtual
                    else -> VirtualStateResolution.Virtual(loaded.state)
                }
            }
            ItemStateLoadResult.Absent -> VirtualStateResolution.NotVirtual
            is ItemStateLoadResult.Legacy -> VirtualStateResolution.Rejected("LegacyStateRequiresMigration")
            is ItemStateLoadResult.Invalid -> VirtualStateResolution.Rejected("InvalidState")
            is ItemStateLoadResult.UnsupportedSchema ->
                VirtualStateResolution.Rejected("UnsupportedSchema:${loaded.actualVersion}")
            ItemStateLoadResult.MissingTarget -> VirtualStateResolution.Failed("MissingTarget")
            is ItemStateLoadResult.Failed -> VirtualStateResolution.Failed(loaded.errorType)
        }
    }

    private val activePlayerTransactions = mutableSetOf<PlayerItemTransactionKey>()
}

public sealed interface VirtualItemPickupOutcome {
    public data object NotApplicable : VirtualItemPickupOutcome

    public data object NotVirtual : VirtualItemPickupOutcome

    public data object NoCapacity : VirtualItemPickupOutcome

    public data class Inserted(
        public val insertedAmount: Long,
        public val remainingAmount: Long,
        public val discardedAmount: Long = 0,
    ) : VirtualItemPickupOutcome

    public data class NativePickupPrepared(
        public val sourceEntityId: UUID,
        public val itemsToRefresh: List<Item>,
        public val reconcileAfterNativePickup: Boolean,
    ) : VirtualItemPickupOutcome

    public data class Rejected(
        public val reason: String,
    ) : VirtualItemPickupOutcome

    public data class Failed(
        public val errorType: String,
    ) : VirtualItemPickupOutcome
}

public sealed interface NonPlayerPickupReconciliationOutcome {
    public data object NotRequired : NonPlayerPickupReconciliationOutcome

    public data object MissingTarget : NonPlayerPickupReconciliationOutcome

    public data class Applied(
        public val item: Item,
    ) : NonPlayerPickupReconciliationOutcome

    public data class Failed(
        public val errorType: String,
    ) : NonPlayerPickupReconciliationOutcome
}

private data class PlayerItemTransactionKey(
    val playerUuid: UUID,
    val itemUuid: UUID,
)

private sealed interface VirtualStateResolution {
    data object NotVirtual : VirtualStateResolution

    data class Virtual(
        val state: ItemState,
    ) : VirtualStateResolution

    data class Rejected(
        val reason: String,
    ) : VirtualStateResolution

    data class Failed(
        val errorType: String,
    ) : VirtualStateResolution

    fun toPickupOutcome(): VirtualItemPickupOutcome =
        when (this) {
            NotVirtual -> VirtualItemPickupOutcome.NotVirtual
            is Virtual -> error("virtual resolution must execute a transaction")
            is Rejected -> VirtualItemPickupOutcome.Rejected(reason)
            is Failed -> VirtualItemPickupOutcome.Failed(errorType)
        }
}
