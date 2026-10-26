package com.themoon.y1.controllers.coverflow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CoverFlowFlyByTest {

    @Test
    fun durationIsLogScaledBetweenTheLimits() {
        assertEquals(CoverFlowFlyBy.MIN_DURATION_MS, CoverFlowFlyBy.durationMs(0f))
        assertEquals(CoverFlowFlyBy.MIN_DURATION_MS, CoverFlowFlyBy.durationMs(1f))
        assertEquals(CoverFlowFlyBy.MAX_DURATION_MS, CoverFlowFlyBy.durationMs(200f))
        assertEquals(CoverFlowFlyBy.MAX_DURATION_MS, CoverFlowFlyBy.durationMs(5000f))
        assertEquals(CoverFlowFlyBy.durationMs(30f), CoverFlowFlyBy.durationMs(-30f))
        // ln 10 / ln 200 = 0.43: 300 + 0.43 * 300
        assertEquals(430L, CoverFlowFlyBy.durationMs(10f))
        var last = 0L
        for (d in 1..300) {
            val ms = CoverFlowFlyBy.durationMs(d.toFloat())
            assertTrue(ms >= last)
            last = ms
        }
    }

    @Test
    fun easeOutIsFastAtTheStartAndSlowAtTheEnd() {
        assertEquals(0f, CoverFlowFlyBy.ease(0f), 0f)
        assertEquals(1f, CoverFlowFlyBy.ease(1f), 0f)
        assertEquals(1f, CoverFlowFlyBy.ease(2f), 0f)
        assertEquals(0.875f, CoverFlowFlyBy.ease(0.5f), 1e-6f)
        assertTrue(CoverFlowFlyBy.ease(0.1f) - CoverFlowFlyBy.ease(0f) > 10 * (CoverFlowFlyBy.ease(1f) - CoverFlowFlyBy.ease(0.9f)))
    }

    @Test
    fun shortDistanceVisitsEachAlbum() {
        val distance = 5f
        val ms = CoverFlowFlyBy.durationMs(distance)
        assertEquals(0, CoverFlowFlyBy.skipped(distance))
        assertEquals(0f, CoverFlowFlyBy.offsetAt(0, ms, distance), 0f)
        assertEquals(distance, CoverFlowFlyBy.offsetAt(ms, ms, distance), 0f)
        assertEquals(distance, CoverFlowFlyBy.offsetAt(ms + 100, ms, distance), 0f)
        var last = 0f
        for (t in 1..ms) {
            val o = CoverFlowFlyBy.offsetAt(t, ms, distance)
            assertTrue("at $t ms", o >= last && o - last < 0.1f) // continuous, never back
            last = o
        }
    }

    @Test
    fun backwardIsTheMirrorOfForward() {
        for (t in 0L..500L step 25) {
            assertEquals(-CoverFlowFlyBy.offsetAt(t, 500, 40f), CoverFlowFlyBy.offsetAt(t, 500, -40f), 0f)
        }
    }

    @Test
    fun longDistanceSkipsWholeAlbumsInTheMiddleOnce() {
        val distance = 200.4f // a fractional start, after a wheel step took over
        val ms = CoverFlowFlyBy.durationMs(distance)
        val skip = CoverFlowFlyBy.skipped(distance)
        assertEquals(176, skip)
        var last = 0f
        var jumps = 0
        for (t in 1..ms) {
            val o = CoverFlowFlyBy.offsetAt(t, ms, distance)
            val step = o - last
            assertTrue("at $t ms", step >= 0f)
            if (step > 2f) {
                jumps++
                // Without the skipped albums the cards move less than one album: they keep their positions.
                assertTrue("at $t ms", step - skip in 0f..1f)
            }
            last = o
        }
        assertEquals(1, jumps)
        assertEquals(distance, last, 0f)
        // The end slows down on the real albums before the target.
        assertTrue(distance - CoverFlowFlyBy.offsetAt(ms * 3 / 4, ms, distance) < 1f)
    }
}
