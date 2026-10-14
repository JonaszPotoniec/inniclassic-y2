package com.themoon.y1.sync

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.FileWriter

class SyncLedgerParityTest {

    // -------------------------------------------------------------------------
    // 1. getLibrarySyncMode and setLibrarySyncMode
    // -------------------------------------------------------------------------

    @Test
    fun getLibrarySyncMode_returnsDefaultModeWhenUnset() {
        val ledger = SyncLedger()
        assertEquals("all", ledger.getLibrarySyncMode("lib-unknown", "all"))
        assertEquals("manual", ledger.getLibrarySyncMode("lib-unknown", "manual"))
        assertEquals("off", ledger.getLibrarySyncMode("lib-unknown", "off"))
        assertEquals("custom_default", ledger.getLibrarySyncMode("lib-unknown", "custom_default"))
    }

    @Test
    fun setLibrarySyncMode_storesAndRetrievesCorrectMode() {
        val ledger = SyncLedger()
        ledger.setLibrarySyncMode("lib-music", "manual")
        assertEquals("manual", ledger.getLibrarySyncMode("lib-music", "all"))

        ledger.setLibrarySyncMode("lib-movies", "off")
        assertEquals("off", ledger.getLibrarySyncMode("lib-movies", "all"))

        ledger.setLibrarySyncMode("lib-shows", "all")
        assertEquals("all", ledger.getLibrarySyncMode("lib-shows", "manual"))
    }

    @Test
    fun setLibrarySyncMode_overwritesExistingMode() {
        val ledger = SyncLedger()
        ledger.setLibrarySyncMode("lib-music", "all")
        assertEquals("all", ledger.getLibrarySyncMode("lib-music", "off"))

        ledger.setLibrarySyncMode("lib-music", "manual")
        assertEquals("manual", ledger.getLibrarySyncMode("lib-music", "off"))

        ledger.setLibrarySyncMode("lib-music", "off")
        assertEquals("off", ledger.getLibrarySyncMode("lib-music", "all"))
    }

    @Test
    fun librarySyncModes_multipleLibrariesAreIndependent() {
        val ledger = SyncLedger()
        ledger.setLibrarySyncMode("lib-1", "all")
        ledger.setLibrarySyncMode("lib-2", "manual")
        ledger.setLibrarySyncMode("lib-3", "off")

        assertEquals("all", ledger.getLibrarySyncMode("lib-1", "fallback"))
        assertEquals("manual", ledger.getLibrarySyncMode("lib-2", "fallback"))
        assertEquals("off", ledger.getLibrarySyncMode("lib-3", "fallback"))
        assertEquals("fallback", ledger.getLibrarySyncMode("lib-4", "fallback"))
    }

    @Test
    fun getLibrarySyncMode_emptyStringFallsBackToDefault() {
        val ledger = SyncLedger()
        ledger.setLibrarySyncMode("lib-empty", "")
        assertEquals("defaultMode", ledger.getLibrarySyncMode("lib-empty", "defaultMode"))
    }

    // -------------------------------------------------------------------------
    // 2. getLibrarySelections defensive copies and non-null semantics
    // -------------------------------------------------------------------------

    @Test
    fun getLibrarySelections_returnsEmptySetWhenNoSelectionsExist_neverNull() {
        val ledger = SyncLedger()
        val selections = ledger.getLibrarySelections("nonexistent-lib")
        assertNotNull("Selections should never be null", selections)
        assertTrue("Selections should be empty for non-existent library", selections.isEmpty())
        assertEquals(0, selections.size)
    }

    @Test
    fun getLibrarySelections_returnsDefensiveCopy_modifyingReturnedSetDoesNotMutateLedger() {
        val ledger = SyncLedger()
        ledger.setLibrarySelections("lib-music", setOf("id1", "id2"))

        val retrieved = ledger.getLibrarySelections("lib-music")
        assertEquals(2, retrieved.size)

        // Mutate the returned set
        val mutableCopy = retrieved as? MutableSet<String> ?: HashSet(retrieved)
        mutableCopy.add("id3")
        mutableCopy.remove("id1")

        // Internal ledger selections must remain pristine
        val freshRetrieved = ledger.getLibrarySelections("lib-music")
        assertEquals(2, freshRetrieved.size)
        assertTrue(freshRetrieved.contains("id1"))
        assertTrue(freshRetrieved.contains("id2"))
        assertFalse(freshRetrieved.contains("id3"))
    }

