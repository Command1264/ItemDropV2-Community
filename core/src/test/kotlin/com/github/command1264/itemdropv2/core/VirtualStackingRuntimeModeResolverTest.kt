package com.github.command1264.itemdropv2.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class VirtualStackingRuntimeModeResolverTest {
    @Test
    fun `creation capability and enabled setting are both required for active mode`() {
        val enabled = VirtualItemStackingSettings(enabled = true)
        val disabled = enabled.copy(enabled = false)

        assertEquals(
            VirtualStackingRuntimeMode.ACTIVE,
            VirtualStackingRuntimeModeResolver(true).creationMode(enabled, 64),
        )
        assertEquals(
            VirtualStackingRuntimeMode.LEGACY_DRAIN,
            VirtualStackingRuntimeModeResolver(true).creationMode(disabled, 64),
        )
        assertEquals(
            VirtualStackingRuntimeMode.LEGACY_DRAIN,
            VirtualStackingRuntimeModeResolver(false).creationMode(enabled, 64),
        )
    }

    @Test
    fun `existing carrier above a reduced maximum drains instead of materializing`() {
        val settings =
            VirtualItemStackingSettings(
                enabled = true,
                maximumAmountPerEntity = VirtualItemAmount.of(128),
            )

        assertEquals(
            VirtualStackingRuntimeMode.LEGACY_DRAIN,
            VirtualStackingRuntimeModeResolver(true).carrierMode(
                settings,
                64,
                VirtualItemAmount.of(8_192),
            ),
        )
    }

    @Test
    fun `unstackable carrier drains when unstackable creation is disabled`() {
        val settings = VirtualItemStackingSettings(enabled = true, unstackableItemsEnabled = false)

        assertEquals(
            VirtualStackingRuntimeMode.LEGACY_DRAIN,
            VirtualStackingRuntimeModeResolver(true).carrierMode(
                settings,
                1,
                VirtualItemAmount.of(40),
            ),
        )
    }
}
