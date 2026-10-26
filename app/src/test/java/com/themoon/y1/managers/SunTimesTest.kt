package com.themoon.y1.managers

import com.themoon.y1.controllers.settings.SettingsController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class SunTimesTest {

    // Reference times: NOAA solar calculator (also astral 3), rounded to the minute
    private fun assertSunTimes(
        zoneId: String, year: Int, month: Int, day: Int,
        sunrise: String, sunset: String
    ) {
        val c = ZoneCoordinates.forZone(zoneId)!!
        val result = SunTimes.compute(year, month, day, c.latitude, c.longitude)
        assertTrue("$zoneId: $result", result is SunTimes.Result.Times)
        val times = result as SunTimes.Result.Times
        assertTime(zoneId, year, month, day, sunrise, times.sunriseMs)
        assertTime(zoneId, year, month, day, sunset, times.sunsetMs)
    }

    private fun assertTime(zoneId: String, year: Int, month: Int, day: Int, hhmm: String, actualMs: Long) {
        val expectedMs = localMs(zoneId, year, month, day, hhmm)
        val diffMin = (actualMs - expectedMs) / 60_000.0
        assertTrue("$zoneId $year-$month-$day $hhmm: off by $diffMin min", Math.abs(diffMin) <= 1.5)
    }

    private fun localMs(zoneId: String, year: Int, month: Int, day: Int, hhmm: String): Long {
        val (h, m) = hhmm.split(":").map { it.toInt() }
        return Calendar.getInstance(TimeZone.getTimeZone(zoneId)).apply {
            clear()
            set(year, month - 1, day, h, m, 0)
        }.timeInMillis
    }

    @Test
    fun testLondonSummerSolstice() = assertSunTimes("Europe/London", 2026, 6, 21, "04:43", "21:21")

    @Test
    fun testLondonWinterSolstice() = assertSunTimes("Europe/London", 2026, 12, 21, "08:04", "15:53")

    @Test
    fun testLisbon() {
        assertSunTimes("Europe/Lisbon", 2026, 6, 21, "06:12", "21:05")
        assertSunTimes("Europe/Lisbon", 2026, 12, 21, "07:51", "17:18")
    }

    @Test
    fun testSydneyDecember() = assertSunTimes("Australia/Sydney", 2026, 12, 21, "05:41", "20:05")

    @Test
    fun testZonesFarFromUtc() {
        assertSunTimes("Pacific/Honolulu", 2026, 3, 20, "06:35", "18:42")
        assertSunTimes("Pacific/Auckland", 2026, 1, 15, "06:18", "20:42")
        assertSunTimes("America/Anchorage", 2026, 12, 21, "10:15", "15:41")
        assertSunTimes("Europe/Helsinki", 2026, 6, 21, "03:55", "22:49")
    }

    @Test
    fun testPolarDayAndNight() {
        // Longyearbyen, Svalbard (78.2° N) and McMurdo (77.8° S)
        assertTrue(SunTimes.compute(2026, 6, 21, 78.22, 15.65) is SunTimes.Result.PolarDay)
        assertTrue(SunTimes.compute(2026, 12, 21, 78.22, 15.65) is SunTimes.Result.PolarNight)
        assertTrue(SunTimes.compute(2026, 6, 21, -77.85, 166.67) is SunTimes.Result.PolarNight)
        assertTrue(SunTimes.compute(2026, 12, 21, -77.85, 166.67) is SunTimes.Result.PolarDay)
        assertTrue(SunTimes.compute(2026, 3, 20, 78.22, 15.65) is SunTimes.Result.Times)
    }

    @Test
    fun testEveryTimeZoneSettingHasCoordinates() {
        for (id in SettingsController.TIME_ZONE_IDS) {
            assertNotNull("No coordinates for $id", ZoneCoordinates.forZone(id))
        }
        assertEquals(SettingsController.TIME_ZONE_IDS.toSet(), ZoneCoordinates.zoneIds)
    }

    @Test
    fun testEveryTimeZoneSettingHasSunriseAndSunset() {
        for (id in SettingsController.TIME_ZONE_IDS) {
            val c = ZoneCoordinates.forZone(id)!!
            for (month in listOf(3, 6, 9, 12)) {
                val result = SunTimes.compute(2026, month, 21, c.latitude, c.longitude)
                assertTrue("$id month $month", result is SunTimes.Result.Times)
                result as SunTimes.Result.Times
                val dayHours = (result.sunsetMs - result.sunriseMs) / 3_600_000.0
                assertTrue("$id month $month: $dayHours h", dayHours in 5.0..20.0)
                // Solar noon is near local noon (summer time and wide zones move it by up to ~2.5 h)
                val noonLocal = Calendar.getInstance(TimeZone.getTimeZone(id)).apply {
                    timeInMillis = (result.sunriseMs + result.sunsetMs) / 2
                }
                assertEquals("$id month $month", 21, noonLocal.get(Calendar.DAY_OF_MONTH))
                val noonHour = noonLocal.get(Calendar.HOUR_OF_DAY) + noonLocal.get(Calendar.MINUTE) / 60.0
                assertTrue("$id month $month: solar noon $noonHour", noonHour in 10.5..14.5)
            }
        }
    }

    @Test
    fun testIsDaytime() {
        // London, 2026-06-21: sunrise 04:43 BST, sunset 21:21 BST
        assertFalse(SunTimes.isDaytime("Europe/London", localMs("Europe/London", 2026, 6, 21, "04:40")))
        assertTrue(SunTimes.isDaytime("Europe/London", localMs("Europe/London", 2026, 6, 21, "04:46")))
        assertTrue(SunTimes.isDaytime("Europe/London", localMs("Europe/London", 2026, 6, 21, "21:18")))
        assertFalse(SunTimes.isDaytime("Europe/London", localMs("Europe/London", 2026, 6, 21, "21:24")))
        assertFalse(SunTimes.isDaytime("Europe/London", localMs("Europe/London", 2026, 6, 21, "00:30")))
        // Sydney, 2026-12-21: sunrise 05:41 AEDT, sunset 20:05 AEDT (UTC dates differ from the local date)
        assertFalse(SunTimes.isDaytime("Australia/Sydney", localMs("Australia/Sydney", 2026, 12, 21, "05:35")))
        assertTrue(SunTimes.isDaytime("Australia/Sydney", localMs("Australia/Sydney", 2026, 12, 21, "05:45")))
        assertTrue(SunTimes.isDaytime("Australia/Sydney", localMs("Australia/Sydney", 2026, 12, 21, "20:00")))
        assertFalse(SunTimes.isDaytime("Australia/Sydney", localMs("Australia/Sydney", 2026, 12, 21, "20:10")))
        // Honolulu, 2026-03-20: sunset 18:42 HST is on the next UTC date
        assertTrue(SunTimes.isDaytime("Pacific/Honolulu", localMs("Pacific/Honolulu", 2026, 3, 20, "18:38")))
        assertFalse(SunTimes.isDaytime("Pacific/Honolulu", localMs("Pacific/Honolulu", 2026, 3, 20, "18:46")))
    }

    @Test
    fun testUnknownZoneUsesFixedDayHours() {
        val zone = "America/Lima"
        assertFalse(SunTimes.isDaytime(zone, localMs(zone, 2026, 6, 21, "06:59")))
        assertTrue(SunTimes.isDaytime(zone, localMs(zone, 2026, 6, 21, "07:00")))
        assertTrue(SunTimes.isDaytime(zone, localMs(zone, 2026, 6, 21, "18:59")))
        assertFalse(SunTimes.isDaytime(zone, localMs(zone, 2026, 6, 21, "19:00")))
    }

    @Test
    fun testAutoThemeNames() {
        assertEquals("Classic Light", AutoThemeManager.themeNameFor(true))
        assertEquals("Classic Dark", AutoThemeManager.themeNameFor(false))
        val noonLondon = localMs("Europe/London", 2026, 12, 21, "12:00")
        val midnightLondon = localMs("Europe/London", 2026, 12, 21, "23:00")
        assertEquals("Classic Light", AutoThemeManager.resolvedThemeName(noonLondon, "Europe/London"))
        assertEquals("Classic Dark", AutoThemeManager.resolvedThemeName(midnightLondon, "Europe/London"))
    }

    // --- Next Auto theme switch ---

    private fun assertNext(zoneId: String, now: String, expected: String, date: Triple<Int, Int, Int> = Triple(2026, 6, 21), expectedDay: Int = date.third) {
        val (y, m, d) = date
        val next = SunTimes.nextTransitionMs(zoneId, localMs(zoneId, y, m, d, now))
        assertTime("$zoneId next after $now", y, m, expectedDay, expected, next, zoneId)
    }

    private fun assertTime(label: String, year: Int, month: Int, day: Int, hhmm: String, actualMs: Long, zoneId: String) {
        val diffMin = (actualMs - localMs(zoneId, year, month, day, hhmm)) / 60_000.0
        assertTrue("$label: expected $hhmm on day $day, off by $diffMin min", Math.abs(diffMin) <= 1.5)
    }

    @Test
    fun testNextTransitionLondon() {
        // 2026-06-21: sunrise 04:43, sunset 21:21 (BST)
        assertNext("Europe/London", "00:30", "04:43")
        assertNext("Europe/London", "12:00", "21:21")
        // After sunset: sunrise of the next date (2026-06-22, about 04:43)
        assertNext("Europe/London", "22:00", "04:43", expectedDay = 22)
    }

    @Test
    fun testNextTransitionLisbonAndSydney() {
        assertNext("Europe/Lisbon", "12:00", "17:18", Triple(2026, 12, 21))
        assertNext("Australia/Sydney", "03:00", "05:41", Triple(2026, 12, 21))
        assertNext("Australia/Sydney", "12:00", "20:05", Triple(2026, 12, 21))
    }

    @Test
    fun testNextTransitionExactlyAtSunriseIsTheSunset() {
        val zone = "Europe/London"
        val c = ZoneCoordinates.forZone(zone)!!
        val times = SunTimes.compute(2026, 6, 21, c.latitude, c.longitude) as SunTimes.Result.Times
        assertTrue(SunTimes.isDaytime(zone, times.sunriseMs))
        assertEquals(times.sunsetMs, SunTimes.nextTransitionMs(zone, times.sunriseMs))
        assertFalse(SunTimes.isDaytime(zone, times.sunsetMs))
    }

    @Test
    fun testNextTransitionOnSummerTimeChangeDay() {
        // London, 2026-03-29: clocks go forward at 01:00 GMT; sunrise about 06:44 BST, sunset about 19:33 BST
        val zone = "Europe/London"
        val c = ZoneCoordinates.forZone(zone)!!
        val times = SunTimes.compute(2026, 3, 29, c.latitude, c.longitude) as SunTimes.Result.Times
        val now = localMs(zone, 2026, 3, 29, "00:30")
        assertEquals(times.sunriseMs, SunTimes.nextTransitionMs(zone, now))
        assertEquals(times.sunsetMs, SunTimes.nextTransitionMs(zone, times.sunriseMs + 1))
        assertTime("DST sunrise", 2026, 3, 29, "06:44", times.sunriseMs, zone)
    }

    @Test
    fun testNextTransitionWithoutCoordinatesUsesFixedHours() {
        val zone = "America/Lima"
        assertNext(zone, "06:00", "07:00")
        assertNext(zone, "07:00", "19:00")
        assertNext(zone, "20:00", "07:00", expectedDay = 22)
    }

    @Test
    fun testNextTransitionOnAPolarDateIsTheNextMidnight() {
        // Longyearbyen (78.2° N): midnight sun on 2026-06-21
        val zone = TimeZone.getTimeZone("Arctic/Longyearbyen")
        val coords = ZoneCoordinates.LatLon(78.22, 15.65)
        val now = localMs("Arctic/Longyearbyen", 2026, 6, 21, "12:00")
        val next = SunTimes.nextTransitionMs(zone, coords, now)
        assertEquals(localMs("Arctic/Longyearbyen", 2026, 6, 22, "00:00"), next)
    }
}
