package com.themoon.y1.views

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DisclosureChevronTest {
    private val eps = 1e-4f

    @Test
    fun chevronIsCentredOnTheCapBandAndEndsAtTheRightEdge() {
        for (cap in floatArrayOf(11f, 15f, 17f)) {
            val p = DisclosureChevron.points(cap)
            val half = DisclosureChevron.strokeWidth(cap) / 2
            val h = DisclosureChevron.height(cap)
            // Outer edge of the round caps and join: from 0 to the chevron height, a little less than the cap height.
            assertEquals(0f, p[1] - half, eps)
            assertEquals(h, p[5] + half, eps)
            assertTrue(h < cap && h > 0.8f * cap)
            // Centred on the cap band.
            val top = DisclosureChevron.top(cap)
            assertEquals(cap / 2, top + p[3], eps)
            assertEquals(cap - (top + h), top, eps)
            // Left at 0, the tip on the right edge.
            assertEquals(0f, p[0] - half, eps)
            assertEquals(DisclosureChevron.width(cap), p[2] + half, eps)
        }
    }

    @Test
    fun proportionsAreAsMacOs() {
        // macOS System Settings: cap height 20 px, chevron 18 x 11 px, stroke ~2.7 px.
        assertEquals(18f, DisclosureChevron.height(20f), 0.5f)
        assertEquals(11f, DisclosureChevron.width(20f), 0.5f)
        assertEquals(2.7f, DisclosureChevron.strokeWidth(20f), 0.3f)
    }

    @Test
    fun strokeIsThinnerThanTheTick() {
        for (cap in floatArrayOf(11f, 15f, 17f)) {
            assertTrue(DisclosureChevron.strokeWidth(cap) < CheckMarkStroke.of(cap))
            assertTrue(DisclosureChevron.strokeWidth(cap) >= 1f)
        }
    }

    @Test
    fun viewIsTheGapAndTheChevron() {
        assertEquals(15f * (DisclosureChevron.GAP_RATIO + DisclosureChevron.WIDTH_RATIO), DisclosureChevron.viewWidth(15f), eps)
    }

    @Test
    fun splitTakesTheTextChevronFromTheValue() {
        assertEquals("" to true, DisclosureChevron.split("〉 "))
        assertEquals("" to true, DisclosureChevron.split("〉"))
        assertEquals("Edit" to true, DisclosureChevron.split("Edit 〉"))
        assertEquals("Normal" to true, DisclosureChevron.split("Normal ›"))
        assertEquals("On" to false, DisclosureChevron.split("On"))
        assertEquals("" to false, DisclosureChevron.split(""))
        assertFalse(DisclosureChevron.split("v1.0").second)
    }

    /** The tick of the selection lists has a stroke of 0.19 of the cap height (CheckMark, other branch). */
    private object CheckMarkStroke {
        fun of(cap: Float) = cap * 0.19f
    }
}
