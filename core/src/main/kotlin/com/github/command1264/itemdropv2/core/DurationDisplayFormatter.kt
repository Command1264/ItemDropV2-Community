package com.github.command1264.itemdropv2.core

internal object DurationDisplayFormatter {
    fun format(totalSeconds: Long): String {
        require(totalSeconds >= 0L) { "duration seconds must not be negative" }

        var remainder = totalSeconds
        val seconds = remainder % SECONDS_PER_MINUTE
        remainder /= SECONDS_PER_MINUTE
        val minutes = remainder % MINUTES_PER_HOUR
        remainder /= MINUTES_PER_HOUR
        val hours = remainder % HOURS_PER_DAY
        remainder /= HOURS_PER_DAY
        val days = remainder % DAYS_PER_MONTH
        remainder /= DAYS_PER_MONTH
        val months = remainder % MONTHS_PER_YEAR
        val years = remainder / MONTHS_PER_YEAR

        return when {
            years > 0L -> "$years-${months.padded()}-${days.padded()} ${hours.padded()}:${minutes.padded()}:${seconds.padded()}"
            months > 0L -> "$months-${days.padded()} ${hours.padded()}:${minutes.padded()}:${seconds.padded()}"
            days > 0L -> "$days ${hours.padded()}:${minutes.padded()}:${seconds.padded()}"
            hours > 0L -> "$hours:${minutes.padded()}:${seconds.padded()}"
            minutes > 0L -> "$minutes:${seconds.padded()}"
            else -> seconds.toString()
        }
    }

    private fun Long.padded(): String = toString().padStart(2, '0')

    private const val SECONDS_PER_MINUTE = 60L
    private const val MINUTES_PER_HOUR = 60L
    private const val HOURS_PER_DAY = 24L
    private const val DAYS_PER_MONTH = 30L
    private const val MONTHS_PER_YEAR = 12L
}
