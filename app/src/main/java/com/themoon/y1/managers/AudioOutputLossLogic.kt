package com.themoon.y1.managers

/**
 * Pure rules: pause when the audio output goes away, and play again when the same Bluetooth device connects again.
 *
 * - Output lost while music plays (Bluetooth audio device disconnects, or headphones unplugged): pause.
 * - The same Bluetooth device connects again within [RESUME_LIMIT_MS], the music is still paused, and the song is
 *   the same: play again from the same place. Headphones that are plugged in again do not start the music (as on
 *   the iPod).
 * - No resume if the user (or a remote) started the music while the device was away, changed the song, or if the
 *   music was not playing when the device disconnected.
 *
 * Android sends ACTION_AUDIO_BECOMING_NOISY ([onNoisy]) before the A2DP DISCONNECTED broadcast ([onA2dpDisconnected])
 * of the same disconnect (AudioService sends it, then A2dpStateMachine broadcasts the state). The two together are
 * one loss: the A2DP broadcast that comes within [NOISY_MATCH_MS] gives the device of the pause.
 *
 * Times are SystemClock.elapsedRealtime() (counts also in deep sleep). Not thread-safe: call on the main thread.
 */
class AudioOutputLossLogic(private val resumeLimitMs: Long = RESUME_LIMIT_MS) {

    enum class Action { NONE, PAUSE, RESUME_LATER, RESUME_NOW, DROP }

    /** What to do, and the reason for the event log (for example `bt-disconnect`). */
    data class Decision(val action: Action, val why: String)

    /** A pause from an output loss. [address] is null until the loss is known to be a Bluetooth device. */
    data class Pending(val address: String?, val name: String?, val atMs: Long, val track: String?)

    var pending: Pending? = null
        private set

    /** Time of our last resume, or 0 (see [ignoresPause]). */
    private var resumedAtMs = 0L

    /** ACTION_AUDIO_BECOMING_NOISY: the output goes away (headphones unplugged, or A2DP disconnects). */
    fun onNoisy(nowMs: Long, playing: Boolean, track: String?): Decision {
        resumedAtMs = 0L
        if (!playing) return Decision(Action.NONE, "noisy-not-playing")
        pending = Pending(null, null, nowMs, track)
        return Decision(Action.PAUSE, "noisy")
    }

    /** A2DP connection state DISCONNECTED (from CONNECTED or DISCONNECTING). */
    fun onA2dpDisconnected(address: String?, name: String?, nowMs: Long, playing: Boolean, track: String?): Decision {
        resumedAtMs = 0L
        if (playing) {
            pending = Pending(address, name, nowMs, track)
            return Decision(Action.PAUSE, "bt-disconnect")
        }
        val p = pending
        if (p != null && p.address == null && nowMs - p.atMs in 0..NOISY_MATCH_MS) {
            // The noisy broadcast of this disconnect paused the music a moment ago.
            pending = p.copy(address = address, name = name)
            return Decision(Action.NONE, "bt-disconnect")
        }
        return Decision(Action.NONE, "bt-disconnect-not-playing")
    }

    /** The music started (a press of Play, a remote key, a new song list) while nothing is resumed by us. */
    fun onPlayStarted(): Decision {
        if (pending == null) return Decision(Action.NONE, "no-pause")
        pending = null
        return Decision(Action.DROP, "play-while-disconnected")
    }

    /** A2DP connection state CONNECTED. [Action.RESUME_LATER]: call [onResumeDue] after [RESUME_DELAY_MS]. */
    fun onA2dpConnected(address: String?, nowMs: Long, playing: Boolean, track: String?): Decision {
        val p = pending ?: return Decision(Action.NONE, "no-pause")
        if (p.address == null || p.address != address) return Decision(Action.NONE, "other-output")
        return check(p, nowMs, playing, track) ?: Decision(Action.RESUME_LATER, "bt-reconnect")
    }

    /**
     * The delay after the connection ended. Android moves the audio route (and the app the volume step) with a
     * delay, and a car can send its own Play in this time: then the music plays already, and we do nothing.
     */
    fun onResumeDue(address: String?, nowMs: Long, connected: Boolean, playing: Boolean, track: String?): Decision {
        val p = pending ?: return Decision(Action.NONE, "no-pause")
        if (!connected) return Decision(Action.NONE, "bt-not-connected")
        if (p.address == null || p.address != address) return Decision(Action.NONE, "other-output")
        check(p, nowMs, playing, track)?.let { return it }
        pending = null
        resumedAtMs = nowMs
        return Decision(Action.RESUME_NOW, "bt-reconnect-resume")
    }

    /**
     * True when a play/pause toggle that would pause must not act: it comes within [DUPLICATE_PLAY_MS] after our
     * resume. On this ROM the Play and the Pause of a car (AVRCP) both arrive as MEDIA_PLAY_PAUSE, so the Play that
     * some cars send when they connect would pause the music that we started. Only one toggle is ignored.
     */
    fun ignoresPause(nowMs: Long): Boolean {
        val at = resumedAtMs
        if (at == 0L) return false
        resumedAtMs = 0L
        return nowMs - at in 0..DUPLICATE_PLAY_MS
    }

    private fun check(p: Pending, nowMs: Long, playing: Boolean, track: String?): Decision? {
        val reason = when {
            nowMs - p.atMs > resumeLimitMs -> "bt-reconnect-expired"
            playing -> "bt-reconnect-playing"
            track != p.track -> "bt-reconnect-other-song"
            else -> return null
        }
        pending = null
        return Decision(Action.DROP, reason)
    }

    companion object {
        /**
         * The resume time limit: 4 hours. Longer than a usual stop (a shop, a charger, a meal, a film; the stop of
         * 2026-10-27 was 18 min). Shorter than a work day or a night: after that, music that starts by itself is a
         * surprise, and the user starts it with one press.
         */
        const val RESUME_LIMIT_MS = 4L * 60 * 60 * 1000

        /** The A2DP DISCONNECTED broadcast comes this long after the noisy broadcast of the same disconnect, or less. */
        const val NOISY_MATCH_MS = 5_000L

        /** Wait after A2DP CONNECTED before the resume (the app checks the output at 0.3, 1.2 and 3 s). */
        const val RESUME_DELAY_MS = 2_000L

        /** A pause toggle this soon after our resume is the car's own Play (see [ignoresPause]). */
        const val DUPLICATE_PLAY_MS = 8_000L
    }
}