    @Test
    fun setLibrarySelections_takesDefensiveCopy_modifyingInputSetDoesNotMutateLedger() {
        val ledger = SyncLedger()
        val mutableInput = mutableSetOf("idA", "idB")
        ledger.setLibrarySelections("lib-music", mutableInput)

        // Mutate input set afterwards
        mutableInput.add("idC")
        mutableInput.clear()

        val retrieved = ledger.getLibrarySelections("lib-music")
        assertEquals(2, retrieved.size)
        assertTrue(retrieved.contains("idA"))
        assertTrue(retrieved.contains("idB"))
        assertFalse(retrieved.contains("idC"))
    }

    // -------------------------------------------------------------------------
    // 3. toggleLibrarySelection
    // -------------------------------------------------------------------------

    @Test
    fun toggleLibrarySelection_togglesOnWhenAbsent_inUninitializedLibrary() {
        val ledger = SyncLedger()
        ledger.toggleLibrarySelection("lib-music", "item-1")

        val selections = ledger.getLibrarySelections("lib-music")
        assertEquals(1, selections.size)
        assertTrue(selections.contains("item-1"))
    }

    @Test
    fun toggleLibrarySelection_togglesOnWhenAbsent_inExistingSelections() {
        val ledger = SyncLedger()
        ledger.setLibrarySelections("lib-music", setOf("item-1"))
        ledger.toggleLibrarySelection("lib-music", "item-2")

        val selections = ledger.getLibrarySelections("lib-music")
        assertEquals(2, selections.size)
        assertTrue(selections.contains("item-1"))
        assertTrue(selections.contains("item-2"))
    }

    @Test
    fun toggleLibrarySelection_togglesOffWhenPresent() {
        val ledger = SyncLedger()
        ledger.setLibrarySelections("lib-music", setOf("item-1", "item-2"))
        ledger.toggleLibrarySelection("lib-music", "item-1")

        val selections = ledger.getLibrarySelections("lib-music")
        assertEquals(1, selections.size)
        assertFalse(selections.contains("item-1"))
        assertTrue(selections.contains("item-2"))
    }

    @Test
    fun toggleLibrarySelection_alternatesOnAndOffRoundTrip() {
        val ledger = SyncLedger()
        // 1. Off -> On
        ledger.toggleLibrarySelection("lib-1", "toggle-item")
        assertTrue(ledger.getLibrarySelections("lib-1").contains("toggle-item"))

        // 2. On -> Off
        ledger.toggleLibrarySelection("lib-1", "toggle-item")
        assertFalse(ledger.getLibrarySelections("lib-1").contains("toggle-item"))

        // 3. Off -> On
        ledger.toggleLibrarySelection("lib-1", "toggle-item")
        assertTrue(ledger.getLibrarySelections("lib-1").contains("toggle-item"))

        // 4. On -> Off
        ledger.toggleLibrarySelection("lib-1", "toggle-item")
        assertFalse(ledger.getLibrarySelections("lib-1").contains("toggle-item"))
        assertTrue(ledger.getLibrarySelections("lib-1").isEmpty())
    }

    @Test
    fun toggleLibrarySelection_independentAcrossLibraries() {
        val ledger = SyncLedger()
        ledger.toggleLibrarySelection("lib-A", "shared-id")

        assertTrue(ledger.getLibrarySelections("lib-A").contains("shared-id"))
        assertFalse(ledger.getLibrarySelections("lib-B").contains("shared-id"))

        ledger.toggleLibrarySelection("lib-B", "shared-id")
        assertTrue(ledger.getLibrarySelections("lib-A").contains("shared-id"))
        assertTrue(ledger.getLibrarySelections("lib-B").contains("shared-id"))

        ledger.toggleLibrarySelection("lib-A", "shared-id")
        assertFalse(ledger.getLibrarySelections("lib-A").contains("shared-id"))
        assertTrue(ledger.getLibrarySelections("lib-B").contains("shared-id"))
    }

