package com.github.command1264.itemdropv2.platform.bukkit

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

/** Formats administrator-visible timestamps without UTC-only or fractional output. */
public object AdministratorTimestampFormatter {
    public fun formatNow(): String = format(Instant.now(), ZoneId.systemDefault())

    public fun format(
        instant: Instant,
        zoneId: ZoneId,
    ): String {
        val localDateTime = LOCAL_DATE_TIME.format(instant.atZone(zoneId))
        val totalMinutes = zoneId.rules.getOffset(instant).totalSeconds / SECONDS_PER_MINUTE
        val sign = if (totalMinutes < 0) '-' else '+'
        val absoluteMinutes = abs(totalMinutes)
        val offsetHours = absoluteMinutes / MINUTES_PER_HOUR
        val offsetMinutes = absoluteMinutes % MINUTES_PER_HOUR
        return String.format(
            Locale.ROOT,
            "%s-UTC%c%02d-%02d",
            localDateTime,
            sign,
            offsetHours,
            offsetMinutes,
        )
    }

    private const val SECONDS_PER_MINUTE = 60
    private const val MINUTES_PER_HOUR = 60
    private val LOCAL_DATE_TIME = DateTimeFormatter.ofPattern("uuuu-MM-dd-HH-mm-ss", Locale.ROOT)
}
