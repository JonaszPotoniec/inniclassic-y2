package com.themoon.y1.views

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpectrumLevelTest {

    private fun gainAtDb(db: Double) = Math.pow(10.0, db / 20.0).toFloat()

    @Test
    fun noCompensationWhileTheVisualizerLiftsTheLevel() {
        assertEquals(1f, SpectrumLevel.gainCompensation(1f), 0f)
        // -30 dB (step 15 of 30): the Visualizer boost of 32 still lifts it
        assertEquals(1f, SpectrumLevel.gainCompensation(gainAtDb(-30.0)), 0.02f)
    }

    @Test
    fun lowVolumeIsCompensatedToTheHeightOfFullVolume() {
        // Step 1 = -58 dB: the Visualizer lifts 30 dB, the compensation the other 28 dB
        val gain = gainAtDb(-58.0)
        val compensation = SpectrumLevel.gainCompensation(gain)
        assertEquals(1f, gain * SpectrumLevel.MAX_VISUALIZER_BOOST * compensation, 0.001f)
    }

    @Test
    fun compensationIsLimited() {
        assertEquals(SpectrumLevel.MAX_VISUALIZER_BOOST, SpectrumLevel.gainCompensation(1e-6f), 0f)
    }

    @Test
    fun muteAndBadValuesGiveNoCompensation() {
        assertEquals(1f, SpectrumLevel.gainCompensation(0f), 0f)
        assertEquals(1f, SpectrumLevel.gainCompensation(-1f), 0f)
        assertEquals(1f, SpectrumLevel.gainCompensation(Float.NaN), 0f)
    }

    @Test
    fun barHeightMovesTowardsTheTargetAndIsCapped() {
        val h = 190f
        // Small magnitude at step 1: with the compensation the bar is as high as at full volume
        val full = SpectrumLevel.barHeight(0f, 40f, 1f, h)
        val low = SpectrumLevel.barHeight(0f, 40f / 25f, 25f, h)
        assertEquals(full, low, 0.001f)
        var bar = 0f
        repeat(200) { bar = SpectrumLevel.barHeight(bar, 1000f, 1f, h) }
        assertTrue(bar <= h * 0.85f + 0.001f)
        assertEquals(h * 0.85f, bar, 0.01f)
    }
}
