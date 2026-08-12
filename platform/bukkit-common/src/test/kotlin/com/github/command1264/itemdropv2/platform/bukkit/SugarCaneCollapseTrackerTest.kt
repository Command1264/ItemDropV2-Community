package com.github.command1264.itemdropv2.platform.bukkit

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

class SugarCaneCollapseTrackerTest {
    @Test
    fun `claims each snapshotted sugar cane position once`() {
        val tracker = SugarCaneCollapseTracker()
        val contextId = tracker.record(context(minY = 64, maxY = 66))

        assertEquals(FIRST_OWNER_ID, tracker.claim(spawn(y = 64))?.ownerUuid)
        assertNull(tracker.claim(spawn(y = 64, entityId = ITEM_ID_4)))
        assertEquals(FIRST_OWNER_ID, tracker.claim(spawn(y = 65))?.ownerUuid)
        assertEquals(FIRST_OWNER_ID, tracker.claim(spawn(y = 66))?.ownerUuid)
        assertTrue(tracker.expire(contextId))
    }

    @Test
    fun `keeps simultaneous adjacent columns assigned to their own players`() {
        val tracker = SugarCaneCollapseTracker()
        tracker.record(context(x = 10, ownerUuid = FIRST_OWNER_ID, minY = 64, maxY = 66))
        tracker.record(context(x = 11, ownerUuid = SECOND_OWNER_ID, minY = 64, maxY = 66))

        assertEquals(SECOND_OWNER_ID, tracker.claim(spawn(x = 11, y = 65, entityId = ITEM_ID_4))?.ownerUuid)
        assertEquals(FIRST_OWNER_ID, tracker.claim(spawn(x = 10, y = 65))?.ownerUuid)
        assertNull(tracker.claim(spawn(x = 12, y = 65)))
    }

    @Test
    fun `newer overlapping break owns only positions it snapshotted`() {
        val tracker = SugarCaneCollapseTracker()
        tracker.record(context(ownerUuid = FIRST_OWNER_ID, minY = 64, maxY = 66))
        tracker.record(context(ownerUuid = SECOND_OWNER_ID, minY = 66, maxY = 66))

        assertEquals(SECOND_OWNER_ID, tracker.claim(spawn(y = 66))?.ownerUuid)
        assertEquals(FIRST_OWNER_ID, tracker.claim(spawn(y = 65))?.ownerUuid)
    }

    @Test
    fun `natural and expired sugar cane drops remain unclaimed`() {
        val tracker = SugarCaneCollapseTracker()

        assertNull(tracker.claim(spawn(y = 64)))
        val contextId = tracker.record(context(minY = 64, maxY = 66))
        assertTrue(tracker.expire(contextId))
        assertNull(tracker.claim(spawn(y = 65)))
    }

    @Test
    fun `bounds contexts and clears claimed entities with lifecycle`() {
        val tracker = SugarCaneCollapseTracker(maxContexts = 1)
        val first = tracker.record(context(x = 10, minY = 64, maxY = 64))
        tracker.record(context(x = 20, minY = 64, maxY = 64))

        assertFalse(tracker.expire(first))
        tracker.claim(spawn(x = 20, y = 64))
        assertTrue(tracker.wasClaimed(ITEM_ID_1))
        tracker.clear()

        assertFalse(tracker.wasClaimed(ITEM_ID_1))
    }

    private fun context(
        x: Int = 10,
        ownerUuid: UUID = FIRST_OWNER_ID,
        minY: Int,
        maxY: Int,
    ): SugarCaneCollapseContext =
        SugarCaneCollapseContext(
            worldId = WORLD_ID,
            x = x,
            z = 20,
            minY = minY,
            maxY = maxY,
            ownerUuid = ownerUuid,
        )

    private fun spawn(
        x: Int = 10,
        y: Int,
        entityId: UUID =
            when (y) {
                64 -> ITEM_ID_1
                65 -> ITEM_ID_2
                else -> ITEM_ID_3
            },
    ): SugarCaneItemSpawn = SugarCaneItemSpawn(entityId, WORLD_ID, x, 20, y)

    private companion object {
        private val WORLD_ID = UUID.fromString("00000000-0000-0000-0000-000000000010")
        private val FIRST_OWNER_ID = UUID.fromString("00000000-0000-0000-0000-000000000020")
        private val SECOND_OWNER_ID = UUID.fromString("00000000-0000-0000-0000-000000000030")
        private val ITEM_ID_1 = UUID.fromString("00000000-0000-0000-0000-000000000101")
        private val ITEM_ID_2 = UUID.fromString("00000000-0000-0000-0000-000000000102")
        private val ITEM_ID_3 = UUID.fromString("00000000-0000-0000-0000-000000000103")
        private val ITEM_ID_4 = UUID.fromString("00000000-0000-0000-0000-000000000104")
    }
}