    // -------------------------------------------------------------------------
    // 4. addLibrarySelections
    // -------------------------------------------------------------------------

    @Test
    fun addLibrarySelections_nullCollectionSafelyIgnoredWithoutNpe() {
        val ledger = SyncLedger()
        // Should not throw NPE when library does not exist yet
        ledger.addLibrarySelections("lib-1", null)
        assertTrue(ledger.getLibrarySelections("lib-1").isEmpty())

        // Should not throw NPE when library already has selections
        ledger.setLibrarySelections("lib-2", setOf("existing-id"))
        ledger.addLibrarySelections("lib-2", null)
        val selections = ledger.getLibrarySelections("lib-2")
        assertEquals(1, selections.size)
        assertTrue(selections.contains("existing-id"))
    }

    @Test
    fun addLibrarySelections_emptyCollectionSafelyIgnored() {
        val ledger = SyncLedger()
        ledger.addLibrarySelections("lib-1", emptyList())
        assertTrue(ledger.getLibrarySelections("lib-1").isEmpty())

        ledger.setLibrarySelections("lib-2", setOf("existing-id"))
        ledger.addLibrarySelections("lib-2", emptySet())
        val selections = ledger.getLibrarySelections("lib-2")
        assertEquals(1, selections.size)
        assertTrue(selections.contains("existing-id"))
    }

    @Test
    fun addLibrarySelections_addsToNewAndExistingSelections_andDeduplicates() {
        val ledger = SyncLedger()
        // Add to new library
        ledger.addLibrarySelections("lib-movies", listOf("m1", "m2"))
        var selections = ledger.getLibrarySelections("lib-movies")
        assertEquals(2, selections.size)
        assertTrue(selections.containsAll(listOf("m1", "m2")))

        // Add to existing library with overlapping items
        ledger.addLibrarySelections("lib-movies", listOf("m2", "m3", "m4"))
        selections = ledger.getLibrarySelections("lib-movies")
        assertEquals(4, selections.size)
        assertTrue(selections.containsAll(listOf("m1", "m2", "m3", "m4")))
    }

    // -------------------------------------------------------------------------
    // 5. removeLibrarySelections
    // -------------------------------------------------------------------------

    @Test
    fun removeLibrarySelections_nullCollectionSafelyIgnored() {
        val ledger = SyncLedger()
        // Absent library
        ledger.removeLibrarySelections("lib-absent", null)
        assertTrue(ledger.getLibrarySelections("lib-absent").isEmpty())

        // Existing library
        ledger.setLibrarySelections("lib-existing", setOf("id1", "id2"))
        ledger.removeLibrarySelections("lib-existing", null)
        val selections = ledger.getLibrarySelections("lib-existing")
        assertEquals(2, selections.size)
        assertTrue(selections.containsAll(listOf("id1", "id2")))
    }

    @Test
    fun removeLibrarySelections_emptyCollectionSafelyIgnored() {
        val ledger = SyncLedger()
        ledger.removeLibrarySelections("lib-absent", emptyList())
        assertTrue(ledger.getLibrarySelections("lib-absent").isEmpty())

        ledger.setLibrarySelections("lib-existing", setOf("id1", "id2"))
        ledger.removeLibrarySelections("lib-existing", emptyList())
        val selections = ledger.getLibrarySelections("lib-existing")
        assertEquals(2, selections.size)
        assertTrue(selections.containsAll(listOf("id1", "id2")))
    }

    @Test
    fun removeLibrarySelections_absentLibrarySafelyIgnored() {
        val ledger = SyncLedger()
        // Removing from a library that was never registered should safely no-op
        ledger.removeLibrarySelections("never-seen-lib", listOf("id1", "id2"))
        assertTrue(ledger.getLibrarySelections("never-seen-lib").isEmpty())
    }

