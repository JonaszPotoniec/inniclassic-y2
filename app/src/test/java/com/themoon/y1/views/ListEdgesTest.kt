package com.themoon.y1.views

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ListEdgesTest {

    @Test
    fun fadeLength_isTwoThirdsOfTheSmallestRow() {
        assertEquals(25, ListEdges.fadeLength(listOf(38, 38, 38), 33))
        assertEquals(22, ListEdges.fadeLength(listOf(64, 33, 48), 38))
    }

    @Test
    fun fadeLength_usesTheFallbackWithoutMeasuredRows() {
        assertEquals(22, ListEdges.fadeLength(emptyList(), 33))
        assertEquals(25, ListEdges.fadeLength(listOf(0, 0), 38))
    }

    @Test
    fun revealDelta_rowClearOfTheFades_doesNotScroll() {
        assertEquals(0, ListEdges.revealDelta(100, 138, 0, 300, 25, rowsBefore = true, rowsAfter = true))
    }

    @Test
    fun revealDelta_rowInTheBottomFade_scrollsItAboveTheFade() {
        // Row 266..304 in a 300 px list with a 25 px fade: the bottom must come to 275.
        assertEquals(29, ListEdges.revealDelta(266, 304, 0, 300, 25, rowsBefore = true, rowsAfter = true))
    }

    @Test
    fun revealDelta_rowInTheTopFade_scrollsItBelowTheFade() {
        assertEquals(-35, ListEdges.revealDelta(-10, 28, 0, 300, 25, rowsBefore = true, rowsAfter = true))
    }

    @Test
    fun revealDelta_firstAndLastRows_haveNoFadeOnTheirSide() {
        assertEquals(0, ListEdges.revealDelta(0, 38, 0, 300, 25, rowsBefore = false, rowsAfter = true))
        assertEquals(0, ListEdges.revealDelta(262, 300, 0, 300, 25, rowsBefore = true, rowsAfter = false))
        assertEquals(4, ListEdges.revealDelta(266, 304, 0, 300, 25, rowsBefore = true, rowsAfter = false))
    }

    @Test
    fun revealDelta_respectsThePadding() {
        assertEquals(-5, ListEdges.revealDelta(30, 68, 10, 300, 25, rowsBefore = true, rowsAfter = true))
    }

    @Test
    fun revealDelta_rowTallerThanTheClearArea_alignsItsTop() {
        assertEquals(-25, ListEdges.revealDelta(0, 80, 0, 100, 25, rowsBefore = true, rowsAfter = true))
    }

    @Test
    fun solidColor_firstOpaqueBackgroundBehindTheList() {
        assertEquals(0xFFFFFFFF.toInt(), ListEdges.solidColor(listOf(0, 0, 0xFFFFFFFF.toInt())))
        assertEquals(0xFF000000.toInt(), ListEdges.solidColor(listOf(0xFF000000.toInt(), 0xFFFFFFFF.toInt())))
    }

    @Test
    fun solidColor_isZeroForTranslucentOrNonColourBackgrounds() {
        assertEquals(0, ListEdges.solidColor(listOf(0, 0x66000000, 0xFFFFFFFF.toInt())))
        assertEquals(0, ListEdges.solidColor(listOf(0, null, 0xFFFFFFFF.toInt())))
        assertEquals(0, ListEdges.solidColor(listOf(0, 0)))
        assertEquals(0, ListEdges.solidColor(emptyList()))
    }

    @Test
    fun edgeStrength_isFullWheneverMoreContentIsPastTheEdge() {
        assertEquals(1f, ListEdges.edgeStrength(true), 0f)
        assertEquals(0f, ListEdges.edgeStrength(false), 0f)
    }

    @Test
    fun listHasMoreBelow_alsoWhenTheLastRowIsCutByOnePixelOrRowsAreNotAttached() {
        // Row boundary on the edge, more rows not attached yet.
        assertEquals(true, ListEdges.listHasMoreBelow(8, 20, 330, 330))
        // The last row is cut by 1 px.
        assertEquals(true, ListEdges.listHasMoreBelow(19, 20, 331, 330))
        // The last row ends on the edge: the list ends here.
        assertEquals(false, ListEdges.listHasMoreBelow(19, 20, 330, 330))
    }

    @Test
    fun listHasMoreAbove_alsoWhenTheFirstRowIsCutByOnePixel() {
        assertEquals(true, ListEdges.listHasMoreAbove(3, 0, 0))
        assertEquals(true, ListEdges.listHasMoreAbove(0, -1, 0))
        assertEquals(false, ListEdges.listHasMoreAbove(0, 0, 0))
    }

    @Test
    fun fadeAlpha_goesFromAlmostOpaqueAtTheEdgeToAlmostClear() {
        assertEquals(250, ListEdges.fadeAlpha(0, 25))
        assertEquals(5, ListEdges.fadeAlpha(24, 25))
        assertEquals(128, ListEdges.fadeAlpha(12, 25))
        for (i in 1 until 25) assertTrue(ListEdges.fadeAlpha(i, 25) < ListEdges.fadeAlpha(i - 1, 25))
    }
}
