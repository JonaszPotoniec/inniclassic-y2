package com.themoon.y1.managers

import com.themoon.y1.controllers.settings.SettingsController
import org.junit.Assert.assertEquals
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
    fun testScreenTimeoutOptionsAndLabelsParity() {
        assertEquals(
            SettingsController.TIMEOUT_VALUES.size,
            SettingsController.TIMEOUT_NAMES.size
        )
        assertEquals(4, SettingsController.TIMEOUT_VALUES.size)
        assertEquals(15000, SettingsController.TIMEOUT_VALUES[0])
        assertEquals(30000, SettingsController.TIMEOUT_VALUES[1])
        assertEquals(60000, SettingsController.TIMEOUT_VALUES[2])
        assertEquals(300000, SettingsController.TIMEOUT_VALUES[3])
    }
}
