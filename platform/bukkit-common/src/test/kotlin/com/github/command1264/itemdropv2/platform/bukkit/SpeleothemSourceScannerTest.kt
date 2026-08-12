package com.github.command1264.itemdropv2.platform.bukkit

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class SpeleothemSourceScannerTest {
    @Test
    fun `parses upward and downward directions regardless of property order`() {
        assertEquals(
            SpeleothemDirection.UP,
            parseSpeleothemDirection(
                "minecraft:pointed_dripstone[thickness=base,vertical_direction=up,waterlogged=false]",
            ),
        )
        assertEquals(
            SpeleothemDirection.DOWN,
            parseSpeleothemDirection(
                "minecraft:sulfur_spike[vertical_direction=down,thickness=tip,waterlogged=false]",
            ),
        )
    }

    @Test
    fun `rejects absent malformed and unsupported directions`() {
        assertNull(parseSpeleothemDirection("minecraft:pointed_dripstone"))
        assertNull(parseSpeleothemDirection("minecraft:pointed_dripstone[thickness=tip]"))
        assertNull(parseSpeleothemDirection("minecraft:pointed_dripstone[vertical_direction=sideways]"))
        assertNull(parseSpeleothemDirection("minecraft:pointed_dripstone[vertical_direction]"))
    }
}
