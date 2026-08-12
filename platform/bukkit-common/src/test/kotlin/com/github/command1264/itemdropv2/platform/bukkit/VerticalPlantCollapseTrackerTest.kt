package com.github.command1264.itemdropv2.platform.bukkit

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

class VerticalPlantCollapseTrackerTest {
    @Test
    fun `limits direct breaks to the detached side of each growth direction`() {
        assertEquals(64..68, affectedVerticalPlantRange(64, 68, 63, false, 1))
        assertEquals(66..68, affectedVerticalPlantRange(64, 68, 66, true, 1))
        assertEquals(64..66, affectedVerticalPlantRange(64, 68, 66, true, -1))
        assertEquals(64..68, affectedVerticalPlantRange(64, 68, 69, false, -1))
    }

    @Test
    fun `claims upward column drops by exact material and position`() {
        val tracker = VerticalPlantCollapseTracker()
        val contextId =
            tracker.record(
                context(
                    expectedItemsByY =
                        mapOf(
                            64 to setOf("KELP"),
                            65 to setOf("KELP"),
                            66 to setOf("KELP"),
                        ),
                ),
            )

        assertEquals(OWNER_ID, tracker.claim(spawn(y = 66, materialName = "KELP"))?.ownerUuid)
        assertNull(tracker.claim(spawn(y = 65, materialName = "CACTUS")))
        assertEquals(OWNER_ID, tracker.claim(spawn(y = 65, materialName = "KELP"))?.ownerUuid)
        assertEquals(OWNER_ID, tracker.claim(spawn(y = 64, materialName = "KELP"))?.ownerUuid)
        assertTrue(tracker.expire(contextId))
    }

    @Test
    fun `claims downward column without depending on spawn order`() {
        val tracker = VerticalPlantCollapseTracker()
        tracker.record(
            context(
                expectedItemsByY =
                    mapOf(
                        61 to setOf("WEEPING_VINES"),
                        62 to setOf("WEEPING_VINES"),
                        63 to setOf("WEEPING_VINES"),
                    ),
            ),
        )

        assertEquals(OWNER_ID, tracker.claim(spawn(y = 61, materialName = "WEEPING_VINES"))?.ownerUuid)
        assertEquals(OWNER_ID, tracker.claim(spawn(y = 63, materialName = "WEEPING_VINES"))?.ownerUuid)
        assertEquals(OWNER_ID, tracker.claim(spawn(y = 62, materialName = "WEEPING_VINES"))?.ownerUuid)
    }

    @Test
    fun `keeps adjacent players and overlapping positions isolated`() {
        val tracker = VerticalPlantCollapseTracker()
        tracker.record(context(x = 10, ownerUuid = OWNER_ID))
        tracker.record(context(x = 11, ownerUuid = SECOND_OWNER_ID))
        tracker.record(
            context(
                x = 10,
                ownerUuid = SECOND_OWNER_ID,
                expectedItemsByY = mapOf(66 to setOf("KELP")),
            ),
        )

        assertEquals(SECOND_OWNER_ID, tracker.claim(spawn(x = 10, y = 66))?.ownerUuid)
        assertEquals(OWNER_ID, tracker.claim(spawn(x = 10, y = 65))?.ownerUuid)
        assertEquals(SECOND_OWNER_ID, tracker.claim(spawn(x = 11, y = 65))?.ownerUuid)
        assertNull(tracker.claim(spawn(x = 12, y = 65)))
    }

    @Test
    fun `does not claim one entity twice or after expiry`() {
        val tracker = VerticalPlantCollapseTracker()
        val contextId = tracker.record(context())
        val item = spawn(y = 64)

        assertEquals(OWNER_ID, tracker.claim(item)?.ownerUuid)
        assertNull(tracker.claim(item))
        assertTrue(tracker.expire(contextId))
        assertNull(tracker.claim(spawn(y = 65)))
    }

    @Test
    fun `protects claimed vertical plant from native merge until context expiry`() {
        val tracker = VerticalPlantCollapseTracker()
        val contextId = tracker.record(context())
        val item = spawn(y = 64)

        tracker.claim(item)
        assertTrue(tracker.isMergeProtected(item.entityId))

        assertTrue(tracker.expire(contextId))
        assertFalse(tracker.isMergeProtected(item.entityId))
    }

    private fun context(
        x: Int = 10,
        ownerUuid: UUID = OWNER_ID,
        expectedItemsByY: Map<Int, Set<String>> =
            mapOf(
                64 to setOf("KELP"),
                65 to setOf("KELP"),
                66 to setOf("KELP"),
            ),
    ): VerticalPlantCollapseContext =
        VerticalPlantCollapseContext(
            worldId = WORLD_ID,
            x = x,
            z = 20,
            ownerUuid = ownerUuid,
            expectedItemNamesByY = expectedItemsByY,
        )

    private fun spawn(
        x: Int = 10,
        y: Int,
        materialName: String = "KELP",
        entityId: UUID = UUID.randomUUID(),
    ): VerticalPlantItemSpawn =
        VerticalPlantItemSpawn(
            entityId = entityId,
            worldId = WORLD_ID,
            x = x,
            z = 20,
            y = y,
            materialName = materialName,
        )

    private companion object {
        private val WORLD_ID = UUID.fromString("00000000-0000-0000-0000-000000000010")
        private val OWNER_ID = UUID.fromString("00000000-0000-0000-0000-000000000020")
        private val SECOND_OWNER_ID = UUID.fromString("00000000-0000-0000-0000-000000000030")
    }
}
