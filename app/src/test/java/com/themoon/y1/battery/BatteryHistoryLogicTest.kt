package com.themoon.y1.battery

import com.themoon.y1.battery.BatteryHistoryLogic.DAY_MS
import com.themoon.y1.battery.BatteryHistoryLogic.HOUR_MS
import com.themoon.y1.battery.BatteryHistoryLogic.MINUTE_MS
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

class BatteryHistoryLogicTest {
    private val utc = TimeZone.getTimeZone("UTC")

    /** 2026-10-30 00:00 UTC. */
    private val midnight = 1_793_318_400_000L
    private fun at(hour: Int, minute: Int = 0) = midnight + hour * HOUR_MS + minute * MINUTE_MS
    private val now = at(19, 50)

    private fun state(t: Long, level: Int, plugged: Boolean = false, screen: Boolean = false, playing: Boolean = false) =
        BatterySample.state(t, level, plugged, screen, playing)

    @Test
    fun linesGoBackToTheSameSamples() {
        val samples = listOf(
            state(at(1), 57, plugged = true, screen = true), state(at(2), 100), state(at(2, 30), 80, playing = true),
            BatterySample.boot(at(3), "ab12"),
            BatterySample.boot(at(4)), BatterySample.down(at(5))
        )
        for (s in samples) assertEquals(s, BatteryHistoryLogic.parse(BatteryHistoryLogic.format(s)))
        assertEquals("${at(1) / 1000} 57 3", BatteryHistoryLogic.format(samples[0]))
        assertEquals("${at(2, 30) / 1000} 80 4", BatteryHistoryLogic.format(samples[2]))
        // Lines from before the play flag (0..3) read as not playing
        assertEquals(state(at(1), 57, screen = true), BatteryHistoryLogic.parse("${at(1) / 1000} 57 2"))
    }

    @Test
    fun cutOrWrongLinesAreSkipped() {
        for (line in listOf("", "17933", "17933 5", "x 50 1", "17933 101 0", "17933 50 8", "17933 D x", "17933 50 1 9")) {
            assertNull(line, BatteryHistoryLogic.parse(line))
        }
    }

    @Test
    fun pruneKeepsTheLastStateBeforeTheCut() {
        val old = state(now - 12 * DAY_MS, 80)
        val older = state(now - 13 * DAY_MS, 90)
        val recent = state(now - DAY_MS, 50)
        assertEquals(listOf(old, recent), BatteryHistoryLogic.prune(listOf(older, old, recent), now))
        assertEquals(listOf(recent), BatteryHistoryLogic.prune(listOf(recent), now))
        assertEquals(listOf(old), BatteryHistoryLogic.prune(listOf(older, old), now))
    }

    @Test
    fun aRecordOnlyForANewValueOrAfterFifteenMinutes() {
        val last = state(at(10), 60)
        assertFalse(BatteryHistoryLogic.needsRecord(last, state(at(10, 14), 60)))
        assertTrue(BatteryHistoryLogic.needsRecord(last, state(at(10, 15), 60)))
        assertTrue(BatteryHistoryLogic.needsRecord(last, state(at(10, 1), 59)))
        assertTrue(BatteryHistoryLogic.needsRecord(last, state(at(10, 1), 60, plugged = true)))
        assertTrue(BatteryHistoryLogic.needsRecord(last, state(at(10, 1), 60, screen = true)))
        assertTrue(BatteryHistoryLogic.needsRecord(last, state(at(10, 1), 60, playing = true)))
        assertTrue(BatteryHistoryLogic.needsRecord(BatterySample.boot(at(10)), state(at(10, 1), 60)))
        assertTrue(BatteryHistoryLogic.needsRecord(null, state(at(10, 1), 60)))
    }

    @Test
    fun theDeviceIsOffFromTheShutdownToTheNextState() {
        val spans = BatteryHistoryLogic.spans(listOf(
            state(at(8), 70), BatterySample.down(at(9)), BatterySample.boot(at(11)), state(at(11, 1), 69)
        ), now)
        assertEquals(listOf(
            BatterySpan(at(8), at(9), 70, false, false),
            BatterySpan(at(11, 1), now, 69, false, false)
        ), spans)
    }

    @Test
    fun aCrashEndsTheLastStateAfterFifteenMinutesAtMost() {
        val spans = BatteryHistoryLogic.spans(listOf(state(at(8), 70), BatterySample.boot(at(12)), state(at(12, 1), 60)), now)
        assertEquals(BatterySpan(at(8), at(8, 15), 70, false, false), spans[0])
        // A boot soon after the last record: the state lasts until the boot
        val soon = BatteryHistoryLogic.spans(listOf(state(at(8), 70), BatterySample.boot(at(8, 5)), state(at(8, 6), 70)), now)
        assertEquals(at(8, 5), soon[0].end)
    }

