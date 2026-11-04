package com.themoon.y1.managers

import com.themoon.y1.managers.AudioOutputLossLogic.Action
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioOutputLossLogicTest {

    private val car = "CC:88:26:54:B6:62"
    private val buds = "48:73:CB:52:59:1C"
    private val song = "/storage/sdcard1/Music/A/B/01 Song.mp3"
    private val t0 = 1_000_000L

    private fun pausedByCar(logic: AudioOutputLossLogic = AudioOutputLossLogic()): AudioOutputLossLogic {
        val d = logic.onA2dpDisconnected(car, "Tesla", t0, playing = true, track = song)
        assertEquals(Action.PAUSE, d.action)
        assertEquals("bt-disconnect", d.why)
        return logic
    }

    @Test
    fun disconnectWhilePlayingPausesAndReconnectResumes() {
        val logic = pausedByCar()
        val c = logic.onA2dpConnected(car, t0 + 18 * 60_000L, playing = false, track = song)
        assertEquals(Action.RESUME_LATER, c.action)
        val r = logic.onResumeDue(car, t0 + 18 * 60_000L + 2_000L, connected = true, playing = false, track = song)
        assertEquals(Action.RESUME_NOW, r.action)
        assertEquals("bt-reconnect-resume", r.why)
        assertNull(logic.pending)
    }

    @Test
    fun noisyThenA2dpDisconnectIsOnePauseWithTheDevice() {
        val logic = AudioOutputLossLogic()
        assertEquals(Action.PAUSE, logic.onNoisy(t0, playing = true, track = song).action)
        // The noisy pause stopped the music, so the A2DP broadcast sees it paused.
        val d = logic.onA2dpDisconnected(car, "Tesla", t0 + 400L, playing = false, track = song)
        assertEquals(Action.NONE, d.action)
        assertEquals(car, logic.pending?.address)
        assertEquals(Action.RESUME_LATER, logic.onA2dpConnected(car, t0 + 60_000L, false, song).action)
    }

    @Test
    fun headphonesUnplugPausesButNeverResumes() {
        val logic = AudioOutputLossLogic()
        assertEquals(Action.PAUSE, logic.onNoisy(t0, playing = true, track = song).action)
        assertNull(logic.pending?.address)
        // A Bluetooth device that connects later is not the lost output.
        assertEquals(Action.NONE, logic.onA2dpConnected(car, t0 + 60_000L, false, song).action)
        // A late A2DP disconnect is not part of the unplug.
        logic.onA2dpDisconnected(car, "Tesla", t0 + 60_000L, playing = false, track = song)
        assertNull(logic.pending?.address)
    }

    @Test
    fun noPauseAndNoResumeWhenTheUserPausedBefore() {
        val logic = AudioOutputLossLogic()
        val d = logic.onA2dpDisconnected(car, "Tesla", t0, playing = false, track = song)
        assertEquals(Action.NONE, d.action)
        assertNull(logic.pending)
        assertEquals(Action.NONE, logic.onA2dpConnected(car, t0 + 60_000L, false, song).action)
    }

    @Test
    fun playWhileDisconnectedCancelsTheResume() {
        val logic = pausedByCar()
        assertEquals(Action.DROP, logic.onPlayStarted().action)
        assertNull(logic.pending)
        assertEquals(Action.NONE, logic.onA2dpConnected(car, t0 + 60_000L, false, song).action)
    }

    @Test
    fun otherSongCancelsTheResume() {
        val logic = pausedByCar()
        val d = logic.onA2dpConnected(car, t0 + 60_000L, playing = false, track = "/other.mp3")
        assertEquals(Action.DROP, d.action)
        assertEquals("bt-reconnect-other-song", d.why)
    }

    @Test
    fun otherDeviceKeepsThePause() {
        val logic = pausedByCar()
        assertEquals(Action.NONE, logic.onA2dpConnected(buds, t0 + 60_000L, false, song).action)
        assertEquals(car, logic.pending?.address)
    }

    @Test
    fun resumeTimeLimitIsFourHours() {
        assertEquals(4L * 3600_000L, AudioOutputLossLogic.RESUME_LIMIT_MS)
        val inTime = pausedByCar()
        assertEquals(Action.RESUME_LATER,
            inTime.onA2dpConnected(car, t0 + AudioOutputLossLogic.RESUME_LIMIT_MS, false, song).action)
        val late = pausedByCar()
        val d = late.onA2dpConnected(car, t0 + AudioOutputLossLogic.RESUME_LIMIT_MS + 1, false, song)
        assertEquals(Action.DROP, d.action)
        assertEquals("bt-reconnect-expired", d.why)
    }

    @Test
    fun carPlayBeforeOurResumeWins() {
        val logic = pausedByCar()
        assertEquals(Action.RESUME_LATER, logic.onA2dpConnected(car, t0 + 60_000L, false, song).action)
        // The car's Play starts the music in the delay: the player listener reports it.
        assertEquals(Action.DROP, logic.onPlayStarted().action)
        assertEquals(Action.NONE, logic.onResumeDue(car, t0 + 62_000L, true, playing = true, track = song).action)
    }

    @Test
    fun alreadyPlayingAtTheResumeTimeDoesNothing() {
        val logic = pausedByCar()
        logic.onA2dpConnected(car, t0 + 60_000L, false, song)
        val d = logic.onResumeDue(car, t0 + 62_000L, connected = true, playing = true, track = song)
        assertEquals(Action.DROP, d.action)
        assertEquals("bt-reconnect-playing", d.why)
    }

    @Test
    fun disconnectAgainBeforeTheResumeTimeDoesNotResume() {
        val logic = pausedByCar()
        logic.onA2dpConnected(car, t0 + 60_000L, false, song)
        val d = logic.onResumeDue(car, t0 + 62_000L, connected = false, playing = false, track = song)
        assertEquals(Action.NONE, d.action)
        assertEquals(car, logic.pending?.address)
    }

    @Test
    fun carPlayJustAfterOurResumeIsIgnoredOnce() {
        val logic = pausedByCar()
        logic.onA2dpConnected(car, t0 + 60_000L, false, song)
        logic.onResumeDue(car, t0 + 62_000L, true, false, song)
        assertTrue(logic.ignoresPause(t0 + 65_000L))
        assertFalse(logic.ignoresPause(t0 + 66_000L))
    }

    @Test
    fun pauseLaterThanTheWindowActs() {
        val logic = pausedByCar()
        logic.onA2dpConnected(car, t0 + 60_000L, false, song)
        logic.onResumeDue(car, t0 + 62_000L, true, false, song)
        assertFalse(logic.ignoresPause(t0 + 62_000L + AudioOutputLossLogic.DUPLICATE_PLAY_MS + 1))
    }

    @Test
    fun pauseWithoutOurResumeActs() {
        assertFalse(AudioOutputLossLogic().ignoresPause(t0))
    }

    @Test
    fun disconnectJustAfterOurResumePausesAgain() {
        val logic = pausedByCar()
        logic.onA2dpConnected(car, t0 + 60_000L, false, song)
        logic.onResumeDue(car, t0 + 62_000L, true, false, song)
        assertEquals(Action.PAUSE, logic.onA2dpDisconnected(car, "Tesla", t0 + 63_000L, true, song).action)
        // Our own pause must not be taken for the car's duplicate Play.
        assertFalse(logic.ignoresPause(t0 + 63_000L))
    }
}
