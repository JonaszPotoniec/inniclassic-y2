package com.themoon.y1.cast

import com.themoon.y1.cast.CastIdle.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CastIdleTest {
    private val min = 60_000L
    private var now = 1_000_000L

    /** Ticks each second from now to [toMs] (relative), and gives the state of the last tick. */
    private fun CastIdle.run(playing: Boolean, toMs: Long): State {
        val end = now + toMs
        var s = state
        while (now < end) {
            now += 1000
            s = tick(playing, now)
        }
        return s
    }

    private fun playing(): CastIdle = CastIdle().also { it.start(true, now) }

    /** A session that played and is paused from now. */
    private fun paused(): CastIdle = playing().also { it.tick(false, now) }

    @Test
    fun playKeepsTheLocks() {
        val c = playing()
        assertEquals(State.ACTIVE, c.run(true, 60 * min))
        assertTrue(c.holdsLocks)
    }

    @Test
    fun pauseReleasesTheLocksAfterFiveMinutesAndClosesTheLinkAfterFifteen() {
        val c = paused()
        assertEquals(State.ACTIVE, c.run(false, 5 * min - 1000))
        assertEquals(State.IDLE, c.run(false, 1000))
        assertFalse(c.holdsLocks)
        assertEquals(State.IDLE, c.run(false, 10 * min - 1000))
        assertEquals(State.SUSPENDED, c.run(false, 1000))
    }

    @Test
    fun sessionThatStartsPausedAlsoReleases() {
        val c = CastIdle().also { it.start(false, now) }
        assertEquals(State.ACTIVE, c.state)
        assertEquals(State.IDLE, c.run(false, 5 * min))
    }

    @Test
    fun playAgainTakesTheLocksAndANewPauseStartsAgain() {
        val c = paused()
        assertEquals(State.IDLE, c.run(false, 7 * min))
        assertEquals(State.ACTIVE, c.tick(true, now))
        assertEquals(State.ACTIVE, c.run(true, min))
        assertEquals(State.ACTIVE, c.tick(false, now))
        assertEquals(State.ACTIVE, c.run(false, 5 * min - 1000))
        assertEquals(State.IDLE, c.run(false, 1000))
    }

    @Test
    fun longSleepGoesStraightToSuspended() {
        val c = paused()
        // The Y2 slept: the next tick comes two hours later
        now += 120 * min
        assertEquals(State.SUSPENDED, c.tick(false, now))
        assertFalse(c.holdsLocks)
    }

    @Test
    fun screenOnDelaysTheSuspendButNotTheRelease() {
        val c = paused()
        assertEquals(State.IDLE, c.run(false, 12 * min))
        assertFalse(c.wake(now))
        assertEquals(State.IDLE, c.run(false, 15 * min - 1000))
        assertEquals(State.SUSPENDED, c.run(false, 1000))
    }

    @Test
    fun suspendedStaysSuspendedUntilResumed() {
        val c = playing()
        c.suspend()
        assertEquals(State.SUSPENDED, c.tick(true, now))
        assertTrue(c.wake(now))
        assertEquals(State.SUSPENDED, c.state)
    }

    @Test
    fun resumedWhilePausedHasNoLocksAndClosesAgainLater() {
        val c = playing()
        c.suspend()
        c.resumed(false, now)
        assertEquals(State.IDLE, c.state)
        assertFalse(c.holdsLocks)
        assertEquals(State.IDLE, c.run(false, 15 * min - 1000))
        assertEquals(State.SUSPENDED, c.run(false, 1000))
    }

    @Test
    fun resumedByPlayHasTheLocks() {
        val c = playing()
        c.suspend()
        c.resumed(true, now)
        assertEquals(State.ACTIVE, c.state)
        assertTrue(c.holdsLocks)
        assertEquals(State.ACTIVE, c.run(true, 30 * min))
    }
}
