package com.themoon.y1.managers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BluetoothDiscoverableLogicTest {

    @Test
    fun noTimerGivesNoRemainingTime() {
        assertNull(BluetoothDiscoverableLogic.remainingMs(0L, 5_000L))
    }

    @Test
    fun remainingTimeNeverGoesBelowZero() {
        assertEquals(45_000L, BluetoothDiscoverableLogic.remainingMs(50_000L, 5_000L))
        assertEquals(0L, BluetoothDiscoverableLogic.remainingMs(50_000L, 60_000L))
    }

    @Test
    fun remainingTimeIsRoundedUpToTheNextSecond() {
        assertEquals("2:00", BluetoothDiscoverableLogic.formatRemaining(120_000L))
        assertEquals("1:45", BluetoothDiscoverableLogic.formatRemaining(105_000L))
        assertEquals("1:45", BluetoothDiscoverableLogic.formatRemaining(104_001L))
        assertEquals("0:01", BluetoothDiscoverableLogic.formatRemaining(200L))
        assertEquals("0:00", BluetoothDiscoverableLogic.formatRemaining(0L))
        assertEquals("0:00", BluetoothDiscoverableLogic.formatRemaining(-5L))
    }

    @Test
    fun durationIsTwoMinutes() {
        assertEquals(120, BluetoothDiscoverableLogic.DURATION_S)
        assertEquals("2:00", BluetoothDiscoverableLogic.formatRemaining(BluetoothDiscoverableLogic.DURATION_S * 1000L))
    }

    @Test
    fun discoverableWithoutOurTimerMustTurnOff() {
        assertTrue(BluetoothDiscoverableLogic.mustTurnOff(discoverable = true, timerRunning = false))
        assertFalse(BluetoothDiscoverableLogic.mustTurnOff(discoverable = true, timerRunning = true))
        assertFalse(BluetoothDiscoverableLogic.mustTurnOff(discoverable = false, timerRunning = false))
        assertFalse(BluetoothDiscoverableLogic.mustTurnOff(discoverable = false, timerRunning = true))
    }

    @Test
    fun timeLeftShowsOnlyWhileDiscoverableWithOurTimer() {
        assertNull(BluetoothDiscoverableLogic.timeLeftText(false, null))
        assertNull(BluetoothDiscoverableLogic.timeLeftText(false, 30_000L))
        assertNull(BluetoothDiscoverableLogic.timeLeftText(true, null))
        assertEquals("1:45", BluetoothDiscoverableLogic.timeLeftText(true, 105_000L))
        assertEquals("2:00", BluetoothDiscoverableLogic.timeLeftText(true, 120_000L))
    }

    @Test
    fun theNameIsAlwaysInnioasisY2() {
        assertEquals("Innioasis Y2", BluetoothDiscoverableLogic.DEVICE_NAME)
        assertEquals("Innioasis Y2", BluetoothDiscoverableLogic.nameToSet("MT6582"))
        assertEquals("Innioasis Y2", BluetoothDiscoverableLogic.nameToSet("Y2"))
        assertEquals("Innioasis Y2", BluetoothDiscoverableLogic.nameToSet(""))
        assertEquals("Innioasis Y2", BluetoothDiscoverableLogic.nameToSet(null))
        assertNull(BluetoothDiscoverableLogic.nameToSet("Innioasis Y2"))
    }

    @Test
    fun timeTextIsCentredOnTheCapHeight() {
        // 38 px row, ascent -19, cap height 15: padding 7.5 -> 8, baseline 27, caps 12..27, centre 19.5.
        assertEquals(8, BluetoothDiscoverableLogic.capCentredPaddingTop(38, -19, 15f))
        assertEquals(10, BluetoothDiscoverableLogic.capCentredPaddingTop(48, -20, 12f))
        assertEquals(0, BluetoothDiscoverableLogic.capCentredPaddingTop(10, -30, 10f))
    }
}
