package com.themoon.y1.views

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CheckMarkTest {
    private val eps = 1e-4f

    @Test
    fun tickIsAsTallAsTheCapHeight() {
        for (cap in floatArrayOf(15f, 17f, 25.5f)) {
            val p = CheckMark.points(cap)
            val half = CheckMark.strokeWidth(cap) / 2
            val ys = floatArrayOf(p[1], p[3], p[5])
            // Outer edge of the round caps and join: top at 0, bottom (the baseline) at the cap height.
            assertEquals(0f, ys.minOrNull()!! - half, eps)
            assertEquals(cap, ys.maxOrNull()!! + half, eps)
            // Left edge at 0, right edge at the tick width.
            assertEquals(0f, p[0] - half, eps)
            assertEquals(CheckMark.width(cap), p[4] + half, eps)
        }
    }

    @Test
    fun tickFitsInTheColumnWithSpaceBeforeTheText() {
        // Y2 (density 1): 21 px and 23 px Nimbus Sans Bold have cap heights of ~15 px and ~17 px.
        for (cap in floatArrayOf(15f, 17f)) {
            val gap = CheckMark.COLUMN_DP - CheckMark.width(cap)
            assertTrue("gap $gap at cap $cap", gap >= 6f)
        }
    }

    @Test
    fun strokeIsAboutAsHeavyAsTheLetters() {
        // The stem of "I" in Nimbus Sans Bold: 3.15 px at 21 px (cap height 15.3 px).
        assertEquals(3.15f, CheckMark.strokeWidth(15.3f), 0.35f)
    }

    @Test
    fun cornerIsTheLowestPoint() {
        val p = CheckMark.points(17f)
        assertTrue(p[3] > p[1] && p[3] > p[5])
        assertTrue(p[0] < p[2] && p[2] < p[4])
    }
}