    @Test
    fun dayViewHasFortyEightLevelBarsAndTheLevelIsTheLastInEachBar() {
        val c = BatteryHistoryLogic.day(listOf(state(at(18), 80), state(at(18, 20), 79), state(at(18, 25), 78), state(at(18, 40), 77)), now, utc)
        // The view ends at the next full hour (20:00) and starts 24 hours before it
        assertEquals(at(20) - DAY_MS, c.start)
        assertEquals(48, c.levels.size)
        val k18 = 44 // 18:00 is bar 44 of 48 (from 20:00 yesterday)
        assertEquals(-1, c.levels[k18 - 1])
        assertEquals(78, c.levels[k18]) // 18:00..18:30: the last level
        assertEquals(77, c.levels[k18 + 1]) // 18:30..19:00
        assertEquals(77, c.levels[47 - 1]) // 19:00..19:30
        assertEquals(77, c.levels[47]) // 19:30..20:00 has now (19:50)
        assertArrayEquals(IntArray(24) { (20 + it) % 24 }, c.hours)
    }

    @Test
    fun futureAndOffTimesHaveNoBar() {
        val c = BatteryHistoryLogic.day(listOf(
            state(at(12), 50), BatterySample.down(at(13)), BatterySample.boot(at(15)), state(at(15), 49)
        ), at(16, 10), utc)
        val start = c.start
        fun slot(t: Long) = ((t - start) / BatteryHistoryLogic.SLOT_MS).toInt()
        assertEquals(50, c.levels[slot(at(12, 45))])
        assertEquals(-1, c.levels[slot(at(13))])
        assertEquals(-1, c.levels[slot(at(14, 45))])
        assertEquals(49, c.levels[slot(at(15))])
        assertEquals(49, c.levels[slot(at(16))])
        assertEquals(-1, c.levels[slot(at(16, 30))]) // after now
        assertEquals(-1, c.screenMinutes[((at(14) - start) / HOUR_MS).toInt()]) // off all the hour
        assertEquals(0, c.screenMinutes[((at(15) - start) / HOUR_MS).toInt()]) // on, screen off
    }

    @Test
    fun chargingAndScreenMinutesPerHour() {
        val c = BatteryHistoryLogic.day(listOf(
            state(at(17), 40, screen = true),
            state(at(17, 30), 40),
            state(at(17, 50), 41, plugged = true),
            state(at(18, 10), 45, plugged = true, screen = true),
            state(at(18, 25), 48)
        ), now, utc)
        val h17 = 21 // 17:00 is hour bar 21 (from 20:00 yesterday)
        assertEquals(30, c.screenMinutes[h17])
        assertEquals(15, c.screenMinutes[h17 + 1])
        assertEquals(0, c.screenMinutes[h17 + 2])
        assertEquals(45, c.screenTotalMinutes)
        val k = h17 * 2
        assertFalse(c.charging[k]) // 17:00..17:30
        assertTrue(c.charging[k + 1]) // 17:30..18:00: from 17:50
        assertTrue(c.charging[k + 2]) // 18:00..18:30: until 18:25
        assertFalse(c.charging[k + 3])
        assertEquals(41, c.levels[k + 1])
        assertEquals(48, c.levels[k + 2])
    }

    @Test
    fun screenOffMinutesAreMusicPlayingWithTheScreenOff() {
        val c = BatteryHistoryLogic.day(listOf(
            state(at(17), 40, screen = true, playing = true), // playing with the screen on: Screen On only
            state(at(17, 10), 40, playing = true), // screen off, playing: Screen Off
            state(at(17, 40), 39), // screen off, not playing: nothing
            state(at(18, 0), 39, playing = true),
            state(at(18, 5), 39)
        ), now, utc)
        val h17 = 21
        assertEquals(10, c.screenMinutes[h17])
        assertEquals(30, c.offMinutes[h17])
        assertEquals(5, c.offMinutes[h17 + 1])
        assertEquals(0, c.offMinutes[h17 + 2])
        assertEquals(35, c.offTotalMinutes)
        val t = BatteryHistoryLogic.tenDays(listOf(state(at(17, 10), 40, playing = true), state(at(17, 40), 39)), now, utc)
        assertEquals(30, t.offMinutes[9])
        assertEquals(0, t.screenMinutes[9])
    }

