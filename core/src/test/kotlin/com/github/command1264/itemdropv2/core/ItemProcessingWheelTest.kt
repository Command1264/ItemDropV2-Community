package com.github.command1264.itemdropv2.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

class ItemProcessingWheelTest {
    @Test
    fun `burst registrations wait a complete second and keep the same phase`() {
        val wheel = ItemProcessingWheel()
        val entityIds = (1..103).map(::uuid)

        val assigned = entityIds.associateWith(wheel::register)
        val firstNineteenTicks =
            (1 until ItemProcessingWheel.SLOT_COUNT).flatMap { wheel.advance() }

        assertTrue(firstNineteenTicks.isEmpty())
        assertEquals(entityIds, wheel.advance())
        assigned.forEach { (entityId, slot) -> assertEquals(slot, wheel.register(entityId)) }
        assertEquals(
            entityIds,
            (1..ItemProcessingWheel.SLOT_COUNT).flatMap { wheel.advance() },
        )
    }

    @Test
    fun `registrations on different ticks retain their individual one-second phase`() {
        val wheel = ItemProcessingWheel()
        val first = uuid(1)
        val second = uuid(2)

        wheel.register(first)
        repeat(7) { assertTrue(wheel.advance().isEmpty()) }
        wheel.register(second)
        repeat(12) { assertTrue(wheel.advance().isEmpty()) }

        assertEquals(listOf(first), wheel.advance())
        repeat(6) { assertTrue(wheel.advance().isEmpty()) }
        assertEquals(listOf(second), wheel.advance())
    }

    @Test
    fun `overloaded aligned phase spills into least loaded slots nearest to one second`() {
        val wheel = ItemProcessingWheel(maximumItemsPerTick = { 2 })
        val entityIds = (1..6).map(::uuid)

        val assignedSlots = entityIds.map(wheel::register)

        assertEquals(listOf(19, 19, 18, 17, 16, 15), assignedSlots)
        assertEquals(
            listOf(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 2),
            wheel.slotLoads(),
        )
    }

    @Test
    fun `overload spill prefers the nearest phase when least loaded slots are tied`() {
        val wheel = ItemProcessingWheel(maximumItemsPerTick = { 1 })
        val first = uuid(1)
        val second = uuid(2)
        val third = uuid(3)

        assertEquals(19, wheel.register(first))
        assertEquals(18, wheel.register(second))
        assertEquals(17, wheel.register(third))
    }

    @Test
    fun `updated processing budget applies to later registrations without moving tracked items`() {
        var maximumItemsPerTick = 2
        val wheel = ItemProcessingWheel(maximumItemsPerTick = { maximumItemsPerTick })

        assertEquals(19, wheel.register(uuid(1)))
        assertEquals(19, wheel.register(uuid(2)))
        assertEquals(18, wheel.register(uuid(3)))

        maximumItemsPerTick = 4

        assertEquals(19, wheel.register(uuid(4)))
        assertEquals(19, wheel.register(uuid(1)))
    }

    @Test
    fun `forgotten items stay frozen before their aligned tick`() {
        val wheel = ItemProcessingWheel()
        val first = uuid(1)
        val second = uuid(2)
        wheel.register(first)
        wheel.register(second)
        wheel.forget(first)

        val processed = (1..ItemProcessingWheel.SLOT_COUNT).flatMap { wheel.advance() }

        assertEquals(listOf(second), processed)
    }

    private fun uuid(value: Int): UUID = UUID(0, value.toLong())
}
