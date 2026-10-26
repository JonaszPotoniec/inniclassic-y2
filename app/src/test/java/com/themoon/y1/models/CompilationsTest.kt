package com.themoon.y1.models

import com.themoon.y1.controllers.browser.MusicMenuItems
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CompilationsTest {

    private fun song(
        path: String,
        artist: String,
        album: String,
        albumArtist: String? = null,
        tag: Boolean = false
    ) = SongItem(File(path), "Title", artist, album, "2001", "Pop", albumArtist, "").also { it.compilationTag = tag }

    /** The Artists list as BrowserController builds it: unique track artists, without the hidden songs. */
    private fun artists(songs: List<SongItem>, compilationsShown: Boolean): List<String> =
        songs.filterNot { Compilations.hiddenFromArtists(it, compilationsShown) }.map { it.artist }.distinct().sorted()

    // A soundtrack with "Various Artists" and one track each by A, B and C, and an album by A.
    private val soundtrack = listOf(
        song("/Music/Compilations/OST/01.mp3", "Artist A", "OST", "Various Artists"),
        song("/Music/Compilations/OST/02.mp3", "Artist B", "OST", "Various Artists"),
        song("/Music/Compilations/OST/03.mp3", "Artist C", "OST", "Various Artists")
    )
    private val albumByA = listOf(
        song("/Music/Artist A/Debut/01.mp3", "Artist A", "Debut", "Artist A"),
        song("/Music/Artist A/Debut/02.mp3", "Artist A", "Debut", "Artist A")
    )

    @Test
    fun variousArtists_matchesNamesCaseInsensitively() {
        assertTrue(Compilations.isVariousArtists("Various Artists"))
        assertTrue(Compilations.isVariousArtists("various artists"))
        assertTrue(Compilations.isVariousArtists(" VA "))
        assertTrue(Compilations.isVariousArtists("Va"))
        assertFalse(Compilations.isVariousArtists("Vanessa"))
        assertFalse(Compilations.isVariousArtists("Various"))
        assertFalse(Compilations.isVariousArtists(""))
        assertFalse(Compilations.isVariousArtists(null))
    }

    @Test
    fun parseFlag_readsTagValues() {
        assertTrue(Compilations.parseFlag("1"))
        assertTrue(Compilations.parseFlag(" 1 "))
        assertTrue(Compilations.parseFlag("true"))
        assertTrue(Compilations.parseFlag("Yes"))
        assertFalse(Compilations.parseFlag("0"))
        assertFalse(Compilations.parseFlag("false"))
        assertFalse(Compilations.parseFlag("no"))
        assertFalse(Compilations.parseFlag(""))
        assertFalse(Compilations.parseFlag(null))
    }

    @Test
    fun mark_variousArtistsAlbumIsCompilation() {
        val library = soundtrack + albumByA
        Compilations.mark(library)
        assertTrue(soundtrack.all { it.isCompilation })
        assertTrue(albumByA.none { it.isCompilation })
    }

    @Test
    fun mark_oneFlaggedTrackMarksTheWholeAlbum() {
        val album = listOf(
            song("/Music/Mix/Party/01.mp3", "Artist D", "Party", "DJ Mix", tag = true),
            song("/Music/Mix/Party/02.mp3", "Artist E", "Party", "DJ Mix")
        )
        // The same title in another folder is another album.
        val other = song("/Music/Artist F/Party/01.mp3", "Artist F", "Party", "Artist F")
        Compilations.mark(album + other)
        assertTrue(album.all { it.isCompilation })
        assertFalse(other.isCompilation)
    }

    @Test
    fun mark_clearsTheFlagWhenTheAlbumIsNoLongerACompilation() {
        val track = song("/Music/Artist G/Album/01.mp3", "Artist G", "Album", "Artist G")
        track.isCompilation = true
        Compilations.mark(listOf(track))
        assertFalse(track.isCompilation)
    }

    @Test
    fun artists_withCompilationsShown_leaveOutCompilationTracks() {
        val library = soundtrack + albumByA
        Compilations.mark(library)
        // B and C have only soundtrack tracks; A stays for the own album.
        assertEquals(listOf("Artist A"), artists(library, compilationsShown = true))
    }

    @Test
    fun artists_withCompilationsHidden_showAllTrackArtists() {
        val library = soundtrack + albumByA
        Compilations.mark(library)
        assertEquals(listOf("Artist A", "Artist B", "Artist C"), artists(library, compilationsShown = false))
    }

    @Test
    fun musicMenu_hidesOnlyAlbumArtistsByDefault() {
        assertTrue(MusicMenuItems.isHiddenByDefault("artists"))
        assertFalse(MusicMenuItems.isHiddenByDefault("track_artists"))
        assertFalse(MusicMenuItems.isHiddenByDefault("compilations"))
        assertFalse(MusicMenuItems.isHiddenByDefault("albums"))
    }
}
