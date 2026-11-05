package com.themoon.y1.views

import com.themoon.y1.views.MenuSlideMotion.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MenuSlideMotionTest {

    private val d = FloatArray(2)
    private val o = FloatArray(3)

    @Test
    fun curves() {
        assertEquals(0f, MenuSlideMotion.decelerate(0f), 1e-6f)
        assertEquals(0.75f, MenuSlideMotion.decelerate(0.5f), 1e-6f)
        assertEquals(1f, MenuSlideMotion.decelerate(2f), 1e-6f)
        assertEquals(0.25f, MenuSlideMotion.accelerate(0.5f), 1e-6f)
        assertEquals(0f, MenuSlideMotion.accelerate(-1f), 1e-6f)
    }

    @Test
    fun paneSlideFitsTheIpodFrames() {
        // iPod main menu -> Music, measured each 33.4 ms from about 0.4 frame after the start:
        // 0.31, 0.50, 0.66, 0.80, 0.89, 0.95 (±0.03)
        val measured = floatArrayOf(0.31f, 0.50f, 0.66f, 0.80f, 0.885f, 0.95f)
        for (i in measured.indices) {
            val t = ((i + 1.1f) * 33.37f).toLong()
            MenuSlideMotion.depth(Kind.PANE, true, t, d)
            assertEquals("frame ${i + 1}", measured[i], d[0], 0.05f)
        }
    }

    @Test
    fun pushAndPopEnds() {
        for (k in Kind.values()) {
            MenuSlideMotion.depth(k, true, 0L, d)
            assertEquals(0f, d[0], 1e-6f)
            assertEquals(0f, d[1], 1e-6f)
            MenuSlideMotion.depth(k, true, MenuSlideMotion.durationMs(k, true), d)
            assertEquals(1f, d[0], 1e-6f)
            assertEquals(1f, d[1], 1e-6f)
            MenuSlideMotion.depth(k, false, 0L, d)
            assertEquals(1f, d[0], 1e-6f)
            assertEquals(1f, d[1], 1e-6f)
            MenuSlideMotion.depth(k, false, MenuSlideMotion.durationMs(k, false), d)
            assertEquals(0f, d[0], 1e-6f)
            assertEquals(0f, d[1], 1e-6f)
            assertFalse(MenuSlideMotion.running(k, true, MenuSlideMotion.durationMs(k, true)))
            assertTrue(MenuSlideMotion.running(k, false, 0L))
        }
    }

    @Test
    fun splitApartArtFollowsTheListAndBackIsTheReverse() {
        MenuSlideMotion.depth(Kind.SPLIT_APART, true, 100L, d)
        assertTrue(d[1] < d[0]) // the art pane starts later
        assertEquals(0.25f, d[0], 1e-6f) // accelerates
        // Back: the art pane comes in first
        MenuSlideMotion.depth(Kind.SPLIT_APART, false, 100L, d)
        assertTrue(d[1] < d[0])
    }

    @Test
    fun offsets() {
        // Split -> split: only the 240 px list pane moves, A out to the left, B in from the right
        MenuSlideMotion.offsets(Kind.PANE, 0.5f, 0.5f, 480f, 240f, o)
        assertEquals(-120f, o[0], 1e-4f)
        assertEquals(120f, o[1], 1e-4f)
        // Split -> full: B covers the art first; the list pane moves only when B reaches it
        MenuSlideMotion.offsets(Kind.SPLIT_TO_FULL, 0.25f, 0.25f, 480f, 240f, o)
        assertEquals(360f, o[1], 1e-4f)
        assertEquals(0f, o[0], 1e-4f)
        MenuSlideMotion.offsets(Kind.SPLIT_TO_FULL, 0.75f, 0.75f, 480f, 240f, o)
        assertEquals(120f, o[1], 1e-4f)
        assertEquals(-120f, o[0], 1e-4f)
        // Full width
        MenuSlideMotion.offsets(Kind.FULL, 1f, 1f, 480f, 0f, o)
        assertEquals(-480f, o[0], 1e-4f)
        assertEquals(0f, o[1], 1e-4f)
        // Split apart: the list pane goes left, the art pane right
        MenuSlideMotion.offsets(Kind.SPLIT_APART, 1f, 0.5f, 480f, 240f, o)
        assertEquals(-240f, o[0], 1e-4f)
        assertEquals(120f, o[2], 1e-4f)
    }

    @Test
    fun paneSlideStaysInTheListPane() {
        // Start of a push: B is at the pane edge (none of it shows), A shows in full
        MenuSlideMotion.offsets(Kind.PANE, 0f, 0f, 480f, 240f, o)
        assertEquals(240f, MenuSlideMotion.paneColumns(o[0], 240f), 1e-4f)
        assertEquals(0f, MenuSlideMotion.paneColumns(o[1], 240f), 1e-4f)
        // Half way: B shows at screen x 120..240 (its columns 0..120), A at 0..120; neither goes over the art
        MenuSlideMotion.offsets(Kind.PANE, 0.5f, 0.5f, 480f, 240f, o)
        val bCols = MenuSlideMotion.paneColumns(o[1], 240f)
        assertEquals(120f, bCols, 1e-4f)
        assertEquals(240f, o[1] + bCols, 1e-4f)
        assertEquals(o[1], o[0] + MenuSlideMotion.paneColumns(o[0], 240f), 1e-4f) // no gap between A and B
        // End: B shows in full
        MenuSlideMotion.offsets(Kind.PANE, 1f, 1f, 480f, 240f, o)
        assertEquals(240f, MenuSlideMotion.paneColumns(o[1], 240f), 1e-4f)
        assertEquals(0f, MenuSlideMotion.paneColumns(480f, 240f), 1e-4f)
    }

    @Test
    fun splitApartArtLayerShowsTheArtLeftOfThePane() {
        val l = IntArray(3)
        MenuSlideMotion.splitApartArtLayer(480, 240, l)
        // The art pane in the layer is at the pane edge on the screen and has the pane width (same crop and zoom)
        assertEquals(240, l[0] + l[2])
        assertEquals(240, l[1] - l[2])
        // At each art offset up to the pane width, the layer covers the full width: the art left of the pane edge shows
        for (art in floatArrayOf(0f, 60f, 120f, 240f)) {
            assertTrue(l[0] + art <= 0f)
            assertTrue(l[0] + l[1] + art >= 480f)
        }
    }

    @Test
    fun splitApartArtLeavesTheScreen() {
        // The art layer draws 92 px of art left of the pane edge (zoom): at the end all of it is right of the screen
        MenuSlideMotion.offsets(Kind.SPLIT_APART, 1f, 1f, 480f, 240f, o, 148f)
        assertEquals(480f, 148f + o[2], 1e-4f)
        MenuSlideMotion.offsets(Kind.SPLIT_APART, 1f, 0f, 480f, 240f, o, 148f)
        assertEquals(0f, o[2], 1e-4f)
        // Without a value (or an art edge right of the pane), the pane edge goes to the right edge
        MenuSlideMotion.offsets(Kind.SPLIT_APART, 1f, 1f, 480f, 240f, o)
        assertEquals(240f, o[2], 1e-4f)
        MenuSlideMotion.offsets(Kind.SPLIT_APART, 1f, 1f, 480f, 240f, o, 300f)
        assertEquals(240f, o[2], 1e-4f)
    }

    @Test
    fun kinds() {
        assertEquals(Kind.PANE, MenuSlideMotion.kindOf(240, 241, false, false))
        assertEquals(Kind.SPLIT_TO_FULL, MenuSlideMotion.kindOf(240, null, false, false))
        assertEquals(Kind.FULL, MenuSlideMotion.kindOf(null, null, false, false))
        assertEquals(Kind.FULL, MenuSlideMotion.kindOf(null, 240, false, false))
        assertEquals(Kind.FULL, MenuSlideMotion.kindOf(240, 200, false, false))
        assertEquals(Kind.SPLIT_APART, MenuSlideMotion.kindOf(240, null, false, true))
        assertNull(MenuSlideMotion.kindOf(null, null, false, true))
        assertNull(MenuSlideMotion.kindOf(null, null, true, false))
    }

    @Test
    fun nowPlayingSplitsApartOnlyFromASplitScreen() {
        // Main menu (or Music) -> Now Playing, and Back: the same split apart as Cover Flow
        assertEquals(Kind.SPLIT_APART, MenuSlideMotion.kindOf(240, null, false, false, nowPlayingB = true))
        // A full-width list (Songs) -> Now Playing: the full-width slide, not a cut
        assertEquals(Kind.FULL, MenuSlideMotion.kindOf(null, null, false, false, nowPlayingB = true))
        // A list that opens from Now Playing (A = Now Playing): the full-width slide
        assertEquals(Kind.FULL, MenuSlideMotion.kindOf(null, null, false, false))
    }
}
