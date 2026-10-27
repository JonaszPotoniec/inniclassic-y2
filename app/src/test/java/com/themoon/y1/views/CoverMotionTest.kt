package com.themoon.y1.views

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.abs
import kotlin.math.max

class CoverMotionTest {

    private val frameMs = 32L

    private fun run(motion: CoverMotion, startMs: Long, frames: Int, each: (FloatArray, FloatArray) -> Unit): Long {
        val before = FloatArray(3)
        val now = FloatArray(3)
        var t = startMs
        motion.frame(t, before)
        repeat(frames) {
            t += frameMs
            motion.frame(t, now)
            each(before, now)
            now.copyInto(before)
        }
        return t
    }

    private fun maxStep(a: FloatArray, b: FloatArray): Float =
        max(abs(a[0] - b[0]), max(abs(a[1] - b[1]), abs(a[2] - b[2])))

    @Test
    fun poseStaysInRangeForTwoHours() {
        for (seed in 1L..5L) {
            run(CoverMotion(Random(seed)), 0L, (2 * 3600 * 1000 / frameMs).toInt()) { _, p ->
                assertTrue(p[0] in -1f..1f && p[1] in -1f..1f && p[2] in 0f..1f)
            }
        }
    }

    @Test
    fun motionIsSlowAndHasNoJump() {
        // A step of 0.004 of the free space for each frame: below 0.5 px for a free space of 100 px.
        var largest = 0f
        run(CoverMotion(Random(7)), 0L, 20000) { a, b -> largest = max(largest, maxStep(a, b)) }
        assertTrue("largest step $largest", largest < 0.004f)
    }

    @Test
    fun motionDoesNotStop() {
        // In each 4 s the pose moves: no rest at the key poses.
        val motion = CoverMotion(Random(3))
        val p = FloatArray(3)
        var t = 0L
        motion.frame(t, p)
        repeat(900) {
            val start = p.copyOf()
            repeat(125) { t += frameMs; motion.frame(t, p) }
            assertTrue("no move near ${t / 1000} s", maxStep(start, p) > 0.01f)
        }
    }

    @Test
    fun newPathKeepsThePose() {
        val motion = CoverMotion(Random(11))
        val before = FloatArray(3)
        val after = FloatArray(3)
        var t = run(motion, 0L, 777) { _, _ -> }
        motion.frame(t, before)
        motion.newPath()
        t += frameMs
        motion.frame(t, after)
        assertTrue(maxStep(before, after) < 0.004f)
        // and later frames continue smoothly
        var largest = 0f
        run(motion, t, 2000) { a, b -> largest = max(largest, maxStep(a, b)) }
        assertTrue("largest step $largest", largest < 0.004f)
    }

    @Test
    fun aPauseDoesNotMoveTheArt() {
        val motion = CoverMotion(Random(5))
        val before = FloatArray(3)
        val after = FloatArray(3)
        val t = run(motion, 0L, 300) { _, _ -> }
        motion.frame(t, before)
        motion.pause()
        motion.frame(t + 10 * 60 * 1000, after) // screen off for 10 min
        assertTrue(maxStep(before, after) == 0f)
        // A long gap with no pause() call moves only one normal frame.
        motion.frame(t + 20 * 60 * 1000, after)
        assertTrue(maxStep(before, after) < 0.004f)
    }

    @Test
    fun restartGivesADifferentStart() {
        val a = FloatArray(3)
        val b = FloatArray(3)
        CoverMotion(Random(1)).frame(0L, a)
        CoverMotion(Random(2)).frame(0L, b)
        assertNotEquals(a.toList(), b.toList())
    }

    @Test
    fun artAlwaysFillsThePane() {
        // Music pane 240 × 330, square art; and a wide video poster.
        val panes = listOf(Triple(240f, 330f, 500f to 500f), Triple(240f, 330f, 1280f to 720f), Triple(240f, 330f, 300f to 900f))
        val r = Random(9)
        for ((vw, vh, art) in panes) {
            val crop = CoverMotion.cropScale(art.first, art.second, vw, vh)
            val drawnW = art.first * crop
            val drawnH = art.second * crop
            repeat(1000) {
                val zoom = CoverMotion.zoom(r.nextFloat())
                val dx = CoverMotion.offset(r.nextFloat() * 2 - 1, drawnW, vw, zoom)
                val dy = CoverMotion.offset(r.nextFloat() * 2 - 1, drawnH, vh, zoom)
                val left = vw / 2 + dx - drawnW * zoom / 2
                val top = vh / 2 + dy - drawnH * zoom / 2
                assertTrue(left <= 0.001f && left + drawnW * zoom >= vw - 0.001f)
                assertTrue(top <= 0.001f && top + drawnH * zoom >= vh - 0.001f)
            }
        }
    }

