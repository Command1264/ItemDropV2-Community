package com.github.command1264.itemdropv2.platform.bukkit

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

class AdministratorTimestampFormatterTest {
    @Test
    fun `formats the computer local time with an explicit positive UTC offset`() {
        assertEquals(
            "2026-08-11-18-30-45-UTC+08-00",
            AdministratorTimestampFormatter.format(
                instant = Instant.parse("2026-08-11T10:30:45.987Z"),
                zoneId = ZoneId.of("Asia/Taipei"),
            ),
        )
    }

    @Test
    fun `preserves offset minutes and formats a negative UTC offset`() {
        assertEquals(
            "2026-08-11-05-00-45-UTC-05-30",
            AdministratorTimestampFormatter.format(
                instant = Instant.parse("2026-08-11T10:30:45.987Z"),
                zoneId = ZoneOffset.of("-05:30"),
            ),
        )
    }
}
