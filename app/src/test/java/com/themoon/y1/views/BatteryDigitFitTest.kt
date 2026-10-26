package com.themoon.y1.views

import org.junit.Assert.assertEquals
import org.junit.Test

class BatteryDigitFitTest {

    // Nimbus Sans Bold (units per em 1000): "1" spans 0..709 above the baseline, "0" spans -23..724.
    private fun one(size: Float) = floatArrayOf(-0.709f * size, 0f)
    private fun zero(size: Float) = floatArrayOf(-0.724f * size, 0.023f * size)

    private fun mapped(glyph: FloatArray, baseline: Int, height: Int): Pair<Float, Float> {
        val s = BatteryDigitFit.scale(glyph[0], glyph[1], height)
        val o = BatteryDigitFit.offset(glyph[0], glyph[1], baseline, height)
        return Pair(glyph[0] * s + o, glyph[1] * s + o)
    }

    @Test
    fun boxHeightUsesTheFlatDigits() {
        // 7.09 px (flat "1", "4", "7") and 7.24 px (round "0"): the box is 7 px.
        assertEquals(7, BatteryDigitFit.boxHeight(floatArrayOf(7.24f, 7.09f, 7.24f, 7.09f)))
        assertEquals(9, BatteryDigitFit.boxHeight(floatArrayOf(8.69f, 8.51f)))
    }

    @Test
    fun boxHeightIgnoresEmptyGlyphs() {
        assertEquals(6, BatteryDigitFit.boxHeight(floatArrayOf(0f, 5.8f)))
        assertEquals(1, BatteryDigitFit.boxHeight(floatArrayOf()))
        assertEquals(1, BatteryDigitFit.boxHeight(floatArrayOf(0.2f)))
    }

    @Test
    fun baselineIsAWholePixelAndCentresTheBox() {
        // The battery shell centre: 10 for a 20 px icon, 8 for a 16 px icon.
        assertEquals(14, BatteryDigitFit.baseline(10f, 7))
        assertEquals(13, BatteryDigitFit.baseline(10f, 6))
        assertEquals(11, BatteryDigitFit.baseline(8f, 6))
    }

    @Test
    fun allDigitsGetTheSameTopAndBottomAtEachSize() {
        // Text sizes of the battery: 0.50 and 0.58 of the icon height (16 and 20 px), and sizes reduced to fit.
        for (size in floatArrayOf(8f, 9.28f, 10f, 11.6f, 7.3f, 8.85f)) {
            val height = BatteryDigitFit.boxHeight(floatArrayOf(0.709f * size, 0.724f * size))
            val baseline = BatteryDigitFit.baseline(10f, height)
            for (glyph in arrayOf(one(size), zero(size))) {
                val (top, bottom) = mapped(glyph, baseline, height)
                assertEquals("top at size $size", (baseline - height).toFloat(), top, 1e-4f)
                assertEquals("bottom at size $size", baseline.toFloat(), bottom, 1e-4f)
            }
        }
    }

    @Test
    fun emptyGlyphIsNotScaled() {
        assertEquals(1f, BatteryDigitFit.scale(0f, 0f, 7), 0f)
    }
}
