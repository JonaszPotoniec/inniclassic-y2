package com.themoon.y1.cast

/**
 * Power of a Play On session while the music is paused. No I/O: [CastManager] does the actions.
 * - [State.ACTIVE]: the music plays, or is paused for less than [RELEASE_MS]. Wake lock and Wi-Fi lock, status each second.
 * - [State.IDLE]: paused for [RELEASE_MS] or more. No locks, so the Y2 can sleep; the status runs only while it is awake.
 * - [State.SUSPENDED]: paused and not used for [SUSPEND_MS], or the connection is lost. The link is closed (no status,
 *   no media server), but the speaker stays the Play On choice. Screen on or Play connects again, and the music does
 *   not start by itself.
 * Times are elapsed real time (with deep sleep): ticks stop while the Y2 sleeps, so the next tick can come hours later.
 */
class CastIdle(private val releaseMs: Long = RELEASE_MS, private val suspendMs: Long = SUSPEND_MS) {
    enum class State { ACTIVE, IDLE, SUSPENDED }

    var state = State.ACTIVE
        private set

    /** The locks are necessary only while the music plays and shortly after a pause. */
    val holdsLocks: Boolean get() = state == State.ACTIVE

    /** Start of the pause; -1 while the music plays. */
    private var pausedAt = -1L
    /** Last use: start of the pause, screen on, or connection again. The link closes [suspendMs] after it. */
    private var usedAt = 0L

    /** A new session: the locks are on, also when the music is paused (the user can start it soon). */
    fun start(playing: Boolean, nowMs: Long) {
        state = State.ACTIVE
        pausedAt = if (playing) -1L else nowMs
        usedAt = nowMs
    }

    /** After a connection again ([State.SUSPENDED] before): paused, the locks stay off until Play. */
    fun resumed(playing: Boolean, nowMs: Long) {
        state = if (playing) State.ACTIVE else State.IDLE
        pausedAt = if (playing) -1L else nowMs
        usedAt = nowMs
    }

    /** Each tick of an open link, and when the Y2 starts to play. Gives the new state. */
    fun tick(playing: Boolean, nowMs: Long): State {
        if (state == State.SUSPENDED) return state
        if (playing) {
            pausedAt = -1L
            state = State.ACTIVE
            return state
        }
        if (pausedAt < 0) {
            pausedAt = nowMs
            usedAt = nowMs
        }
        if (nowMs - usedAt >= suspendMs) state = State.SUSPENDED
        else if (nowMs - pausedAt >= releaseMs) state = State.IDLE
        return state
    }

    /** The screen comes on. True when the link is closed and must connect again. */
    fun wake(nowMs: Long): Boolean {
        if (state == State.SUSPENDED) return true
        usedAt = nowMs
        return false
    }

    /** The link closes (the connection is lost): the speaker stays chosen. */
    fun suspend() {
        state = State.SUSPENDED
    }

    companion object {
        const val RELEASE_MS = 5 * 60_000L
        const val SUSPEND_MS = 15 * 60_000L
    }
}
