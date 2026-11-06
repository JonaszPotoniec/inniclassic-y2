package com.themoon.y1.cast

import kotlin.math.abs

/**
 * Keeps the speaker and the Y2 player together. The Y2 player stays in control (queue, next track, Now Playing,
 * lyrics, scrobbles) and plays with no sound; the speaker plays the same file. At each tick:
 * - a change on the Y2 (track, play/pause, seek) goes to the speaker;
 * - a change on the speaker (pause or play in the Sonos app or with the TV remote) comes back to the Y2;
 * - the speaker gives the clock: when the two differ by more than [DRIFT_MS], the Y2 seeks to the speaker
 *   position, so the Y2 goes to the next track when the speaker ends the track.
 * No I/O: [CastManager] does the actions.
 */
class CastSync {
    data class Local(val trackPath: String?, val playing: Boolean, val positionMs: Long, val durationMs: Long, val nowMs: Long)

    sealed class Action {
        data class Load(val startMs: Long, val play: Boolean) : Action()
        object RemotePlay : Action()
        object RemotePause : Action()
        data class RemoteSeek(val ms: Long) : Action()
        data class LocalSeekBy(val deltaMs: Long) : Action()
        object LocalPause : Action()
        object LocalPlay : Action()
        data class End(val reason: String) : Action()
    }

    var loadedTrack: String? = null
        private set
    /** The URL that the speaker must play (set by [CastManager] after a load). */
    var expectedUrl: String? = null
    private var last: Local? = null
    private var wantPlaying = false
    private var graceUntil = 0L
    private var startSeekMs: Long? = null
    private var failures = 0

    /** [remote] is null when the status call failed. */
    fun tick(local: Local, remote: RemoteStatus?): List<Action> {
        val now = local.nowMs
        val prev = last
        last = local
        // No file (nothing loaded yet, or a podcast stream that the Y2 plays itself): the speaker stays chosen, and
        // the next song plays on it. A speaker that still plays the last file pauses, so only one sound plays.
        if (local.trackPath == null) {
            if (remote?.state != RemoteStatus.State.PLAYING || loadedTrack == null) return emptyList()
            wantPlaying = false
            graceUntil = now + COMMAND_GRACE_MS
            return listOf(Action.RemotePause)
        }

        if (local.trackPath != loadedTrack) return load(local)

        if (prev != null) {
            val expected = prev.positionMs + if (prev.playing) now - prev.nowMs else 0L
            if (abs(local.positionMs - expected) > SEEK_DETECT_MS) {
                // From the end to the start of the same track (Repeat One): the speaker stopped or finished, and a seek
                // does not start it again
                val restart = prev.durationMs > 0 && prev.durationMs - prev.positionMs < END_MS && local.positionMs < START_MS
                if (restart) return load(local)
                graceUntil = now + COMMAND_GRACE_MS
                return listOf(Action.RemoteSeek(local.positionMs))
            }
        }

        if (local.playing != wantPlaying) {
            wantPlaying = local.playing
            graceUntil = now + COMMAND_GRACE_MS
            return listOf(if (local.playing) Action.RemotePlay else Action.RemotePause)
        }

        if (remote == null) {
            return if (++failures >= MAX_FAILURES) listOf(Action.End(LOST_CONNECTION)) else emptyList()
        }
        failures = 0
        if (now < graceUntil) return emptyList()

        val url = expectedUrl
        if (remote.trackUrl != null && url != null && remote.trackUrl != url) return listOf(Action.End("another app plays"))

        when (remote.state) {
            RemoteStatus.State.STOPPED, RemoteStatus.State.FINISHED -> {
                val nearEnd = local.durationMs > 0 && local.durationMs - local.positionMs < END_MS
                if (remote.state == RemoteStatus.State.FINISHED || nearEnd || !wantPlaying) return emptyList()
                return listOf(Action.End("stopped on the speaker"))
            }
            RemoteStatus.State.PAUSED -> if (wantPlaying) {
                wantPlaying = false
                return listOf(Action.LocalPause)
            }
            RemoteStatus.State.PLAYING -> {
                if (!wantPlaying) {
                    wantPlaying = true
                    return listOf(Action.LocalPlay)
                }
                if (startSeekMs != null) {
                    startSeekMs = null
                    // A player that refused the seek of the load starts at 0: send it again. A smaller difference
                    // (the Y2 played on during the load) is a drift: the Y2 moves, with no jump on the speaker.
                    if (remote.positionMs < START_MS && local.positionMs > SEEK_DETECT_MS) {
                        graceUntil = now + COMMAND_GRACE_MS
                        return listOf(Action.RemoteSeek(local.positionMs))
                    }
                }
                val drift = remote.positionMs - local.positionMs
                if (abs(drift) > DRIFT_MS) {
                    // The next tick must not take this seek for a seek of the user
                    last = local.copy(positionMs = local.positionMs + drift)
                    return listOf(Action.LocalSeekBy(drift))
                }
            }
            else -> {}
        }
        return emptyList()
    }

    private fun load(local: Local): List<Action> {
        loadedTrack = local.trackPath
        wantPlaying = local.playing
        graceUntil = local.nowMs + LOAD_GRACE_MS
        startSeekMs = if (local.positionMs > 1000) local.positionMs else null
        failures = 0
        return listOf(Action.Load(local.positionMs, local.playing))
    }

    companion object {
        const val TICK_MS = 1000L
        const val DRIFT_MS = 1500L
        const val SEEK_DETECT_MS = 3000L
        const val LOAD_GRACE_MS = 5000L
        const val COMMAND_GRACE_MS = 2500L
        const val END_MS = 4000L
        const val START_MS = 2500L
        const val MAX_FAILURES = 8
        const val LOST_CONNECTION = "lost connection"
    }
}
