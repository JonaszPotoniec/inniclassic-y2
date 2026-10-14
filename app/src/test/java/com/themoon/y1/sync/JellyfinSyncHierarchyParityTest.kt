package com.themoon.y1.sync

import com.themoon.y1.sync.jellyfin.JellyfinMediaItem
import com.themoon.y1.sync.jellyfin.JellyfinSyncHierarchy
import com.themoon.y1.sync.jellyfin.JellyfinSyncHierarchy.AlbumNode
import com.themoon.y1.sync.jellyfin.JellyfinSyncHierarchy.ArtistNode
import com.themoon.y1.sync.jellyfin.JellyfinSyncHierarchy.SeasonNode
import com.themoon.y1.sync.jellyfin.JellyfinSyncHierarchy.SeriesNode
import com.themoon.y1.sync.jellyfin.JellyfinSyncHierarchy.TriState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Comprehensive parity tests for [JellyfinSyncHierarchy].
 * Verifies tri-state selection logic, count computations, node helpers,
 * and TV/Music/Movies hierarchy generation, sorting, and edge cases.
 */
class JellyfinSyncHierarchyParityTest {

    // =========================================================================
    // 1. computeTriState(itemIds, selectedIds)
    // =========================================================================

    @Test
    fun computeTriState_nullItemIds_returnsUnchecked() {
        assertEquals(TriState.UNCHECKED, JellyfinSyncHierarchy.computeTriState(null, null))
        assertEquals(TriState.UNCHECKED, JellyfinSyncHierarchy.computeTriState(null, emptySet()))
        assertEquals(TriState.UNCHECKED, JellyfinSyncHierarchy.computeTriState(null, setOf("id1", "id2")))
    }

    @Test
    fun computeTriState_emptyItemIds_returnsUnchecked() {
        assertEquals(TriState.UNCHECKED, JellyfinSyncHierarchy.computeTriState(emptyList(), null))
        assertEquals(TriState.UNCHECKED, JellyfinSyncHierarchy.computeTriState(emptyList(), emptySet()))
        assertEquals(TriState.UNCHECKED, JellyfinSyncHierarchy.computeTriState(emptyList(), setOf("id1")))
    }

    @Test
    fun computeTriState_nullSelectedIds_returnsUnchecked() {
        val items = listOf("id1", "id2")
        assertEquals(TriState.UNCHECKED, JellyfinSyncHierarchy.computeTriState(items, null))
    }

    @Test
    fun computeTriState_emptySelectedIds_returnsUnchecked() {
        val items = listOf("id1", "id2")
        assertEquals(TriState.UNCHECKED, JellyfinSyncHierarchy.computeTriState(items, emptySet()))
    }

    @Test
    fun computeTriState_allMatching_returnsChecked() {
        // Single item
        assertEquals(TriState.CHECKED, JellyfinSyncHierarchy.computeTriState(listOf("a"), setOf("a")))

        // Multiple items
        val items = listOf("a", "b", "c")
        val selected = setOf("a", "b", "c")
        assertEquals(TriState.CHECKED, JellyfinSyncHierarchy.computeTriState(items, selected))
    }

    @Test
    fun computeTriState_allMatchingWithExtraSelectedIds_returnsChecked() {
        val items = listOf("a", "b")
        val selected = setOf("a", "b", "c", "d")
        assertEquals(TriState.CHECKED, JellyfinSyncHierarchy.computeTriState(items, selected))
    }

    @Test
    fun computeTriState_noneMatching_returnsUnchecked() {
        val items = listOf("a", "b", "c")
        val selected = setOf("x", "y", "z")
        assertEquals(TriState.UNCHECKED, JellyfinSyncHierarchy.computeTriState(items, selected))
    }

    @Test
    fun computeTriState_partialMatching_returnsIndeterminate() {
        val items = listOf("a", "b", "c")

        // 1 of 3 matching
        assertEquals(TriState.INDETERMINATE, JellyfinSyncHierarchy.computeTriState(items, setOf("a")))

        // 2 of 3 matching
        assertEquals(TriState.INDETERMINATE, JellyfinSyncHierarchy.computeTriState(items, setOf("b", "c")))

        // 1 matching, plus unrelated selected IDs
        assertEquals(TriState.INDETERMINATE, JellyfinSyncHierarchy.computeTriState(items, setOf("a", "z")))
    }

