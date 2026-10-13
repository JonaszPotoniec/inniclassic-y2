package com.themoon.y1.sync

import com.themoon.y1.sync.jellyfin.JellyfinMediaItem
import com.themoon.y1.sync.jellyfin.JellyfinSyncHierarchy
import com.themoon.y1.sync.jellyfin.JellyfinSyncHierarchy.TriState
import org.junit.Test
import org.junit.Assert.*

class JellyfinSyncHierarchyTest {

    @Test
    fun testTvHierarchyAndTriState() {
        val ep1 = JellyfinMediaItem().apply {
            id = "ep1"; name = "Pilot"; seriesName = "Breaking Bad"; seriesId = "s1"
            parentIndexNumber = 1; seasonName = "Season 1"; indexNumber = 1; fileSize = 1000
        }
        val ep2 = JellyfinMediaItem().apply {
            id = "ep2"; name = "Cat's in the Bag..."; seriesName = "Breaking Bad"; seriesId = "s1"
            parentIndexNumber = 1; seasonName = "Season 1"; indexNumber = 2; fileSize = 1000
        }
        val ep3 = JellyfinMediaItem().apply {
            id = "ep3"; name = "Seven Thirty-Seven"; seriesName = "Breaking Bad"; seriesId = "s1"
            parentIndexNumber = 2; seasonName = "Season 2"; indexNumber = 1; fileSize = 1000
        }

        val hierarchy = JellyfinSyncHierarchy.buildTvHierarchy(listOf(ep1, ep2, ep3))

        assertEquals(1, hierarchy.seriesList.size)
        assertEquals(3, hierarchy.allItemIds.size)
        assertEquals(3000, hierarchy.totalLibrarySize)

        val series = hierarchy.seriesList[0]
        assertEquals("Breaking Bad", series.name)
        assertEquals(2, series.seasons.size)

        val season1 = series.seasons[0]
        assertEquals("Season 1", season1.name)
        assertEquals(2, season1.episodes.size)

        val season2 = series.seasons[1]
        assertEquals("Season 2", season2.name)
        assertEquals(1, season2.episodes.size)

        // TriState checks
        val selected = mutableSetOf<String>()
        assertEquals(TriState.UNCHECKED, series.getTriState(selected))
        assertEquals(TriState.UNCHECKED, season1.getTriState(selected))

        // Select 1 of Season 1
        selected.add("ep1")
        assertEquals(TriState.INDETERMINATE, series.getTriState(selected))
        assertEquals(TriState.INDETERMINATE, season1.getTriState(selected))
        assertEquals(TriState.UNCHECKED, season2.getTriState(selected))
        assertEquals(1, series.getSelectedCount(selected))
        assertEquals(1, season1.getSelectedCount(selected))

        // Select both of Season 1
        selected.add("ep2")
        assertEquals(TriState.INDETERMINATE, series.getTriState(selected))
        assertEquals(TriState.CHECKED, season1.getTriState(selected))
        assertEquals(2, series.getSelectedCount(selected))

        // Select Season 2 as well -> all selected
        selected.add("ep3")
        assertEquals(TriState.CHECKED, series.getTriState(selected))
        assertEquals(TriState.CHECKED, season2.getTriState(selected))
        assertEquals(3, series.getSelectedCount(selected))
    }

    @Test
    fun testMusicHierarchy() {
        val s1 = JellyfinMediaItem().apply {
            id = "song1"; name = "Come Together"; artist = "The Beatles"
            album = "Abbey Road"; indexNumber = 1; fileSize = 500
        }
        val s2 = JellyfinMediaItem().apply {
            id = "song2"; name = "Something"; artist = "The Beatles"
            album = "Abbey Road"; indexNumber = 2; fileSize = 500
        }
        val s3 = JellyfinMediaItem().apply {
            id = "song3"; name = "Taxman"; artist = "The Beatles"
            album = "Revolver"; indexNumber = 1; fileSize = 500
        }

        val hierarchy = JellyfinSyncHierarchy.buildMusicHierarchy(listOf(s1, s2, s3))

        assertEquals(1, hierarchy.artistsList.size)
        val artist = hierarchy.artistsList[0]
        assertEquals("The Beatles", artist.name)
        assertEquals(2, artist.albums.size)

        val selected = setOf("song1", "song2")
        assertEquals(TriState.INDETERMINATE, artist.getTriState(selected))

        val abbeyRoad = artist.albums.firstOrNull { it.name == "Abbey Road" }
        assertNotNull(abbeyRoad)
        assertEquals(TriState.CHECKED, abbeyRoad!!.getTriState(selected))
    }

    @Test
    fun testMoviesHierarchy() {
        val m1 = JellyfinMediaItem().apply {
            id = "m1"; name = "Inception"; year = "2010"; fileSize = 2000000
        }
        val m2 = JellyfinMediaItem().apply {
            id = "m2"; name = "Interstellar"; year = "2014"; fileSize = 2500000
        }

        val hierarchy = JellyfinSyncHierarchy.buildMoviesHierarchy(listOf(m1, m2))

        assertEquals(2, hierarchy.moviesList.size)
        assertEquals("Inception", hierarchy.moviesList[0].name)
        assertEquals("Interstellar", hierarchy.moviesList[1].name)
        assertEquals(2, hierarchy.allItemIds.size)
    }
}
