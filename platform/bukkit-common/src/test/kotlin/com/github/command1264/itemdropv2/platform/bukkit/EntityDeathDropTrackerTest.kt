package com.github.command1264.itemdropv2.platform.bukkit

import org.bukkit.Material
import org.bukkit.inventory.ItemStack
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.util.UUID

class EntityDeathDropTrackerTest {
    @Test
    fun `claims only matching nearby expected drops and consumes amount`() {
        val tracker = EntityDeathDropTracker { expected, actual -> expected.type == actual.type }
        tracker.record(
            EntityDeathDropContext(WORLD, 10.0, 64.0, 20.0, listOf(ItemStack(Material.STONE, 2)), listOf(OWNER)),
        )

        assertEquals(listOf(OWNER), tracker.claim(drop(Material.STONE, 1)))
        assertEquals(listOf(OWNER), tracker.claim(drop(Material.STONE, 1)))
        assertNull(tracker.claim(drop(Material.STONE, 1)))
        assertNull(tracker.claim(drop(Material.DIRT, 1)))
    }

    @Test
    fun `expired context cannot claim a later item`() {
        val tracker = EntityDeathDropTracker()
        val id = tracker.record(EntityDeathDropContext(WORLD, 10.0, 64.0, 20.0, listOf(ItemStack(Material.STONE)), listOf(OWNER)))
        tracker.expire(id)

        assertNull(tracker.claim(drop(Material.STONE, 1)))
    }

    @Test
    fun `nearest of adjacent identical contexts wins before recency`() {
        val firstOwner = UUID.fromString("00000000-0000-0000-0000-000000000021")
        val secondOwner = UUID.fromString("00000000-0000-0000-0000-000000000022")
        val tracker = EntityDeathDropTracker { expected, actual -> expected.type == actual.type }
        tracker.record(
            EntityDeathDropContext(WORLD, 10.0, 64.0, 20.0, listOf(ItemStack(Material.STONE)), listOf(firstOwner)),
        )
        tracker.record(
            EntityDeathDropContext(WORLD, 11.0, 64.0, 20.0, listOf(ItemStack(Material.STONE)), listOf(secondOwner)),
        )

        assertEquals(listOf(firstOwner), tracker.claim(dropAt(10.1, Material.STONE)))
        assertEquals(listOf(secondOwner), tracker.claim(dropAt(10.9, Material.STONE)))
    }

    private fun drop(
        material: Material,
        amount: Int,
    ) = EntitySpawnedDrop(WORLD, 10.5, 64.2, 20.5, ItemStack(material, amount))

    private fun dropAt(
        x: Double,
        material: Material,
    ) = EntitySpawnedDrop(WORLD, x, 64.0, 20.0, ItemStack(material))

    private companion object {
        private val WORLD = UUID.fromString("00000000-0000-0000-0000-000000000010")
        private val OWNER = UUID.fromString("00000000-0000-0000-0000-000000000020")
    }
}
