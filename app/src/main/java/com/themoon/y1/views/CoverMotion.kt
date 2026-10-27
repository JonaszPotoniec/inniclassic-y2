package com.themoon.y1.views

import java.util.Random
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

/**
 * The slow pan and zoom of the split-screen art (the "Ken Burns" motion of the iPod Classic). No Android code, so
 * that unit tests can run it.
 *
 * A pose is (x, y, z). x and y are -1..1: the part of the free space at each side (the part of the zoomed art that is
 * outside the pane) at the current zoom, so the art always fills the pane. z is 0..1: the zoom from [ZOOM_MIN] to
 * [ZOOM_MAX]. The path goes through a new random key pose every [SEGMENT_MS] on a smooth curve (cubic Hermite with
 * Catmull-Rom tangents): it does not stop and it does not turn sharply.
 *
 * The time of the motion moves only while frames come ([frame]). After a gap (screen off, pane hidden) the motion
 * continues from where it stopped, with no jump.
 */
class CoverMotion(private val random: Random = Random()) {

    companion object {
        /** Zoom range of the art, relative to the pane (1 = the art just fills the pane). */
        const val ZOOM_MIN = 1.2f
        const val ZOOM_MAX = 1.5f

        /** Time from one key pose to the next. */
        const val SEGMENT_MS = 20000L

        /** Key poses stay in this part of the free space (the curve between them can go a little further). */
        const val KEY_LIMIT = 0.85f

        /** Distance (x, y) from one key pose to the next. */
        const val MIN_STEP = 0.5f
        const val MAX_STEP = 1.2f

        /** Largest zoom change (z) from one key pose to the next. */
        const val MAX_ZOOM_STEP = 0.6f

        /** A longer time between two frames is a pause: the motion then moves by one normal frame only. */
        const val MAX_FRAME_GAP_MS = 250L
        const val NORMAL_FRAME_MS = 48L

        /** True when the art moves: the setting is on, and the screen is on and not blanked (FakeScreenOff). */
        fun moves(enabled: Boolean, screenOn: Boolean, blanked: Boolean): Boolean = enabled && screenOn && !blanked

        /** Zoom for the z part of a pose. */
        fun zoom(z: Float): Float = ZOOM_MIN + z.coerceIn(0f, 1f) * (ZOOM_MAX - ZOOM_MIN)

        /**
         * Size factor of CENTER_CROP: the art ([contentW] × [contentH]) times this fills the view ([viewW] × [viewH]).
         */
        fun cropScale(contentW: Float, contentH: Float, viewW: Float, viewH: Float): Float =
            if (contentW > 0f && contentH > 0f) max(viewW / contentW, viewH / contentH) else 1f

        /**
         * Offset (px) of the art centre from the view centre for the pose part [part] (-1..1) on one axis.
         * [drawnSize] = the size of the art on that axis after CENTER_CROP, before the [zoom].
         */
        fun offset(part: Float, drawnSize: Float, viewSize: Float, zoom: Float): Float {
            val free = (drawnSize * zoom - viewSize) / 2f
            return if (free > 0f) part.coerceIn(-1f, 1f) * free else 0f
        }
    }

    // The current segment goes from p1 to p2 (tangents t1, t2); p3 is the key pose after p2.
    private val p1 = FloatArray(3)
    private val p2 = FloatArray(3)
    private val p3 = FloatArray(3)
    private val t1 = FloatArray(3)
    private val t2 = FloatArray(3)
    private val pose = FloatArray(3)

    private var clock = 0L
    private var segmentStart = 0L
    private var lastFrame = -1L

    init {
        restart()
    }

    /** A new random start: a random pose that already moves (used when a pane comes into view). */
    fun restart() {
        val p0 = FloatArray(3)
        randomKey(p0)
        nextKey(p0, p1)
        nextKey(p1, p2)
        nextKey(p2, p3)
        for (i in 0..2) {
            t1[i] = (p2[i] - p0[i]) / 2f
            t2[i] = (p3[i] - p1[i]) / 2f
        }
        segmentStart = clock
        evaluate(0f, pose)
    }

