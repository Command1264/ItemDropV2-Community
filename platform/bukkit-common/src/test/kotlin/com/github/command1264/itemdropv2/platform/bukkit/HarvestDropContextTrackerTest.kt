package com.github.command1264.itemdropv2.platform.bukkit

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

class HarvestDropContextTrackerTest {
    @Test
    fun `recognizes only mature sweet berries and berry-bearing cave vines`() {
        assertFalse(isHarvestableBlock("SWEET_BERRY_BUSH", "minecraft:sweet_berry_bush[age=1]"))
        assertTrue(isHarvestableBlock("SWEET_BERRY_BUSH", "minecraft:sweet_berry_bush[age=2]"))
        assertTrue(isHarvestableBlock("SWEET_BERRY_BUSH", "minecraft:sweet_berry_bush[age=3]"))
        assertFalse(isHarvestableBlock("CAVE_VINES", "minecraft:cave_vines[berries=false]"))
        assertTrue(isHarvestableBlock("CAVE_VINES", "minecraft:cave_vines[berries=true]"))
        assertTrue(isHarvestableBlock("CAVE_VINES_PLANT", "minecraft:cave_vines_plant[berries=true]"))
        assertFalse(isHarvestableBlock("STONE", "minecraft:stone"))
    }

    @Test
    fun `keeps adjacent players and harvested materials isolated`() {
        val tracker = HarvestDropContextTracker()
        tracker.record(source(x = 10, owner = FIRST_OWNER, dropMaterial = "SWEET_BERRIES"))
        tracker.record(source(x = 11, owner = SECOND_OWNER, dropMaterial = "GLOW_BERRIES"))

        assertNull(tracker.claim(spawn(x = 10, material = "GLOW_BERRIES", amount = 1)))
        assertEquals(FIRST_OWNER, tracker.claim(spawn(x = 10, material = "SWEET_BERRIES", amount = 3)))
        assertEquals(SECOND_OWNER, tracker.claim(spawn(x = 11, material = "GLOW_BERRIES", amount = 1)))
    }

    @Test
    fun `new interaction replaces the same position without stale expiry deleting it`() {
        val tracker = HarvestDropContextTracker()
        val oldId = tracker.record(source(owner = FIRST_OWNER))
        val newId = tracker.record(source(owner = SECOND_OWNER))

        tracker.expire(oldId)

        assertEquals(SECOND_OWNER, tracker.claim(spawn(amount = 2)))
        tracker.expire(newId)
        assertNull(tracker.claim(spawn(amount = 1)))
    }

    @Test
    fun `rejects invalid or excessive spawned amounts`() {
        val tracker = HarvestDropContextTracker()
        tracker.record(source(maxAmount = 4))

        assertNull(tracker.claim(spawn(amount = 0)))
        assertNull(tracker.claim(spawn(amount = 5)))
        assertEquals(FIRST_OWNER, tracker.claim(spawn(amount = 3)))
        assertNull(tracker.claim(spawn(amount = 2)))
        assertEquals(FIRST_OWNER, tracker.claim(spawn(amount = 1)))
    }

    private fun source(
        x: Int = 10,
        owner: UUID = FIRST_OWNER,
        dropMaterial: String = "SWEET_BERRIES",
        maxAmount: Int = 64,
    ): HarvestDropSource =
        HarvestDropSource(
            worldId = WORLD_ID,
            x = x,
            y = 64,
            z = 20,
            ownerUuid = owner,
            dropMaterialName = dropMaterial,
            maximumAmount = maxAmount,
        )

    private fun spawn(
        x: Int = 10,
        material: String = "SWEET_BERRIES",
        amount: Int,
    ): HarvestItemSpawn =
        HarvestItemSpawn(
            worldId = WORLD_ID,
            x = x,
            y = 64,
            z = 20,
            materialName = material,
            amount = amount,
        )

    private companion object {
        private val WORLD_ID = UUID.fromString("30000000-0000-0000-0000-000000000001")
        private val FIRST_OWNER = UUID.fromString("30000000-0000-0000-0000-000000000002")
        private val SECOND_OWNER = UUID.fromString("30000000-0000-0000-0000-000000000003")
    }
}
