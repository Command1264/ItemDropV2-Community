package com.github.command1264.itemdropv2.platform.bukkit

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.util.UUID

class ProjectileBlockDropTrackerTest {
    @Test
    fun `accepts chorus projectiles but restricts speleothems to tridents`() {
        assertEquals(true, supportsProjectileBlockDrop("CHORUS_FLOWER", "ARROW"))
        assertEquals(true, supportsProjectileBlockDrop("POINTED_DRIPSTONE", "TRIDENT"))
        assertEquals(true, supportsProjectileBlockDrop("SULFUR_SPIKE", "TRIDENT"))
        assertEquals(false, supportsProjectileBlockDrop("POINTED_DRIPSTONE", "ARROW"))
        assertEquals(false, supportsProjectileBlockDrop("SULFUR_SPIKE", "SNOWBALL"))
        assertEquals(false, supportsProjectileBlockDrop("STONE", "TRIDENT"))
    }

    @Test
    fun `keeps different projectile-broken materials isolated at the same position`() {
        val tracker = ChorusFlowerProjectileTracker()
        tracker.record(context("POINTED_DRIPSTONE", POINTED_OWNER))
        tracker.record(context("SULFUR_SPIKE", SULFUR_OWNER))

        assertEquals(SULFUR_OWNER, tracker.claim(spawn("SULFUR_SPIKE")))
        assertEquals(POINTED_OWNER, tracker.claim(spawn("POINTED_DRIPSTONE")))
    }

    @Test
    fun `does not consume a context for a different dropped material`() {
        val tracker = ChorusFlowerProjectileTracker()
        tracker.record(context("POINTED_DRIPSTONE", POINTED_OWNER))

        assertNull(tracker.claim(spawn("SULFUR_SPIKE")))
        assertEquals(POINTED_OWNER, tracker.claim(spawn("POINTED_DRIPSTONE")))
    }

    private fun context(
        materialName: String,
        ownerUuid: UUID,
    ): ChorusFlowerProjectileContext =
        ChorusFlowerProjectileContext(
            worldId = WORLD_ID,
            x = 10,
            y = 64,
            z = 20,
            ownerUuid = ownerUuid,
            materialName = materialName,
        )

    private fun spawn(materialName: String): ChorusFlowerItemSpawn =
        ChorusFlowerItemSpawn(
            worldId = WORLD_ID,
            x = 10,
            y = 64,
            z = 20,
            materialName = materialName,
        )

    private companion object {
        private val WORLD_ID = UUID.fromString("00000000-0000-0000-0000-000000000001")
        private val POINTED_OWNER = UUID.fromString("00000000-0000-0000-0000-000000000002")
        private val SULFUR_OWNER = UUID.fromString("00000000-0000-0000-0000-000000000003")
    }
}
