package com.themoon.y1.sync;

import org.junit.Test;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.*;

public class SyncLedgerTest {

    @Test
    public void testLedgerItemJson() {
        SyncLedger.LedgerItem item = new SyncLedger.LedgerItem(
                "item-123",
                "lib-music",
                "music",
                "/storage/sdcard1/Music/song.mp3",
                1024500L,
                "2024-01-01T00:00:00Z",
                false
        );

        assertEquals("item-123", item.jellyfinId);
        assertEquals("lib-music", item.libraryId);
        assertEquals("music", item.collectionType);
        assertEquals("/storage/sdcard1/Music/song.mp3", item.localPath);
        assertEquals(1024500L, item.fileSize);
        assertEquals("2024-01-01T00:00:00Z", item.serverDateModified);
        assertFalse(item.orphaned);

        org.json.JSONObject json = item.toJson();
        SyncLedger.LedgerItem restored = SyncLedger.LedgerItem.fromJson("item-123", json);

        assertEquals(item.jellyfinId, restored.jellyfinId);
        assertEquals(item.libraryId, restored.libraryId);
        assertEquals(item.collectionType, restored.collectionType);
        assertEquals(item.localPath, restored.localPath);
        assertEquals(item.fileSize, restored.fileSize);
        assertEquals(item.serverDateModified, restored.serverDateModified);
        assertEquals(item.orphaned, restored.orphaned);
    }

    @Test
    public void testLibraryModesAndSelections() {
        SyncLedger ledger = new SyncLedger();
        assertEquals("all", ledger.getLibrarySyncMode("lib-music", "all"));

        ledger.setLibrarySyncMode("lib-music", "manual");
        assertEquals("manual", ledger.getLibrarySyncMode("lib-music", "all"));

        Set<String> sel = new HashSet<>(Arrays.asList("id1", "id2"));
        ledger.setLibrarySelections("lib-movies", sel);

        Set<String> retrieved = ledger.getLibrarySelections("lib-movies");
        assertEquals(2, retrieved.size());
        assertTrue(retrieved.contains("id1"));
        assertTrue(retrieved.contains("id2"));

        ledger.toggleLibrarySelection("lib-movies", "id1");
        assertFalse(ledger.getLibrarySelections("lib-movies").contains("id1"));

        ledger.toggleLibrarySelection("lib-movies", "id1");
        assertTrue(ledger.getLibrarySelections("lib-movies").contains("id1"));
    }
}
