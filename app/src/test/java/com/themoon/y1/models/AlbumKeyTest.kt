package com.themoon.y1.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AlbumKeyTest {

    private fun song(name: String, artist: String, album: String, albumArtist: String? = null) =
        SongItem(File("/Music/$name.mp3"), name, artist, album, null, null, albumArtist)

    @Test
    fun sameTitleByDifferentArtists_areDifferentAlbums() {
        val gaye = song("a", "Marvin Gaye", "Sexual Healing")
        val kygo = song("b", "Kygo", "Sexual Healing")
        assertNotEquals(AlbumKey.of(gaye), AlbumKey.of(kygo))
    }

    @Test
    fun compilationWithOneAlbumArtist_isOneAlbum() {
        val first = song("a", "Kygo", "Hits 2016", "Various Artists")
        val second = song("b", "Sia", "Hits 2016", "Various Artists")
        assertEquals(AlbumKey.of(first), AlbumKey.of(second))
    }

    @Test
    fun key_ignoresCaseAndSurroundingSpaces() {
        assertEquals(AlbumKey.of("Greatest Hits ", "Queen"), AlbumKey.of("greatest hits", " QUEEN"))
    }

    @Test
    fun artistOf_usesTrackArtistWhenAlbumArtistIsEmpty() {
        val s = song("a", "Kygo", "Stargazing")
        s.albumArtist = ""
        assertEquals("Kygo", AlbumKey.artistOf(s))
        assertEquals("Various Artists", AlbumKey.artistOf(song("b", "Kygo", "Hits", "Various Artists")))
    }

    @Test
    fun matches_requiresTitleAndAlbumArtist() {
        val kygo = song("b", "Kygo", "Sexual Healing")
        assertTrue(AlbumKey.matches(kygo, "sexual healing", "KYGO"))
        assertFalse(AlbumKey.matches(kygo, "Sexual Healing", "Marvin Gaye"))
        assertFalse(AlbumKey.matches(kygo, "Cloud Nine", "Kygo"))
    }

    @Test
    fun matches_emptyAlbumArtistMatchesAnyArtist() {
        assertTrue(AlbumKey.matches(song("b", "Kygo", "Sexual Healing"), "Sexual Healing", ""))
    }

    @Test
    fun firstSongPerAlbum_keepsOneSongPerTitleAndArtistInOrder() {
        val gaye1 = song("g1", "Marvin Gaye", "Sexual Healing")
        val kygo = song("k1", "Kygo", "Sexual Healing")
        val gaye2 = song("g2", "Marvin Gaye", "sexual healing")
        val comp1 = song("c1", "Kygo", "Hits 2016", "Various Artists")
        val comp2 = song("c2", "Sia", "Hits 2016", "Various Artists")

        val albums = AlbumKey.firstSongPerAlbum(listOf(gaye1, kygo, gaye2, comp1, comp2))

        assertEquals(listOf(gaye1, kygo, comp1), albums)
    }
}
