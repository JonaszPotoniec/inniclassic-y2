package com.themoon.y1.sync

import com.themoon.y1.models.SongItem
import com.themoon.y1.sync.jellyfin.JellyfinMediaItem
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.Assert.*
import java.nio.file.Files

class MetadataReconcilerTest {

    @Rule
    @JvmField
    val folder = TemporaryFolder()

    @Test
    fun testReconcileMusicMatchingAndDiff() {
        val dummyFile = folder.newFile("song.mp3")

        val localSong = SongItem(dummyFile, "Comfortably Numb", "Pink Floyd", "The Wall",
            "1979", "Rock", "Pink Floyd", "Roger Waters")
        val localSongs = listOf(localSong)
        val trackNumberMap = mapOf(dummyFile.absolutePath to 1)
        val ledger = SyncLedger()

        // Remote item matches local song by metadata, but has updated genre and track number
        val remoteItem = JellyfinMediaItem().apply {
            id = "jf-100"
            name = "Comfortably Numb"
            artist = "Pink Floyd"
            album = "The Wall"
            year = "1979"
            genre = "Progressive Rock" // changed
            albumArtist = "Pink Floyd"
            composer = "Roger Waters"
            indexNumber = 6 // changed from 1 to 6
            libraryId = "lib-music"
            collectionType = "music"
            fileSize = dummyFile.length()
        }

        val res = MetadataReconciler.reconcileMusic(listOf(remoteItem), localSongs, trackNumberMap, ledger)

        // Should NOT download the file because local file matched
        assertEquals(0, res.itemsToDownload.size)
        assertTrue(res.isDirty)
        assertEquals(1, res.updatedSongs.size)
        assertEquals("Progressive Rock", localSong.genre)
        assertEquals(6, res.updatedTrackNumbers[dummyFile.absolutePath])

        // Ledger should have the entry registered
        assertNotNull(ledger.getItem("jf-100"))
        assertEquals(dummyFile.absolutePath, ledger.getItem("jf-100")!!.localPath)
    }

    @Test
    fun testReconcileMusicMissingNeedsDownload() {
        val ledger = SyncLedger()

        val remoteItem = JellyfinMediaItem().apply {
            id = "jf-200"
            name = "Time"
            artist = "Pink Floyd"
            album = "The Dark Side of the Moon"
            libraryId = "lib-music"
            collectionType = "music"
        }

        val res = MetadataReconciler.reconcileMusic(listOf(remoteItem), emptyList(), emptyMap(), ledger)

        assertEquals(1, res.itemsToDownload.size)
        assertEquals("jf-200", res.itemsToDownload[0].id)
    }

    @Test
    fun testReconcileVideos() {
        val dummyVideo = folder.newFile("movie.mp4")
        Files.write(dummyVideo.toPath(), byteArrayOf(1, 2, 3))
        val ledger = SyncLedger()
        ledger.putItem(SyncLedger.LedgerItem(
            "jf-movie-1",
            "lib-movies",
            "movies",
            dummyVideo.absolutePath,
            dummyVideo.length(),
            "2024-01-01",
            false
        ))

        val remoteMovie1 = JellyfinMediaItem().apply {
            id = "jf-movie-1"
            name = "Inception"
            collectionType = "movies"
        }
        val remoteMovie2 = JellyfinMediaItem().apply {
            id = "jf-movie-2"
            name = "The Matrix"
            collectionType = "movies"
        }

        val res = MetadataReconciler.reconcileVideos(listOf(remoteMovie1, remoteMovie2), ledger)

        // Movie 1 already exists on disk and is in ledger; Movie 2 needs download
        assertEquals(1, res.itemsToDownload.size)
        assertEquals("jf-movie-2", res.itemsToDownload[0].id)
    }
}
