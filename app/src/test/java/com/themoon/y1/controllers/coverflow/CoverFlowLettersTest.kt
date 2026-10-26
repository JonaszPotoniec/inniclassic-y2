package com.themoon.y1.controllers.coverflow

import com.themoon.y1.models.SongItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class CoverFlowLettersTest {

    private fun album(title: String, artist: String, albumArtist: String? = null) =
        SongItem(File("/Music/$artist/$title/01.mp3"), "01", artist, title, null, null, albumArtist)

    /** The albums in Cover Flow order. */
    private fun sorted(vararg albums: SongItem): List<SongItem> =
        albums.toMutableList().also { CoverFlowOrder.sort(it) }

    /** Each letter with its first album, as the wheel moves through them from the first album. */
    private fun index(letters: CoverFlowLetters): List<Pair<String?, Int>> {
        val result = mutableListOf(letters.letterAt(0) to 0)
        var i = letters.jump(0, 1)
        while (i >= 0) {
            result += letters.letterAt(i) to i
            i = letters.jump(i, 1)
        }
        return result
    }

    private val library = sorted(
        album("Ziggy Stardust", "David Bowie"),
        album("Abbey Road", "The Beatles"),
        album("Help!", "The Beatles"),
        album("21", "Adele"),
        album("All Eyez on Me", "2Pac"),
        album("Disintegration", "The Cure"),
        album("La Vie en rose", "Édith Piaf"),
        album("Odelay", "beck"),
        album("Hits", "Abba", "Various Artists"),
        album("Them", "Them"),
        album("Debut", "Björk"),
        album("Kid A", "Radiohead"),
        album("Greatest Hits", "!!!")
    )

    @Test
    fun lettersOfTheAlbumsInCoverFlowOrder() {
        val letters = CoverFlowLetters.of(library)!!
        assertEquals(library.size, letters.size)
        assertEquals(
            listOf("#", "#", "A", "B", "B", "B", "B", "C", "D", "E", "R", "T", "V"),
            (0 until letters.size).map { letters.letterAt(it) }
        )
    }

    @Test
    fun eachLetterGoesToItsFirstAlbum() {
        val letters = CoverFlowLetters.of(library)!!
        assertEquals(
            listOf("#" to 0, "A" to 2, "B" to 3, "C" to 7, "D" to 8, "E" to 9, "R" to 10, "T" to 11, "V" to 12),
            index(letters)
        )
        assertEquals("Abbey Road", library[3].album) // "The Beatles" is under B
        assertEquals("Odelay", library[5].album) // case is ignored: "beck" is under B
        assertEquals("Debut", library[6].album) // "Björk" is under B
        assertEquals("La Vie en rose", library[9].album) // "Édith Piaf" is under E
    }

    @Test
    fun symbolsAndDigitsAreBeforeA() {
        val letters = CoverFlowLetters.of(library)!!
        assertEquals("#", letters.letterAt(0))
        assertEquals(-1, letters.jump(1, -1)) // nothing before "#"
        assertEquals(0, letters.jump(2, -1)) // from A back to the first "#" album
    }

    @Test
    fun jumpsBackToTheFirstAlbumOfThePreviousLetter() {
        val letters = CoverFlowLetters.of(library)!!
        assertEquals(3, letters.jump(7, -1)) // from C to the first B album
        assertEquals(2, letters.jump(5, -1)) // from inside B to A
        assertEquals(-1, letters.jump(12, 1)) // nothing after V
    }

    @Test
    fun usesTheAlbumArtistBeforeTheTrackArtist() {
        val letters = CoverFlowLetters.of(library)!!
        assertEquals("Hits", library[12].album)
        assertEquals("V", letters.letterAt(12)) // "Various Artists", not "Abba"
    }

    @Test
    fun noLettersWhenTheAlbumsAreNotSortedByArtist() {
        val unsorted = listOf(
            album("Kid A", "Radiohead"), album("Help!", "The Beatles"), album("Them", "Them"),
            album("21", "Adele"), album("Odelay", "Beck"), album("Disintegration", "The Cure")
        )
        assertNull(CoverFlowLetters.of(unsorted))
        assertNull(CoverFlowLetters.of(emptyList()))
    }
}