    @Test
    fun removeLibrarySelections_removesOnlyTargetItems() {
        val ledger = SyncLedger()
        ledger.setLibrarySelections("lib-shows", setOf("s1", "s2", "s3", "s4"))

        // Remove subset with non-existent item mixed in
        ledger.removeLibrarySelections("lib-shows", listOf("s1", "s3", "s999"))

        val selections = ledger.getLibrarySelections("lib-shows")
        assertEquals(2, selections.size)
        assertTrue(selections.contains("s2"))
        assertTrue(selections.contains("s4"))
        assertFalse(selections.contains("s1"))
        assertFalse(selections.contains("s3"))
    }

    @Test
    fun removeLibrarySelections_removingAllResultsInEmptySet() {
        val ledger = SyncLedger()
        ledger.setLibrarySelections("lib-shows", setOf("s1", "s2"))
        ledger.removeLibrarySelections("lib-shows", listOf("s1", "s2"))

        val selections = ledger.getLibrarySelections("lib-shows")
        assertNotNull(selections)
        assertTrue(selections.isEmpty())
    }

    // -------------------------------------------------------------------------
    // 6. Item operations: putItem, getItem, removeItem, getAllItems, getItemsByLibrary
    // -------------------------------------------------------------------------

    @Test
    fun getItem_returnsNullWhenItemDoesNotExist() {
        val ledger = SyncLedger()
        assertNull(ledger.getItem("non-existent-id"))
    }

    @Test
    fun putItem_and_getItem_storesAndRetrievesItem() {
        val ledger = SyncLedger()
        val item = SyncLedger.LedgerItem(
            "jf-1",
            "lib-music",
            "music",
            "/storage/sdcard0/Music/track1.flac",
            25000000L,
            "2024-05-01T12:00:00Z",
            false
        )

        ledger.putItem(item)

        val retrieved = ledger.getItem("jf-1")
        assertNotNull(retrieved)
        assertEquals("jf-1", retrieved?.jellyfinId)
        assertEquals("lib-music", retrieved?.libraryId)
        assertEquals("music", retrieved?.collectionType)
        assertEquals("/storage/sdcard0/Music/track1.flac", retrieved?.localPath)
        assertEquals(25000000L, retrieved?.fileSize)
        assertEquals("2024-05-01T12:00:00Z", retrieved?.serverDateModified)
        assertFalse(retrieved?.orphaned == true)
    }

    @Test
    fun putItem_overwritesExistingItemWithSameJellyfinId() {
        val ledger = SyncLedger()
        val item1 = SyncLedger.LedgerItem(
            "jf-1",
            "lib-music",
            "music",
            "/path/old.mp3",
            1000L,
            "old-date",
            false
        )
        val item2 = SyncLedger.LedgerItem(
            "jf-1",
            "lib-music",
            "music",
            "/path/new.mp3",
            2000L,
            "new-date",
            true
        )

        ledger.putItem(item1)
        assertEquals("/path/old.mp3", ledger.getItem("jf-1")?.localPath)

        ledger.putItem(item2)
        assertEquals("/path/new.mp3", ledger.getItem("jf-1")?.localPath)
        assertEquals(2000L, ledger.getItem("jf-1")?.fileSize)
        assertTrue(ledger.getItem("jf-1")?.orphaned == true)
    }

    @Test
    fun removeItem_deletesItemAndSafeOnNonExistent() {
        val ledger = SyncLedger()
        val item = SyncLedger.LedgerItem("jf-1", "lib-1", "type", "/p", 100L, "d", false)
        ledger.putItem(item)
        assertNotNull(ledger.getItem("jf-1"))

        ledger.removeItem("jf-1")
        assertNull(ledger.getItem("jf-1"))

        // Removing non-existent item should safely no-op
        ledger.removeItem("non-existent-id")
    }

    @Test
    fun getAllItems_returnsAllItemsAndIsDefensiveCopy() {
        val ledger = SyncLedger()
        val item1 = SyncLedger.LedgerItem("jf-1", "lib-1", "music", "/p1", 100L, "d1", false)
        val item2 = SyncLedger.LedgerItem("jf-2", "lib-2", "video", "/p2", 200L, "d2", true)

        ledger.putItem(item1)
        ledger.putItem(item2)

        val all = ledger.getAllItems()
        assertEquals(2, all.size)
        assertTrue(all.containsKey("jf-1"))
        assertTrue(all.containsKey("jf-2"))

        // Mutating returned map must not affect ledger
        val mutableAll = all as? MutableMap<String, SyncLedger.LedgerItem> ?: HashMap(all)
        mutableAll.clear()

        assertEquals(2, ledger.getAllItems().size)
        assertNotNull(ledger.getItem("jf-1"))
        assertNotNull(ledger.getItem("jf-2"))
    }

