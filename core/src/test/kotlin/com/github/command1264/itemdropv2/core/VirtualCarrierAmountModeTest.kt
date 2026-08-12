package com.github.command1264.itemdropv2.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class VirtualCarrierAmountModeTest {
    @Test
    fun `proportional maps the configured range to one through sixty four`() {
        val settings =
            VirtualItemStackingSettings(
                maximumAmountPerEntity = VirtualItemAmount.of(8_192),
                carrierAmountMode = VirtualCarrierAmountMode.PROPORTIONAL,
            )

        assertEquals(1, settings.carrierAmountFor(VirtualItemAmount.of(1), nativeStackSize = 64))
        assertEquals(1, settings.carrierAmountFor(VirtualItemAmount.of(128), nativeStackSize = 64))
        assertEquals(2, settings.carrierAmountFor(VirtualItemAmount.of(129), nativeStackSize = 64))
        assertEquals(32, settings.carrierAmountFor(VirtualItemAmount.of(4_096), nativeStackSize = 64))
        assertEquals(64, settings.carrierAmountFor(VirtualItemAmount.of(8_192), nativeStackSize = 64))
    }

    @Test
    fun `proportional supports unstackable carriers and long maximum without overflow`() {
        val unstackable =
            VirtualItemStackingSettings(
                maximumNativeStacksPerEntity = 128,
                carrierAmountMode = VirtualCarrierAmountMode.PROPORTIONAL,
            )
        assertEquals(1, unstackable.carrierAmountFor(VirtualItemAmount.of(2), nativeStackSize = 1))
        assertEquals(32, unstackable.carrierAmountFor(VirtualItemAmount.of(64), nativeStackSize = 1))
        assertEquals(64, unstackable.carrierAmountFor(VirtualItemAmount.of(128), nativeStackSize = 1))

        val longMaximum =
            VirtualItemStackingSettings(
                maximumAmountPerEntity = VirtualItemAmount.of(Long.MAX_VALUE),
                carrierAmountMode = VirtualCarrierAmountMode.PROPORTIONAL,
            )
        val firstBucketMaximum = Long.MAX_VALUE / 64
        assertEquals(1, longMaximum.carrierAmountFor(VirtualItemAmount.of(firstBucketMaximum), 64))
        assertEquals(2, longMaximum.carrierAmountFor(VirtualItemAmount.of(firstBucketMaximum + 1), 64))
        assertEquals(64, longMaximum.carrierAmountFor(VirtualItemAmount.of(Long.MAX_VALUE), 64))
    }

    @Test
    fun `count capped exposes logical count up to sixty four`() {
        val settings =
            VirtualItemStackingSettings(
                maximumAmountPerEntity = VirtualItemAmount.of(8_192),
                carrierAmountMode = VirtualCarrierAmountMode.COUNT_CAPPED,
            )

        assertEquals(1, settings.carrierAmountFor(VirtualItemAmount.of(1), nativeStackSize = 1))
        assertEquals(63, settings.carrierAmountFor(VirtualItemAmount.of(63), nativeStackSize = 1))
        assertEquals(64, settings.carrierAmountFor(VirtualItemAmount.of(64), nativeStackSize = 1))
        assertEquals(64, settings.carrierAmountFor(VirtualItemAmount.of(8_192), nativeStackSize = 1))
    }

    @Test
    fun `minimal preserves the existing native saturation sentinel`() {
        val settings =
            VirtualItemStackingSettings(
                maximumAmountPerEntity = VirtualItemAmount.of(8_192),
                carrierAmountMode = VirtualCarrierAmountMode.MINIMAL,
            )

        assertEquals(1, settings.carrierAmountFor(VirtualItemAmount.of(8_191), nativeStackSize = 64))
        assertEquals(64, settings.carrierAmountFor(VirtualItemAmount.of(8_192), nativeStackSize = 64))
        assertEquals(1, settings.carrierAmountFor(VirtualItemAmount.of(8_192), nativeStackSize = 1))
    }

    @Test
    fun `carrier amount rejects state above the configured maximum`() {
        val settings =
            VirtualItemStackingSettings(
                maximumAmountPerEntity = VirtualItemAmount.of(64),
            )

        assertThrows(IllegalArgumentException::class.java) {
            settings.carrierAmountFor(VirtualItemAmount.of(65), nativeStackSize = 64)
        }
    }
}