    // =========================================================================
    // 2. countSelected(itemIds, selectedIds)
    // =========================================================================

    @Test
    fun countSelected_nullItemIds_returnsZero() {
        assertEquals(0, JellyfinSyncHierarchy.countSelected(null, null))
        assertEquals(0, JellyfinSyncHierarchy.countSelected(null, emptySet()))
        assertEquals(0, JellyfinSyncHierarchy.countSelected(null, setOf("id1", "id2")))
    }

    @Test
    fun countSelected_emptyItemIds_returnsZero() {
        assertEquals(0, JellyfinSyncHierarchy.countSelected(emptyList(), null))
        assertEquals(0, JellyfinSyncHierarchy.countSelected(emptyList(), emptySet()))
        assertEquals(0, JellyfinSyncHierarchy.countSelected(emptyList(), setOf("id1")))
    }

    @Test
    fun countSelected_nullSelectedIds_returnsZero() {
        assertEquals(0, JellyfinSyncHierarchy.countSelected(listOf("a", "b"), null))
    }

    @Test
    fun countSelected_emptySelectedIds_returnsZero() {
        assertEquals(0, JellyfinSyncHierarchy.countSelected(listOf("a", "b"), emptySet()))
    }

    @Test
    fun countSelected_noneMatching_returnsZero() {
        assertEquals(0, JellyfinSyncHierarchy.countSelected(listOf("a", "b"), setOf("x", "y")))
    }

    @Test
    fun countSelected_allMatching_returnsAccurateCount() {
        val items = listOf("a", "b", "c")
        assertEquals(3, JellyfinSyncHierarchy.countSelected(items, setOf("a", "b", "c")))
    }

    @Test
    fun countSelected_partialMatching_returnsAccurateIntersectionCount() {
        val items = listOf("a", "b", "c", "d")
        assertEquals(1, JellyfinSyncHierarchy.countSelected(items, setOf("a")))
        assertEquals(2, JellyfinSyncHierarchy.countSelected(items, setOf("b", "c")))
        assertEquals(3, JellyfinSyncHierarchy.countSelected(items, setOf("a", "c", "d")))
    }

    @Test
    fun countSelected_selectedIdsWithExtraItems_countsOnlyMatchesInItemIds() {
        val items = listOf("a", "b")
        val selected = setOf("a", "x", "y", "z")
        assertEquals(1, JellyfinSyncHierarchy.countSelected(items, selected))
    }

    // =========================================================================
    // 3. Node Classes: SeriesNode, SeasonNode, ArtistNode, AlbumNode
    // =========================================================================

    @Test
    fun seriesNode_stateAndSelectionMethods() {
        val series = SeriesNode("series-1", "Breaking Bad")
        assertEquals("series-1", series.id)
        assertEquals("Breaking Bad", series.name)
        assertTrue(series.seasons.isEmpty())
        assertTrue(series.allEpisodeIds.isEmpty())
        assertEquals(0L, series.totalSize)

        // When allEpisodeIds is empty
        assertEquals(TriState.UNCHECKED, series.getTriState(null))
        assertEquals(TriState.UNCHECKED, series.getTriState(setOf("ep1")))
        assertEquals(0, series.getSelectedCount(null))
        assertEquals(0, series.getSelectedCount(setOf("ep1")))

        // Populate episode IDs
        series.allEpisodeIds.addAll(listOf("ep1", "ep2", "ep3"))
        series.totalSize = 4500L
        assertEquals(4500L, series.totalSize)

        // Null and empty selections
        assertEquals(TriState.UNCHECKED, series.getTriState(null))
        assertEquals(TriState.UNCHECKED, series.getTriState(emptySet()))
        assertEquals(0, series.getSelectedCount(null))
        assertEquals(0, series.getSelectedCount(emptySet()))

        // Partial selection
        assertEquals(TriState.INDETERMINATE, series.getTriState(setOf("ep1")))
        assertEquals(1, series.getSelectedCount(setOf("ep1")))
        assertEquals(TriState.INDETERMINATE, series.getTriState(setOf("ep1", "ep2")))
        assertEquals(2, series.getSelectedCount(setOf("ep1", "ep2")))

        // All selected
        assertEquals(TriState.CHECKED, series.getTriState(setOf("ep1", "ep2", "ep3")))
        assertEquals(3, series.getSelectedCount(setOf("ep1", "ep2", "ep3")))

        // Unrelated selection
        assertEquals(TriState.UNCHECKED, series.getTriState(setOf("other-ep")))
        assertEquals(0, series.getSelectedCount(setOf("other-ep")))
    }

