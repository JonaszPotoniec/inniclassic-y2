package com.themoon.y1.managers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PowerBacklightManagerTest {

    @Test
    fun testBacklightTimerOptionsAndLabelsParity() {
        assertEquals(
            PowerBacklightManager.BACKLIGHT_TIMER_OPTIONS_MS.size,
            PowerBacklightManager.BACKLIGHT_TIMER_LABELS.size
        )
        assertEquals(6, PowerBacklightManager.BACKLIGHT_TIMER_OPTIONS_MS.size)
        assertEquals(10000L, PowerBacklightManager.BACKLIGHT_TIMER_OPTIONS_MS[0])
        assertEquals(20000L, PowerBacklightManager.BACKLIGHT_TIMER_OPTIONS_MS[1])
        assertEquals(30000L, PowerBacklightManager.BACKLIGHT_TIMER_OPTIONS_MS[2])
        assertEquals(60000L, PowerBacklightManager.BACKLIGHT_TIMER_OPTIONS_MS[3])
        assertEquals(120000L, PowerBacklightManager.BACKLIGHT_TIMER_OPTIONS_MS[4])
        assertEquals(0L, PowerBacklightManager.BACKLIGHT_TIMER_OPTIONS_MS[5])
    }

    @Test
    fun screenOffTimeoutIsTheBacklightValue() {
        assertEquals(10000, BacklightTimer.screenOffTimeoutMs(0))
        assertEquals(30000, BacklightTimer.screenOffTimeoutMs(2))
        assertEquals(60000, BacklightTimer.screenOffTimeoutMs(3))
        assertEquals(120000, BacklightTimer.screenOffTimeoutMs(4))
    }

    @Test
    fun alwaysOnIsTheLargestTimeout() {
        assertEquals(Int.MAX_VALUE, BacklightTimer.screenOffTimeoutMs(5))
    }

    @Test
    fun badIndexUsesTheDefault() {
        assertEquals(60000, BacklightTimer.screenOffTimeoutMs(-1))
        assertEquals(60000, BacklightTimer.screenOffTimeoutMs(6))
        assertEquals(BacklightTimer.DEFAULT_INDEX, BacklightTimer.validIndex(99))
    }

    @Test
    fun appTimerRunsOnlyWhileTheAppHoldsTheScreenOn() {
        for (i in BacklightTimer.OPTIONS_MS.indices) {
            assertEquals(0L, BacklightTimer.appTimerMs(i, appHoldsScreenOn = false))
        }
        assertEquals(10000L, BacklightTimer.appTimerMs(0, appHoldsScreenOn = true))
        assertEquals(120000L, BacklightTimer.appTimerMs(4, appHoldsScreenOn = true))
        assertEquals(0L, BacklightTimer.appTimerMs(5, appHoldsScreenOn = true)) // Always On
    }

    @Test
    fun appTimerUsesTheSameValueAsAndroid() {
        for (i in 0 until 5) {
            assertEquals(BacklightTimer.screenOffTimeoutMs(i).toLong(), BacklightTimer.appTimerMs(i, appHoldsScreenOn = true))
        }
    }

    @Test
    fun realScreenOffUnlessTheServerHoldsTheScreenOn() {
        assertFalse(BacklightTimer.useFakeScreenOff(appHoldsScreenOn = false))
        assertTrue(BacklightTimer.useFakeScreenOff(appHoldsScreenOn = true))
    }
}
