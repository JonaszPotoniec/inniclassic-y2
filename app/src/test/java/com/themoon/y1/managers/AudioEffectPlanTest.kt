package com.themoon.y1.managers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioEffectPlanTest {

    private fun settings(
        softwareEq: Boolean = false,
        eqProfile: String = "preset_0",
        eqBandLevels: IntArray = IntArray(0),
        bassBoostStep: Int = 0,
        virtualizerStep: Int = 0,
        crossfeedStep: Int = 0,
        soundCheck: Boolean = false
    ) = AudioEffectSettings(softwareEq, eqProfile, eqBandLevels, bassBoostStep, virtualizerStep, crossfeedStep, soundCheck)

    private val none = AudioEffectPlan(
        hardwareEq = false, softwareEq = false, bassBoost = false,
        virtualizer = false, crossfeed = false, loudnessEnhancer = false
    )

    @Test
    fun defaultSettings_needNoEffect() {
        assertEquals(none, AudioEffectPlan.from(settings()))
    }

    @Test
    fun hardwarePresetOff_needsNoEqualizer() {
        assertFalse(AudioEffectPlan.from(settings(eqProfile = "preset_0")).hardwareEq)
        assertFalse(AudioEffectPlan.from(settings(eqProfile = "preset_x")).hardwareEq)
    }

    @Test
    fun hardwarePreset_needsEqualizer() {
        val plan = AudioEffectPlan.from(settings(eqProfile = "preset_3"))
        assertTrue(plan.hardwareEq)
        assertFalse(plan.softwareEq)
    }

    @Test
    fun hardwareCustomProfile_needsEqualizerOnlyWhenNotFlat() {
        assertFalse(AudioEffectPlan.from(settings(eqProfile = "custom_A", eqBandLevels = IntArray(5))).hardwareEq)
        val plan = AudioEffectPlan.from(settings(eqProfile = "custom_A", eqBandLevels = intArrayOf(0, 0, -300, 0, 0)))
        assertTrue(plan.hardwareEq)
        assertFalse(plan.softwareEq)
    }

    @Test
    fun softwareEq_neverNeedsHardwareEqualizer() {
        val flat = AudioEffectPlan.from(settings(softwareEq = true, eqProfile = "preset_5", eqBandLevels = IntArray(10)))
        assertEquals(none, flat)
        val shaped = AudioEffectPlan.from(settings(softwareEq = true, eqProfile = "custom_A", eqBandLevels = intArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 0, 200)))
        assertTrue(shaped.softwareEq)
        assertFalse(shaped.hardwareEq)
    }

    @Test
    fun softwareEqPreset_usesFlatLevels() {
        assertFalse(AudioEffectPlan.from(settings(softwareEq = true, eqProfile = "preset_2", eqBandLevels = IntArray(10))).softwareEq)
        assertTrue(AudioEffectPlan.from(settings(softwareEq = true, eqProfile = "preset_2", eqBandLevels = intArrayOf(100))).softwareEq)
    }

    @Test
    fun steps_needEffectOnlyAboveZero() {
        for (step in 1..3) {
            assertEquals(none.copy(bassBoost = true), AudioEffectPlan.from(settings(bassBoostStep = step)))
            assertEquals(none.copy(virtualizer = true), AudioEffectPlan.from(settings(virtualizerStep = step)))
            assertEquals(none.copy(crossfeed = true), AudioEffectPlan.from(settings(crossfeedStep = step)))
        }
    }

    @Test
    fun soundCheck_needsOnlyLoudnessEnhancer() {
        assertEquals(none.copy(loudnessEnhancer = true), AudioEffectPlan.from(settings(soundCheck = true)))
    }

    @Test
    fun presetIndexAndLevelsName() {
        assertEquals(4, AudioEffectPlan.presetIndex("preset_4"))
        assertEquals(0, AudioEffectPlan.presetIndex("preset_"))
        assertNull(AudioEffectPlan.presetIndex("custom_User EQ 1"))
        assertEquals("User EQ 1", AudioEffectPlan.levelsName("custom_User EQ 1", softwareEq = false))
        assertEquals("Flat", AudioEffectPlan.levelsName("preset_4", softwareEq = true))
        assertNull(AudioEffectPlan.levelsName("preset_4", softwareEq = false))
        assertEquals(5, AudioEffectPlan.bandCount(false))
        assertEquals(10, AudioEffectPlan.bandCount(true))
    }

    @Test
    fun bandLevelKey_isTheSavedProfileKey() {
        // Saved profiles use this pref key: do not change it
        assertEquals("eq_custom_User EQ 1_band_0", AudioEffectPlan.bandLevelKey("User EQ 1", 0))
        assertEquals("eq_custom_Flat_band_9", AudioEffectPlan.bandLevelKey("Flat", 9))
    }

    @Test
    fun stepBandLevel_staysInRange() {
        assertEquals(100, AudioEffectPlan.stepBandLevel(0, 100))
        assertEquals(-100, AudioEffectPlan.stepBandLevel(0, -100))
        assertEquals(1500, AudioEffectPlan.stepBandLevel(1500, 100))
        assertEquals(-1500, AudioEffectPlan.stepBandLevel(-1450, -100))
    }

    @Test
    fun strengthForStep_matchesOldMapping() {
        assertEquals(0.toShort(), AudioEffectPlan.strengthForStep(0))
        assertEquals(333.toShort(), AudioEffectPlan.strengthForStep(1))
        assertEquals(666.toShort(), AudioEffectPlan.strengthForStep(2))
        assertEquals(1000.toShort(), AudioEffectPlan.strengthForStep(3))
    }
}