    @Test
    fun getItemsByLibrary_filtersCorrectly() {
        val ledger = SyncLedger()
        val item1 = SyncLedger.LedgerItem("jf-1", "lib-music", "music", "/p1", 100L, "d1", false)
        val item2 = SyncLedger.LedgerItem("jf-2", "lib-music", "music", "/p2", 200L, "d2", false)
        val item3 = SyncLedger.LedgerItem("jf-3", "lib-movies", "movies", "/p3", 300L, "d3", false)
        val item4 = SyncLedger.LedgerItem("jf-4", null, null, null, 0L, null, true)

        ledger.putItem(item1)
        ledger.putItem(item2)
        ledger.putItem(item3)
        ledger.putItem(item4)

        val musicItems = ledger.getItemsByLibrary("lib-music")
        assertEquals(2, musicItems.size)
        val musicIds = musicItems.map { it.jellyfinId }.toSet()
        assertTrue(musicIds.contains("jf-1"))
        assertTrue(musicIds.contains("jf-2"))

        val movieItems = ledger.getItemsByLibrary("lib-movies")
        assertEquals(1, movieItems.size)
        assertEquals("jf-3", movieItems[0].jellyfinId)

        val emptyItems = ledger.getItemsByLibrary("lib-nonexistent")
        assertNotNull(emptyItems)
        assertTrue(emptyItems.isEmpty())
    }

    @Test
    fun ledgerItem_toJsonAndFromJson_handlesNullFieldsGracefully() {
        val itemWithNulls = SyncLedger.LedgerItem(
            "jf-nulls",
            null,
            null,
            null,
            0L,
            null,
            false
        )

        val json = itemWithNulls.toJson()
        assertEquals("", json.optString("libraryId"))
        assertEquals("", json.optString("collectionType"))
        assertEquals("", json.optString("localPath"))
        assertEquals(0L, json.optLong("fileSize"))
        assertEquals("", json.optString("serverDateModified"))
        assertFalse(json.optBoolean("orphaned"))

        val restored = SyncLedger.LedgerItem.fromJson("jf-nulls", json)
        assertEquals("jf-nulls", restored.jellyfinId)
        assertEquals("", restored.libraryId)
        assertEquals("", restored.collectionType)
        assertEquals("", restored.localPath)
        assertEquals(0L, restored.fileSize)
        assertEquals("", restored.serverDateModified)
        assertFalse(restored.orphaned)
    }

    // -------------------------------------------------------------------------
    // 7. Serialization / Persistence: save and load round-trip
    // -------------------------------------------------------------------------

