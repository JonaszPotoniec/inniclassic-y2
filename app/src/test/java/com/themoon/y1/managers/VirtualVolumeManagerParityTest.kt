package com.themoon.y1.managers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class VirtualVolumeManagerParityTest {

    private val delta = 0.0001f

    // -------------------------------------------------------------------------
    // 1. virtualToSoftwareGain
    // -------------------------------------------------------------------------

    @Test
    fun testVirtualToSoftwareGain_zeroAndNegative() {
        assertEquals(0.0f, VirtualVolumeManager.virtualToSoftwareGain(-10), delta)
        assertEquals(0.0f, VirtualVolumeManager.virtualToSoftwareGain(-1), delta)
        assertEquals(0.0f, VirtualVolumeManager.virtualToSoftwareGain(0), delta)
    }

    @Test
    fun testVirtualToSoftwareGain_fineGrainedSteps() {
        assertEquals(0.04f, VirtualVolumeManager.virtualToSoftwareGain(1), delta)
        assertEquals(0.10f, VirtualVolumeManager.virtualToSoftwareGain(2), delta)
        assertEquals(0.20f, VirtualVolumeManager.virtualToSoftwareGain(3), delta)
        assertEquals(0.35f, VirtualVolumeManager.virtualToSoftwareGain(4), delta)
        assertEquals(0.60f, VirtualVolumeManager.virtualToSoftwareGain(5), delta)
    }

    @Test
    fun testVirtualToSoftwareGain_unattenuatedSteps() {
        val testSteps = intArrayOf(6, 15, 30, 35)
        for (step in testSteps) {
            assertEquals(
                "Gain for virtual volume $step should be 1.0f",
                1.0f,
                VirtualVolumeManager.virtualToSoftwareGain(step),
                delta
            )
        }
    }

    // -------------------------------------------------------------------------
    // 2. virtualToSystemVolume
    // -------------------------------------------------------------------------

    @Test
    fun testVirtualToSystemVolume_zeroAndNegative() {
        assertEquals(0, VirtualVolumeManager.virtualToSystemVolume(-5, 15))
        assertEquals(0, VirtualVolumeManager.virtualToSystemVolume(-1, 15))
        assertEquals(0, VirtualVolumeManager.virtualToSystemVolume(0, 15))
        assertEquals(0, VirtualVolumeManager.virtualToSystemVolume(0, 25))
    }

    @Test
    fun testVirtualToSystemVolume_range1To6() {
        for (v in 1..6) {
            assertEquals(
                "virtualVol=$v should map to system volume 1",
                1,
                VirtualVolumeManager.virtualToSystemVolume(v, 15)
            )
        }
    }

    @Test
    fun testVirtualToSystemVolume_sysMaxVolLessThanOrEqualToOne() {
        for (v in 1..30) {
            assertEquals(1, VirtualVolumeManager.virtualToSystemVolume(v, 1))
            assertEquals(1, VirtualVolumeManager.virtualToSystemVolume(v, 0))
            assertEquals(1, VirtualVolumeManager.virtualToSystemVolume(v, -1))
        }
    }

    @Test
    fun testVirtualToSystemVolume_range7To30_withSysMaxVol15() {
        val sysMaxVol = 15
        for (v in 7..30) {
            val expected = 2 + Math.round((v - 7).toFloat() * (sysMaxVol - 2) / 23.0f)
            val actual = VirtualVolumeManager.virtualToSystemVolume(v, sysMaxVol)
            assertEquals("Mismatch for virtual volume $v", expected, actual)
        }
    }

    @Test
    fun testVirtualToSystemVolume_maxStep30() {
        assertEquals(15, VirtualVolumeManager.virtualToSystemVolume(30, 15))
        assertEquals(25, VirtualVolumeManager.virtualToSystemVolume(30, 25))
    }

    // -------------------------------------------------------------------------
    // 3. systemToVirtualVolume
    // -------------------------------------------------------------------------

    @Test
    fun testSystemToVirtualVolume_zeroAndNegative() {
        assertEquals(0, VirtualVolumeManager.systemToVirtualVolume(0, 15, 10))
        assertEquals(0, VirtualVolumeManager.systemToVirtualVolume(-1, 15, 5))
        assertEquals(0, VirtualVolumeManager.systemToVirtualVolume(-10, 15, 0))
    }

    @Test
    fun testSystemToVirtualVolume_sysVol1_retainsCurrentVirtual1To6() {
        for (currentV in 1..6) {
            val actual = VirtualVolumeManager.systemToVirtualVolume(1, 15, currentV)
            assertEquals(
                "systemToVirtualVolume(1, 15, $currentV) should retain $currentV",
                currentV,
                actual
            )
        }
    }

    @Test
    fun testSystemToVirtualVolume_sysVol1_clampsAbove6() {
        val testVolumes = intArrayOf(7, 10, 15, 30)
        for (currentV in testVolumes) {
            val actual = VirtualVolumeManager.systemToVirtualVolume(1, 15, currentV)
            assertEquals(
                "systemToVirtualVolume(1, 15, $currentV) should clamp to 6",
                6,
                actual
            )
        }
    }

    @Test
    fun testSystemToVirtualVolume_greaterOrEqualSysMaxVol() {
        assertEquals(30, VirtualVolumeManager.systemToVirtualVolume(15, 15, 10))
        assertEquals(30, VirtualVolumeManager.systemToVirtualVolume(16, 15, 10))
        assertEquals(30, VirtualVolumeManager.systemToVirtualVolume(100, 15, 0))
    }

    @Test
    fun testRoundTripStability_range7To30() {
        val sysMaxVol = 15
        for (v in 7..30) {
            val sysVol = VirtualVolumeManager.virtualToSystemVolume(v, sysMaxVol)
            val roundTrip = VirtualVolumeManager.systemToVirtualVolume(sysVol, sysMaxVol, v)
            val diff = abs(roundTrip - v)
            assertTrue(
                "Round-trip stability violated for v=$v: mapped to sysVol=$sysVol, roundTrip=$roundTrip (diff=$diff > 1)",
                diff <= 1
            )
        }
    }
}
