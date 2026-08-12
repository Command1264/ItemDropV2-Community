package com.github.command1264.itemdropv2.platform.bukkit

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class MaterialDisplayNameFormatterTest {
    @Test
    fun `converts enum names into a stable readable fallback`() {
        assertEquals("Diamond Sword", MaterialDisplayNameFormatter.format("DIAMOND_SWORD"))
        assertEquals("Stone", MaterialDisplayNameFormatter.format("STONE"))
    }

    @Test
    fun `rejects malformed material names`() {
        assertThrows(IllegalArgumentException::class.java) { MaterialDisplayNameFormatter.format("") }
        assertThrows(IllegalArgumentException::class.java) { MaterialDisplayNameFormatter.format("BAD-NAME") }
    }
}
