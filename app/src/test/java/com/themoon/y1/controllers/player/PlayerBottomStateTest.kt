package com.themoon.y1.controllers.player

import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerBottomStateTest {

    @Test
    fun cycleWithoutLyricsWrapsAfterRating() {
        assertEquals(1, PlayerController.nextBottomState(0, hasLyrics = false))
        assertEquals(2, PlayerController.nextBottomState(1, hasLyrics = false))
        assertEquals(3, PlayerController.nextBottomState(2, hasLyrics = false))
        assertEquals(0, PlayerController.nextBottomState(3, hasLyrics = false))
    }

    @Test
    fun cycleWithLyricsShowsLyricsAfterProgress() {
        assertEquals(PlayerController.LYRICS_STATE, PlayerController.nextBottomState(0, hasLyrics = true))
        assertEquals(1, PlayerController.nextBottomState(PlayerController.LYRICS_STATE, hasLyrics = true))
        assertEquals(2, PlayerController.nextBottomState(1, hasLyrics = true))
        assertEquals(3, PlayerController.nextBottomState(2, hasLyrics = true))
        assertEquals(0, PlayerController.nextBottomState(3, hasLyrics = true))
    }

    @Test
    fun lyricsStateMovesOnWhenLyricsAreGone() {
        assertEquals(1, PlayerController.nextBottomState(PlayerController.LYRICS_STATE, hasLyrics = false))
    }
}
