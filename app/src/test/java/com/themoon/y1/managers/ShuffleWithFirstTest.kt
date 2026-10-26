package com.themoon.y1.managers

import org.junit.Assert.assertEquals
import org.junit.Test

class ShuffleWithFirstTest {

    @Test
    fun startsWithChosenTrackAndKeepsEveryTrack() {
        val tracks = (1..50).map { "song$it.mp3" }
        val list = ArrayList(tracks)

        shuffleWithFirst(list, "song37.mp3")

        assertEquals("song37.mp3", list[0])
        assertEquals(tracks.sorted(), list.sorted())
    }

    @Test
    fun handlesSingleTrack() {
        val list = arrayListOf("only.mp3")

        shuffleWithFirst(list, "only.mp3")

        assertEquals(listOf("only.mp3"), list)
    }
}