    @Test
    fun serialization_roundTripUsingTempFile_verifiesJsonStructureAndRestoration() {
        val tempFile = File.createTempFile("sync_ledger_test", ".json")
        tempFile.deleteOnExit()

        try {
            val originalLedger = SyncLedger(tempFile)
            val syncTimestamp = 1728864000000L
            originalLedger.setLastSyncTime(syncTimestamp)

            // Configure library sync modes
            originalLedger.setLibrarySyncMode("lib-music", "all")
            originalLedger.setLibrarySyncMode("lib-shows", "manual")
            originalLedger.setLibrarySyncMode("lib-movies", "off")

            // Configure library selections
            originalLedger.setLibrarySelections("lib-shows", setOf("show-1", "show-2"))
            originalLedger.setLibrarySelections("lib-movies", setOf("movie-101"))

            // Configure items
            val item1 = SyncLedger.LedgerItem(
                "item-1",
                "lib-music",
                "music",
                "/storage/sdcard1/Music/song1.mp3",
                4500000L,
                "2024-04-10T10:00:00Z",
                false
            )
            val item2 = SyncLedger.LedgerItem(
                "item-2",
                "lib-shows",
                "tvshows",
                "/storage/sdcard1/Videos/ep1.mp4",
                980000000L,
                "2024-04-11T11:00:00Z",
                true
            )
            originalLedger.putItem(item1)
            originalLedger.putItem(item2)

            // 1. Save to temp file
            originalLedger.save()
            assertTrue("Saved file must exist on disk", tempFile.exists())
            assertTrue("Saved file must have content", tempFile.length() > 0)

            // 2. Direct inspection of on-disk JSON structure
            val fileContent = tempFile.readText()
            val rootJson = JSONObject(fileContent)

            // Verify version and lastSyncTime
            assertEquals(2, rootJson.getInt("version"))
            assertEquals(syncTimestamp, rootJson.getLong("lastSyncTime"))

            // Verify librarySyncModes in JSON
            val modesJson = rootJson.getJSONObject("librarySyncModes")
            assertEquals("all", modesJson.getString("lib-music"))
            assertEquals("manual", modesJson.getString("lib-shows"))
            assertEquals("off", modesJson.getString("lib-movies"))

            // Verify librarySelections in JSON
            val selectionsJson = rootJson.getJSONObject("librarySelections")
            val showsArray = selectionsJson.getJSONArray("lib-shows")
            assertEquals(2, showsArray.length())
            val restoredShowIds = mutableSetOf<String>()
            for (i in 0 until showsArray.length()) {
                restoredShowIds.add(showsArray.getString(i))
            }
            assertEquals(setOf("show-1", "show-2"), restoredShowIds)

            val moviesArray = selectionsJson.getJSONArray("lib-movies")
            assertEquals(1, moviesArray.length())
            assertEquals("movie-101", moviesArray.getString(0))

            // Verify items in JSON
            val itemsJson = rootJson.getJSONObject("items")
            assertTrue(itemsJson.has("item-1"))
            assertTrue(itemsJson.has("item-2"))

            val item1Json = itemsJson.getJSONObject("item-1")
            assertEquals("lib-music", item1Json.getString("libraryId"))
            assertEquals("music", item1Json.getString("collectionType"))
            assertEquals("/storage/sdcard1/Music/song1.mp3", item1Json.getString("localPath"))
            assertEquals(4500000L, item1Json.getLong("fileSize"))
            assertEquals("2024-04-10T10:00:00Z", item1Json.getString("serverDateModified"))
            assertFalse(item1Json.getBoolean("orphaned"))

            val item2Json = itemsJson.getJSONObject("item-2")
            assertEquals("lib-shows", item2Json.getString("libraryId"))
            assertEquals(980000000L, item2Json.getLong("fileSize"))
            assertTrue(item2Json.getBoolean("orphaned"))

            // 3. Load into a completely new SyncLedger instance
            val loadedLedger = SyncLedger(tempFile)
            loadedLedger.load()

            assertEquals(syncTimestamp, loadedLedger.getLastSyncTime())
            assertEquals("all", loadedLedger.getLibrarySyncMode("lib-music", "default"))
            assertEquals("manual", loadedLedger.getLibrarySyncMode("lib-shows", "default"))
            assertEquals("off", loadedLedger.getLibrarySyncMode("lib-movies", "default"))

            assertEquals(setOf("show-1", "show-2"), loadedLedger.getLibrarySelections("lib-shows"))
            assertEquals(setOf("movie-101"), loadedLedger.getLibrarySelections("lib-movies"))

            val loadedAllItems = loadedLedger.getAllItems()
            assertEquals(2, loadedAllItems.size)

            val loadedItem1 = loadedLedger.getItem("item-1")
            assertNotNull(loadedItem1)
            assertEquals("item-1", loadedItem1?.jellyfinId)
            assertEquals("lib-music", loadedItem1?.libraryId)
            assertEquals("music", loadedItem1?.collectionType)
            assertEquals("/storage/sdcard1/Music/song1.mp3", loadedItem1?.localPath)
            assertEquals(4500000L, loadedItem1?.fileSize)
            assertEquals("2024-04-10T10:00:00Z", loadedItem1?.serverDateModified)
            assertFalse(loadedItem1?.orphaned == true)

            val loadedItem2 = loadedLedger.getItem("item-2")
            assertNotNull(loadedItem2)
            assertEquals("item-2", loadedItem2?.jellyfinId)
            assertEquals("lib-shows", loadedItem2?.libraryId)
            assertEquals("tvshows", loadedItem2?.collectionType)
            assertEquals("/storage/sdcard1/Videos/ep1.mp4", loadedItem2?.localPath)
            assertEquals(980000000L, loadedItem2?.fileSize)
            assertEquals("2024-04-11T11:00:00Z", loadedItem2?.serverDateModified)
            assertTrue(loadedItem2?.orphaned == true)
        } finally {
            if (tempFile.exists()) {
                tempFile.delete()
            }
        }
    }

