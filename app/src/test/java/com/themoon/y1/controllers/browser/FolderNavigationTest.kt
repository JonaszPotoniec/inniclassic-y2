package com.themoon.y1.controllers.browser

import com.themoon.y1.StoragePaths
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class FolderNavigationTest {

    @Before
    fun setUp() {
        StoragePaths.invalidate()
    }

    @After
    fun tearDown() {
        StoragePaths.invalidate()
    }

    @Test
    fun testIsTopStorageRoot_storageDirectoryIsTop() {
        assertTrue("File('/storage') must be identified as top storage root",
            StoragePaths.isTopStorageRoot(File("/storage")))
        assertTrue("String '/storage' must be identified as top storage root",
            StoragePaths.isTopStorageRoot("/storage"))
        assertTrue("Null file must be treated as top storage root",
            StoragePaths.isTopStorageRoot(null as File?))
        assertTrue("Null path must be treated as top storage root",
            StoragePaths.isTopStorageRoot(null as String?))
    }

    @Test
    fun testIsTopStorageRoot_subdirectoriesAreNotTop() {
        val storageExists = File("/storage").exists()
        if (storageExists) {
            assertFalse(StoragePaths.isTopStorageRoot(File("/storage/sdcard0")))
            assertFalse(StoragePaths.isTopStorageRoot(File("/storage/sdcard1")))
            assertFalse(StoragePaths.isTopStorageRoot(File("/storage/sdcard0/Music")))
            assertFalse(StoragePaths.isTopStorageRoot(File("/storage/sdcard1/Music")))
        } else {
            // In host environments without /storage:
            assertFalse(StoragePaths.isTopStorageRoot(File(StoragePaths.getPrimaryRoot(), "Music")))
        }
    }

    @Test
    fun testFolderHierarchyTraversesUpToStorage() {
        val musicDir = File("/storage/sdcard0/Music")
        val sdcard0 = musicDir.parentFile
        assertNotNull(sdcard0)
        assertEquals("/storage/sdcard0", sdcard0!!.absolutePath)

        val storage = sdcard0.parentFile
        assertNotNull(storage)
        assertEquals("/storage", storage!!.absolutePath)
        assertTrue(StoragePaths.isTopStorageRoot(storage))

        val sdMusicDir = File("/storage/sdcard1/Music")
        val sdcard1 = sdMusicDir.parentFile
        assertNotNull(sdcard1)
        assertEquals("/storage/sdcard1", sdcard1!!.absolutePath)

        val storageFromSd = sdcard1.parentFile
        assertNotNull(storageFromSd)
        assertEquals("/storage", storageFromSd!!.absolutePath)
        assertTrue(StoragePaths.isTopStorageRoot(storageFromSd))
    }

    @Test
    fun testGetMusicDir_returnsPrimaryMusicFolder() {
        val musicDir = StoragePaths.getMusicDir()
        assertNotNull(musicDir)
        assertEquals("Music", musicDir.name)
        assertEquals(StoragePaths.getPrimaryRoot().absolutePath, musicDir.parentFile?.absolutePath)
    }

    @Test
    fun testGetDefaultMusicDir_returnsValidDirectory() {
        val defaultDir = StoragePaths.getDefaultMusicDir()
        assertNotNull(defaultDir)
        assertEquals("Music", defaultDir.name)
    }

    @Test
    fun testFolderHistory_initialState() {
        val start = File("/storage/sdcard1/Music")
        val history = FolderHistory(start)
        assertTrue(history.isEmpty)
        assertFalse(history.isNotEmpty)
        assertEquals(0, history.size)
        assertEquals(start, history.currentFolder)
        assertNull(history.back())
    }

    @Test
    fun testFolderHistory_enterSubfolder() {
        val root = File("/storage/sdcard1/Music")
        val sub = File("/storage/sdcard1/Music/Rock")
        val history = FolderHistory(root)

        history.enter(sub)
        assertFalse(history.isEmpty)
        assertEquals(1, history.size)
        assertEquals(sub, history.currentFolder)

        val backEntry = history.back()
        assertNotNull(backEntry)
        assertEquals(root, backEntry!!.folder)
        assertEquals("Rock", backEntry.focusText)
        assertEquals(root, history.currentFolder)
        assertTrue(history.isEmpty)
    }

    @Test
    fun testFolderHistory_upNavigation() {
        val current = File("/storage/sdcard1/Music")
        val parent = File("/storage/sdcard1")
        val history = FolderHistory(current)

        val exited = history.up(parent)
        assertEquals("Music", exited)
        assertEquals(parent, history.currentFolder)
        assertEquals(1, history.size)

        val backEntry = history.back()
        assertNotNull(backEntry)
        assertEquals(current, backEntry!!.folder)
        assertEquals("..", backEntry.focusText)
        assertEquals(current, history.currentFolder)
        assertTrue(history.isEmpty)
    }

    @Test
    fun testFolderHistory_upWithNullParentDoesNotChangeState() {
        val root = File("/")
        val history = FolderHistory(root)

        val exited = history.up(null)
        assertNull(exited)
        assertEquals(root, history.currentFolder)
        assertTrue(history.isEmpty)
    }

    @Test
    fun testFolderHistory_deepNavigationMixesEnterUpAndBack() {
        val music = File("/storage/sdcard1/Music")
        val queen = File("/storage/sdcard1/Music/Queen")
        val album = File("/storage/sdcard1/Music/Queen/Greatest Hits")
        val history = FolderHistory(music)

        history.enter(queen)
        assertEquals(queen, history.currentFolder)

        history.enter(album)
        assertEquals(album, history.currentFolder)

        // Navigate up using '..' from album to queen
        val exitedFromUp = history.up(queen)
        assertEquals("Greatest Hits", exitedFromUp)
        assertEquals(queen, history.currentFolder)

        // Back 1: returns to album, focusing '..'
        val b1 = history.back()
        assertNotNull(b1)
        assertEquals(album, b1!!.folder)
        assertEquals("..", b1.focusText)
        assertEquals(album, history.currentFolder)

        // Back 2: returns to queen, focusing 'Greatest Hits'
        val b2 = history.back()
        assertNotNull(b2)
        assertEquals(queen, b2!!.folder)
        assertEquals("Greatest Hits", b2.focusText)
        assertEquals(queen, history.currentFolder)

        // Back 3: returns to music, focusing 'Queen'
        val b3 = history.back()
        assertNotNull(b3)
        assertEquals(music, b3!!.folder)
        assertEquals("Queen", b3.focusText)
        assertEquals(music, history.currentFolder)

        // Back 4: history is exhausted, returns null to trigger exit
        assertNull(history.back())
        assertEquals(music, history.currentFolder)
    }

    @Test
    fun testFolderHistory_resetClearsHistoryAndUpdatesCurrentFolder() {
        val root = File("/storage/sdcard1/Music")
        val sub = File("/storage/sdcard1/Music/Classical")
        val newRoot = File("/storage/sdcard0/Music")
        val history = FolderHistory(root)

        history.enter(sub)
        assertEquals(1, history.size)

        history.reset(newRoot)
        assertTrue(history.isEmpty)
        assertEquals(0, history.size)
        assertEquals(newRoot, history.currentFolder)
        assertNull(history.back())
    }

    @Test
    fun testFolderHistory_clearPreservesCurrentFolderAndEmptiesStack() {
        val root = File("/storage/sdcard1/Music")
        val sub = File("/storage/sdcard1/Music/Classical")
        val history = FolderHistory(root)

        history.enter(sub)
        assertEquals(sub, history.currentFolder)
        assertEquals(1, history.size)

        history.clear()
        assertTrue(history.isEmpty)
        assertEquals(sub, history.currentFolder)
        assertNull(history.back())
    }
}
