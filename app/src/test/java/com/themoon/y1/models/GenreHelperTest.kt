package com.themoon.y1.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class GenreHelperTest {

    @Test
    fun parseGenres_nullOrBlank_returnsUnknownGenre() {
        assertEquals(listOf("Unknown Genre"), GenreHelper.parseGenres(null))
        assertEquals(listOf("Unknown Genre"), GenreHelper.parseGenres(""))
        assertEquals(listOf("Unknown Genre"), GenreHelper.parseGenres("   "))
    }

    @Test
    fun parseGenres_unknownGenre_returnsUnknownGenre() {
        assertEquals(listOf("Unknown Genre"), GenreHelper.parseGenres("Unknown Genre"))
        assertEquals(listOf("Unknown Genre"), GenreHelper.parseGenres("unknown genre"))
    }

    @Test
    fun parseGenres_singleGenre_returnsSingleGenre() {
        assertEquals(listOf("Ambient"), GenreHelper.parseGenres("Ambient"))
        assertEquals(listOf("Rock"), GenreHelper.parseGenres("  Rock  "))
    }

    @Test
    fun parseGenres_semicolonSeparated_splitsAndTrims() {
        assertEquals(listOf("Ambient", "Techno"), GenreHelper.parseGenres("Ambient; Techno"))
        assertEquals(listOf("Ambient", "Techno"), GenreHelper.parseGenres("Ambient;Techno"))
        assertEquals(listOf("Ambient", "Techno", "IDM"), GenreHelper.parseGenres("Ambient; Techno; IDM"))
        assertEquals(listOf("Ambient", "Techno"), GenreHelper.parseGenres("Ambient; ; Techno"))
    }

    @Test
    fun parseGenres_slashSeparated_splitsAndTrims() {
        assertEquals(listOf("Rock", "Pop"), GenreHelper.parseGenres("Rock / Pop"))
        assertEquals(listOf("Rock", "Pop"), GenreHelper.parseGenres("Rock/Pop"))
        assertEquals(listOf("Ambient", "Drone", "Electronic"), GenreHelper.parseGenres("Ambient / Drone / Electronic"))
    }

    @Test
    fun parseGenres_commaSeparated_splitsAndTrims() {
        assertEquals(listOf("Ambient", "Techno"), GenreHelper.parseGenres("Ambient, Techno"))
        assertEquals(listOf("Post-Rock", "Shoegaze"), GenreHelper.parseGenres("Post-Rock,Shoegaze"))
    }

    @Test
    fun parseGenres_doubleBackslashSeparated_splitsAndTrims() {
        assertEquals(listOf("Ambient", "Techno"), GenreHelper.parseGenres("Ambient\\Techno"))
        assertEquals(listOf("Ambient", "Techno"), GenreHelper.parseGenres("Ambient\\\\Techno"))
    }

    @Test
    fun parseGenres_nullByteSeparated_splitsAndTrims() {
        assertEquals(listOf("Ambient", "Techno"), GenreHelper.parseGenres("Ambient\u0000Techno"))
    }

    @Test
    fun parseGenres_newlineSeparated_splitsAndTrims() {
        assertEquals(listOf("Ambient", "Techno"), GenreHelper.parseGenres("Ambient\r\nTechno"))
        assertEquals(listOf("Ambient", "Techno"), GenreHelper.parseGenres("Ambient\nTechno"))
    }

    @Test
    fun parseGenres_preservesSingerSongwriter() {
        assertEquals(listOf("Singer/Songwriter"), GenreHelper.parseGenres("Singer/Songwriter"))
        assertEquals(listOf("Singer/Songwriter"), GenreHelper.parseGenres("singer/songwriter"))
        assertEquals(listOf("Singer/Songwriter"), GenreHelper.parseGenres("Singer / Songwriter"))
        assertEquals(listOf("Singer/Songwriter"), GenreHelper.parseGenres("Singer, Songwriter"))
        assertEquals(listOf("Singer/Songwriter"), GenreHelper.parseGenres("Singer,Songwriter"))
        assertEquals(listOf("Singer/Songwriter"), GenreHelper.parseGenres("singer, songwriter"))
    }

    @Test
    fun parseGenres_singerSongwriterWithOtherGenres_splitsCorrectly() {
        assertEquals(listOf("Singer/Songwriter", "Folk"), GenreHelper.parseGenres("Singer/Songwriter; Folk"))
        assertEquals(listOf("Folk", "Singer/Songwriter"), GenreHelper.parseGenres("Folk / Singer/Songwriter"))
        assertEquals(listOf("Acoustic", "Singer/Songwriter", "Indie"), GenreHelper.parseGenres("Acoustic, Singer/Songwriter; Indie"))
        assertEquals(listOf("Indie Singer/Songwriter"), GenreHelper.parseGenres("Indie Singer/Songwriter"))
        assertEquals(listOf("Indie Singer/Songwriter", "Folk"), GenreHelper.parseGenres("Indie Singer/Songwriter; Folk"))
        assertEquals(listOf("Singer/Songwriter", "Folk"), GenreHelper.parseGenres("Singer, Songwriter, Folk"))
        assertEquals(listOf("Folk", "Singer/Songwriter"), GenreHelper.parseGenres("Folk, Singer, Songwriter"))
        assertEquals(listOf("Indie Singer/Songwriter"), GenreHelper.parseGenres("Indie Singer, Songwriter"))
    }

    @Test
    fun parseGenres_caching_returnsSameListInstance() {
        GenreHelper.clearCache()
        val first = GenreHelper.parseGenres("Ambient; Techno")
        val second = GenreHelper.parseGenres("Ambient; Techno")
        assertSame(first, second)

        val unknownFirst = GenreHelper.parseGenres(null)
        val unknownSecond = GenreHelper.parseGenres("")
        assertSame(unknownFirst, unknownSecond)
    }

    @Test
    fun parseGenres_deduplicatesCaseInsensitive_preservesFirstCasing() {
        assertEquals(listOf("Rock"), GenreHelper.parseGenres("Rock; rock; ROCK"))
        assertEquals(listOf("Ambient", "Techno"), GenreHelper.parseGenres("Ambient; Techno; ambient; techno"))
    }

    @Test
    fun parseGenres_mixedDelimiters_splitsAll() {
        assertEquals(
            listOf("Rock", "Pop", "Electronic", "Ambient"),
            GenreHelper.parseGenres("Rock; Pop / Electronic, Ambient")
        )
    }

    @Test
    fun matches_exactAndCaseInsensitive_returnsTrue() {
        assertTrue(GenreHelper.matches("Ambient; Techno", "Ambient"))
        assertTrue(GenreHelper.matches("Ambient; Techno", "ambient"))
        assertTrue(GenreHelper.matches("Ambient; Techno", "Techno"))
        assertTrue(GenreHelper.matches("Ambient; Techno", "techno"))
    }

    @Test
    fun matches_noMatch_returnsFalse() {
        assertFalse(GenreHelper.matches("Ambient; Techno", "Rock"))
        assertFalse(GenreHelper.matches("Ambient; Techno", "Amb"))
    }

    @Test
    fun matches_nullOrBlank_returnsFalse() {
        assertFalse(GenreHelper.matches(null, "Ambient"))
        assertFalse(GenreHelper.matches("", "Ambient"))
        assertFalse(GenreHelper.matches("Ambient", null))
        assertFalse(GenreHelper.matches("Ambient", ""))
        assertFalse(GenreHelper.matches("Ambient", "   "))
    }

    @Test
    fun matches_singerSongwriter_matchesCorrectly() {
        assertTrue(GenreHelper.matches("Singer/Songwriter; Folk", "Singer/Songwriter"))
        assertTrue(GenreHelper.matches("Singer/Songwriter; Folk", "singer/songwriter"))
        assertTrue(GenreHelper.matches("Singer/Songwriter; Folk", "Folk"))
        assertFalse(GenreHelper.matches("Singer/Songwriter; Folk", "Singer"))
        assertFalse(GenreHelper.matches("Singer/Songwriter; Folk", "Songwriter"))
    }
}