    @Test
    fun serialization_emptyLedgerRoundTrip() {
        val tempFile = File.createTempFile("empty_ledger_test", ".json")
        tempFile.deleteOnExit()

        try {
            val emptyLedger = SyncLedger()
            emptyLedger.save(tempFile)

            assertTrue(tempFile.exists())
            val rootJson = JSONObject(tempFile.readText())
            assertEquals(2, rootJson.getInt("version"))
            assertEquals(0L, rootJson.getLong("lastSyncTime"))
            assertEquals(0, rootJson.getJSONObject("librarySyncModes").length())
            assertEquals(0, rootJson.getJSONObject("librarySelections").length())
            assertEquals(0, rootJson.getJSONObject("items").length())

            val restoredLedger = SyncLedger()
            restoredLedger.load(tempFile)

            assertEquals(0L, restoredLedger.getLastSyncTime())
            assertEquals(0, restoredLedger.getAllItems().size)
            assertEquals("default", restoredLedger.getLibrarySyncMode("any", "default"))
            assertTrue(restoredLedger.getLibrarySelections("any").isEmpty())
        } finally {
            if (tempFile.exists()) {
                tempFile.delete()
            }
        }
    }

    @Test
    fun serialization_loadNonExistentFileIsSafeNoOp() {
        val nonExistent = File("completely_fake_non_existent_file_12345.json")
        assertFalse(nonExistent.exists())

        val ledger = SyncLedger()
        ledger.setLastSyncTime(9999L)
        ledger.setLibrarySyncMode("lib-music", "all")

        // Loading non-existent file should be a safe no-op
        ledger.load(nonExistent)

        // Prior state should remain intact
        assertEquals(9999L, ledger.getLastSyncTime())
        assertEquals("all", ledger.getLibrarySyncMode("lib-music", "fallback"))
    }

    @Test
    fun serialization_loadCorruptedJsonDoesNotThrow() {
        val corruptFile = File.createTempFile("corrupt_ledger", ".json")
        corruptFile.deleteOnExit()

        try {
            FileWriter(corruptFile).use { it.write("{ this is not valid json : [[") }

            val ledger = SyncLedger()
            ledger.setLastSyncTime(5000L)

            // Should safely catch exception and not crash
            ledger.load(corruptFile)

            assertEquals(5000L, ledger.getLastSyncTime())
        } finally {
            if (corruptFile.exists()) {
                corruptFile.delete()
            }
        }
    }

    @Test
    fun serialization_atomicWriteCleansUpTempFile() {
        val tempFile = File.createTempFile("atomic_ledger_test", ".json")
        tempFile.deleteOnExit()

        try {
            val ledger = SyncLedger()
            ledger.setLastSyncTime(1234L)
            ledger.save(tempFile)

            assertTrue(tempFile.exists())
            val intermediateTempFile = File(tempFile.parentFile ?: File("."), "${tempFile.name}.tmp")
            assertFalse("Intermediate .tmp file should be renamed/cleaned up after save", intermediateTempFile.exists())
        } finally {
            if (tempFile.exists()) {
                tempFile.delete()
            }
        }
    }

    @Test
    fun lastSyncTime_getterAndSetter() {
        val ledger = SyncLedger()
        assertEquals(0L, ledger.getLastSyncTime())

        ledger.setLastSyncTime(1700000000L)
        assertEquals(1700000000L, ledger.getLastSyncTime())
    }
}
