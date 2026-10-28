package com.themoon.y1.managers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PowerMenuTest {

    @Test
    fun onlyAndroidsPowerMenuReasonOpensOurScreen() {
        assertTrue(PowerMenu.isAndroidPowerMenu("globalactions"))
        assertFalse(PowerMenu.isAndroidPowerMenu("homekey"))
        assertFalse(PowerMenu.isAndroidPowerMenu("recentapps"))
        assertFalse(PowerMenu.isAndroidPowerMenu(null))
        // Our own close broadcast must not open the screen again
        assertFalse(PowerMenu.isAndroidPowerMenu(PowerMenu.REASON_OURS))
    }

    @Test
    fun actionsUseAndroidsShutdownFirstThenReboot() {
        assertEquals(listOf("Restart", "Power Off"), PowerMenu.Action.values().map { it.label })
        assertEquals(listOf("svc power reboot", "reboot"), PowerMenu.Action.RESTART.commands)
        assertEquals(listOf("svc power shutdown", "reboot -p"), PowerMenu.Action.POWER_OFF.commands)
    }

    @Test
    fun dryRunPropertyNameFitsAndroid44Limit() {
        assertTrue(PowerMenu.DRY_RUN_PROP.length < 32)
    }
}
