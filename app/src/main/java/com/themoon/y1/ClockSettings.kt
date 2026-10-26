package com.themoon.y1

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Preference keys, defaults and formatting for Settings > Date & Time. */
object ClockSettings {
    const val KEY_24_HOUR_FORMAT = "is_24h_format"
    const val DEFAULT_24_HOUR_FORMAT = true

    const val KEY_TIME_IN_TITLE = "time_in_title_bar"
    const val DEFAULT_TIME_IN_TITLE = false

    fun statusBarPattern(is24HourFormat: Boolean): String =
        if (is24HourFormat) "HH:mm" else "h:mm a"

    fun formatStatusBarTime(
        date: Date,
        is24HourFormat: Boolean,
        timeZone: TimeZone = TimeZone.getDefault()
    ): String = SimpleDateFormat(statusBarPattern(is24HourFormat), Locale.US).apply {
        this.timeZone = timeZone
    }.format(date)
}
