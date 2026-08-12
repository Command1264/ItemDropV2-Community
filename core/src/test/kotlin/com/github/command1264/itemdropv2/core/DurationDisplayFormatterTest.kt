package com.github.command1264.itemdropv2.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class DurationDisplayFormatterTest {
    @Test
    fun `omits absent leading units while padding every lower unit`() {
        val cases =
            mapOf(
                0L to "0",
                59L to "59",
                60L to "1:00",
                3_599L to "59:59",
                3_600L to "1:00:00",
                86_399L to "23:59:59",
                86_400L to "1 00:00:00",
                2_591_999L to "29 23:59:59",
                2_592_000L to "1-00 00:00:00",
                31_103_999L to "11-29 23:59:59",
                31_104_000L to "1-00-00 00:00:00",
            )

        cases.forEach { (seconds, expected) ->
            assertEquals(expected, DurationDisplayFormatter.format(seconds), "$seconds seconds")
        }
    }

    @Test
    fun `uses fixed thirty day months and twelve month years`() {
        val seconds =
            2L * 31_104_000L +
                3L * 2_592_000L +
                4L * 86_400L +
                5L * 3_600L +
                6L * 60L +
                7L

        assertEquals("2-03-04 05:06:07", DurationDisplayFormatter.format(seconds))
    }

    @Test
    fun `formats Long maximum seconds without overflow or truncating the year`() {
        assertEquals(
            "296533308798-00-20 15:30:07",
            DurationDisplayFormatter.format(Long.MAX_VALUE),
        )
    }
}
