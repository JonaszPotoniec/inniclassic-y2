package com.themoon.y1.controllers.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LyricsScriptTest {

    @Test
    fun sidecarPathReplacesTheExtension() {
        assertEquals("/m/Jay Chou/Singles/Qi Li Xiang.romanised.lrc",
            LyricsScript.sidecarPath("/m/Jay Chou/Singles/Qi Li Xiang.mp3"))
        assertEquals("/m/a.b/01 Song.romanised.lrc", LyricsScript.sidecarPath("/m/a.b/01 Song.m4a"))
        assertNull(LyricsScript.sidecarPath("/m/a.b/noext"))
    }

    @Test
    fun originalShowsTheOriginalLine() {
        assertEquals(Pair("七里香", -1), LyricsScript.line("七里香", "qī lǐ xiāng", LyricsScript.ORIGINAL))
    }

    @Test
    fun romanisedShowsTheRomanisedLine() {
        assertEquals(Pair("qī lǐ xiāng", -1), LyricsScript.line("七里香", "qī lǐ xiāng", LyricsScript.ROMANISED))
    }

    @Test
    fun bothPutsTheRomanisedLineUnderTheOriginal() {
        val (text, start) = LyricsScript.line("七里香", "qī lǐ xiāng", LyricsScript.BOTH)
        assertEquals("七里香\nqī lǐ xiāng", text)
        assertEquals("qī lǐ xiāng", text.substring(start))
    }

    @Test
    fun sameLineShowsOnce() {
        assertEquals(Pair("fire away", -1), LyricsScript.line("fire away", "fire away", LyricsScript.BOTH))
    }

    @Test
    fun missingRomanisedLineShowsTheOriginal() {
        assertEquals(Pair("七里香", -1), LyricsScript.line("七里香", null, LyricsScript.ROMANISED))
        assertEquals(Pair("七里香", -1), LyricsScript.line("七里香", " ", LyricsScript.BOTH))
    }

    @Test
    fun invisibleRomanisedLineShowsTheOriginal() {
        // No blank lyrics view in Romanised: a NUL or a zero-width space is no romanised text
        assertEquals(Pair("七里香", -1), LyricsScript.line("七里香", "\u0000", LyricsScript.ROMANISED))
        assertEquals(Pair("七里香", -1), LyricsScript.line("七里香", "\u200B", LyricsScript.ROMANISED))
        assertEquals(listOf("一", "二"), LyricsScript.plainLines("一\n二", "\u0000\n\uFEFF", LyricsScript.ROMANISED)!!.map { it.first })
    }

    @Test
    fun plainLyricsPairInOrder() {
        val lines = LyricsScript.plainLines("一\n\n二", "yī\n\nèr", LyricsScript.BOTH)!!
        assertEquals(listOf("一\nyī", "", "二\nèr"), lines.map { it.first })
    }

    @Test
    fun blankRomanisedLineAtTheStartOrEndStaysPaired() {
        assertEquals(listOf("一", "èr"), LyricsScript.plainLines("一\n二", "\nèr", LyricsScript.ROMANISED)!!.map { it.first })
        assertEquals(listOf("yī", "二"), LyricsScript.plainLines("一\n二", "yī\n", LyricsScript.ROMANISED)!!.map { it.first })
        // Extra blank lines (the end of the file, or the blank lines that the original text lost) are removed
        assertEquals(listOf("一", "èr"), LyricsScript.plainLines("一\n二", "\nèr\n\n", LyricsScript.ROMANISED)!!.map { it.first })
        assertEquals(listOf("yī", "èr"), LyricsScript.plainLines("一\n二", "\n\nyī\nèr\n", LyricsScript.ROMANISED)!!.map { it.first })
    }

    @Test
    fun plainLyricsWithOtherLineCountShowTheOriginal() {
        assertNull(LyricsScript.plainLines("一\n二", "yī", LyricsScript.ROMANISED))
        assertNull(LyricsScript.plainLines("一", "yī", LyricsScript.ORIGINAL))
        assertNull(LyricsScript.plainLines("一", null, LyricsScript.BOTH))
    }

    @Test
    fun unknownValueIsOriginal() {
        assertEquals(LyricsScript.ORIGINAL, LyricsScript.normalize(7))
        assertEquals("Original", LyricsScript.label(7))
        assertEquals("Romanised", LyricsScript.label(LyricsScript.ROMANISED))
        assertEquals("Original + Romanised", LyricsScript.label(LyricsScript.BOTH))
    }
}