    @Test
    fun seasonNode_stateAndSelectionMethods() {
        val season = SeasonNode("season-1", "Season 1", 1, "Breaking Bad")
        assertEquals("season-1", season.id)
        assertEquals("Season 1", season.name)
        assertEquals(1, season.seasonNumber)
        assertEquals("Breaking Bad", season.seriesName)
        assertTrue(season.episodes.isEmpty())
        assertTrue(season.allEpisodeIds.isEmpty())
        assertEquals(0L, season.totalSize)

        // When empty
        assertEquals(TriState.UNCHECKED, season.getTriState(null))
        assertEquals(0, season.getSelectedCount(null))

        season.allEpisodeIds.addAll(listOf("s1e1", "s1e2"))
        season.totalSize = 2000L
        assertEquals(2000L, season.totalSize)

        // Selection states
        assertEquals(TriState.UNCHECKED, season.getTriState(null))
        assertEquals(TriState.UNCHECKED, season.getTriState(emptySet()))
        assertEquals(0, season.getSelectedCount(null))

        assertEquals(TriState.INDETERMINATE, season.getTriState(setOf("s1e1")))
        assertEquals(1, season.getSelectedCount(setOf("s1e1")))

        assertEquals(TriState.CHECKED, season.getTriState(setOf("s1e1", "s1e2")))
        assertEquals(2, season.getSelectedCount(setOf("s1e1", "s1e2")))
    }

    @Test
    fun artistNode_stateAndSelectionMethods() {
        val artist = ArtistNode("Daft Punk")
        assertEquals("Daft Punk", artist.name)
        assertTrue(artist.albums.isEmpty())
        assertTrue(artist.allSongIds.isEmpty())
        assertEquals(0L, artist.totalSize)

        // When empty
        assertEquals(TriState.UNCHECKED, artist.getTriState(null))
        assertEquals(0, artist.getSelectedCount(null))

        artist.allSongIds.addAll(listOf("song1", "song2", "song3"))
        artist.totalSize = 15000L
        assertEquals(15000L, artist.totalSize)

        assertEquals(TriState.UNCHECKED, artist.getTriState(null))
        assertEquals(TriState.UNCHECKED, artist.getTriState(setOf("unrelated")))
        assertEquals(0, artist.getSelectedCount(setOf("unrelated")))

        assertEquals(TriState.INDETERMINATE, artist.getTriState(setOf("song1")))
        assertEquals(1, artist.getSelectedCount(setOf("song1")))

        assertEquals(TriState.CHECKED, artist.getTriState(setOf("song1", "song2", "song3", "extra")))
        assertEquals(3, artist.getSelectedCount(setOf("song1", "song2", "song3", "extra")))
    }

    @Test
    fun albumNode_stateAndSelectionMethods() {
        val album = AlbumNode("alb-1", "Discovery", "Daft Punk")
        assertEquals("alb-1", album.id)
        assertEquals("Discovery", album.name)
        assertEquals("Daft Punk", album.artistName)
        assertTrue(album.songs.isEmpty())
        assertTrue(album.allSongIds.isEmpty())
        assertEquals(0L, album.totalSize)

        // When empty
        assertEquals(TriState.UNCHECKED, album.getTriState(null))
        assertEquals(0, album.getSelectedCount(null))

        album.allSongIds.addAll(listOf("track1", "track2"))
        album.totalSize = 8000L
        assertEquals(8000L, album.totalSize)

        assertEquals(TriState.UNCHECKED, album.getTriState(null))
        assertEquals(TriState.UNCHECKED, album.getTriState(emptySet()))
        assertEquals(0, album.getSelectedCount(null))

        assertEquals(TriState.INDETERMINATE, album.getTriState(setOf("track2")))
        assertEquals(1, album.getSelectedCount(setOf("track2")))

        assertEquals(TriState.CHECKED, album.getTriState(setOf("track1", "track2")))
        assertEquals(2, album.getSelectedCount(setOf("track1", "track2")))
    }

    // =========================================================================
    // 4. buildTvHierarchy
    // =========================================================================

