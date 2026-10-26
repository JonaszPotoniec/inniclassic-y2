package com.themoon.y1.controllers.connectivity

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WifiScanFilterTest {

    @Test
    fun driverWarningEntryIsLeftOut() {
        assertTrue(WifiScanFilter.isDriverWarning("00:00:00:00:00:00", "NVRAM WARNING: Err = 0x10"))
    }

    @Test
    fun emptyBssidIsLeftOut() {
        assertTrue(WifiScanFilter.isDriverWarning("00:00:00:00:00:00", "Some Network"))
    }

    @Test
    fun warningSsidIsLeftOutWithAnyBssid() {
        assertTrue(WifiScanFilter.isDriverWarning("12:34:56:78:9a:bc", "NVRAM WARNING: Err = 0x01"))
        assertTrue(WifiScanFilter.isDriverWarning(null, "NVRAM WARNING"))
    }

    @Test
    fun realNetworksStay() {
        assertFalse(WifiScanFilter.isDriverWarning("12:34:56:78:9a:bc", "Home"))
        assertFalse(WifiScanFilter.isDriverWarning("12:34:56:78:9a:bc", "My NVRAM WARNING"))
        assertFalse(WifiScanFilter.isDriverWarning(null, null))
    }
}
