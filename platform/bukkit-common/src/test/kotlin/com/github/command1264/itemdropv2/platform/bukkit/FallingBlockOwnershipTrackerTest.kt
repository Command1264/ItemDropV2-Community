package com.github.command1264.itemdropv2.platform.bukkit

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

class FallingBlockOwnershipTrackerTest {
    @Test
    fun `binds adjacent source positions to their own players and falling entities`() {
        val tracker = FallingBlockOwnershipTracker()
        tracker.record(listOf(source(x = 10, ownerUuid = FIRST_OWNER), source(x = 11, ownerUuid = SECOND_OWNER)))

        assertEquals(FIRST_OWNER, tracker.bind(falling(x = 10, entityId = FIRST_ENTITY)))
        assertEquals(SECOND_OWNER, tracker.bind(falling(x = 11, entityId = SECOND_ENTITY)))
        assertEquals(FIRST_OWNER, tracker.ownerOf(FIRST_ENTITY))
        assertEquals(SECOND_OWNER, tracker.ownerOf(SECOND_ENTITY))
    }

    @Test
    fun `newer source context exclusively replaces an overlapping position`() {
        val tracker = FallingBlockOwnershipTracker()
        val oldContext = tracker.record(listOf(source(ownerUuid = FIRST_OWNER)))
        tracker.record(listOf(source(ownerUuid = SECOND_OWNER)))

        assertFalse(tracker.expire(oldContext))
        assertEquals(SECOND_OWNER, tracker.bind(falling()))
    }

    @Test
    fun `requires exact world position and material`() {
        val tracker = FallingBlockOwnershipTracker()
        tracker.record(listOf(source()))

        assertNull(tracker.bind(falling(worldId = OTHER_WORLD)))
        assertNull(tracker.bind(falling(x = 11)))
        assertNull(tracker.bind(falling(materialName = "GRAVEL")))
        assertEquals(FIRST_OWNER, tracker.bind(falling()))
    }

    @Test
    fun `landing and item drop consume the falling entity owner`() {
        val tracker = FallingBlockOwnershipTracker()
        tracker.record(listOf(source()))
        tracker.bind(falling())

        assertTrue(tracker.complete(FIRST_ENTITY))
        assertNull(tracker.ownerOf(FIRST_ENTITY))
        assertFalse(tracker.complete(FIRST_ENTITY))
    }

    @Test
    fun `expired or natural falling blocks remain unowned`() {
        val tracker = FallingBlockOwnershipTracker()
        val contextId = tracker.record(listOf(source()))

        assertTrue(tracker.expire(contextId))
        assertNull(tracker.bind(falling()))
        assertNull(tracker.ownerOf(FIRST_ENTITY))
    }

    private fun source(
        worldId: UUID = WORLD,
        x: Int = 10,
        ownerUuid: UUID = FIRST_OWNER,
    ): FallingBlockSource =
        FallingBlockSource(
            worldId = worldId,
            x = x,
            y = 70,
            z = 20,
            materialName = "SAND",
            ownerUuid = ownerUuid,
        )

    private fun falling(
        entityId: UUID = FIRST_ENTITY,
        worldId: UUID = WORLD,
        x: Int = 10,
        materialName: String = "SAND",
    ): FallingBlockSpawn =
        FallingBlockSpawn(
            entityId = entityId,
            worldId = worldId,
            x = x,
            y = 70,
            z = 20,
            materialName = materialName,
        )

    private companion object {
        private val WORLD = UUID.fromString("30000000-0000-0000-0000-000000000001")
        private val OTHER_WORLD = UUID.fromString("30000000-0000-0000-0000-000000000002")
        private val FIRST_OWNER = UUID.fromString("30000000-0000-0000-0000-000000000011")
        private val SECOND_OWNER = UUID.fromString("30000000-0000-0000-0000-000000000012")
        private val FIRST_ENTITY = UUID.fromString("30000000-0000-0000-0000-000000000021")
        private val SECOND_ENTITY = UUID.fromString("30000000-0000-0000-0000-000000000022")
    }
}
