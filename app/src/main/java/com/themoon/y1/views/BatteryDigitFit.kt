package com.themoon.y1.views

import kotlin.math.roundToInt

/**
 * Puts all digits of the battery percentage in one box of whole pixels.
 *
 * The digits of a font have different outline heights: round digits (0, 3, 6, 8, 9) go a little above
 * and below the flat ones (1, 4, 7). With hinting, Android 4.4 snaps some digits to the pixel grid and
 * not others, so at 8-12 px the "1" of "100" showed 1 px taller than the "0"s. [BatteryIconView] draws
 * each digit as an outline and maps its top and bottom on the edges of this box instead.
 *
 * No Android dependencies, so the maths can be unit-tested.
 */
object BatteryDigitFit {

    /** Box height in whole pixels, from the heights above the baseline of the digits (overshoot not included). */
    fun boxHeight(digitHeights: FloatArray): Int {
        var min = Float.MAX_VALUE
        for (h in digitHeights) if (h > 0f && h < min) min = h
        if (min == Float.MAX_VALUE) return 1
        return min.roundToInt().coerceAtLeast(1)
    }

    /** Whole-pixel baseline that puts the centre of a box [height] tall on [centerY]. */
    fun baseline(centerY: Float, height: Int): Int = (centerY + height / 2f).roundToInt()

    /**
     * Vertical scale for a glyph whose outline spans [top]..[bottom] (y down, baseline at 0), so that it
     * spans [baseline] - [height]..[baseline]. The glyph y is mapped as y * scale + [offset].
     */
    fun scale(top: Float, bottom: Float, height: Int): Float {
        val span = bottom - top
        return if (span > 0f) height / span else 1f
    }

    fun offset(top: Float, bottom: Float, baseline: Int, height: Int): Float =
        (baseline - height) - top * scale(top, bottom, height)
}