    @Test
    fun buildTvHierarchy_nullOrEmpty_returnsEmptyHierarchy() {
        val hNull = JellyfinSyncHierarchy.buildTvHierarchy(null)
        assertNotNull(hNull)
        assertTrue(hNull.seriesList.isEmpty())
        assertTrue(hNull.allItemIds.isEmpty())
        assertEquals(0L, hNull.totalLibrarySize)

        val hEmpty = JellyfinSyncHierarchy.buildTvHierarchy(emptyList())
        assertNotNull(hEmpty)
        assertTrue(hEmpty.seriesList.isEmpty())
        assertTrue(hEmpty.allItemIds.isEmpty())
        assertEquals(0L, hEmpty.totalLibrarySize)
    }

    @Test
    fun buildTvHierarchy_ignoresItemsWithNullOrEmptyId() {
        val validEp = JellyfinMediaItem().apply {
            id = "valid1"; name = "Valid Episode"; seriesName = "Show A"; seriesId = "showA"
            parentIndexNumber = 1; indexNumber = 1; fileSize = 100L
        }
        val emptyIdEp = JellyfinMediaItem().apply {
            id = ""; name = "Invalid Episode"; seriesName = "Show A"; seriesId = "showA"
            parentIndexNumber = 1; indexNumber = 2; fileSize = 500L
        }

        val hierarchy = JellyfinSyncHierarchy.buildTvHierarchy(listOf(validEp, emptyIdEp))
        assertEquals(1, hierarchy.seriesList.size)
        assertEquals(1, hierarchy.allItemIds.size)
        assertTrue(hierarchy.allItemIds.contains("valid1"))
        assertEquals(100L, hierarchy.totalLibrarySize)
    }

    @Test
    fun buildTvHierarchy_seriesKeyAndTitleFallbacks() {
        // Episode with both seriesId and seriesName
        val ep1 = JellyfinMediaItem().apply {
            id = "ep1"; seriesId = "sid-100"; seriesName = "Known Show"
            parentIndexNumber = 1; indexNumber = 1; fileSize = 10L
        }
        // Episode with seriesName only (no seriesId) -> fallback key is seriesName
        val ep2 = JellyfinMediaItem().apply {
            id = "ep2"; seriesId = ""; seriesName = "No-Id Show"
            parentIndexNumber = 1; indexNumber = 1; fileSize = 20L
        }
        // Episode with neither seriesId nor seriesName -> fallback is "Unknown Series"
        val ep3 = JellyfinMediaItem().apply {
            id = "ep3"; seriesId = ""; seriesName = null
            parentIndexNumber = 1; indexNumber = 1; fileSize = 30L
        }

        val hierarchy = JellyfinSyncHierarchy.buildTvHierarchy(listOf(ep1, ep2, ep3))
        assertEquals(3, hierarchy.seriesList.size)

        val s1 = hierarchy.seriesList.find { it.id == "sid-100" }
        assertNotNull(s1)
        assertEquals("Known Show", s1!!.name)

        val s2 = hierarchy.seriesList.find { it.id == "No-Id Show" }
        assertNotNull(s2)
        assertEquals("No-Id Show", s2!!.name)

        val s3 = hierarchy.seriesList.find { it.id == "Unknown Series" }
        assertNotNull(s3)
        assertEquals("Unknown Series", s3!!.name)
    }

    @Test
    fun buildTvHierarchy_seasonTitleFallbacks() {
        // Season title explicitly provided
        val epExplicit = JellyfinMediaItem().apply {
            id = "e1"; seriesId = "s1"; seriesName = "Show"
            parentIndexNumber = 1; seasonName = "The First Season"; indexNumber = 1
        }
        // Season title missing with parentIndexNumber > 0 -> "Season " + parentIndexNumber
        val epNumbered = JellyfinMediaItem().apply {
            id = "e2"; seriesId = "s1"; seriesName = "Show"
            parentIndexNumber = 2; seasonName = null; indexNumber = 1
        }
        // Season title missing with parentIndexNumber == 0 -> "Specials"
        val epSpecials = JellyfinMediaItem().apply {
            id = "e3"; seriesId = "s1"; seriesName = "Show"
            parentIndexNumber = 0; seasonName = ""; indexNumber = 1
        }

        val hierarchy = JellyfinSyncHierarchy.buildTvHierarchy(listOf(epExplicit, epNumbered, epSpecials))
        val series = hierarchy.seriesList[0]
        assertEquals(3, series.seasons.size)

        val sExplicit = series.seasons.find { it.seasonNumber == 1 }
        assertNotNull(sExplicit)
        assertEquals("The First Season", sExplicit!!.name)

        val sNumbered = series.seasons.find { it.seasonNumber == 2 }
        assertNotNull(sNumbered)
        assertEquals("Season 2", sNumbered!!.name)

        val sSpecials = series.seasons.find { it.seasonNumber == 0 }
        assertNotNull(sSpecials)
        assertEquals("Specials", sSpecials!!.name)
    }

