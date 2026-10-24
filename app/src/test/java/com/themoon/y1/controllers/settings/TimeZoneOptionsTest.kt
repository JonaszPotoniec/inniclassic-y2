package com.themoon.y1.controllers.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

class TimeZoneOptionsTest {

    @Test
    fun testTimeZoneIdsAreKnownAndUnique() {
        val ids = SettingsController.TIME_ZONE_IDS
        assertEquals(ids.size, ids.toSet().size)
        assertTrue("Europe/London" in ids)
        assertTrue("GMT" in ids)
        for (id in ids) {
            // Unknown IDs silently fall back to GMT
            assertEquals(id, TimeZone.getTimeZone(id).id)
        }
    }

    @Test
    fun testTimeZoneLabels() {
        val winter = 1767225600000L // 2026-01-01T00:00:00Z
        val summer = 1782864000000L // 2026-07-01T00:00:00Z
        assertEquals("UTC+00:00 London", SettingsController.timeZoneLabel("Europe/London", winter))
        assertEquals("UTC+01:00 London", SettingsController.timeZoneLabel("Europe/London", summer))
        assertEquals("UTC-05:00 New York", SettingsController.timeZoneLabel("America/New_York", winter))
        assertEquals("UTC+05:30 Kolkata", SettingsController.timeZoneLabel("Asia/Kolkata", winter))
        assertEquals("UTC+00:00 GMT", SettingsController.timeZoneLabel("GMT", summer))
        assertEquals("Buenos Aires", SettingsController.timeZoneCityName("America/Argentina/Buenos_Aires"))
    }
}
