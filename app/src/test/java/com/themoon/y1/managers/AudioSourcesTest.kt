package com.themoon.y1.managers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioSourcesTest {

    private var radio = false
    private var music = false
    private var radioStops = 0
    private var musicPauses = 0

    private var onRadioScreen = false
    private var now = 1_000_000L
    private var station = 0
    private var radioToggles = 0
    private val musicKeys = ArrayList<String>()

    private val sources = AudioSources(
        radioOn = { radio },
        stopRadio = { radioStops++; radio = false },
        musicPlaying = { music },
        pauseMusic = { musicPauses++; music = false },
        radio = object : AudioSources.Radio {
            override fun toggle() {
                radioToggles++
                radio = !radio
            }

            override fun tuneSaved(next: Boolean) {
                station += if (next) 1 else -1
            }
        },
        music = object : AudioSources.Music {
            override fun playPause() {
                musicKeys.add("playPause")
            }

            override fun skip(next: Boolean) {
                musicKeys.add(if (next) "next" else "prev")
            }
        },
        onRadioScreen = { onRadioScreen },
        nowMs = { now }
    )

    @Test
    fun musicThatStartsTurnsTheRadioOff() {
        radio = true
        sources.musicStarts()
        assertFalse(radio)
        assertEquals(1, radioStops)
        assertEquals(0, musicPauses)
    }

    @Test
    fun musicThatStartsWithTheRadioOffChangesNothing() {
        sources.musicStarts()
        assertEquals(0, radioStops)
        assertEquals(0, musicPauses)
    }

    @Test
    fun radioThatStartsPausesTheMusic() {
        music = true
        sources.radioStarts()
        assertFalse(music)
        assertEquals(1, musicPauses)
        assertEquals(0, radioStops)
    }

    @Test
    fun radioThatStartsWithTheMusicPausedChangesNothing() {
        sources.radioStarts()
        assertEquals(0, musicPauses)
    }

    @Test
    fun videoThatStartsStopsTheRadioAndPausesTheMusic() {
        // Not possible from the screens, but each start makes the new source the only one
        radio = true
        music = true
        sources.videoStarts()
        assertFalse(radio)
        assertFalse(music)
        assertEquals(1, radioStops)
        assertEquals(1, musicPauses)
    }

    @Test
    fun onlyOneSourceAfterEachStart() {
        // The bug of 2026-10-29: radio on, then Play
        sources.radioStarts(); radio = true
        sources.musicStarts(); music = true
        assertTrue(music && !radio)
        // And back to the radio
        sources.radioStarts(); radio = true
        assertTrue(radio && !music)
    }

    @Test
    fun withTheRadioOffTheButtonsControlTheMusic() {
        sources.playPauseKey()
        sources.skipKey(true)
        sources.skipKey(false)
        assertEquals(listOf("playPause", "next", "prev"), musicKeys)
        assertEquals(0, radioToggles)
    }

    @Test
    fun playPausePausesAndResumesThePlayingRadioFromAnyScreen() {
        radio = true
        sources.playPauseKey()
        assertFalse(radio)
        assertTrue(sources.isRadioPaused)
        sources.playPauseKey()
        assertTrue(radio)
        assertEquals(2, radioToggles)
        assertTrue(musicKeys.isEmpty())
    }

    @Test
    fun nextAndPreviousTuneTheSavedStationsWhileTheRadioPlays() {
        radio = true
        sources.skipKey(true)
        sources.skipKey(true)
        sources.skipKey(false)
        assertEquals(1, station)
        assertTrue(radio)
        assertEquals(0, radioToggles)
        assertTrue(musicKeys.isEmpty())
    }

    @Test
    fun nextOnPausedRadioTunesAndPlaysTheNewStation() {
        radio = true
        sources.playPauseKey()
        sources.skipKey(true)
        assertEquals(1, station)
        assertTrue(radio)
    }

    @Test
    fun musicThatStartsClearsPausedRadio() {
        radio = true
        sources.playPauseKey()
        // The user chooses a song
        sources.musicStarts()
        assertFalse(sources.isRadioPaused)
        sources.playPauseKey()
        assertEquals(listOf("playPause"), musicKeys)
        assertFalse(radio)
    }

    @Test
    fun pausedRadioIsClearedAfterFifteenMinutes() {
        radio = true
        sources.playPauseKey()
        now += AudioSources.PAUSED_RADIO_KEEP_MS - 1
        assertTrue(sources.isRadioPaused)
        now += 1
        assertFalse(sources.isRadioPaused)
        sources.playPauseKey()
        assertEquals(listOf("playPause"), musicKeys)
    }

    @Test
    fun onTheRadioScreenPlayTurnsTheRadioOnAndNextTunesOnly() {
        onRadioScreen = true
        sources.skipKey(true)
        assertEquals(1, station)
        assertFalse(radio)
        sources.playPauseKey()
        assertTrue(radio)
        assertTrue(musicKeys.isEmpty())
    }

    @Test
    fun turningTheRadioOffOnItsScreenIsNotAPause() {
        // The centre button turns the radio off: no paused radio, the buttons go back to the music
        radio = true
        radio = false
        assertFalse(sources.isRadioPaused)
        sources.playPauseKey()
        assertEquals(listOf("playPause"), musicKeys)
    }

    @Test
    fun aPauseThatTheRadioRefusesIsNotAPause() {
        // toggleRadioPower ignores a second press within 800 ms: the radio stays on
        val stuck = AudioSources(
            radioOn = { true }, stopRadio = {}, musicPlaying = { false }, pauseMusic = {},
            radio = object : AudioSources.Radio {
                override fun toggle() {}
                override fun tuneSaved(next: Boolean) {}
            },
            nowMs = { now }
        )
        stuck.playPauseKey()
        assertFalse(stuck.isRadioPaused)
    }
}
