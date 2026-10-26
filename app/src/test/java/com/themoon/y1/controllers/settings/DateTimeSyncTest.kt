package com.themoon.y1.controllers.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.util.Calendar
import java.util.TimeZone

class DateTimeSyncTest {

    private val london = TimeZone.getTimeZone("Europe/London")

    private fun ms(y: Int, mo: Int, d: Int, h: Int, mi: Int, s: Int = 0, zone: TimeZone = london): Long =
        Calendar.getInstance(zone).apply { clear(); set(y, mo - 1, d, h, mi, s) }.timeInMillis

    @Test
    fun timestampsGoThroughTheNtpFormat() {
        val b = ByteArray(48)
        for (t in longArrayOf(1793001659714L, 0L + 1000, 1700000000123L)) {
            DateTimeSync.writeTimestamp(b, 40, t)
            assertEquals(t.toDouble(), DateTimeSync.readTimestamp(b, 40).toDouble(), 1.0)
        }
    }

    @Test
    fun requestIsAVersion3ClientPacket() {
        val b = DateTimeSync.request(1793001659714L)
        assertEquals(48, b.size)
        assertEquals(0x1B, b[0].toInt())
        assertEquals(1793001659714.0, DateTimeSync.readTimestamp(b, 40).toDouble(), 1.0)
    }

    private fun answer(t1: Long, t2: Long, t3: Long, mode: Int = 4, stratum: Int = 2, leap: Int = 0): ByteArray {
        val b = ByteArray(48)
        b[0] = ((leap shl 6) or (3 shl 3) or mode).toByte()
        b[1] = stratum.toByte()
        DateTimeSync.writeTimestamp(b, 24, t1)
        DateTimeSync.writeTimestamp(b, 32, t2)
        DateTimeSync.writeTimestamp(b, 40, t3)
        return b
    }

    @Test
    fun networkTimeCorrectsTheOffsetAndTheTravelTime() {
        // Local clock 2 min slow; 100 ms each way; the server answers in 10 ms.
        val t1 = 1_000_000L
        val real1 = t1 + 120_000
        val b = answer(t1, real1 + 100, real1 + 110)
        val t4 = t1 + 210
        assertEquals((real1 + 210).toDouble(), DateTimeSync.networkTime(b, 48, t1, t4)!!.toDouble(), 1.0)
    }

    @Test
    fun badAnswersAreRefused() {
        val t1 = 1_000_000L
        assertNull(DateTimeSync.networkTime(answer(t1, t1, t1, stratum = 0), 48, t1, t1))     // kiss of death
        assertNull(DateTimeSync.networkTime(answer(t1, t1, t1, mode = 3), 48, t1, t1))        // not a server
        assertNull(DateTimeSync.networkTime(answer(t1, t1, t1, leap = 3), 48, t1, t1))        // clock not synchronised
        assertNull(DateTimeSync.networkTime(answer(t1 + 5000, t1, t1), 48, t1, t1))           // not our request
        assertNull(DateTimeSync.networkTime(answer(t1, t1, t1), 40, t1, t1))                  // short
    }

    @Test
    fun setTimeArgsAreTwoInt32LowWordFirst() {
        // Checked on the Y2: service call alarm 2 i32 2000297282 i32 417 = 1793001659714 ms
        assertEquals("i32 2000297282 i32 417", DateTimeSync.setTimeArgs(1793001659714L))
        assertEquals("i32 -1 i32 0", DateTimeSync.setTimeArgs(0xFFFFFFFFL))
    }

    @Test
    fun oneValueChangesAndTheOthersStay() {
        val now = ms(2026, 10, 26, 8, 15, 42)
        assertEquals(ms(2027, 10, 26, 8, 15, 42), DateTimeSync.withField(now, london, "Year", 2027))
        assertEquals(ms(2026, 3, 26, 8, 15, 42), DateTimeSync.withField(now, london, "Month", 3))
        assertEquals(ms(2026, 10, 2, 8, 15, 42), DateTimeSync.withField(now, london, "Day", 2))
        assertEquals(ms(2026, 10, 26, 21, 15, 0), DateTimeSync.withField(now, london, "Hour", 21))
        assertEquals(ms(2026, 10, 26, 8, 3, 0), DateTimeSync.withField(now, london, "Minute", 3))
        assertEquals(now, DateTimeSync.withField(now, london, "Other", 3))
    }

    @Test
    fun theDayStaysInTheMonth() {
        val jan31 = ms(2026, 1, 31, 12, 0)
        assertEquals(ms(2026, 2, 28, 12, 0), DateTimeSync.withField(jan31, london, "Month", 2))
        assertEquals(ms(2028, 2, 29, 12, 0), DateTimeSync.withField(ms(2028, 2, 29, 12, 0), london, "Day", 31))
        assertEquals(ms(2027, 2, 28, 12, 0), DateTimeSync.withField(ms(2028, 2, 29, 12, 0), london, "Year", 2027))
    }

    @Test
    fun theTimeIsTheLocalTimeOfTheZone() {
        val tokyo = TimeZone.getTimeZone("Asia/Tokyo")
        val now = ms(2026, 10, 26, 8, 0, zone = tokyo)
        assertEquals(ms(2026, 10, 26, 23, 0, zone = tokyo), DateTimeSync.withField(now, tokyo, "Hour", 23))
    }

    @Test
    fun dateTimeScreenHasNoApplyButton() {
        val dir = "src/main/java/com/themoon/y1/controllers/settings/SettingsController.kt"
        val code = listOf(File(dir), File("app/$dir")).first { it.exists() }.readText()
        assertEquals(-1, code.indexOf("APPLY DATE & TIME"))
        val start = code.indexOf("fun buildDateTimeUI(")
        val end = code.indexOf("\n    private fun ", start)
        assertEquals("no divider in Date & Time", -1, code.substring(start, end).indexOf("createCategoryHeader"))
    }
}
