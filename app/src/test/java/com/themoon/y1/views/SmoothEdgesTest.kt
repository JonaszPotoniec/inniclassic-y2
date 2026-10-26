package com.themoon.y1.views

import com.themoon.y1.views.SmoothEdges.Box
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SmoothEdgesTest {

    @Test
    fun borderIsTwoPixelsOnTheY2AndNeverZero() {
        assertEquals(2, SmoothEdges.borderPx(1f))
        assertEquals(3, SmoothEdges.borderPx(1.5f))
        assertEquals(1, SmoothEdges.borderPx(0.1f))
    }

    @Test
    fun borderedBitmapAddsTheBorderOnEverySide() {
        assertEquals(Pair(224, 224), SmoothEdges.borderedSize(220, 220, 2))
        assertEquals(Pair(204, 54), SmoothEdges.borderedSize(200, 50, 2))
    }

    @Test
    fun centerCropOfSquareArtFillsTheView() {
        assertEquals(Box(0, 0, 220, 220), SmoothEdges.centerCropBounds(500, 500, 220, 220))
    }

    @Test
    fun centerCropOfWideArtCutsTheSides() {
        // 400 x 200 scaled by 1.1 to 440 x 220, centred: 110 px past each side.
        assertEquals(Box(-110, 0, 330, 220), SmoothEdges.centerCropBounds(400, 200, 220, 220))
    }

    @Test
    fun centerCropOfTallArtCutsTopAndBottom() {
        assertEquals(Box(0, -50, 200, 250), SmoothEdges.centerCropBounds(256, 384, 200, 200))
    }

    @Test
    fun centerCropWithoutIntrinsicSizeFillsTheView() {
        assertEquals(Box(0, 0, 220, 32), SmoothEdges.centerCropBounds(-1, -1, 220, 32))
    }

    @Test
    fun unflippedViewShowsItsTopRows() {
        val s = SmoothEdges.visibleStrip(viewTop = 0, viewHeight = 220, parentHeight = 32, flipped = false, pivotY = 110f)!!
        assertEquals(0, s.top)
        assertEquals(32, s.bottom)
        assertEquals(0f, s.opaqueY, 0f)
        assertEquals(32f, s.clearY, 0f)
    }

    @Test
    fun flippedReflectionShowsTheBottomRowsAndFadesUpwards() {
        // Now Playing: 220 px art flipped about its centre, in a 32 px frame.
        val s = SmoothEdges.visibleStrip(viewTop = 0, viewHeight = 220, parentHeight = 32, flipped = true, pivotY = 110f)!!
        assertEquals(188, s.top)
        assertEquals(220, s.bottom)
        // Opaque where it touches the art (frame top = view row 220), clear at the frame bottom.
        assertEquals(220f, s.opaqueY, 0f)
        assertEquals(188f, s.clearY, 0f)
    }

    @Test
    fun ipodLayoutReflectionUsesTheFrameHeight() {
        val s = SmoothEdges.visibleStrip(viewTop = 0, viewHeight = 204, parentHeight = 30, flipped = true, pivotY = 102f)!!
        assertEquals(174, s.top)
        assertEquals(204, s.bottom)
    }

    @Test
    fun viewWithNoRowsInsideTheParentShowsNothing() {
        assertNull(SmoothEdges.visibleStrip(viewTop = 40, viewHeight = 220, parentHeight = 32, flipped = false, pivotY = 110f))
    }
}
