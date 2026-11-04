package com.themoon.y1.controllers.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NowPlayingSpectrumTest {

    private fun capture(
        spectrumOn: Boolean = true,
        nowPlayingShown: Boolean = true,
        lyricsShowing: Boolean = false,
        onScreen: Boolean = true
    ) = NowPlayingSpectrum.shouldCapture(spectrumOn, nowPlayingShown, lyricsShowing, onScreen)

    @Test
    fun capturesWhileTheSpectrumIsVisible() {
        assertTrue(capture())
    }

    @Test
    fun noCaptureWhenTheSettingIsOff() {
        assertFalse(capture(spectrumOn = false))
    }

    @Test
    fun noCaptureOnOtherScreens() {
        assertFalse(capture(nowPlayingShown = false))
    }

    @Test
    fun noCaptureWhileTheLyricsShow() {
        assertFalse(capture(lyricsShowing = true))
    }

    @Test
    fun noCaptureWithTheScreenOffOrTheAppCovered() {
        assertFalse(capture(onScreen = false))
    }

    @Test
    fun onlyOneCombinationCaptures() {
        val all = listOf(true, false)
        val count = all.sumOf { a ->
            all.sumOf { b ->
                all.sumOf { c ->
                    all.count { d -> NowPlayingSpectrum.shouldCapture(a, b, c, d) }
                }
            }
        }
        assertEquals(1, count)
    }
}
