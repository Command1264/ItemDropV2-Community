package com.github.command1264.itemdropv2

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ItemMergeControllerRegistrationTest {
    @Test
    fun `registers common merge controller before activating capability`() {
        val calls = mutableListOf<String>()

        registerItemMergeThenActivate(
            registerItemMerge = { calls += "merge" },
            activateCapability = { calls += "capability" },
        )

        assertEquals(listOf("merge", "capability"), calls)
    }

    @Test
    fun `does not activate capability when common merge registration fails`() {
        val calls = mutableListOf<String>()

        runCatching {
            registerItemMergeThenActivate(
                registerItemMerge = {
                    calls += "merge"
                    error("registration failed")
                },
                activateCapability = { calls += "capability" },
            )
        }

        assertEquals(listOf("merge"), calls)
    }
}
