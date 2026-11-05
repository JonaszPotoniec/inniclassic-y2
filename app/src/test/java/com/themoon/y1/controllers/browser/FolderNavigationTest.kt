package com.themoon.y1.controllers.browser

import com.themoon.y1.StoragePaths
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
}
