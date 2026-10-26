package com.themoon.y1.controllers.coverflow

/**
 * The fly-by at the end of a letter jump: the covers scroll past fast from the album in the centre to the
 * target album, and slow down at the end (ease-out). No Android dependencies, so it can be unit-tested.
 *
 * Positions are in albums and can be fractional: the cards move continuously. The time is a budget for the
 * full distance ([durationMs]), not a time for each album, so a long jump is not slow. In a long jump the
 * albums in the middle are skipped ([skipped]): the fly-by shows the first albums after the start, then the
 * last albums before the target.
 */
object CoverFlowFlyBy {
    /** Time for a distance of one album or less. */
    const val MIN_DURATION_MS = 300L
    /** Time for a distance of [FULL_DISTANCE] albums or more. */
    const val MAX_DURATION_MS = 600L
    /** From this distance (albums), the fly-by takes [MAX_DURATION_MS]. */
    const val FULL_DISTANCE = 200f
    /** A fly-by shows about this many albums at most; it skips the albums in the middle of a longer distance. */
    const val MAX_RUN = 24
    /** Fade (ms) of the album text when a fly-by starts and when it ends. */
    const val TEXT_FADE_MS = 120

    /** Time (ms) for [distance] albums (either sign): it increases with the logarithm of the distance. */
    fun durationMs(distance: Float): Long {
        val d = Math.abs(distance)
        if (d <= 1f) return MIN_DURATION_MS
        val f = Math.min(1.0, Math.log(d.toDouble()) / Math.log(FULL_DISTANCE.toDouble()))
        return MIN_DURATION_MS + Math.round((MAX_DURATION_MS - MIN_DURATION_MS) * f)
    }

    /** Ease-out (cubic): the part of the distance at the part [u] (0..1) of the time. */
    fun ease(u: Float): Float {
        val r = 1f - u.coerceIn(0f, 1f)
        return 1f - r * r * r
    }

    /**
     * Albums skipped in the middle of a fly-by of [distance] albums: 0 up to [MAX_RUN]. A whole number, so the
     * cards keep their positions when the albums change at the skip.
     */
    fun skipped(distance: Float): Int {
        val d = Math.abs(distance)
        return if (d <= MAX_RUN) 0 else Math.floor((d - MAX_RUN).toDouble()).toInt()
    }

    /**
     * Position (albums from the start, with the sign of [distance]) [elapsedMs] after the start of a fly-by
     * of [distance] albums that takes [durationMs]. It is exactly [distance] at the end.
     */
    fun offsetAt(elapsedMs: Long, durationMs: Long, distance: Float): Float {
        if (elapsedMs >= durationMs) return distance
        if (elapsedMs <= 0L) return 0f
        val skip = skipped(distance)
        val run = Math.abs(distance) - skip
        val v = run * ease(elapsedMs.toFloat() / durationMs)
        val offset = if (v < run / 2f) v else v + skip
        return if (distance < 0f) -offset else offset
    }
}
