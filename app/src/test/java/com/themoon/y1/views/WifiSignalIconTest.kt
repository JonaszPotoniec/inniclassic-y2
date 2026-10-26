package com.themoon.y1.views

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The unfilled arcs of the Wi-Fi signal icon must stay visible on the Classic Light and Dark rows. */
class WifiSignalIconTest {

    private val lightText = 0xFF1C1C1E.toInt()
    private val darkText = 0xFFF2F2F7.toInt()
    private val white = 0xFFFFFFFF.toInt()
    private val black = 0xFF000000.toInt()
    private val highlight = 0xFF2588DC.toInt() // middle of the Classic selected-row gradient

    @Test
    fun faintArcsKeepTheColourWithLessAlpha() {
        assertTrue(RowGlyphs.WIFI_FAINT_ALPHA_PERCENT in 35..45)
        val faint = RowGlyphs.faintColor(darkText)
        assertEquals(darkText and 0x00FFFFFF, faint and 0x00FFFFFF)
        assertEquals(255 * RowGlyphs.WIFI_FAINT_ALPHA_PERCENT / 100, faint ushr 24)
    }

    @Test
    fun faintArcsAreVisibleAndDifferFromFullArcs() {
        // (icon colour, row background, minimum contrast of the faint arcs on the background)
        val cases = listOf(
            Triple(darkText, black, 3.0),   // Classic Dark
            Triple(lightText, white, 2.0),  // Classic Light
            Triple(white, highlight, 1.5),  // highlighted row, both themes
        )
        for ((color, bg, minContrast) in cases) {
            val faint = over(RowGlyphs.faintColor(color), bg)
            val full = over(color, bg)
            val onBg = contrast(faint, bg)
            val toFull = contrast(faint, full)
            assertTrue("faint on bg ${hex(bg)}: $onBg", onBg >= minContrast)
            assertTrue("faint vs full on bg ${hex(bg)}: $toFull", toFull >= 2.0)
        }
    }

    private fun hex(c: Int) = Integer.toHexString(c)

    /** [fg] (with alpha) drawn over the opaque [bg]. */
    private fun over(fg: Int, bg: Int): Int {
        val a = (fg ushr 24) / 255.0
        fun ch(shift: Int) = Math.round(((fg shr shift) and 0xFF) * a + ((bg shr shift) and 0xFF) * (1 - a)).toInt()
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    private fun luminance(c: Int): Double {
        fun lin(v: Int): Double {
            val s = v / 255.0
            return if (s <= 0.03928) s / 12.92 else Math.pow((s + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * lin((c shr 16) and 0xFF) + 0.7152 * lin((c shr 8) and 0xFF) + 0.0722 * lin(c and 0xFF)
    }

    private fun contrast(a: Int, b: Int): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05)
    }
}
