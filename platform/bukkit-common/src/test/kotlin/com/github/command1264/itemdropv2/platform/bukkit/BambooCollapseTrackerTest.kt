package com.github.command1264.itemdropv2.platform.bukkit

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

class BambooCollapseTrackerTest {
    @Test
    fun `prefers the same source height without shifting the entire column down`() {
        val tracker = BambooCollapseTracker()
        tracker.record(
            BambooCollapseContext(
                worldId = WORLD_ID,
                x = 10,
                z = 20,
                minY = 64,
                maxY = 66,
                ownerUuid = FIRST_OWNER_ID,
            ),
        )

        assertEquals(FIRST_OWNER_ID, tracker.claim(spawn(y = 64))?.ownerUuid)
        assertEquals(FIRST_OWNER_ID, tracker.claim(spawn(y = 65))?.ownerUuid)
        assertEquals(FIRST_OWNER_ID, tracker.claim(spawn(y = 66))?.ownerUuid)
    }

    @Test
    fun `claims each snapshotted bamboo position once`() {
        val tracker = BambooCollapseTracker()
        val contextId = tracker.record(context(minY = 64, maxY = 79))

        (64..79).forEachIndexed { index, y ->
            assertEquals(FIRST_OWNER_ID, tracker.claim(spawn(y = y, entityId = itemId(index)))?.ownerUuid)
        }
        assertNull(tracker.claim(spawn(y = 79, entityId = ITEM_ID_DUPLICATE)))
        assertTrue(tracker.expire(contextId))
    }

    @Test
    fun `claims bamboo drops spawned one block above their source positions`() {
        val tracker = BambooCollapseTracker()
        val contextId = tracker.record(context(minY = 64, maxY = 66))

        assertEquals(FIRST_OWNER_ID, tracker.claim(spawn(y = 65, entityId = itemId(1)))?.ownerUuid)
        assertEquals(FIRST_OWNER_ID, tracker.claim(spawn(y = 66, entityId = itemId(2)))?.ownerUuid)
        assertEquals(FIRST_OWNER_ID, tracker.claim(spawn(y = 67, entityId = itemId(3)))?.ownerUuid)
        assertTrue(tracker.expire(contextId))
    }

    @Test
    fun `extends an active column for sequential drops above the snapshotted top`() {
        val tracker = BambooCollapseTracker()
        tracker.record(context(minY = 64, maxY = 66))

        (64..66).forEachIndexed { index, y ->
            assertEquals(FIRST_OWNER_ID, tracker.claim(spawn(y = y, entityId = itemId(index)))?.ownerUuid)
        }
        (67..70).forEachIndexed { index, y ->
            assertEquals(FIRST_OWNER_ID, tracker.claim(spawn(y = y, entityId = itemId(10 + index)))?.ownerUuid)
        }
    }

    @Test
    fun `keeps elevated drops from adjacent bamboo columns assigned to their own players`() {
        val tracker = BambooCollapseTracker()
        tracker.record(context(x = 10, ownerUuid = FIRST_OWNER_ID, minY = 64, maxY = 66))
        tracker.record(context(x = 11, ownerUuid = SECOND_OWNER_ID, minY = 64, maxY = 66))

        assertEquals(SECOND_OWNER_ID, tracker.claim(spawn(x = 11, y = 67, entityId = itemId(4)))?.ownerUuid)
        assertEquals(FIRST_OWNER_ID, tracker.claim(spawn(x = 10, y = 67, entityId = itemId(5)))?.ownerUuid)
        assertNull(tracker.claim(spawn(x = 12, y = 67, entityId = itemId(6))))
    }

    @Test
    fun `keeps simultaneous adjacent bamboo columns assigned to their own players`() {
        val tracker = BambooCollapseTracker()
        tracker.record(context(x = 10, ownerUuid = FIRST_OWNER_ID, minY = 64, maxY = 66))
        tracker.record(context(x = 11, ownerUuid = SECOND_OWNER_ID, minY = 64, maxY = 66))

        assertEquals(SECOND_OWNER_ID, tracker.claim(spawn(x = 11, y = 65, entityId = ITEM_ID_DUPLICATE))?.ownerUuid)
        assertEquals(FIRST_OWNER_ID, tracker.claim(spawn(x = 10, y = 65))?.ownerUuid)
        assertNull(tracker.claim(spawn(x = 12, y = 65)))
    }

    @Test
    fun `newer overlapping break owns only positions it snapshotted`() {
        val tracker = BambooCollapseTracker()
        tracker.record(context(ownerUuid = FIRST_OWNER_ID, minY = 64, maxY = 66))
        tracker.record(context(ownerUuid = SECOND_OWNER_ID, minY = 66, maxY = 66))

        assertEquals(SECOND_OWNER_ID, tracker.claim(spawn(y = 66))?.ownerUuid)
        assertEquals(FIRST_OWNER_ID, tracker.claim(spawn(y = 65))?.ownerUuid)
    }

    @Test
    fun `natural and expired bamboo drops remain unclaimed`() {
        val tracker = BambooCollapseTracker()

        assertNull(tracker.claim(spawn(y = 64)))
        val contextId = tracker.record(context(minY = 64, maxY = 79))
        assertTrue(tracker.expire(contextId))
        assertNull(tracker.claim(spawn(y = 65)))
    }

    @Test
    fun `protects claimed bamboo from native merge until context expiry`() {
        val tracker = BambooCollapseTracker()
        val contextId = tracker.record(context(minY = 64, maxY = 66))

        tracker.claim(spawn(y = 64))
        assertTrue(tracker.isMergeProtected(ITEM_ID_1))

        assertTrue(tracker.expire(contextId))
        assertFalse(tracker.isMergeProtected(ITEM_ID_1))
    }

    @Test
    fun `bounds contexts and clears claimed entities with lifecycle`() {
        val tracker = BambooCollapseTracker(maxContexts = 1)
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
    ): BambooCollapseContext =
        BambooCollapseContext(
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
    ): BambooItemSpawn = BambooItemSpawn(entityId, WORLD_ID, x, 20, y)

    private fun itemId(index: Int): UUID = UUID(0L, 1_000L + index)

    private companion object {
        private val WORLD_ID = UUID.fromString("00000000-0000-0000-0000-000000000010")
        private val FIRST_OWNER_ID = UUID.fromString("00000000-0000-0000-0000-000000000020")
        private val SECOND_OWNER_ID = UUID.fromString("00000000-0000-0000-0000-000000000030")
        private val ITEM_ID_1 = UUID.fromString("00000000-0000-0000-0000-000000000101")
        private val ITEM_ID_2 = UUID.fromString("00000000-0000-0000-0000-000000000102")
        private val ITEM_ID_3 = UUID.fromString("00000000-0000-0000-0000-000000000103")
        private val ITEM_ID_DUPLICATE = UUID.fromString("00000000-0000-0000-0000-000000000104")
    }
}
