package com.github.command1264.itemdropv2.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class InventoryInsertionPlannerTest {
    private val planner = InventoryInsertionPlanner()

    @Test
    fun `fills partial stacks before empty slots using legal native amounts`() {
        val plan =
            planner.plan(
                virtualAmount = VirtualItemAmount.of(100),
                transactionLimit = 100,
                slots =
                    listOf(
                        InventorySlotCapacity(0, currentAmount = 60, maximumAmount = 64, acceptsItem = true),
                        InventorySlotCapacity(1, currentAmount = 0, maximumAmount = 64, acceptsItem = true),
                        InventorySlotCapacity(2, currentAmount = 0, maximumAmount = 64, acceptsItem = true),
                    ),
            )

        assertEquals(
            listOf(
                InventorySlotMutation(0, 64),
                InventorySlotMutation(1, 64),
                InventorySlotMutation(2, 32),
            ),
            plan.mutations,
        )
        assertEquals(100L, plan.insertedAmount)
        assertEquals(0L, plan.remainingAmount)
    }

    @Test
    fun `supports native max sixteen and reports capacity remainder`() {
        val plan =
            planner.plan(
                VirtualItemAmount.of(40),
                transactionLimit = 40,
                slots =
                    listOf(
                        InventorySlotCapacity(0, 15, 16, true),
                        InventorySlotCapacity(1, 0, 16, true),
                    ),
            )

        assertEquals(listOf(InventorySlotMutation(0, 16), InventorySlotMutation(1, 16)), plan.mutations)
        assertEquals(17L, plan.insertedAmount)
        assertEquals(23L, plan.remainingAmount)
    }

    @Test
    fun `hopper limit moves at most one native batch`() {
        val plan =
            planner.plan(
                VirtualItemAmount.of(8_192),
                transactionLimit = 64,
                slots =
                    listOf(
                        InventorySlotCapacity(0, 0, 64, true),
                        InventorySlotCapacity(1, 0, 64, true),
                    ),
            )

        assertEquals(listOf(InventorySlotMutation(0, 64)), plan.mutations)
        assertEquals(64L, plan.insertedAmount)
        assertEquals(8_128L, plan.remainingAmount)
    }

    @Test
    fun `full or incompatible inventory inserts nothing`() {
        val plan =
            planner.plan(
                VirtualItemAmount.of(8),
                transactionLimit = 8,
                slots =
                    listOf(
                        InventorySlotCapacity(0, 64, 64, true),
                        InventorySlotCapacity(1, 0, 64, false),
                    ),
            )

        assertEquals(emptyList<InventorySlotMutation>(), plan.mutations)
        assertEquals(0L, plan.insertedAmount)
        assertEquals(8L, plan.remainingAmount)
    }

    @Test
    fun `validates slot and transaction boundaries`() {
        assertThrows(IllegalArgumentException::class.java) {
            InventorySlotCapacity(-1, 0, 64, true)
        }
        assertThrows(IllegalArgumentException::class.java) {
            planner.plan(
                VirtualItemAmount.ONE,
                transactionLimit = 0,
                slots = listOf(InventorySlotCapacity(0, 0, 64, true)),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            planner.plan(
                VirtualItemAmount.ONE,
                transactionLimit = 1,
                slots =
                    listOf(
                        InventorySlotCapacity(0, 0, 64, true),
                        InventorySlotCapacity(0, 0, 64, true),
                    ),
            )
        }
    }
}
