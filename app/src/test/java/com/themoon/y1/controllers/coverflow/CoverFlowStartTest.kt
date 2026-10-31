package com.themoon.y1.controllers.coverflow

import com.themoon.y1.models.SongItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.random.Random

class CoverFlowStartTest {

    private fun song(album: String, artist: String, albumArtist: String? = null, track: String = "01") =
        SongItem(File("/Music/$artist/$album/$track.mp3"), track, artist, album, null, null, albumArtist)

    private val albums = listOf(
        song("Cloud Nine", "Kygo"),
        song("Greatest Hits", "Queen"),
        song("Greatest Hits", "ABBA"),
        song("Hits", "Abba", "Various Artists"),
        song("Sexual Healing", "Marvin Gaye")
    )

    @Test
    fun findsTheAlbumOfTheSong() {
        assertEquals(4, CoverFlowStart.albumIndex(albums, song("Sexual Healing", "Marvin Gaye", track = "02")))
    }

    @Test
    fun keepsAlbumsWithTheSameTitleApartByAlbumArtist() {
        assertEquals(1, CoverFlowStart.albumIndex(albums, song("Greatest Hits", "Queen", track = "05")))
        assertEquals(2, CoverFlowStart.albumIndex(albums, song("Greatest Hits", "ABBA", track = "05")))
    }

    @Test
    fun usesTheAlbumArtistOfACompilation() {
        assertEquals(3, CoverFlowStart.albumIndex(albums, song("Hits", "Toto", "Various Artists", track = "07")))
    }

    @Test
    fun ignoresCaseAndSpaces() {
        assertEquals(0, CoverFlowStart.albumIndex(albums, song(" cloud nine ", "KYGO")))
    }

    @Test
    fun takesTheOnlyAlbumOfATitleWhenAlbumsAreGroupedByTitle() {
        // By Title: one album for all tracks with this title, whatever their artist.
        assertEquals(4, CoverFlowStart.albumIndex(albums, song("Sexual Healing", "Kygo")))
    }

    @Test
    fun noAlbumForNoSongOrAnAlbumThatCoverFlowDoesNotShow() {
        assertEquals(-1, CoverFlowStart.albumIndex(albums, null))
        assertEquals(-1, CoverFlowStart.albumIndex(albums, song("Podcast Episode 12", "Some Show")))
        assertEquals(-1, CoverFlowStart.albumIndex(albums, song("", "Kygo")))
    }

    @Test
    fun startsAtTheAlbumOfTheSong() {
        assertEquals(1, CoverFlowStart.startIndex(albums, song("Greatest Hits", "Queen"), Random(1)))
    }

    @Test
    fun startsAtARandomAlbumWithoutASong() {
        val seen = (0 until 200).map { CoverFlowStart.startIndex(albums, null, Random(it)) }.toSet()
        assertTrue(seen.all { it in albums.indices })
        assertEquals(albums.size, seen.size)
    }

    @Test
    fun startsAtZeroWithNoAlbums() {
        assertEquals(0, CoverFlowStart.startIndex(emptyList(), null))
    }
}
