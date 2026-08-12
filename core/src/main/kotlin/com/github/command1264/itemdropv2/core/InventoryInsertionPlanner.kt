package com.github.command1264.itemdropv2.core

public data class InventorySlotCapacity(
    public val index: Int,
    public val currentAmount: Int,
    public val maximumAmount: Int,
    public val acceptsItem: Boolean,
) {
    init {
        require(index >= 0) { "inventory slot index must not be negative" }
        require(maximumAmount > 0) { "inventory slot maximum must be positive" }
        require(currentAmount in 0..maximumAmount) { "inventory slot amount exceeds its maximum" }
    }
}

public data class InventorySlotMutation(
    public val index: Int,
    public val newAmount: Int,
) {
    init {
        require(index >= 0) { "inventory slot index must not be negative" }
        require(newAmount > 0) { "inventory slot mutation amount must be positive" }
    }
}

public data class InventoryInsertionPlan(
    public val mutations: List<InventorySlotMutation>,
    public val insertedAmount: Long,
    public val remainingAmount: Long,
) {
    init {
        require(insertedAmount >= 0) { "inserted amount must not be negative" }
        require(remainingAmount >= 0) { "remaining amount must not be negative" }
    }
}

public class InventoryInsertionPlanner {
    public fun plan(
        virtualAmount: VirtualItemAmount,
        transactionLimit: Long,
        slots: List<InventorySlotCapacity>,
    ): InventoryInsertionPlan {
        require(transactionLimit > 0) { "inventory transaction limit must be positive" }
        require(slots.map(InventorySlotCapacity::index).distinct().size == slots.size) {
            "inventory slot indexes must be unique"
        }

        var pending = minOf(virtualAmount.value, transactionLimit)
        val mutations = mutableListOf<InventorySlotMutation>()
        val orderedSlots =
            slots
                .asSequence()
                .filter(InventorySlotCapacity::acceptsItem)
                .filter { it.currentAmount < it.maximumAmount }
                .sortedBy { if (it.currentAmount > 0) PARTIAL_STACK_ORDER else EMPTY_SLOT_ORDER }
        for (slot in orderedSlots) {
            if (pending == 0L) break
            val capacity = slot.maximumAmount - slot.currentAmount
            val moved = minOf(pending, capacity.toLong()).toInt()
            mutations += InventorySlotMutation(slot.index, slot.currentAmount + moved)
            pending -= moved
        }
        val inserted = minOf(virtualAmount.value, transactionLimit) - pending
        return InventoryInsertionPlan(
            mutations = mutations,
            insertedAmount = inserted,
            remainingAmount = virtualAmount.value - inserted,
        )
    }

    private companion object {
        private const val PARTIAL_STACK_ORDER = 0
        private const val EMPTY_SLOT_ORDER = 1
    }
}
