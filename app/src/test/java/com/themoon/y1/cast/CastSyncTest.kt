package com.themoon.y1.cast

import com.themoon.y1.cast.CastSync.Action
import com.themoon.y1.cast.RemoteStatus.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CastSyncTest {
    private val url = "http://y2/m/a.mp3"
    private var now = 100_000L

    private fun local(pos: Long, playing: Boolean = true, track: String? = "/music/a.mp3", dur: Long = 300_000L) =
        CastSync.Local(track, playing, pos, dur, now)

    private fun remote(state: State, pos: Long, u: String? = url) = RemoteStatus(state, pos, u)

    /** A sync that loaded track a.mp3 at [pos] and is past the load grace. */
    private fun loaded(pos: Long = 0L): CastSync {
        val s = CastSync()
        assertEquals(listOf(Action.Load(pos, true)), s.tick(local(pos), null))
        s.expectedUrl = url
        now += CastSync.LOAD_GRACE_MS
        return s
    }

    @Test
    fun loadsOnStartAndOnTrackChange() {
        val s = loaded(120_000)
        now += 1000
        assertEquals(listOf(Action.Load(0, true)), s.tick(local(0, track = "/music/b.mp3"), null))
    }

    @Test
    fun streamPausesThePlayingSpeakerOnce() {
        val s = loaded()
        now += 1000
        assertEquals(listOf(Action.RemotePause), s.tick(local(0, track = null), remote(State.PLAYING, 5000)))
        now += 1000
        assertEquals(emptyList<Action>(), s.tick(local(1000, track = null), remote(State.PAUSED, 5000)))
        assertEquals(emptyList<Action>(), CastSync().tick(local(0, track = null), remote(State.PLAYING, 0)))
    }

    @Test
    fun steadyPlayDoesNothing() {
        val s = loaded()
        now += 1000
        assertEquals(emptyList<Action>(), s.tick(local(5000), remote(State.PLAYING, 4800)))
    }

    @Test
    fun pauseAndPlayOnTheY2GoToTheSpeaker() {
        val s = loaded()
        now += 1000
        assertEquals(listOf(Action.RemotePause), s.tick(local(5000, playing = false), remote(State.PLAYING, 5000)))
        now += 1000
        assertEquals(listOf(Action.RemotePlay), s.tick(local(5000, playing = true), remote(State.PAUSED, 5000)))
    }

    @Test
    fun pauseInTheSonosAppPausesTheY2() {
        val s = loaded()
        now += 1000
        assertEquals(listOf(Action.LocalPause), s.tick(local(5000), remote(State.PAUSED, 5000)))
        now += 1000
        // The Y2 is paused now: no more actions
        assertEquals(emptyList<Action>(), s.tick(local(5000, playing = false), remote(State.PAUSED, 5000)))
        now += 1000
        assertEquals(listOf(Action.LocalPlay), s.tick(local(5000, playing = false), remote(State.PLAYING, 5000)))
    }

    @Test
    fun seekOnTheY2GoesToTheSpeaker() {
        val s = loaded()
        now += 1000
        s.tick(local(6_000), remote(State.PLAYING, 6_000))
        now += 1000
        assertEquals(listOf(Action.RemoteSeek(240_000)), s.tick(local(240_000), remote(State.PLAYING, 7_000)))
    }

    /** A sync that plays a.mp3 (300 s) at 297 s, 3 s before the end. */
    private fun nearTheEnd(): CastSync {
        val s = loaded(291_000)
        now += 1000
        assertEquals(emptyList<Action>(), s.tick(local(297_000), remote(State.PLAYING, 297_000)))
        now += 1000
        return s
    }

    @Test
    fun repeatOneOnAFinishedCastReceiverLoadsAgain() {
        val s = nearTheEnd()
        assertEquals(listOf(Action.Load(500, true)), s.tick(local(500), remote(State.FINISHED, 0)))
    }

    @Test
    fun repeatOneOnAStoppedUpnpSpeakerLoadsAgain() {
        val s = nearTheEnd()
        assertEquals(listOf(Action.Load(500, true)), s.tick(local(500), remote(State.STOPPED, 0)))
        // The load grace: no "stopped on the speaker" while the speaker loads
        now += 1000
        assertEquals(emptyList<Action>(), s.tick(local(1_500), remote(State.STOPPED, 0)))
    }

    @Test
    fun seekBackFromNearTheEndIsASeek() {
        val s = nearTheEnd()
        assertEquals(listOf(Action.RemoteSeek(100_000)), s.tick(local(100_000), remote(State.PLAYING, 298_000)))
    }

    @Test
    fun driftMovesTheY2ToTheSpeakerAndIsNotTakenForASeek() {
        val s = loaded()
        now += 1000
        val a = s.tick(local(6_000), remote(State.PLAYING, 4_000))
        assertEquals(listOf(Action.LocalSeekBy(-2_000)), a)
        now += 1000
        // The Y2 is now at 4 s + 1 s
        assertEquals(emptyList<Action>(), s.tick(local(5_000), remote(State.PLAYING, 5_000)))
    }

    @Test
    fun playerThatRefusedTheStartSeekGetsItAgain() {
        val s = loaded(120_000)
        now += 1000
        assertEquals(listOf(Action.RemoteSeek(121_000 + 5000)), s.tick(local(121_000 + 5000), remote(State.PLAYING, 1000)))
    }

    @Test
    fun startBehindTheY2IsADriftNotASeek() {
        val s = loaded(70_000)
        now += 1000
        // The Sonos started at 70 s plus 1.5 s; the Y2 played on during the load
        assertEquals(listOf(Action.LocalSeekBy(71_500L - 76_000L)), s.tick(local(76_000), remote(State.PLAYING, 71_500)))
    }

    @Test
    fun anotherAppOnTheSpeakerEndsTheSession() {
        val s = loaded()
        now += 1000
        val a = s.tick(local(5000), remote(State.PLAYING, 1000, u = "x-sonos-spotify:track"))
        assertTrue(a.single() is Action.End)
    }

    @Test
    fun stopNearTheEndWaitsForTheNextTrack() {
        val s = loaded(292_000)
        now += 1000
        assertEquals(emptyList<Action>(), s.tick(local(298_000), remote(State.STOPPED, 0)))
        now += 1000
        assertEquals(emptyList<Action>(), s.tick(local(299_000), remote(State.FINISHED, 0)))
    }

    @Test
    fun stopInTheMiddleEndsTheSession() {
        val s = loaded()
        now += 1000
        assertTrue(s.tick(local(6_000), remote(State.STOPPED, 0)).single() is Action.End)
    }

    @Test
    fun lostConnectionEndsAfterSomeFailures() {
        val s = loaded()
        var last: List<Action> = emptyList()
        for (i in 1..CastSync.MAX_FAILURES) {
            now += 1000
            last = s.tick(local(5_000 + i * 1000L), null)
            if (i < CastSync.MAX_FAILURES) assertEquals(emptyList<Action>(), last)
        }
        assertTrue(last.single() is Action.End)
    }

    @Test
    fun graceAfterLoadIgnoresTheOldState() {
        val s = CastSync()
        s.tick(local(0), null)
        s.expectedUrl = url
        now += 1000
        // Still the state from before the load
        assertEquals(emptyList<Action>(), s.tick(local(1000), remote(State.STOPPED, 0, u = "old")))
    }
}
