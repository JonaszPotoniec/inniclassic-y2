package com.themoon.y1.managers

import com.themoon.y1.managers.LowBatteryWarnings.Warning
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LowBatteryWarningsTest {
    private val centre = 23
    private val wheel = 22

    /** Discharges from [from] to [to] (1 % per step) and returns each new warning with its level. */
    private fun discharge(w: LowBatteryWarnings, from: Int, to: Int): List<Pair<Warning, Int>> {
        val out = ArrayList<Pair<Warning, Int>>()
        for (level in from downTo to) {
            if (w.onBattery(level, charging = false) && w.warning != Warning.NONE) out.add(w.warning to w.warningLevel)
        }
        return out
    }

    @Test
    fun aDischargeShowsTheBannerAt10AndTheScreenAt2OneTimeEach() {
        val w = LowBatteryWarnings()
        assertEquals(listOf(Warning.BANNER to 10, Warning.SCREEN to 2), discharge(w, 100, 0))
    }

    @Test
    fun nothingShowsAbove10() {
        val w = LowBatteryWarnings()
        assertEquals(emptyList<Pair<Warning, Int>>(), discharge(w, 100, 11))
        assertEquals(Warning.NONE, w.warning)
    }

    @Test
    fun aLevelThatGoesUpAndDownAgainDoesNotShowTheBannerAgain() {
        val w = LowBatteryWarnings()
        assertTrue(w.onBattery(10, false))
        assertTrue(w.onBannerTimeout())
        assertFalse(w.onBattery(11, false))
        assertFalse(w.onBattery(10, false))
        assertFalse(w.onBattery(9, false))
        assertEquals(Warning.NONE, w.warning)
    }

    @Test
    fun aClosedScreenComesBackOnlyInTheNextDischarge() {
        val w = LowBatteryWarnings()
        discharge(w, 3, 2)
        assertEquals(Warning.SCREEN, w.warning)
        assertTrue(w.onKey(centre, true, 0, 0))
        assertEquals(Warning.NONE, w.warning)
        assertFalse(w.onBattery(3, false))
        assertFalse(w.onBattery(2, false))
        assertFalse(w.onBattery(1, false))
        assertFalse(w.onBattery(0, false))
        assertEquals(Warning.NONE, w.warning)
    }

    @Test
    fun aJumpPastBothLevelsShowsOnlyTheScreen() {
        val w = LowBatteryWarnings()
        assertFalse(w.onBattery(12, false))
        assertEquals(Warning.NONE, w.warning)
        assertTrue(w.onBattery(1, false))
        assertEquals(Warning.SCREEN, w.warning)
        assertEquals(1, w.warningLevel)
        w.onKey(centre, true, 0, 0)
        assertFalse(w.onBattery(5, false))
        assertEquals(Warning.NONE, w.warning)
    }

    @Test
    fun aDischargeThatStartsBelow10ShowsTheBannerAtOnce() {
        val w = LowBatteryWarnings()
        assertTrue(w.onBattery(7, false))
        assertEquals(Warning.BANNER, w.warning)
        assertEquals(7, w.warningLevel)
    }

    @Test
    fun theScreenReplacesAVisibleBanner() {
        val w = LowBatteryWarnings()
        w.onBattery(10, false)
        assertTrue(w.onBattery(2, false))
        assertEquals(Warning.SCREEN, w.warning)
    }

    @Test
    fun chargingRemovesTheWarningAndStartsANewDischarge() {
        val w = LowBatteryWarnings()
        w.onBattery(2, false)
        assertEquals(Warning.SCREEN, w.warning)
        assertTrue(w.onBattery(2, true))
        assertEquals(Warning.NONE, w.warning)
        // More charge broadcasts: no change.
        assertFalse(w.onBattery(3, true))
        // Unplugged again: a new discharge, so the warnings show again.
        assertEquals(listOf(Warning.BANNER to 10, Warning.SCREEN to 2), discharge(w, 10, 2))
    }

    @Test
    fun chargingWithoutAWarningChangesNothing() {
        val w = LowBatteryWarnings()
        assertFalse(w.onBattery(50, true))
        assertFalse(w.onBattery(-1, true))
    }

    @Test
    fun anUnknownLevelShowsNothing() {
        val w = LowBatteryWarnings()
        assertFalse(w.onBattery(-1, false))
        assertEquals(Warning.NONE, w.warning)
    }

    @Test
    fun theBannerTimeoutDoesNotCloseTheScreen() {
        val w = LowBatteryWarnings()
        w.onBattery(2, false)
        assertFalse(w.onBannerTimeout())
        assertEquals(Warning.SCREEN, w.warning)
    }

    @Test
    fun keysPassWhenNoWarningShows() {
        val w = LowBatteryWarnings()
        assertFalse(w.onKey(centre, true, 0, 0))
        assertFalse(w.onKey(centre, false, 0, 50))
        assertFalse(w.onKey(wheel, true, 0, 100))
    }

    @Test
    fun theKeyThatClosesAWarningDoesNothingElseUntilItIsReleased() {
        val w = LowBatteryWarnings()
        w.onBattery(10, false)
        assertTrue(w.onKey(centre, true, 0, 0))
        assertEquals(Warning.NONE, w.warning)
        // The repeats of a hold and the release of the same push.
        assertTrue(w.onKey(centre, true, 1, 500))
        assertTrue(w.onKey(centre, true, 2, 550))
        assertTrue(w.onKey(centre, false, 0, 600))
        // A new push after the short time: the normal action.
        assertFalse(w.onKey(centre, true, 0, 600 + LowBatteryWarnings.KEY_AFTER_MS + 1))
        assertFalse(w.onKey(centre, false, 0, 1000))
    }

    @Test
    fun theOtherStepsOfOneWheelTurnAreIgnored() {
        val w = LowBatteryWarnings()
        w.onBattery(2, false)
        var t = 0L
        assertTrue(w.onKey(wheel, true, 0, t))
        assertTrue(w.onKey(wheel, false, 0, t + 20))
        // Steps 90 ms apart (a normal turn): all ignored.
        for (i in 1..10) {
            t += 90
            assertTrue(w.onKey(wheel, true, 0, t))
            assertTrue(w.onKey(wheel, false, 0, t + 20))
        }
        // A pause, then the wheel works again.
        t += 20 + LowBatteryWarnings.KEY_AFTER_MS + 1
        assertFalse(w.onKey(wheel, true, 0, t))
    }

    @Test
    fun otherKeysWorkAtOnceAfterTheClose() {
        val w = LowBatteryWarnings()
        w.onBattery(10, false)
        assertTrue(w.onKey(wheel, true, 0, 0))
        assertTrue(w.onKey(wheel, false, 0, 20))
        assertFalse(w.onKey(centre, true, 0, 40))
        assertFalse(w.onKey(centre, false, 0, 80))
    }

    @Test
    fun theReleaseOfAKeyPushedBeforeTheWarningIsNotIgnored() {
        val w = LowBatteryWarnings()
        assertFalse(w.onKey(centre, true, 0, 0))
        w.onBattery(10, false)
        assertFalse(w.onKey(centre, false, 0, 100))
        assertEquals(Warning.BANNER, w.warning)
    }

    @Test
    fun aRepeatDoesNotCloseAWarning() {
        val w = LowBatteryWarnings()
        w.onBattery(10, false)
        assertFalse(w.onKey(centre, true, 3, 0))
        assertEquals(Warning.BANNER, w.warning)
    }
}
