package com.themoon.y1.controllers.coverflow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CoverFlowGeometryTest {

    private val tilt = CoverFlowGeometry.sideTilt()
    private val size = CoverFlowGeometry.COVER_SIZE.toFloat()

    @Test
    fun coverTop_matchesIpodBelowA30pxStatusBar() {
        // 360 px screen, 30 px status bar: cover y 63..254, album baseline 308, artist baseline 336.
        val top = 30 + CoverFlowGeometry.coverTop(330)
        assertEquals(63, top)
        assertEquals(254, top + CoverFlowGeometry.COVER_SIZE - 1)
        assertEquals(308, top + CoverFlowGeometry.TITLE_BASELINE)
        assertEquals(336, top + CoverFlowGeometry.ARTIST_BASELINE)
    }

    @Test
    fun coverTop_keepsTheProportionsBelowA26pxStatusBar() {
        // 334 px area: 61 px free, shared 33:24 above the cover and below the artist baseline.
        val gap = CoverFlowGeometry.coverTop(334)
        assertEquals(35, gap)
        assertEquals(26, 334 - gap - CoverFlowGeometry.ARTIST_BASELINE)
    }

    @Test
    fun reflection_isUnderTheCoverAndIpodLength() {
        // 52 px on the iPod for a 128 px cover, ~25-30 % opaque at the top.
        assertEquals(78, CoverFlowGeometry.REFLECTION_HEIGHT)
        val alpha = CoverFlowGeometry.REFLECTION_TOP_ALPHA / 255f
        assertTrue(alpha in 0.25f..0.30f)
        // The text is on the reflection, as on the iPod.
        assertTrue(CoverFlowGeometry.TITLE_BASELINE < CoverFlowGeometry.COVER_SIZE + CoverFlowGeometry.REFLECTION_HEIGHT)
        assertEquals(28, CoverFlowGeometry.ARTIST_BASELINE - CoverFlowGeometry.TITLE_BASELINE)
    }

    @Test
    fun sideTilt_isAboutSeventyDegrees() {
        assertTrue(tilt.rotationY in 68f..75f)
        assertEquals(0.912f, tilt.scale, 0.005f)
        assertTrue(tilt.cameraDistancePx > 0f)
    }

    @Test
    fun sideCover_projectsToTheIpodShape() {
        val (inner, outer) = CoverFlowGeometry.projectedEdges(tilt)
        assertEquals(60f, outer - inner, 0.01f)
        val (innerK, outerK) = CoverFlowGeometry.edgeHeightRatios(tilt)
        assertEquals(195f, outerK * size, 0.01f)
        assertEquals(159f, innerK * size, 0.01f)
    }

    @Test
    fun sideCovers_showStripsOf60And42And42() {
        val (inner, outer) = CoverFlowGeometry.projectedEdges(tilt)
        // The first side cover starts at the edge of the centre cover.
        assertEquals(size / 2f, CoverFlowGeometry.translationX(1, tilt) + inner, 0.01f)
        val outerEdges = (1..3).map { CoverFlowGeometry.translationX(it, tilt) + outer }
        assertEquals(156f, outerEdges[0], 0.01f)
        assertEquals(198f, outerEdges[1], 0.01f)
        // The third cover ends at the screen edge (240 px from the centre).
        assertEquals(240f, outerEdges[2], 0.01f)
        // The fourth is past the screen edge, under the third.
        assertTrue(CoverFlowGeometry.translationX(4, tilt) + inner > CoverFlowGeometry.translationX(3, tilt) + inner)
        assertEquals(0f, CoverFlowGeometry.translationX(0, tilt), 0f)
    }

    @Test
    fun pivot_putsTheInnerEndWhereTheIpodHasIt() {
        // iPod: inner end top 16 px below the centre cover top, bottom 6 px above its bottom (x 1.5).
        val (innerK, outerK) = CoverFlowGeometry.edgeHeightRatios(tilt)
        val pivot = size * CoverFlowGeometry.PIVOT_Y_FRACTION
        assertEquals(24f, pivot - innerK * pivot, 1f)
        assertEquals(9f, size - (pivot + innerK * (size - pivot)), 1.5f)
        // The outer edge is a little taller than the centre cover, at both ends.
        assertTrue(pivot - outerK * pivot < 0f)
        assertTrue(pivot + outerK * (size - pivot) > size)
    }
}
