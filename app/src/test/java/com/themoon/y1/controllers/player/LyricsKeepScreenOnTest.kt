package com.themoon.y1.controllers.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsKeepScreenOnTest {

    @Test
    fun holdsOnlyWithTheSettingWhileLyricsShowAndMusicPlays() {
        assertTrue(LyricsKeepScreenOn.shouldHold(setting = true, lyricsVisible = true, playing = true))
        for (setting in listOf(true, false)) for (visible in listOf(true, false)) for (playing in listOf(true, false)) {
            if (setting && visible && playing) continue
            assertFalse("$setting $visible $playing", LyricsKeepScreenOn.shouldHold(setting, visible, playing))
        }
    }

    @Test
    fun settingIsOnByDefault() {
        assertTrue(LyricsKeepScreenOn.DEFAULT)
        assertEquals("lyrics_keep_screen_on", LyricsKeepScreenOn.PREF)
    }
}
