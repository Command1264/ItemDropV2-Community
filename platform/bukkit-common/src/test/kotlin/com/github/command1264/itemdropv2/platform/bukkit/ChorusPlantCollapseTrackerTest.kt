package com.github.command1264.itemdropv2.platform.bukkit

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

class ChorusPlantCollapseTrackerTest {
    @Test
    fun `claims random drops at distinct branch positions for the recorded owner`() {
        val tracker = ChorusPlantCollapseTracker()
        val context =
            context(
                ownerUuid = FIRST_OWNER_ID,
                positions = setOf(position(10, 64, 20), position(10, 65, 20), position(11, 65, 20)),
            )
        tracker.record(context)

        val firstEntity = UUID.randomUUID()
        val branchEntity = UUID.randomUUID()

        assertEquals(FIRST_OWNER_ID, tracker.claim(spawn(firstEntity, 10, 64, 20))?.ownerUuid)
        assertEquals(FIRST_OWNER_ID, tracker.claim(spawn(branchEntity, 11, 65, 20))?.ownerUuid)
        assertNull(tracker.claim(spawn(UUID.randomUUID(), 12, 65, 20)))
        assertTrue(tracker.wasClaimed(firstEntity))
        assertTrue(tracker.wasClaimed(branchEntity))
    }

    @Test
    fun `keeps adjacent disconnected structures assigned to different players`() {
        val tracker = ChorusPlantCollapseTracker()
        tracker.record(context(FIRST_OWNER_ID, setOf(position(10, 64, 20), position(11, 64, 20))))
        tracker.record(context(SECOND_OWNER_ID, setOf(position(13, 64, 20), position(14, 64, 20))))

        assertEquals(
            FIRST_OWNER_ID,
            tracker.claim(spawn(UUID.randomUUID(), 11, 64, 20))?.ownerUuid,
        )
        assertEquals(
            SECOND_OWNER_ID,
            tracker.claim(spawn(UUID.randomUUID(), 13, 64, 20))?.ownerUuid,
        )
    }

    @Test
    fun `newer break owns only overlapping positions in one structure`() {
        val tracker = ChorusPlantCollapseTracker()
        tracker.record(
            context(
                FIRST_OWNER_ID,
                setOf(position(10, 64, 20), position(10, 65, 20), position(11, 65, 20)),
            ),
        )
        tracker.record(
            context(
                SECOND_OWNER_ID,
                setOf(position(10, 65, 20), position(11, 65, 20), position(12, 65, 20)),
            ),
        )

        assertEquals(
            FIRST_OWNER_ID,
            tracker.claim(spawn(UUID.randomUUID(), 10, 64, 20))?.ownerUuid,
        )
        assertEquals(
            SECOND_OWNER_ID,
            tracker.claim(spawn(UUID.randomUUID(), 10, 65, 20))?.ownerUuid,
        )
        assertEquals(
            SECOND_OWNER_ID,
            tracker.claim(spawn(UUID.randomUUID(), 12, 65, 20))?.ownerUuid,
        )
    }

    @Test
    fun `does not claim duplicate entities or natural drops outside an active topology`() {
        val tracker = ChorusPlantCollapseTracker()
        tracker.record(context(FIRST_OWNER_ID, setOf(position(10, 64, 20), position(10, 65, 20))))
        val entityId = UUID.randomUUID()

        assertEquals(FIRST_OWNER_ID, tracker.claim(spawn(entityId, 10, 64, 20))?.ownerUuid)
        assertNull(tracker.claim(spawn(entityId, 10, 65, 20)))
        assertNull(tracker.claim(spawn(UUID.randomUUID(), 40, 64, 20)))
    }

    @Test
    fun `expiry and clear remove pending topology positions`() {
        val tracker = ChorusPlantCollapseTracker()
        val firstContext =
            tracker.record(context(FIRST_OWNER_ID, setOf(position(10, 64, 20))))
        assertTrue(tracker.expire(firstContext))
        assertFalse(tracker.expire(firstContext))
        assertNull(tracker.claim(spawn(UUID.randomUUID(), 10, 64, 20)))

        tracker.record(context(FIRST_OWNER_ID, setOf(position(11, 64, 20))))
        tracker.clear()
        assertNull(tracker.claim(spawn(UUID.randomUUID(), 11, 64, 20)))
    }

    private fun context(
        ownerUuid: UUID,
        positions: Set<ChorusBlockPosition>,
    ): ChorusPlantCollapseContext =
        ChorusPlantCollapseContext(
            worldId = WORLD_ID,
            positions = positions,
            ownerUuid = ownerUuid,
        )

    private fun spawn(
        entityId: UUID,
        x: Int,
        y: Int,
        z: Int,
    ): ChorusFruitItemSpawn =
        ChorusFruitItemSpawn(
            entityId = entityId,
            worldId = WORLD_ID,
            position = position(x, y, z),
        )

    private fun position(
        x: Int,
        y: Int,
        z: Int,
    ): ChorusBlockPosition = ChorusBlockPosition(x, y, z)

    private companion object {
        private val WORLD_ID = UUID.fromString("00000000-0000-0000-0000-000000000010")
        private val FIRST_OWNER_ID = UUID.fromString("00000000-0000-0000-0000-000000000020")
        private val SECOND_OWNER_ID = UUID.fromString("00000000-0000-0000-0000-000000000030")
    }
}
