package com.themoon.y1.controllers.connectivity

import org.junit.Assert.assertEquals
import org.junit.Test

class WifiSignalTest {

    @Test
    fun strongSignalHasAllArcs() {
        assertEquals(3, WifiSignal.level(-30))
        assertEquals(3, WifiSignal.level(-55))
    }

    @Test
    fun levelsStepDownWithTheSignal() {
        assertEquals(2, WifiSignal.level(-56))
        assertEquals(2, WifiSignal.level(-70))
        assertEquals(1, WifiSignal.level(-71))
        assertEquals(1, WifiSignal.level(-85))
        assertEquals(0, WifiSignal.level(-86))
        assertEquals(0, WifiSignal.level(-100))
        assertEquals(0, WifiSignal.level(-120))
    }

    @Test
    fun sameAsAndroidCalculateSignalLevelWithFourLevels() {
        for (rssi in -120..-20) {
            val expected = when {
                rssi <= -100 -> 0
                rssi >= -55 -> 3
                else -> (rssi + 100) * 3 / 45
            }
            assertEquals("rssi $rssi", expected, WifiSignal.level(rssi))
        }
    }

    @Test
    fun unsignedAndMissingValues() {
        assertEquals(WifiSignal.level(-60), WifiSignal.level(196))
        assertEquals(0, WifiSignal.level(0))
    }
}