    @Test
    fun tenDaysCountsTheDropsWhileNotChargingAndTheScreenTime() {
        val day1 = midnight - DAY_MS
        val c = BatteryHistoryLogic.tenDays(listOf(
            state(day1 + 8 * HOUR_MS, 100, screen = true),
            state(day1 + 9 * HOUR_MS, 90),
            state(day1 + 20 * HOUR_MS, 60),
            state(day1 + 21 * HOUR_MS, 62, plugged = true),
            state(day1 + 23 * HOUR_MS, 100, plugged = true),
            state(midnight + 2 * HOUR_MS, 100),
            state(midnight + 3 * HOUR_MS, 97)
        ), now, utc)
        assertEquals(11, c.dayStarts.size)
        assertEquals(midnight, c.dayStarts[9])
        assertEquals(midnight + DAY_MS, c.dayStarts[10])
        assertEquals(40, c.used[8]) // 100 → 90 → 60; the charge does not count
        assertEquals(3, c.used[9])
        assertEquals(-1, c.used[7]) // no record that day
        assertEquals(60, c.screenMinutes[8])
        assertTrue(c.charging[8])
        assertTrue(c.charging[9]) // plugged from 23:00 to 02:00
        assertEquals(java.util.Calendar.FRIDAY, c.dayOfWeek[9]) // 2026-10-30
        assertEquals(listOf(0, 5), BatteryHistoryLogic.weekMarks(c.dayOfWeek)) // 21 Oct (first) and Monday 26 Oct
    }

    @Test
    fun tenDaysDoesNotCountTheDropWhileTheDeviceIsOff() {
        val c = BatteryHistoryLogic.tenDays(listOf(
            state(at(8), 70), state(at(9), 65), BatterySample.down(at(9, 30)),
            BatterySample.boot(at(12)), state(at(12, 1), 60), state(at(13), 58),
            BatterySample.boot(at(14)), state(at(14, 1), 50) // a crash: no shutdown record
        ), now, utc)
        assertEquals(7, c.used[9]) // 70 → 65 and 60 → 58
    }

    @Test
    fun lastChargeIsTheLevelWhenTheChargerWasDisconnected() {
        assertNull(BatteryHistoryLogic.lastCharge(listOf(state(at(1), 50))))
        val samples = listOf(state(at(1), 30, plugged = true), state(at(2), 99, plugged = true), state(at(2, 5), 100), state(at(5), 90))
        assertEquals(LastCharge(100, at(2, 5)), BatteryHistoryLogic.lastCharge(samples))
        assertEquals(90, BatteryHistoryLogic.current(samples)?.level)
    }

    @Test
    fun daylightSavingDaysAreTwentyFiveHoursLong() {
        val london = TimeZone.getTimeZone("Europe/London")
        // Summer time ended on 2026-10-25 at 02:00 BST: that day had 25 hours
        val starts = BatteryHistoryLogic.dayStarts(now, london)
        val oct25 = starts.indexOfFirst { it == 1_792_882_800_000L } // 2026-10-25 00:00 BST = 2026-10-24 23:00 UTC
        assertTrue(oct25 >= 0)
        assertEquals(25 * HOUR_MS, starts[oct25 + 1] - starts[oct25])
    }

    @Test
    fun scalesAndTexts() {
        assertEquals(100, BatteryHistoryLogic.usedScale(intArrayOf(-1, 30, 100)))
        assertEquals(200, BatteryHistoryLogic.usedScale(intArrayOf(120)))
        assertEquals(300, BatteryHistoryLogic.usedScale(intArrayOf(201)))
        assertEquals(1, BatteryHistoryLogic.screenScaleHours(intArrayOf(-1, 0, 45)))
        assertEquals(4, BatteryHistoryLogic.screenScaleHours(intArrayOf(130)))
        assertEquals(16, BatteryHistoryLogic.screenScaleHours(intArrayOf(800)))
        assertEquals(24, BatteryHistoryLogic.screenScaleHours(intArrayOf(1000)))
        assertEquals("00", BatteryHistoryLogic.hourLabel(0, true))
        assertEquals("21", BatteryHistoryLogic.hourLabel(21, true))
        assertEquals("12 AM", BatteryHistoryLogic.hourLabel(0, false))
        assertEquals("3", BatteryHistoryLogic.hourLabel(15, false))
        assertEquals("9", BatteryHistoryLogic.hourLabel(9, false))
        assertEquals("12 PM", BatteryHistoryLogic.hourLabel(12, false))
        assertEquals("45m", BatteryHistoryLogic.durationText(45))
        assertEquals("2h", BatteryHistoryLogic.durationText(120))
        assertEquals("2h 5m", BatteryHistoryLogic.durationText(125))
        // A date under the first labelled hour and at midnight
        assertEquals(listOf(1, 4), BatteryHistoryLogic.dateMarks(IntArray(24) { (20 + it) % 24 }))
        assertEquals(listOf(0), BatteryHistoryLogic.dateMarks(IntArray(24) { it }))
        assertEquals(0, BatteryHistoryLogic.daysAgo(at(0, 1), now, utc))
        assertEquals(1, BatteryHistoryLogic.daysAgo(at(0) - 1, now, utc))
        assertEquals(3, BatteryHistoryLogic.daysAgo(at(12) - 3 * DAY_MS, now, utc))
    }
}
