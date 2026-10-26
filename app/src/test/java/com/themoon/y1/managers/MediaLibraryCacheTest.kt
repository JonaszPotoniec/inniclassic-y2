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
}
