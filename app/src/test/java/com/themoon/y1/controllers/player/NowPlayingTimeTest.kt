package com.themoon.y1.controllers.player

import org.junit.Assert.assertEquals
import org.junit.Test

class NowPlayingTimeTest {

    private val seconds: (Int) -> String = { "${it / 1000}s" }

    @Test
    fun showsRemainingTimeByDefault() {
        assertEquals("-140s", NowPlayingTime.rightText(22_000, 162_000, false, seconds))
        assertEquals("-0s", NowPlayingTime.rightText(170_000, 162_000, false, seconds))
    }

    @Test
    fun showsTotalTimeWhenChosen() {
        assertEquals("162s", NowPlayingTime.rightText(22_000, 162_000, true, seconds))
        assertEquals("0s", NowPlayingTime.rightText(0, 0, true, seconds))
    }
}
