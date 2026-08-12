package com.github.command1264.itemdropv2.platform.bukkit

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PlantCollapseOwnershipTrackerTest {
    @Test
    fun `keeps context alive through delayed physics and every expected position`() {
        assertEquals(63L, cactusColumnContextLifetimeTicks(columnHeight = 3))
        assertEquals(100L, cactusColumnContextLifetimeTicks(columnHeight = 40))
        assertEquals(76L, plantColumnContextLifetimeTicks(columnHeight = 16, minimumTicks = 40))
    }

    @Test
    fun `extends context lifetime to cover every position in a world-height column`() {
        assertEquals(444L, plantColumnContextLifetimeTicks(columnHeight = 384, minimumTicks = 40))
    }

    @Test
    fun `extends chorus context lifetime by topology size`() {
        assertEquals(72L, plantTopologyContextLifetimeTicks(topologySize = 12, minimumTicks = 40))
        assertEquals(124L, plantTopologyContextLifetimeTicks(topologySize = 64, minimumTicks = 40))
    }
}
