package com.themoon.y1.managers

import java.util.Locale

/**
 * When a song counts as played, for the play history and the play counts.
 *
 * A song counts once for each play when you listened to half of it, or to 4 minutes of a long song (the
 * Last.fm scrobble rule). It is the time you listened that counts, not the position: a skip or a seek to the
 * end does not count. Because the song counts while it plays, the rule does not need an end event (a gapless
 * queue and Repeat One give none).
 *
 * [sample] gets the player state about once a second, from [PlayHistoryManager], which gives each counted
 * play to the play history and to [LastFmScrobbler]. One rule, so the two always agree.
 */
class PlayCountRule {

    private var path: String? = null
    private var listenedMs = 0L
    private var counted = false
    private var lastPositionMs = -1L
    private var lastSampleMs = 0L

    /** The time listened in the current play, in ms of the song. */
    val listened: Long get() = listenedMs

    /**
     * One sample of the player. [nowMs] is a clock that does not jump (elapsed real time), [speed] the
     * playback speed. Returns the path when this play now counts (once for each play), else null.
     */
    fun sample(path: String?, positionMs: Long, durationMs: Long, playing: Boolean, nowMs: Long, speed: Float = 1f): String? {
        if (path == null) {
            reset(null)
            return null
        }
        if (path != this.path) reset(path)
        if (!playing) {
            lastPositionMs = -1L // after a pause, a seek while paused must not count
            return null
        }
        if (lastPositionMs >= 0) {
            val step = positionMs - lastPositionMs
            val maxStep = ((nowMs - lastSampleMs).coerceAtLeast(0) * speed.coerceAtLeast(1f)).toLong() + SEEK_SLACK_MS
            if (step in 1..maxStep) {
                listenedMs += step
            } else if (step < 0 && positionMs < RESTART_MS && counted) {
                // Back to the start after a counted play: Repeat One, or the song again
                listenedMs = 0L
                counted = false
            }
        }
        lastPositionMs = positionMs
        lastSampleMs = nowMs
        if (!counted && listenedMs >= thresholdMs(durationMs)) {
            counted = true
            return path
        }
        return null
    }

    private fun reset(newPath: String?) {
        path = newPath
        listenedMs = 0L
        counted = false
        lastPositionMs = -1L
    }

    companion object {
        /** A long song counts after 4 minutes. */
        const val MAX_THRESHOLD_MS = 4 * 60 * 1000L

        /** A forward step up to the elapsed time plus this much is play; a larger step is a seek. */
        const val SEEK_SLACK_MS = 1500L

        /** A jump back to before this position after a counted play starts a new play. */
        const val RESTART_MS = 3000L

        /** Half of the song, but not more than 4 minutes. With no duration: 4 minutes. */
        fun thresholdMs(durationMs: Long): Long =
            if (durationMs > 0) minOf(durationMs / 2, MAX_THRESHOLD_MS) else MAX_THRESHOLD_MS

        /** Audiobooks, podcasts and sound effects are not in the music history. */
        fun isMusicPath(path: String): Boolean {
            val lower = path.lowercase(Locale.ROOT)
            return !lower.contains("/audiobooks/") && !lower.contains("/podcasts/") &&
                !lower.contains("/sound_effects/") && !lower.startsWith("/podcast_stream")
        }
    }
}