    @Test
    fun buildTvHierarchy_aggregatesLibrarySizeAndAllItemIds() {
        val ep1 = JellyfinMediaItem().apply {
            id = "ep1"; seriesId = "s1"; seriesName = "Show 1"
            parentIndexNumber = 1; indexNumber = 1; fileSize = 1500L
        }
        val ep2 = JellyfinMediaItem().apply {
            id = "ep2"; seriesId = "s1"; seriesName = "Show 1"
            parentIndexNumber = 1; indexNumber = 2; fileSize = 2500L
        }
        val ep3 = JellyfinMediaItem().apply {
            id = "ep3"; seriesId = "s2"; seriesName = "Show 2"
            parentIndexNumber = 1; indexNumber = 1; fileSize = 3000L
        }

        val hierarchy = JellyfinSyncHierarchy.buildTvHierarchy(listOf(ep1, ep2, ep3))
        assertEquals(7000L, hierarchy.totalLibrarySize)
        assertEquals(setOf("ep1", "ep2", "ep3"), hierarchy.allItemIds)

        val show1 = hierarchy.seriesList.find { it.id == "s1" }!!
        assertEquals(4000L, show1.totalSize)
        assertEquals(setOf("ep1", "ep2"), show1.allEpisodeIds)

        val show2 = hierarchy.seriesList.find { it.id == "s2" }!!
        assertEquals(3000L, show2.totalSize)
        assertEquals(setOf("ep3"), show2.allEpisodeIds)
    }

    @Test
    fun buildTvHierarchy_sortsSeriesByNameCaseInsensitively() {
        val epZ = JellyfinMediaItem().apply { id = "z"; seriesName = "zebra"; seriesId = "sz"; parentIndexNumber = 1 }
        val epA = JellyfinMediaItem().apply { id = "a"; seriesName = "Apple"; seriesId = "sa"; parentIndexNumber = 1 }
        val epM = JellyfinMediaItem().apply { id = "m"; seriesName = "mango"; seriesId = "sm"; parentIndexNumber = 1 }
        val epB = JellyfinMediaItem().apply { id = "b"; seriesName = "BANANA"; seriesId = "sb"; parentIndexNumber = 1 }

        val hierarchy = JellyfinSyncHierarchy.buildTvHierarchy(listOf(epZ, epA, epM, epB))
        val names = hierarchy.seriesList.map { it.name }
        assertEquals(listOf("Apple", "BANANA", "mango", "zebra"), names)
    }

    @Test
    fun buildTvHierarchy_sortsSeasonsByNumberWithSpecialsLast() {
        // Season 2, Season 0 (Specials), Season 1, Season 3
        val epS2 = JellyfinMediaItem().apply { id = "s2"; seriesId = "s"; seriesName = "Show"; parentIndexNumber = 2 }
        val epS0 = JellyfinMediaItem().apply { id = "s0"; seriesId = "s"; seriesName = "Show"; parentIndexNumber = 0 }
        val epS1 = JellyfinMediaItem().apply { id = "s1"; seriesId = "s"; seriesName = "Show"; parentIndexNumber = 1 }
        val epS3 = JellyfinMediaItem().apply { id = "s3"; seriesId = "s"; seriesName = "Show"; parentIndexNumber = 3 }

        val hierarchy = JellyfinSyncHierarchy.buildTvHierarchy(listOf(epS2, epS0, epS1, epS3))
        val series = hierarchy.seriesList[0]
        val seasonNumbers = series.seasons.map { it.seasonNumber }

        // Rule: Season 1, Season 2, Season 3, and Season 0 (Specials) sorted to the very end
        assertEquals(listOf(1, 2, 3, 0), seasonNumbers)
    }

