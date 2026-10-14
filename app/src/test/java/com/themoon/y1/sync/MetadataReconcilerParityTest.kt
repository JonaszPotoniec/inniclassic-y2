package com.themoon.y1.sync

import com.themoon.y1.models.SongItem
import com.themoon.y1.sync.jellyfin.JellyfinMediaItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class MetadataReconcilerParityTest {

    @Rule
    @JvmField
    val folder = TemporaryFolder()

    // -------------------------------------------------------------------------
    // 1. normalizeText(text)
    // -------------------------------------------------------------------------

    @Test
    fun normalizeText_nullReturnsEmptyString() {
        assertEquals("", MetadataReconciler.normalizeText(null))
    }

    @Test
    fun normalizeText_trimmedAndLowercased() {
        assertEquals("hello world", MetadataReconciler.normalizeText("  HELLO  World "))
    }

    @Test
    fun normalizeText_emptyAndWhitespaceOnlyReturnsEmpty() {
        assertEquals("", MetadataReconciler.normalizeText(""))
        assertEquals("", MetadataReconciler.normalizeText("   "))
        assertEquals("", MetadataReconciler.normalizeText("\t\n\r"))
    }

    @Test
    fun normalizeText_alreadyNormalizedReturnsSame() {
        assertEquals("already lowercase", MetadataReconciler.normalizeText("already lowercase"))
    }

    // -------------------------------------------------------------------------
    // 2. makeSongKey(title, artist, album)
    // -------------------------------------------------------------------------

    @Test
    fun makeSongKey_nullsMapToEmptyStringWithSeparators() {
        assertEquals("||", MetadataReconciler.makeSongKey(null, null, null))
    }

    @Test
    fun makeSongKey_valuesNormalizedWithPipeSeparator() {
        assertEquals(
            "title|artist|album",
            MetadataReconciler.makeSongKey("  Title  ", " ARTIST ", "Album ")
        )
    }

    @Test
    fun makeSongKey_partialNullsHandledCorrectly() {
        assertEquals("title||album", MetadataReconciler.makeSongKey(" Title ", null, " Album "))
        assertEquals("|artist|", MetadataReconciler.makeSongKey(null, " Artist ", null))
        assertEquals("title|artist|", MetadataReconciler.makeSongKey(" Title ", " Artist ", null))
        assertEquals("||album", MetadataReconciler.makeSongKey(null, null, " Album "))
    }

    // -------------------------------------------------------------------------
    // 3. reconcileMusic(remoteItems, localSongs, trackNumberMap, ledger)
    // -------------------------------------------------------------------------

    @Test
    fun reconcileMusic_remoteItemsEmpty_returnsEmptyDownloadAndNotDirty() {
        val ledger = SyncLedger()
        val dummyFile = folder.newFile("song.mp3")
        val localSong = SongItem(dummyFile, "Title", "Artist", "Album")
        val localSongs = listOf(localSong)
        val trackMap = mapOf(dummyFile.absolutePath to 1)

        val result = MetadataReconciler.reconcileMusic(
            remoteItems = emptyList(),
            localSongs = localSongs,
            trackNumberMap = trackMap,
            ledger = ledger
        )

        assertNotNull(result)
        assertTrue(result.itemsToDownload.isEmpty())
        assertTrue(result.updatedSongs.isEmpty())
        assertTrue(result.updatedTrackNumbers.isEmpty())
        assertFalse(result.isDirty)
    }

    @Test
    fun reconcileMusic_localSongsNull_doesNotCrashNpeGuard() {
        val ledger = SyncLedger()

        // 1. Remote items empty + localSongs null
        val emptyResult = MetadataReconciler.reconcileMusic(
            remoteItems = emptyList(),
            localSongs = null,
            trackNumberMap = null,
            ledger = ledger
        )
        assertNotNull(emptyResult)
        assertFalse(emptyResult.isDirty)
        assertTrue(emptyResult.itemsToDownload.isEmpty())

        // 2. Remote item present + localSongs null -> does not crash, queues for download
        val remoteItem = JellyfinMediaItem().apply {
            id = "jf-remote-1"
            name = "Stairway to Heaven"
            artist = "Led Zeppelin"
            album = "Led Zeppelin IV"
        }

        val downloadResult = MetadataReconciler.reconcileMusic(
            remoteItems = listOf(remoteItem),
            localSongs = null,
            trackNumberMap = null,
            ledger = ledger
        )
        assertNotNull(downloadResult)
        assertEquals(1, downloadResult.itemsToDownload.size)
        assertEquals("jf-remote-1", downloadResult.itemsToDownload[0].id)
        assertFalse(downloadResult.isDirty)

        // 3. Ledger match present + localSongs null -> does not crash, matches by ledger path
        val existingFile = folder.newFile("existing_track.mp3")
        ledger.putItem(
            SyncLedger.LedgerItem(
                jellyfinId = "jf-remote-2",
                libraryId = "lib-1",
                collectionType = "music",
                localPath = existingFile.absolutePath,
                fileSize = existingFile.length(),
                serverDateModified = "2026-01-01",
                orphaned = false
            )
        )
        val remoteItem2 = JellyfinMediaItem().apply {
            id = "jf-remote-2"
            name = "Kashmir"
            artist = "Led Zeppelin"
            album = "Physical Graffiti"
            year = "1975"
            genre = "Rock"
            albumArtist = "Led Zeppelin"
            composer = "Jimmy Page"
        }

        val ledgerMatchResult = MetadataReconciler.reconcileMusic(
            remoteItems = listOf(remoteItem2),
            localSongs = null,
            trackNumberMap = null,
            ledger = ledger
        )
        assertNotNull(ledgerMatchResult)
        assertTrue(ledgerMatchResult.itemsToDownload.isEmpty())
    }

    @Test
    fun reconcileMusic_ledgerMatchWithLocalFilePresent_matchesByPathDoesNotRedownload() {
        val ledger = SyncLedger()
        val localFile = folder.newFile("matched_by_ledger.mp3")

        // Register in ledger
        ledger.putItem(
            SyncLedger.LedgerItem(
                jellyfinId = "jf-ledger-1",
                libraryId = "lib-music",
                collectionType = "music",
                localPath = localFile.absolutePath,
                fileSize = localFile.length(),
                serverDateModified = "2025-06-01",
                orphaned = false
            )
        )

        // Local song list has different title/artist/album (e.g. metadata was edited or different)
        // so key-based matching would fail
        val localSong = SongItem(localFile, "Original Title", "Original Artist", "Original Album")
        val localSongs = listOf(localSong)

        // Remote item with same Jellyfin ID
        val remoteItem = JellyfinMediaItem().apply {
            id = "jf-ledger-1"
            name = "Updated Title"
            artist = "Updated Artist"
            album = "Updated Album"
            libraryId = "lib-music"
        }

        val result = MetadataReconciler.reconcileMusic(
            remoteItems = listOf(remoteItem),
            localSongs = localSongs,
            trackNumberMap = null,
            ledger = ledger
        )

        // Must match by path and NOT request download
        assertEquals(0, result.itemsToDownload.size)
        // Metadata changed, so dirty flag and updatedSongs must reflect this
        assertTrue(result.isDirty)
        assertEquals(1, result.updatedSongs.size)
        assertEquals("Updated Title", localSong.title)
        assertEquals("Updated Artist", localSong.artist)
        assertEquals("Updated Album", localSong.album)

        // Verify ledger was updated with current song info
        val updatedLedgerItem = ledger.getItem("jf-ledger-1")
        assertNotNull(updatedLedgerItem)
        assertEquals(localFile.absolutePath, updatedLedgerItem!!.localPath)
    }

    @Test
    fun reconcileMusic_matchByTitleArtistAlbumKey_updatesSongMetadataIfChangedSetsDirtyTrue() {
        val ledger = SyncLedger()
        val songFile = folder.newFile("key_matched.mp3")

        val localSong = SongItem(
            file = songFile,
            title = "Echoes",
            artist = "Pink Floyd",
            album = "Meddle",
            year = "1971",
            genre = "Progressive Rock",
            albumArtist = "Pink Floyd",
            composer = "Roger Waters"
        )
        val localSongs = listOf(localSong)

        // Remote item has same key (case/space variation), but updated year, genre, composer
        val remoteItem = JellyfinMediaItem().apply {
            id = "jf-key-match"
            name = "  echoes  "
            artist = "PINK FLOYD"
            album = "meddle"
            year = "1971 (Remastered)"
            genre = "Psychedelic Rock"
            albumArtist = "Pink Floyd"
            composer = "Pink Floyd"
            libraryId = "lib-music"
        }

        val result = MetadataReconciler.reconcileMusic(
            remoteItems = listOf(remoteItem),
            localSongs = localSongs,
            trackNumberMap = null,
            ledger = ledger
        )

        // Should NOT download
        assertEquals(0, result.itemsToDownload.size)
        // Metadata changed -> dirty = true and updatedSongs contains the song
        assertTrue(result.isDirty)
        assertEquals(1, result.updatedSongs.size)
        assertEquals("1971 (Remastered)", localSong.year)
        assertEquals("Psychedelic Rock", localSong.genre)
        assertEquals("Pink Floyd", localSong.composer)

        // Ledger must now contain this item
        val savedLedger = ledger.getItem("jf-key-match")
        assertNotNull(savedLedger)
        assertEquals(songFile.absolutePath, savedLedger!!.localPath)
    }

    @Test
    fun reconcileMusic_matchByTitleArtistAlbumKey_identicalMetadata_notDirty() {
        val ledger = SyncLedger()
        val songFile = folder.newFile("identical.mp3")

        val localSong = SongItem(
            file = songFile,
            title = "Time",
            artist = "Pink Floyd",
            album = "Dark Side",
            year = "1973",
            genre = "Rock",
            albumArtist = "Pink Floyd",
            composer = "Pink Floyd"
        )
        val localSongs = listOf(localSong)
        val trackMap = mapOf(songFile.absolutePath to 4)

        val remoteItem = JellyfinMediaItem().apply {
            id = "jf-identical"
            name = "Time"
            artist = "Pink Floyd"
            album = "Dark Side"
            year = "1973"
            genre = "Rock"
            albumArtist = "Pink Floyd"
            composer = "Pink Floyd"
            indexNumber = 4
            libraryId = "lib-music"
        }

        val result = MetadataReconciler.reconcileMusic(
            remoteItems = listOf(remoteItem),
            localSongs = localSongs,
            trackNumberMap = trackMap,
            ledger = ledger
        )

        assertEquals(0, result.itemsToDownload.size)
        assertFalse(result.isDirty)
        assertTrue(result.updatedSongs.isEmpty())
        assertTrue(result.updatedTrackNumbers.isEmpty())
        assertNotNull(ledger.getItem("jf-identical"))
    }

    @Test
    fun reconcileMusic_noMatchFound_addsRemoteItemToItemsToDownload() {
        val ledger = SyncLedger()
        val songFile = folder.newFile("other.mp3")
        val localSong = SongItem(songFile, "Song A", "Artist A", "Album A")
        val localSongs = listOf(localSong)

        val remoteItem = JellyfinMediaItem().apply {
            id = "jf-unmatched-1"
            name = "Song B"
            artist = "Artist B"
            album = "Album B"
            libraryId = "lib-music"
        }

        val result = MetadataReconciler.reconcileMusic(
            remoteItems = listOf(remoteItem),
            localSongs = localSongs,
            trackNumberMap = emptyMap(),
            ledger = ledger
        )

        assertEquals(1, result.itemsToDownload.size)
        assertEquals("jf-unmatched-1", result.itemsToDownload[0].id)
        assertFalse(result.isDirty)
        assertTrue(result.updatedSongs.isEmpty())
        assertTrue(result.updatedTrackNumbers.isEmpty())
    }

    @Test
    fun reconcileMusic_trackNumberUpdatesPropagatedToUpdatedTrackNumbersMap() {
        val ledger = SyncLedger()
        val songFile = folder.newFile("track_num.mp3")
        val absPath = songFile.absolutePath

        val localSong = SongItem(songFile, "Money", "Pink Floyd", "Dark Side")
        val localSongs = listOf(localSong)

        // Case 1: Track number map is missing the track (null) -> updates and marks dirty
        val remoteItem1 = JellyfinMediaItem().apply {
            id = "jf-track-1"
            name = "Money"
            artist = "Pink Floyd"
            album = "Dark Side"
            indexNumber = 6
        }

        val res1 = MetadataReconciler.reconcileMusic(
            remoteItems = listOf(remoteItem1),
            localSongs = localSongs,
            trackNumberMap = emptyMap(),
            ledger = ledger
        )

        assertEquals(6, res1.updatedTrackNumbers[absPath])
        assertTrue(res1.isDirty)
        assertTrue(res1.updatedSongs.contains(localSong))

        // Case 2: Track number map has different index number -> updates to new index
        val trackMapWithOldNum = mapOf(absPath to 2)
        val remoteItem2 = JellyfinMediaItem().apply {
            id = "jf-track-2"
            name = "Money"
            artist = "Pink Floyd"
            album = "Dark Side"
            indexNumber = 6
        }

        val res2 = MetadataReconciler.reconcileMusic(
            remoteItems = listOf(remoteItem2),
            localSongs = localSongs,
            trackNumberMap = trackMapWithOldNum,
            ledger = ledger
        )

        assertEquals(6, res2.updatedTrackNumbers[absPath])
        assertTrue(res2.isDirty)

        // Case 3: Track number map has identical index number -> not updated
        val trackMapWithCurrentNum = mapOf(absPath to 6)
        val remoteItem3 = JellyfinMediaItem().apply {
            id = "jf-track-3"
            name = "Money"
            artist = "Pink Floyd"
            album = "Dark Side"
            indexNumber = 6
        }

        val res3 = MetadataReconciler.reconcileMusic(
            remoteItems = listOf(remoteItem3),
            localSongs = localSongs,
            trackNumberMap = trackMapWithCurrentNum,
            ledger = ledger
        )

        assertFalse(res3.updatedTrackNumbers.containsKey(absPath))
        assertFalse(res3.isDirty)

        // Case 4: Remote indexNumber <= 0 -> ignored even if trackNumberMap is empty
        val remoteItemZero = JellyfinMediaItem().apply {
            id = "jf-track-zero"
            name = "Money"
            artist = "Pink Floyd"
            album = "Dark Side"
            indexNumber = 0
        }

        val res4 = MetadataReconciler.reconcileMusic(
            remoteItems = listOf(remoteItemZero),
            localSongs = localSongs,
            trackNumberMap = emptyMap(),
            ledger = ledger
        )

        assertFalse(res4.updatedTrackNumbers.containsKey(absPath))
        assertFalse(res4.isDirty)
    }

    @Test
    fun reconcileMusic_localSongFileDoesNotExistOnDisk_requiresDownload() {
        val ledger = SyncLedger()
        val nonExistentFile = File(folder.root, "deleted_song.mp3")
        assertFalse(nonExistentFile.exists())

        val localSong = SongItem(nonExistentFile, "Ghost Song", "The Doors", "An American Prayer")
        val localSongs = listOf(localSong)

        val remoteItem = JellyfinMediaItem().apply {
            id = "jf-ghost"
            name = "Ghost Song"
            artist = "The Doors"
            album = "An American Prayer"
        }

        val result = MetadataReconciler.reconcileMusic(
            remoteItems = listOf(remoteItem),
            localSongs = localSongs,
            trackNumberMap = emptyMap(),
            ledger = ledger
        )

        // Since file does not exist on disk, localIndex ignores it, requiring download
        assertEquals(1, result.itemsToDownload.size)
        assertEquals("jf-ghost", result.itemsToDownload[0].id)
    }

    @Test
    fun reconcileMusic_ledgerFileDoesNotExistOnDisk_fallsBackToKeyOrDownload() {
        val ledger = SyncLedger()
        val nonExistentFile = File(folder.root, "missing_from_ledger.mp3")

        ledger.putItem(
            SyncLedger.LedgerItem(
                jellyfinId = "jf-missing",
                libraryId = "lib-1",
                collectionType = "music",
                localPath = nonExistentFile.absolutePath,
                fileSize = 1000L,
                serverDateModified = "2024-01-01",
                orphaned = false
            )
        )

        val remoteItem = JellyfinMediaItem().apply {
            id = "jf-missing"
            name = "Missing Track"
            artist = "Unknown"
            album = "Unknown"
        }

        val result = MetadataReconciler.reconcileMusic(
            remoteItems = listOf(remoteItem),
            localSongs = emptyList(),
            trackNumberMap = emptyMap(),
            ledger = ledger
        )

        // File doesn't exist on disk, and no localSong matches key -> must be queued for download
        assertEquals(1, result.itemsToDownload.size)
        assertEquals("jf-missing", result.itemsToDownload[0].id)
    }
}