    /**
     * A new random path from the current pose, for new art. The pose and its speed do not change at this moment,
     * so the art does not jump.
     */
    fun newPath() {
        val u = segmentProgress()
        val now = FloatArray(3)
        val speed = FloatArray(3)
        evaluate(u, now)
        tangent(u, speed)
        for (i in 0..2) {
            p1[i] = now[i]
            t1[i] = speed[i]
        }
        nextKey(p1, p2)
        nextKey(p2, p3)
        for (i in 0..2) t2[i] = (p3[i] - p1[i]) / 2f
        segmentStart = clock
    }

    /** Stop the clock: the next [frame] continues from the current pose. */
    fun pause() {
        lastFrame = -1L
    }

    /** The current pose, without a move. */
    fun current(out: FloatArray) {
        out[0] = pose[0]; out[1] = pose[1]; out[2] = pose[2]
    }

    /** Moves the motion to the frame time [nowMs] and writes the pose (x, y, z) into [out]. */
    fun frame(nowMs: Long, out: FloatArray) {
        if (lastFrame >= 0L) {
            var dt = nowMs - lastFrame
            if (dt < 0L) dt = 0L
            if (dt > MAX_FRAME_GAP_MS) dt = NORMAL_FRAME_MS
            clock += dt
        }
        lastFrame = nowMs
        while (clock - segmentStart >= SEGMENT_MS) nextSegment()
        evaluate(segmentProgress(), pose)
        current(out)
    }

    private fun segmentProgress(): Float = ((clock - segmentStart).toFloat() / SEGMENT_MS).coerceIn(0f, 1f)

    private fun nextSegment() {
        for (i in 0..2) {
            p1[i] = p2[i]
            t1[i] = t2[i]
            p2[i] = p3[i]
        }
        nextKey(p2, p3)
        for (i in 0..2) t2[i] = (p3[i] - p1[i]) / 2f
        segmentStart += SEGMENT_MS
    }

    /** Cubic Hermite curve of the segment at [u] (0..1); x and y kept in -1..1, z in 0..1. */
    private fun evaluate(u: Float, out: FloatArray) {
        val u2 = u * u
        val u3 = u2 * u
        val h00 = 2 * u3 - 3 * u2 + 1
        val h10 = u3 - 2 * u2 + u
        val h01 = -2 * u3 + 3 * u2
        val h11 = u3 - u2
        for (i in 0..2) out[i] = h00 * p1[i] + h10 * t1[i] + h01 * p2[i] + h11 * t2[i]
        out[0] = out[0].coerceIn(-1f, 1f)
        out[1] = out[1].coerceIn(-1f, 1f)
        out[2] = out[2].coerceIn(0f, 1f)
    }

    /** Derivative of the curve at [u] (pose change for one full segment). */
    private fun tangent(u: Float, out: FloatArray) {
        val u2 = u * u
        val d00 = 6 * u2 - 6 * u
        val d10 = 3 * u2 - 4 * u + 1
        val d01 = -6 * u2 + 6 * u
        val d11 = 3 * u2 - 2 * u
        for (i in 0..2) out[i] = d00 * p1[i] + d10 * t1[i] + d01 * p2[i] + d11 * t2[i]
    }

    private fun randomKey(out: FloatArray) {
        out[0] = (random.nextFloat() * 2f - 1f) * KEY_LIMIT
        out[1] = (random.nextFloat() * 2f - 1f) * KEY_LIMIT
        out[2] = 0.05f + random.nextFloat() * 0.9f
    }

    /** A random key pose at a distance of [MIN_STEP]..[MAX_STEP] from [from] (the best of some tries). */
    private fun nextKey(from: FloatArray, out: FloatArray) {
        val c = FloatArray(3)
        var bestError = Float.MAX_VALUE
        for (attempt in 0 until 12) {
            randomKey(c)
            val dz = c[2] - from[2]
            if (abs(dz) > MAX_ZOOM_STEP) c[2] = from[2] + MAX_ZOOM_STEP * Math.signum(dz)
            val d = hypot(c[0] - from[0], c[1] - from[1])
            val error = when {
                d < MIN_STEP -> MIN_STEP - d
                d > MAX_STEP -> d - MAX_STEP
                else -> 0f
            }
            if (error < bestError) {
                bestError = error
                out[0] = c[0]; out[1] = c[1]; out[2] = c[2]
            }
            if (error == 0f) return
        }
    }
}
