package com.themoon.y1.views

/**
 * Bar height rules for the Now Playing spectrum. Plain maths, so it is unit-testable.
 *
 * The Android Visualizer reads the audio after the player volume (the software gain of the volume steps). In its
 * normalised mode it lifts each capture to full scale, but by [MAX_VISUALIZER_BOOST] (30 dB) at most. At lower
 * volumes the bars got shorter with each step down, and near mute only dots showed.
 */
object SpectrumLevel {

    /** The largest boost of the Visualizer's normalised mode: a shift of 3 bits in place of 8 = 2^5. */
    const val MAX_VISUALIZER_BOOST = 32f

    /**
     * Factor for the FFT magnitudes at the player gain [masterGain] (0..1): it removes the part of the volume that
     * the Visualizer does not lift, so the bars have the same height at each volume. 1 at mute (the
     * Visualizer then gets silence: no bars can show) and when the Visualizer lifts all of it.
     */
    fun gainCompensation(masterGain: Float): Float {
        if (masterGain <= 0f || masterGain.isNaN()) return 1f
        return (1f / (MAX_VISUALIZER_BOOST * masterGain)).coerceIn(1f, MAX_VISUALIZER_BOOST)
    }

    /** Height of a bar: [current] moved part of the way to the target of [magnitude], at most 85 % of [viewHeight]. */
    fun barHeight(current: Float, magnitude: Float, compensation: Float, viewHeight: Float): Float {
        val target = minOf(viewHeight * 0.85f, magnitude * compensation * viewHeight / 100f)
        return current + (target - current) * 0.15f
    }
}
