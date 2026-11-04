package com.themoon.y1.views

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpectrumColorsTest {

    private val white = 0xFFFFFFFF.toInt()
    private val black = 0xFF000000.toInt()
    private val accent = 0xFF2588DC.toInt()

    @Test
    fun contrastOfBlackOnWhiteIs21() {
        assertEquals(21.0, SpectrumColors.contrast(black, white), 0.01)
    }

    @Test
    fun lightCoverColourIsDarkenedOnWhite() {
        val paleYellow = 0xFFF5E6A0.toInt()
        val result = SpectrumColors.spectrumColor(paleYellow, white, accent)
        assertTrue(SpectrumColors.contrast(result, white) >= SpectrumColors.MIN_CONTRAST)
        // Darkened towards black: red stays the largest channel, so the hue is kept
        val r = (result shr 16) and 0xFF
        val b = result and 0xFF
        assertTrue(r > b)
    }

    @Test
    fun darkCoverColourIsLightenedOnBlack() {
        val darkRed = 0xFF400000.toInt()
        val result = SpectrumColors.spectrumColor(darkRed, black, accent)
        assertTrue(SpectrumColors.contrast(result, black) >= SpectrumColors.MIN_CONTRAST)
    }

    @Test
    fun colourWithEnoughContrastIsKept() {
        val darkBlue = 0xFF102080.toInt()
        assertEquals(darkBlue, SpectrumColors.spectrumColor(darkBlue, white, accent))
    }

    @Test
    fun greyOrMissingCoverUsesAccent() {
        assertEquals(accent, SpectrumColors.spectrumColor(0xFFB0B0B0.toInt(), white, accent))
        assertEquals(accent, SpectrumColors.spectrumColor(null, white, accent))
    }
}
