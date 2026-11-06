package com.themoon.y1.managers

import com.themoon.y1.models.SongItem
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class MediaLibraryCacheTest {
    private val mlm = MediaLibraryManager.getInstance()

    private val cacheJson = JSONObject(
        """
        {"music":[
          {"path":"/music/a.mp3","title":"A","artist":"X","album":"One","trackNum":2},
          {"path":"/music/gone.mp3","title":"Gone","artist":"X","album":"One","trackNum":3},
          {"path":"/music/a.mp3","title":"A again","artist":"X","album":"One","trackNum":2}
        ],
        "books":[
          {"path":"/books/b.m4b","title":"B","artist":"Y","album":"Book","trackNum":1}
        ]}
        """.trimIndent()
    )

    private val usable: (File, Boolean) -> Boolean = { f, _ -> !f.path.contains("gone") }

    @Before
    fun setUp() = clearShared()

    @After
    fun tearDown() = clearShared()

    private fun clearShared() {
        mlm.musicLibrary.clear()
        mlm.audiobookLibrary.clear()
        mlm.trackNumberMap.clear()
        mlm.uniqueAlbumList.clear()
    }

    @Test
    fun parseSkipsMissingAndDuplicateFilesWithoutTouchingSharedLists() {
        val old = SongItem(File("/music/old.mp3"), "Old", "Z", "Old", "", "", "", "")
        mlm.musicLibrary.add(old)

        val cache = mlm.parseLibraryCache(cacheJson, usable)

        assertEquals(listOf("A"), cache.music.map { it.title })
        assertEquals(listOf("B"), cache.books.map { it.title })
        assertEquals(mapOf("/music/a.mp3" to 2, "/books/b.m4b" to 1), cache.trackNumbers)
        assertEquals(listOf(old), mlm.musicLibrary.toList())
        assertTrue(mlm.trackNumberMap.isEmpty())
    }

    @Test
    fun applyReplacesSharedListsAndRebuildsAlbums() {
        mlm.musicLibrary.add(SongItem(File("/music/old.mp3"), "Old", "Z", "Old", "", "", "", ""))

        mlm.applyLibraryCache(mlm.parseLibraryCache(cacheJson, usable))

        assertEquals(listOf("A"), mlm.musicLibrary.map { it.title })
        assertEquals(listOf("B"), mlm.audiobookLibrary.map { it.title })
        assertEquals(2, mlm.trackNumberMap["/music/a.mp3"])
        assertEquals(setOf("One", "Book"), mlm.uniqueAlbumList.map { it.album }.toSet())
    }

    @Test
    fun parseLibraryCacheReadsDateModifiedAndFileSize() {
        val json = JSONObject(
            """
            {"music":[
              {"path":"/music/t1.mp3","title":"T1","artist":"A","album":"Alb","trackNum":1,"dateModified":1729000000000,"fileSize":98765},
              {"path":"/music/t2.mp3","title":"T2","artist":"A","album":"Alb","trackNum":2,"modified":1729111111000,"size":54321},
              {"path":"/music/t3.mp3","title":"T3","artist":"A","album":"Alb","trackNum":3}
            ],
            "books":[]}
            """.trimIndent()
        )

        val cache = mlm.parseLibraryCache(json) { _, _ -> true }
        assertEquals(3, cache.music.size)

        val t1 = cache.music[0]
        assertEquals(1729000000000L, t1.dateModified)
        assertEquals(98765L, t1.fileSize)

        val t2 = cache.music[1]
        assertEquals(1729111111000L, t2.dateModified)
        assertEquals(54321L, t2.fileSize)

        val t3 = cache.music[2]
        assertEquals(0L, t3.dateModified)
        assertEquals(0L, t3.fileSize)
    }

    @Test
    fun writeSongListJsonSerializesDateModifiedAndFileSize() {
        val song = SongItem(File("/music/song.mp3"), "Song", "Artist", "Album")
        song.dateModified = 1729222222000L
        song.fileSize = 456789L
        mlm.trackNumberMap["/music/song.mp3"] = 4

        val sw = java.io.StringWriter()
        mlm.writeSongListJson(sw, listOf(song))
        val jsonStr = sw.toString()

        assertTrue(jsonStr.contains("\"dateModified\":1729222222000"))
        assertTrue(jsonStr.contains("\"fileSize\":456789"))
        assertTrue(jsonStr.contains("\"trackNum\":4"))
    }

    @Test
    fun scanDirectorySkipsUnchangedFilesAndUpdatesModifiedFilesInPlace() {
        val tempDir = java.io.File(System.getProperty("java.io.tmpdir"), "classy_test_" + System.currentTimeMillis()).canonicalFile
        tempDir.mkdirs()
        try {
            val audioFile = java.io.File(tempDir, "track.mp3")
            // Create dummy file >= 1024 bytes
            val dummyBytes = ByteArray(2048) { 1 }
            audioFile.writeBytes(dummyBytes)
            audioFile.setLastModified(1000000000000L)

            val targetList = ArrayList<SongItem>()
            val existingMap = HashMap<String, SongItem>()

            // 1. Initial scan: file is discovered and added
            var scanned = mlm.scanDirectory(
                folder = tempDir,
                targetList = targetList,
                existingMap = existingMap,
                blacklist = emptySet(),
                startScanned = 0,
                total = 1,
                isAudiobook = false,
                listener = null
            )
            assertEquals(1, scanned)
            assertEquals(1, targetList.size)
            val initialItem = targetList[0]
            assertEquals(audioFile.lastModified(), initialItem.dateModified)
            assertEquals(audioFile.length(), initialItem.fileSize)
            assertEquals(1, existingMap.size)

            // 2. Second scan without file modification: should fast-forward and retain exact same instance
            scanned = mlm.scanDirectory(
                folder = tempDir,
                targetList = targetList,
                existingMap = existingMap,
                blacklist = emptySet(),
                startScanned = 0,
                total = 1,
                isAudiobook = false,
                listener = null
            )
            assertEquals(1, scanned)
            assertEquals(1, targetList.size)
            assertTrue(initialItem === targetList[0])

            // 3. Modification timestamp within 2-second tolerance: should fast-forward and retain instance
            audioFile.setLastModified(audioFile.lastModified() + 1500L)
            scanned = mlm.scanDirectory(
                folder = tempDir,
                targetList = targetList,
                existingMap = existingMap,
                blacklist = emptySet(),
                startScanned = 0,
                total = 1,
                isAudiobook = false,
                listener = null
            )
            assertEquals(1, scanned)
            assertEquals(1, targetList.size)
            assertTrue(initialItem === targetList[0])

            // 4. File modified on disk (new size and modified timestamp beyond 2 seconds)
            val newBytes = ByteArray(4096) { 2 }
            audioFile.writeBytes(newBytes)
            audioFile.setLastModified(2000000000000L)

            scanned = mlm.scanDirectory(
                folder = tempDir,
                targetList = targetList,
                existingMap = existingMap,
                blacklist = emptySet(),
                startScanned = 0,
                total = 1,
                isAudiobook = false,
                listener = null
            )
            assertEquals(1, scanned)
            assertEquals(1, targetList.size)
            // Updated in place:
            val updatedItem = targetList[0]
            assertTrue(initialItem === updatedItem)
            assertEquals(audioFile.lastModified(), updatedItem.dateModified)
            assertEquals(audioFile.length(), updatedItem.fileSize)
        } finally {
            tempDir.deleteRecursively()
        }
    }
}
