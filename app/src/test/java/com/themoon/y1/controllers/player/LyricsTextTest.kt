package com.themoon.y1.controllers.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsTextTest {

    @Test
    fun emptyId3LyricsFrameIsNoLyrics() {
        // "I've Been Lost" (You Can't Win, Charlie Brown) and "Star Wars" (John Williams) on the Y2:
        // USLT frame = encoding 3 (UTF-8), language "XXX", empty description, text = one NUL
        val frame = byteArrayOf(3, 'X'.code.toByte(), 'X'.code.toByte(), 'X'.code.toByte(), 0, 0)
        val text = String(frame, 5, 1, Charsets.UTF_8).trim() // as extractEmbeddedLyrics reads it
        assertEquals("\u0000", text)
        assertNull(LyricsText.plain(text))
    }

    @Test
    fun blankPlainLyricsAreNoLyrics() {
        assertNull(LyricsText.plain(null))
        assertNull(LyricsText.plain(""))
        assertNull(LyricsText.plain(" \n\t\r\n "))
        assertNull(LyricsText.plain("\u0000\n\u0000"))
        assertNull(LyricsText.plain("﻿​　"))
    }

    @Test
    fun plainLyricsKeepTheirLines() {
        assertEquals("Line one\nLine two", LyricsText.plain("\nLine one\nLine two\n\u0000"))
        assertEquals("♪", LyricsText.plain("♪"))
    }

    @Test
    fun timedLinesNeedVisibleText() {
        // The text after a time tag ("[00:12.34]", trimmed): empty or invisible lines do not count
        assertFalse(LyricsText.isVisible(""))
        assertFalse(LyricsText.isVisible("\u0000"))
        assertFalse(LyricsText.isVisible("​"))
        assertFalse(LyricsText.isVisible(" "))
        assertTrue(LyricsText.isVisible("Hello"))
        assertTrue(LyricsText.isVisible("七里香"))
        assertTrue(LyricsText.isVisible("​…"))
        assertTrue(LyricsText.isVisible("🎵")) // an emoji (two UTF-16 chars)
    }
}
