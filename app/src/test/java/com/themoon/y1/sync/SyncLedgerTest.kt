package com.themoon.y1.sync

import org.junit.Test
import org.junit.Assert.*

class SyncLedgerTest {

    @Test
    fun testLedgerItemJson() {
        val item = SyncLedger.LedgerItem(
            "item-123",
            "lib-music",
            "music",
            "/storage/sdcard1/Music/song.mp3",
            1024500L,
            "2024-01-01T00:00:00Z",
            false
        )

        assertEquals("item-123", item.jellyfinId)
        assertEquals("lib-music", item.libraryId)
        assertEquals("music", item.collectionType)
        assertEquals("/storage/sdcard1/Music/song.mp3", item.localPath)
        assertEquals(1024500L, item.fileSize)
        assertEquals("2024-01-01T00:00:00Z", item.serverDateModified)
        assertFalse(item.orphaned)

        val json = item.toJson()
        val restored = SyncLedger.LedgerItem.fromJson("item-123", json)

        assertEquals(item.jellyfinId, restored.jellyfinId)
        assertEquals(item.libraryId, restored.libraryId)
        assertEquals(item.collectionType, restored.collectionType)
        assertEquals(item.localPath, restored.localPath)
        assertEquals(item.fileSize, restored.fileSize)
        assertEquals(item.serverDateModified, restored.serverDateModified)
        assertEquals(item.orphaned, restored.orphaned)
    }

    @Test
    fun testLibraryModesAndSelections() {
        val ledger = SyncLedger()
        assertEquals("all", ledger.getLibrarySyncMode("lib-music", "all"))

        ledger.setLibrarySyncMode("lib-music", "manual")
        assertEquals("manual", ledger.getLibrarySyncMode("lib-music", "all"))

        val sel = setOf("id1", "id2")
        ledger.setLibrarySelections("lib-movies", sel)

        val retrieved = ledger.getLibrarySelections("lib-movies")
        assertEquals(2, retrieved.size)
        assertTrue(retrieved.contains("id1"))
        assertTrue(retrieved.contains("id2"))

        ledger.toggleLibrarySelection("lib-movies", "id1")
        assertFalse(ledger.getLibrarySelections("lib-movies").contains("id1"))

        ledger.toggleLibrarySelection("lib-movies", "id1")
        assertTrue(ledger.getLibrarySelections("lib-movies").contains("id1"))

        // Batch additions and removals
        ledger.addLibrarySelections("lib-movies", listOf("id3", "id4"))
        val afterAdd = ledger.getLibrarySelections("lib-movies")
        assertEquals(4, afterAdd.size)
        assertTrue(afterAdd.containsAll(listOf("id1", "id2", "id3", "id4")))

        ledger.removeLibrarySelections("lib-movies", listOf("id1", "id3"))
        val afterRemove = ledger.getLibrarySelections("lib-movies")
        assertEquals(2, afterRemove.size)
        assertTrue(afterRemove.contains("id2"))
        assertTrue(afterRemove.contains("id4"))
        assertFalse(afterRemove.contains("id1"))
        assertFalse(afterRemove.contains("id3"))
    }
}
