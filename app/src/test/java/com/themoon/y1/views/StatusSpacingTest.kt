package com.themoon.y1.views

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StatusSpacingTest {
    private val eps = 1e-4f
    private val ink = 0xFF1A5DB0.toInt()

    /** [rows] of '#' (ink), '.' (empty) and '+' (faint ink, alpha 0x40). */
    private fun pixels(vararg rows: String): IntArray =
        rows.joinToString("").map {
            when (it) {
                '#' -> ink
                '+' -> 0x401A5DB0
                else -> 0
            }
        }.toIntArray()

    @Test
    fun flatItemsKeepTheGapOf8() {
        val bar = pixels("##", "##", "##")
        val b = StatusSpacing.bearings(bar, 2, 0, 3)
        assertArrayEquals(floatArrayOf(0f, 0f), b, eps)
        assertEquals(8, StatusSpacing.gap(b[1], b[0]))
    }

    @Test
    fun emptySidesCountUpToTheCapPerRow() {
        // Rows: ink at the left only; ink in the middle; no ink (counts as the cap, 3).
        val icon = pixels(
            "#.......",
            "...##...",
            "........"
        )
        val b = StatusSpacing.bearings(icon, 8, 0, 3)
        assertEquals((0f + 3f + 3f) / 3f, b[0], eps)
        assertEquals((3f + 3f + 3f) / 3f, b[1], eps)
    }

    @Test
    fun onlyTheRowsOfTheBoxCountAndFaintPixelsAreNotInk() {
        val icon = pixels(
            "....",
            "+..#",
            "#..#",
            "...."
        )
        val b = StatusSpacing.bearings(icon, 4, 1, 3)
        // Row 1: the faint pixel is not ink (left 3); row 2: left 0.
        assertEquals(1.5f, b[0], eps)
        assertEquals(0f, b[1], eps)
    }

    @Test
    fun gapIsTheTargetMinusTheFacingBearingsWithAMinimum() {
        assertEquals(6, StatusSpacing.gap(1.2f, 0.9f))
        assertEquals(4, StatusSpacing.gap(2.4f, 1.7f))
        assertEquals(StatusSpacing.MIN_GAP, StatusSpacing.gap(3f, 3f))
    }

    @Test
    fun inkColumnsOfTheDrawnTextAlsoCountFaintPixels() {
        val text = pixels(
            "..#....",
            "..##..+",
            "......."
        )
        assertArrayEquals(intArrayOf(2, 6), StatusSpacing.inkColumns(text, 7))
        assertNull(StatusSpacing.inkColumns(pixels("...", "..."), 3))
    }

    // A fan-like right side (ink at the edge only in the top rows) and a rune-like left side (arm ends at the edge
    // in the top and bottom rows, empty middle), and the same rune with a dot at the left edge in the middle rows.
    private val fan = pixels(
        "######",
        "#####.",
        "####..",
        "###...",
        "##....",
        "#....."
    )
    private val rune = pixels(
        "#.....",
        "..#...",
        "...#..",
        "...#..",
        "..#...",
        "#....."
    )
    private val runeWithDots = pixels(
        "..#.......",
        "....#.....",
        "#....#...#",
        "#....#...#",
        "....#.....",
        "..#......."
    )

    @Test
    fun runeWithoutDotsGetsTheOpticalGap() {
        val fanRight = StatusSpacing.bearings(fan, 6, 0, 6)[1]
        val runeLeft = StatusSpacing.iconBearings(rune, 6, 0, 6, sideDots = false)[0]
        assertEquals((0f + 1f + 2f + 3f + 3f + 3f) / 6f, fanRight, eps)
        assertEquals((0f + 2f + 3f + 3f + 2f + 0f) / 6f, runeLeft, eps)
        assertEquals(4, StatusSpacing.gap(fanRight, runeLeft))
    }

    @Test
    fun runeWithDotsGetsTheGapOfAFlatSideOnBothSides() {
        val fanRight = StatusSpacing.bearings(fan, 6, 0, 6)[1]
        val b = StatusSpacing.iconBearings(runeWithDots, 10, 0, 6, sideDots = true)
        assertArrayEquals(floatArrayOf(0f, 0f), b, eps)
        // Fan -> left dot: the flat-side gap (more than the gap to the rune alone); right dot -> pause bars: 8.
        assertEquals(StatusSpacing.gap(fanRight, 0f), StatusSpacing.gap(fanRight, b[0]))
        assertEquals(6, StatusSpacing.gap(fanRight, b[0]))
        assertEquals(8, StatusSpacing.gap(b[1], 0f))
        // The bearings of the drawn pixels would bring the fan nearer to the dot.
        val measured = StatusSpacing.bearings(runeWithDots, 10, 0, 6)
        assertTrue(StatusSpacing.gap(fanRight, measured[0]) < StatusSpacing.gap(fanRight, b[0]))
    }
}