    @Test
    fun stillArtIsInsideTheZoomRange() {
        assertTrue(CoverPane.ZOOM in CoverMotion.ZOOM_MIN..CoverMotion.ZOOM_MAX)
        assertEquals(CoverMotion.ZOOM_MIN, CoverMotion.zoom(-1f))
        assertEquals(CoverMotion.ZOOM_MAX, CoverMotion.zoom(2f))
    }

    // Shared motion of all panes (SharedCoverMotion)

    /** Pane frames from [startMs] to [endMs] with [art]; returns the last pose. */
    private fun pane(shared: SharedCoverMotion, art: Any, startMs: Long, endMs: Long): FloatArray {
        val p = FloatArray(3)
        var t = startMs
        while (t <= endMs) {
            shared.frame(t, art, true, p)
            t += frameMs
        }
        return p
    }

    @Test
    fun sameTimeGivesTheSameTransformInEachPane() {
        val shared = SharedCoverMotion(CoverMotion(Random(21)))
        val art = Any()
        val a = FloatArray(3)
        val b = FloatArray(3)
        var t = 0L
        repeat(3000) {
            shared.frame(t, art, true, a) // main menu pane
            shared.frame(t, art, true, b) // Music pane in the same frame
            assertEquals(a.toList(), b.toList())
            t += frameMs
        }
    }

    @Test
    fun noJumpWhenSwitchingPanesWithTheSameArt() {
        // Main menu → Music → back → Music → back, with the same art in each pane: the first frame of each pane
        // has the pose of the last frame before the switch, and the path is the path with no switch.
        val shared = SharedCoverMotion(CoverMotion(Random(33))) { x, y -> x == y }
        val reference = CoverMotion(Random(33))
        val ref = FloatArray(3)
        val first = FloatArray(3)
        var t = 0L
        var motionTime = 0L
        var refTime = 0L
        reference.frame(0L, ref)
        for (switch in 0 until 4) {
            val last = pane(shared, "art", t, t + 4000)
            motionTime += if (switch == 0) 4000 else 4000 + frameMs
            shared.pause() // the old pane hides
            t += 4000 + 350 // the next pane shows its first frame 350 ms later
            // a new bitmap object with the same pixels counts as the same art
            assertEquals(null, shared.frame(t, String("art".toCharArray()), true, first))
            assertEquals("switch $switch", last.toList(), first.toList())
            t += frameMs
            while (refTime < motionTime) { refTime += frameMs; reference.frame(refTime, ref) }
            assertEquals(ref.toList(), first.toList())
        }
    }

    @Test
    fun newArtAtASwitchKeepsThePose() {
        val shared = SharedCoverMotion(CoverMotion(Random(44)))
        val album = Any()
        val poster = Any()
        val last = pane(shared, album, 0L, 6000L)
        shared.pause()
        val first = FloatArray(3)
        assertTrue(shared.frame(6400L, poster, true, first) === album) // the pane can fade from the album art
        assertEquals(last.toList(), first.toList())
        var largest = 0f
        val before = first.copyOf()
        val now = FloatArray(3)
        var t = 6400L
        repeat(2000) {
            t += frameMs
            shared.frame(t, poster, true, now)
            largest = max(largest, maxStep(before, now))
            now.copyInto(before)
        }
        assertTrue("largest step $largest", largest < 0.004f)
    }

    @Test
    fun sharedMotionStopsWhenNotMoving() {
        val shared = SharedCoverMotion(CoverMotion(Random(55)))
        val art = Any()
        val last = pane(shared, art, 0L, 3000L)
        val still = FloatArray(3)
        shared.frame(3000L + 60_000L, art, false, still) // screen off for a minute
        assertEquals(last.toList(), still.toList())
        val again = FloatArray(3)
        shared.frame(3000L + 120_000L, art, true, again) // screen on again
        assertEquals(last.toList(), again.toList())
    }

    @Test
    fun movesOnlyWhenOnAndScreenShows() {
        assertTrue(CoverMotion.moves(enabled = true, screenOn = true, blanked = false))
        assertFalse(CoverMotion.moves(enabled = false, screenOn = true, blanked = false))
        assertFalse(CoverMotion.moves(enabled = true, screenOn = false, blanked = false))
        assertFalse(CoverMotion.moves(enabled = true, screenOn = true, blanked = true))
    }
}
