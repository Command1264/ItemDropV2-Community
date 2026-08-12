package com.github.command1264.itemdropv2.core

import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.util.UUID

class ItemStateTest {
    @Test
    fun `accepts state without ownership or custom lifetime`() {
        ItemState(
            ownership = null,
            remainingLifetimeSeconds = null,
        )
    }

    @Test
    fun `rejects invalid state time boundaries`() {
        assertThrows(IllegalArgumentException::class.java) {
            ItemState(ownership = null, remainingLifetimeSeconds = -2)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ItemState(ownership = null, remainingLifetimeSeconds = 0)
        }
        ItemState(ownership = null, remainingLifetimeSeconds = Long.MAX_VALUE)
        ItemState(ownership = null, remainingLifetimeSeconds = ItemLifetimeSettings.NEVER_EXPIRES)
        assertThrows(IllegalArgumentException::class.java) {
            ItemState(
                ownership = null,
                elapsedLifetimeSeconds = -1,
                originalLifetimeSeconds = 300,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            ItemOwnership(OWNER_UUID, protectionSecondsRemaining = 0)
        }
    }

    private companion object {
        private val OWNER_UUID = UUID.fromString("00000000-0000-0000-0000-000000000101")
    }
}
