package com.github.command1264.itemdropv2.platform.bukkit

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

class CactusCollapseTrackerTest {
    @Test
    fun `claims every expected cactus drop in the same column once`() {
        val tracker = CactusCollapseTracker()
        val contextId = tracker.record(context(minY = 64, maxY = 66))

        assertEquals(OWNER_ID, tracker.claim(spawn(y = 64))?.ownerUuid)
        assertEquals(OWNER_ID, tracker.claim(spawn(y = 65))?.ownerUuid)
        assertEquals(OWNER_ID, tracker.claim(spawn(y = 66))?.ownerUuid)
        assertNull(tracker.claim(spawn(y = 66, entityId = ITEM_ID_4)))
        assertTrue(tracker.wasClaimed(ITEM_ID_1))
        assertFalse(tracker.wasClaimed(ITEM_ID_4))
        assertTrue(tracker.expire(contextId))
    }

    @Test
    fun `does not claim adjacent natural or expired cactus drops`() {
        val tracker = CactusCollapseTracker()
        val contextId = tracker.record(context(minY = 64, maxY = 66))

        assertNull(tracker.claim(spawn(x = 11, y = 64)))
        assertTrue(tracker.expire(contextId))
        assertNull(tracker.claim(spawn(y = 64)))
    }

    @Test
    fun `newer overlapping break owns each overlapping position exclusively`() {
        val tracker = CactusCollapseTracker()
        tracker.record(context(ownerUuid = OWNER_ID, minY = 64, maxY = 66))
        tracker.record(context(ownerUuid = SECOND_OWNER_ID, minY = 66, maxY = 66))

        assertEquals(SECOND_OWNER_ID, tracker.claim(spawn(y = 66))?.ownerUuid)
        assertNull(tracker.claim(spawn(y = 66, entityId = ITEM_ID_4)))
        assertEquals(OWNER_ID, tracker.claim(spawn(y = 65))?.ownerUuid)
    }

    @Test
    fun `claims cactus flower only at the recorded cap position`() {
        val tracker = CactusCollapseTracker()
        tracker.record(
            context(
                minY = 64,
                maxY = 67,
                expectedItemNamesByY =
                    mapOf(
                        64 to setOf("CACTUS"),
                        65 to setOf("CACTUS"),
                        66 to setOf("CACTUS"),
                        67 to setOf("CACTUS_FLOWER"),
                    ),
            ),
        )

        assertNull(tracker.claim(spawn(y = 66, materialName = "CACTUS_FLOWER")))
        assertEquals(
            OWNER_ID,
            tracker.claim(spawn(y = 67, materialName = "CACTUS_FLOWER"))?.ownerUuid,
        )
        assertNull(tracker.claim(spawn(y = 67, entityId = ITEM_ID_4, materialName = "CACTUS")))
    }

    @Test
    fun `bounds active contexts and clears claims with lifecycle`() {
        val tracker = CactusCollapseTracker(maxContexts = 1)
        val first = tracker.record(context(x = 10, minY = 64, maxY = 64))
        tracker.record(context(x = 20, minY = 64, maxY = 64))

        assertFalse(tracker.expire(first))
        tracker.claim(spawn(x = 20, y = 64))
        tracker.clear()

        assertFalse(tracker.wasClaimed(ITEM_ID_1))
    }

    @Test
    fun `protects claimed cactus from native merge until context expiry`() {
        val tracker = CactusCollapseTracker()
        val contextId = tracker.record(context(minY = 64, maxY = 66))

        tracker.claim(spawn(y = 64))
        assertTrue(tracker.isMergeProtected(ITEM_ID_1))

        assertTrue(tracker.expire(contextId))
        assertFalse(tracker.isMergeProtected(ITEM_ID_1))
    }

    private fun context(
        x: Int = 10,
        ownerUuid: UUID = OWNER_ID,
        minY: Int,
        maxY: Int,
        expectedItemNamesByY: Map<Int, Set<String>> =
            (minY..maxY).associateWith { setOf("CACTUS") },
    ): CactusCollapseContext =
        CactusCollapseContext(
            worldId = WORLD_ID,
            x = x,
            z = 20,
            minY = minY,
            maxY = maxY,
            ownerUuid = ownerUuid,
            expectedItemNamesByY = expectedItemNamesByY,
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
        materialName: String = "CACTUS",
    ): CactusItemSpawn = CactusItemSpawn(entityId, WORLD_ID, x, 20, y, materialName)

    private companion object {
        private val WORLD_ID = UUID.fromString("00000000-0000-0000-0000-000000000010")
        private val OWNER_ID = UUID.fromString("00000000-0000-0000-0000-000000000020")
        private val SECOND_OWNER_ID = UUID.fromString("00000000-0000-0000-0000-000000000030")
        private val ITEM_ID_1 = UUID.fromString("00000000-0000-0000-0000-000000000101")
        private val ITEM_ID_2 = UUID.fromString("00000000-0000-0000-0000-000000000102")
        private val ITEM_ID_3 = UUID.fromString("00000000-0000-0000-0000-000000000103")
        private val ITEM_ID_4 = UUID.fromString("00000000-0000-0000-0000-000000000104")
    }
}
