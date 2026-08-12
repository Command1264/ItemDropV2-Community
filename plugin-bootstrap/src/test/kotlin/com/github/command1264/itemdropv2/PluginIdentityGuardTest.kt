package com.github.command1264.itemdropv2

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PluginIdentityGuardTest {
    private val guard = PluginIdentityGuard()

    @Test
    fun `accepts exactly one matching plugin identity`() {
        assertEquals(
            PluginIdentityResult.Accepted,
            guard.evaluate("ItemDropV2", listOf("ItemDropV2", "PlaceholderAPI")),
        )
    }

    @Test
    fun `rejects duplicate identity case insensitively`() {
        assertEquals(
            PluginIdentityResult.Rejected(2),
            guard.evaluate("ItemDropV2", listOf("ItemDropV2", "itemdropv2")),
        )
    }

    @Test
    fun `rejects missing own identity`() {
        assertEquals(
            PluginIdentityResult.Rejected(0),
            guard.evaluate("ItemDropV2", listOf("PlaceholderAPI")),
        )
    }
}