    @Test
    fun buildTvHierarchy_sortsEpisodesByIndexNumber() {
        val ep3 = JellyfinMediaItem().apply { id = "e3"; seriesId = "s"; seriesName = "Show"; parentIndexNumber = 1; indexNumber = 3 }
        val ep1 = JellyfinMediaItem().apply { id = "e1"; seriesId = "s"; seriesName = "Show"; parentIndexNumber = 1; indexNumber = 1 }
        val ep2 = JellyfinMediaItem().apply { id = "e2"; seriesId = "s"; seriesName = "Show"; parentIndexNumber = 1; indexNumber = 2 }

        val hierarchy = JellyfinSyncHierarchy.buildTvHierarchy(listOf(ep3, ep1, ep2))
        val episodes = hierarchy.seriesList[0].seasons[0].episodes
        assertEquals(listOf(1, 2, 3), episodes.map { it.indexNumber })
        assertEquals(listOf("e1", "e2", "e3"), episodes.map { it.id })
    }

    // =========================================================================
    // 5. buildMusicHierarchy
    // =========================================================================

    @Test
    fun buildMusicHierarchy_nullOrEmpty_returnsEmptyHierarchy() {
        val hNull = JellyfinSyncHierarchy.buildMusicHierarchy(null)
        assertNotNull(hNull)
        assertTrue(hNull.artistsList.isEmpty())
        assertTrue(hNull.allItemIds.isEmpty())
        assertEquals(0L, hNull.totalLibrarySize)

        val hEmpty = JellyfinSyncHierarchy.buildMusicHierarchy(emptyList())
        assertNotNull(hEmpty)
        assertTrue(hEmpty.artistsList.isEmpty())
        assertTrue(hEmpty.allItemIds.isEmpty())
        assertEquals(0L, hEmpty.totalLibrarySize)
    }

    @Test
    fun buildMusicHierarchy_ignoresItemsWithNullOrEmptyId() {
        val validSong = JellyfinMediaItem().apply {
            id = "song-1"; name = "Track 1"; artist = "Artist A"; album = "Album A"; fileSize = 1000L
        }
        val emptyIdSong = JellyfinMediaItem().apply {
            id = ""; name = "Track 2"; artist = "Artist A"; album = "Album A"; fileSize = 2000L
        }

        val hierarchy = JellyfinSyncHierarchy.buildMusicHierarchy(listOf(validSong, emptyIdSong))
        assertEquals(1, hierarchy.artistsList.size)
        assertEquals(1, hierarchy.allItemIds.size)
        assertTrue(hierarchy.allItemIds.contains("song-1"))
        assertEquals(1000L, hierarchy.totalLibrarySize)
    }

    @Test
    fun buildMusicHierarchy_artistNameFallbacks() {
        // 1. albumArtist takes precedence over artist
        val song1 = JellyfinMediaItem().apply {
            id = "s1"; albumArtist = "Primary Artist"; artist = "Track Artist"; album = "Alb 1"
        }
        // 2. artist used when albumArtist is blank
        val song2 = JellyfinMediaItem().apply {
            id = "s2"; albumArtist = "   "; artist = "Track Only Artist"; album = "Alb 2"
        }
        // 3. "Unknown Artist" used when both are blank
        val song3 = JellyfinMediaItem().apply {
            id = "s3"; albumArtist = ""; artist = ""; album = "Alb 3"
        }

        val hierarchy = JellyfinSyncHierarchy.buildMusicHierarchy(listOf(song1, song2, song3))
        assertEquals(3, hierarchy.artistsList.size)

        val names = hierarchy.artistsList.map { it.name }.toSet()
        assertTrue(names.contains("Primary Artist"))
        assertTrue(names.contains("Track Only Artist"))
        assertTrue(names.contains("Unknown Artist"))
    }

    @Test
    fun buildMusicHierarchy_caseInsensitiveArtistGrouping() {
        // "Radiohead" and "radiohead" must merge into the same ArtistNode
        val song1 = JellyfinMediaItem().apply { id = "s1"; artist = "Radiohead"; album = "OK Computer" }
        val song2 = JellyfinMediaItem().apply { id = "s2"; artist = "radiohead"; album = "The Bends" }

        val hierarchy = JellyfinSyncHierarchy.buildMusicHierarchy(listOf(song1, song2))
        assertEquals(1, hierarchy.artistsList.size)
        assertEquals(2, hierarchy.artistsList[0].albums.size)
        assertEquals(setOf("s1", "s2"), hierarchy.artistsList[0].allSongIds)
    }

