package com.themoon.y1

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Date
import java.util.TimeZone

class ClockSettingsTest {
    private val utc = TimeZone.getTimeZone("UTC")

    // 2026-10-24 21:05:00 UTC
    private val evening = Date(1792875900000L)

    @Test
    fun formats24HourTime() {
        assertEquals("21:05", ClockSettings.formatStatusBarTime(evening, true, utc))
    }

    @Test
    fun formats12HourTimeWithMarker() {
        assertEquals("9:05 PM", ClockSettings.formatStatusBarTime(evening, false, utc))
    }

    @Test
    fun usesGivenTimeZone() {
        val tokyo = TimeZone.getTimeZone("Asia/Tokyo")
        assertEquals("06:05", ClockSettings.formatStatusBarTime(evening, true, tokyo))
    }

    @Test
    fun keysMatchTheSettingsScreen() {
        assertEquals("is_24h_format", ClockSettings.KEY_24_HOUR_FORMAT)
        assertEquals("time_in_title_bar", ClockSettings.KEY_TIME_IN_TITLE)
    }
}
