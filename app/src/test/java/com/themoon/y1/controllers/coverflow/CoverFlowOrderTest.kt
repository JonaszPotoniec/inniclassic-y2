package com.themoon.y1.controllers.coverflow

import com.themoon.y1.models.SongItem
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class CoverFlowOrderTest {

    private fun album(title: String, artist: String, albumArtist: String? = null) =
        SongItem(File("/Music/$artist/$title/01.mp3"), "01", artist, title, null, null, albumArtist)

    private fun sorted(vararg albums: SongItem): List<String> {
        val list = albums.toMutableList()
        CoverFlowOrder.sort(list)
        return list.map { "${it.albumArtist} - ${it.album}" }
    }

    @Test
    fun sortsByAlbumArtistThenTitle() {
        assertEquals(
            listOf("Kygo - Cloud Nine", "Kygo - Sexual Healing", "Marvin Gaye - Sexual Healing"),
            sorted(
                album("Sexual Healing", "Marvin Gaye"),
                album("Sexual Healing", "Kygo"),
                album("Cloud Nine", "Kygo")
            )
        )
    }

    @Test
    fun usesAlbumArtistBeforeTrackArtist() {
        assertEquals(
            listOf("Adele - 21", "Various Artists - Hits"),
            sorted(album("Hits", "Abba", "Various Artists"), album("21", "Adele"))
        )
    }

    @Test
    fun usesTrackArtistWhenAlbumArtistIsEmpty() {
        val noAlbumArtist = album("Hits", "Abba")
        noAlbumArtist.albumArtist = ""
        val list = mutableListOf(album("21", "Adele"), noAlbumArtist)
        CoverFlowOrder.sort(list)
        assertEquals(listOf("Hits", "21"), list.map { it.album })
    }

    @Test
    fun ignoresCaseAndLeadingThe() {
        assertEquals(
            listOf("The Beatles - Abbey Road", "beck - Odelay", "The Cure - Disintegration"),
            sorted(
                album("Disintegration", "The Cure"),
                album("Odelay", "beck"),
                album("Abbey Road", "The Beatles")
            )
        )
    }

    @Test
    fun sortsAccentedLettersWithTheirBaseLetters() {
        assertEquals(
            listOf("Édith Piaf - La Vie en rose", "Eurovision - 2017", "Zeca Afonso - Cantigas"),
            sorted(
                album("Cantigas", "Zeca Afonso"),
                album("2017", "Eurovision"),
                album("La Vie en rose", "Édith Piaf")
            )
        )
    }

    @Test
    fun sortName_dropsOnlyALeadingThe() {
        assertEquals("Beatles", CoverFlowOrder.sortName("The Beatles"))
        assertEquals("Theory of a Deadman", CoverFlowOrder.sortName("Theory of a Deadman"))
        assertEquals("The", CoverFlowOrder.sortName("The"))
        assertEquals("Them", CoverFlowOrder.sortName("Them"))
    }
}