    @Test
    fun buildMusicHierarchy_albumTitleAndIdFallbacks() {
        // Song with explicit albumId and title
        val song1 = JellyfinMediaItem().apply {
            id = "s1"; artist = "Artist"; album = "Named Album"; albumId = "custom-alb-id"
        }
        // Song without albumId -> fallback id is albumKey
        val song2 = JellyfinMediaItem().apply {
            id = "s2"; artist = "Artist"; album = "No Id Album"; albumId = null
        }
        // Song with blank album -> "Unknown Album"
        val song3 = JellyfinMediaItem().apply {
            id = "s3"; artist = "Artist"; album = "   "; albumId = null
        }

        val hierarchy = JellyfinSyncHierarchy.buildMusicHierarchy(listOf(song1, song2, song3))
        val artist = hierarchy.artistsList[0]
        assertEquals(3, artist.albums.size)

        val alb1 = artist.albums.find { it.name == "Named Album" }!!
        assertEquals("custom-alb-id", alb1.id)

        val alb2 = artist.albums.find { it.name == "No Id Album" }!!
        assertEquals("artist_no id album", alb2.id)

        val alb3 = artist.albums.find { it.name == "Unknown Album" }!!
        assertEquals("Unknown Album", alb3.name)
        assertEquals("artist_unknown album", alb3.id)
    }

    @Test
    fun buildMusicHierarchy_distinguishesSameAlbumNameAcrossDifferentArtists() {
        val s1 = JellyfinMediaItem().apply { id = "s1"; artist = "Queen"; album = "Greatest Hits" }
        val s2 = JellyfinMediaItem().apply { id = "s2"; artist = "ABBA"; album = "Greatest Hits" }

        val hierarchy = JellyfinSyncHierarchy.buildMusicHierarchy(listOf(s1, s2))
        assertEquals(2, hierarchy.artistsList.size)

        val abba = hierarchy.artistsList.find { it.name == "ABBA" }!!
        val queen = hierarchy.artistsList.find { it.name == "Queen" }!!

        assertEquals(1, abba.albums.size)
        assertEquals(1, queen.albums.size)
        assertEquals("ABBA", abba.albums[0].artistName)
        assertEquals("Queen", queen.albums[0].artistName)
    }

    @Test
    fun buildMusicHierarchy_aggregatesLibrarySizeAndSongIds() {
        val s1 = JellyfinMediaItem().apply { id = "s1"; artist = "A"; album = "Alb 1"; fileSize = 100L }
        val s2 = JellyfinMediaItem().apply { id = "s2"; artist = "A"; album = "Alb 1"; fileSize = 200L }
        val s3 = JellyfinMediaItem().apply { id = "s3"; artist = "A"; album = "Alb 2"; fileSize = 300L }

        val hierarchy = JellyfinSyncHierarchy.buildMusicHierarchy(listOf(s1, s2, s3))
        assertEquals(600L, hierarchy.totalLibrarySize)
        assertEquals(setOf("s1", "s2", "s3"), hierarchy.allItemIds)

        val artist = hierarchy.artistsList[0]
        assertEquals(600L, artist.totalSize)
        assertEquals(setOf("s1", "s2", "s3"), artist.allSongIds)

        val alb1 = artist.albums.find { it.name == "Alb 1" }!!
        assertEquals(300L, alb1.totalSize)
        assertEquals(setOf("s1", "s2"), alb1.allSongIds)
    }

    @Test
    fun buildMusicHierarchy_sortsArtistsAndAlbumsCaseInsensitively() {
        val sZ = JellyfinMediaItem().apply { id = "1"; artist = "zz top"; album = "Eliminator" }
        val sB = JellyfinMediaItem().apply { id = "2"; artist = "Beatles"; album = "Rubber Soul" }
        val sA = JellyfinMediaItem().apply { id = "3"; artist = "abba"; album = "Arrival" }
        val sB2 = JellyfinMediaItem().apply { id = "4"; artist = "Beatles"; album = "abbey Road" }

        val hierarchy = JellyfinSyncHierarchy.buildMusicHierarchy(listOf(sZ, sB, sA, sB2))
        val artistNames = hierarchy.artistsList.map { it.name }
        assertEquals(listOf("abba", "Beatles", "zz top"), artistNames)

        val beatles = hierarchy.artistsList.find { it.name == "Beatles" }!!
        val albumNames = beatles.albums.map { it.name }
        assertEquals(listOf("abbey Road", "Rubber Soul"), albumNames)
    }

    @Test
    fun buildMusicHierarchy_sortsSongsByDiscNumberThenTrackNumber() {
        // Disc 2 Track 1, Disc 1 Track 2, Disc 1 Track 1
        val songD2T1 = JellyfinMediaItem().apply {
            id = "d2t1"; artist = "Pink Floyd"; album = "The Wall"
            parentIndexNumber = 2; indexNumber = 1
        }
        val songD1T2 = JellyfinMediaItem().apply {
            id = "d1t2"; artist = "Pink Floyd"; album = "The Wall"
            parentIndexNumber = 1; indexNumber = 2
        }
        val songD1T1 = JellyfinMediaItem().apply {
            id = "d1t1"; artist = "Pink Floyd"; album = "The Wall"
            parentIndexNumber = 1; indexNumber = 1
        }

        val hierarchy = JellyfinSyncHierarchy.buildMusicHierarchy(listOf(songD2T1, songD1T2, songD1T1))
        val songs = hierarchy.artistsList[0].albums[0].songs

        val expectedIds = listOf("d1t1", "d1t2", "d2t1")
        assertEquals(expectedIds, songs.map { it.id })
    }

    // =========================================================================
    // 6. buildMoviesHierarchy
    // =========================================================================

    @Test
    fun buildMoviesHierarchy_nullOrEmpty_returnsEmptyHierarchy() {
        val hNull = JellyfinSyncHierarchy.buildMoviesHierarchy(null)
        assertNotNull(hNull)
        assertTrue(hNull.moviesList.isEmpty())
        assertTrue(hNull.allItemIds.isEmpty())
        assertEquals(0L, hNull.totalLibrarySize)

        val hEmpty = JellyfinSyncHierarchy.buildMoviesHierarchy(emptyList())
        assertNotNull(hEmpty)
        assertTrue(hEmpty.moviesList.isEmpty())
        assertTrue(hEmpty.allItemIds.isEmpty())
        assertEquals(0L, hEmpty.totalLibrarySize)
    }

    @Test
    fun buildMoviesHierarchy_ignoresItemsWithNullOrEmptyId() {
        val validMovie = JellyfinMediaItem().apply { id = "m1"; name = "Matrix"; fileSize = 1000L }
        val emptyIdMovie = JellyfinMediaItem().apply { id = ""; name = "Avatar"; fileSize = 2000L }

        val hierarchy = JellyfinSyncHierarchy.buildMoviesHierarchy(listOf(validMovie, emptyIdMovie))
        assertEquals(1, hierarchy.moviesList.size)
        assertEquals("Matrix", hierarchy.moviesList[0].name)
        assertEquals(setOf("m1"), hierarchy.allItemIds)
        assertEquals(1000L, hierarchy.totalLibrarySize)
    }

    @Test
    fun buildMoviesHierarchy_aggregatesSizeAndItemIds() {
        val m1 = JellyfinMediaItem().apply { id = "m1"; name = "Film 1"; fileSize = 5000000L }
        val m2 = JellyfinMediaItem().apply { id = "m2"; name = "Film 2"; fileSize = 7000000L }

        val hierarchy = JellyfinSyncHierarchy.buildMoviesHierarchy(listOf(m1, m2))
        assertEquals(12000000L, hierarchy.totalLibrarySize)
        assertEquals(setOf("m1", "m2"), hierarchy.allItemIds)
        assertEquals(2, hierarchy.moviesList.size)
    }

    @Test
    fun buildMoviesHierarchy_sortsMoviesCaseInsensitivelyWithNullNameSafety() {
        val mZ = JellyfinMediaItem().apply { id = "1"; name = "zodiac" }
        val mA = JellyfinMediaItem().apply { id = "2"; name = "Alien" }
        val mM = JellyfinMediaItem().apply { id = "3"; name = "Matrix" }
        val mB = JellyfinMediaItem().apply { id = "4"; name = "batman" }

        val hierarchy = JellyfinSyncHierarchy.buildMoviesHierarchy(listOf(mZ, mA, mM, mB))
        val names = hierarchy.moviesList.map { it.name }
        assertEquals(listOf("Alien", "batman", "Matrix", "zodiac"), names)
    }
}
