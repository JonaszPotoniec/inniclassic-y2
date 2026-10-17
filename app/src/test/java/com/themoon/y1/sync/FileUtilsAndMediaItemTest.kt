package com.themoon.y1.sync

import com.themoon.y1.sync.jellyfin.JellyfinMediaItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for FileUtils, JellyfinMediaItem, and SyncManager pure state
 * validating parity and edge cases against Java originals.
 */
class FileUtilsAndMediaItemTest {

    // ---------------------------------------------------------------------------------------------
    // 1. FileUtils.sanitizeFilename(name)
    // ---------------------------------------------------------------------------------------------

    @Test
    fun testSanitizeFilename_nullReturnsUnknown() {
        assertEquals("Unknown", FileUtils.sanitizeFilename(null))
    }

    @Test
    fun testSanitizeFilename_emptyOrWhitespaceReturnsUnknown() {
        assertEquals("Unknown", FileUtils.sanitizeFilename(""))
        assertEquals("Unknown", FileUtils.sanitizeFilename("   "))
        assertEquals("Unknown", FileUtils.sanitizeFilename("\t"))
        assertEquals("Unknown", FileUtils.sanitizeFilename("\n\r  \t"))
    }

    @Test
    fun testSanitizeFilename_illegalCharsReplacedByUnderscore() {
        // Individual illegal characters: [/ \ : * ? " < > |]
        assertEquals("A_B", FileUtils.sanitizeFilename("A/B"))
        assertEquals("A_B", FileUtils.sanitizeFilename("A\\B"))
        assertEquals("A_B", FileUtils.sanitizeFilename("A:B"))
        assertEquals("A_B", FileUtils.sanitizeFilename("A*B"))
        assertEquals("A_B", FileUtils.sanitizeFilename("A?B"))
        assertEquals("A_B", FileUtils.sanitizeFilename("A\"B"))
        assertEquals("A_B", FileUtils.sanitizeFilename("A<B"))
        assertEquals("A_B", FileUtils.sanitizeFilename("A>B"))
        assertEquals("A_B", FileUtils.sanitizeFilename("A|B"))

        // Composite sequence of all illegal characters
        assertEquals("A_B_C_D_E_F_G_H_I_J", FileUtils.sanitizeFilename("A/B\\C:D*E?F\"G<H>I|J"))

        // Real-world song / album naming examples
        assertEquals("AC_DC - Highway to Hell", FileUtils.sanitizeFilename("AC/DC - Highway to Hell"))
        assertEquals("Track_ Subtitle_ (10_10)", FileUtils.sanitizeFilename("Track: Subtitle? (10*10)"))
    }

    @Test
    fun testSanitizeFilename_leadingAndTrailingDotsUnderscoresAndWhitespaceStripped() {
        // Dots
        assertEquals("Comfortably Numb", FileUtils.sanitizeFilename(".Comfortably Numb."))
        assertEquals("Hello World", FileUtils.sanitizeFilename("  ...Hello World...  "))

        // Underscores
        assertEquals("Comfortably Numb", FileUtils.sanitizeFilename("_Comfortably Numb_"))
        assertEquals("Track", FileUtils.sanitizeFilename("___Track___"))

        // Mixed dots, underscores, and whitespace
        assertEquals("Track", FileUtils.sanitizeFilename(" . _ . Track . _ . "))
        assertEquals("Song", FileUtils.sanitizeFilename("  ...___Song___...  "))
        assertEquals("song", FileUtils.sanitizeFilename("._.. song _.."))

        // Internal dots and underscores should be preserved
        assertEquals("Track_01.mp3", FileUtils.sanitizeFilename("Track_01.mp3"))
        assertEquals("Pink Floyd - The Wall", FileUtils.sanitizeFilename("Pink Floyd - The Wall"))
    }

    @Test
    fun testSanitizeFilename_unicodeAndNonBreakingSpaceHandling() {
        // Japanese, Cyrillic, Accents, and special Unicode characters
        assertEquals("日本語 - トラック", FileUtils.sanitizeFilename("日本語 - トラック"))
        assertEquals("日本語_トラック", FileUtils.sanitizeFilename("日本語/トラック"))
        assertEquals("Café _ Müller", FileUtils.sanitizeFilename("Café / Müller"))
        assertEquals("Sigur Rós - Ágætis byrjun", FileUtils.sanitizeFilename("Sigur Rós - Ágætis byrjun"))
        assertEquals("Чайковский - Лебединое озеро", FileUtils.sanitizeFilename("Чайковский - Лебединое озеро"))

        // Non-breaking space (U+00A0) inside text is preserved
        assertEquals("Song\u00A0Title", FileUtils.sanitizeFilename("Song\u00A0Title"))

        // Non-breaking space at boundaries is stripped by trim
        assertEquals("Song", FileUtils.sanitizeFilename("\u00A0Song\u00A0"))
        assertEquals("Song", FileUtils.sanitizeFilename("  \u00A0Song\u00A0  "))
    }

    @Test
    fun testSanitizeFilename_sanitizedResultBecomesEmptyReturnsUnknown() {
        // Only illegal characters become underscores, then get stripped
        assertEquals("Unknown", FileUtils.sanitizeFilename("/\\:*?\"<>|"))
        assertEquals("Unknown", FileUtils.sanitizeFilename("///:::***???"))

        // Only dots, underscores, or spaces
        assertEquals("Unknown", FileUtils.sanitizeFilename("..."))
        assertEquals("Unknown", FileUtils.sanitizeFilename("___"))
        assertEquals("Unknown", FileUtils.sanitizeFilename(". . ."))
        assertEquals("Unknown", FileUtils.sanitizeFilename("_ _ _"))
        assertEquals("Unknown", FileUtils.sanitizeFilename(" . _ . "))
        assertEquals("Unknown", FileUtils.sanitizeFilename("  ...___...  "))

        // Combinations of illegal characters and whitespace
        assertEquals("Unknown", FileUtils.sanitizeFilename("  / : * ?  "))
        assertEquals("Unknown", FileUtils.sanitizeFilename("..//.."))

        // Only non-breaking spaces
        assertEquals("Unknown", FileUtils.sanitizeFilename("\u00A0"))
        assertEquals("Unknown", FileUtils.sanitizeFilename("\u00A0\u00A0\u00A0"))
        assertEquals("Unknown", FileUtils.sanitizeFilename("  \u00A0  \u00A0  "))
    }

    // ---------------------------------------------------------------------------------------------
    // 2. JellyfinMediaItem
    // ---------------------------------------------------------------------------------------------

    @Test
    fun testJellyfinMediaItem_defaultConstructorAndParameters() {
        val item = JellyfinMediaItem()

        assertEquals("", item.id)
        assertEquals("", item.name)
        assertEquals("", item.artist)
        assertNull(item.album)
        assertEquals("", item.albumArtist)
        assertEquals("", item.composer)
        assertNull(item.year)
        assertNull(item.genre)
        assertEquals(0, item.trackNumber)
        assertEquals(0, item.discNumber)
        assertEquals("", item.container)
        assertEquals(0L, item.runTimeTicks)
        assertEquals(0L, item.size)

        // Aliases / auxiliary properties
        assertEquals(0, item.indexNumber)
        assertEquals(0, item.parentIndexNumber)
        assertEquals(0L, item.fileSize)
        assertNull(item.albumId)
        assertNull(item.albumPrimaryImageTag)
        assertEquals("", item.collectionType)
        assertEquals("", item.libraryId)
        assertNull(item.seriesName)
        assertEquals("", item.seriesId)
        assertEquals("", item.seasonId)
        assertNull(item.seasonName)
        assertEquals("", item.serverDateModified)
    }

    @Test
    fun testJellyfinMediaItem_parameterizedConstructor() {
        val item = JellyfinMediaItem(
            id = "jf-item-001",
            name = "Comfortably Numb",
            artist = "Pink Floyd",
            album = "The Wall",
            albumArtist = "Pink Floyd",
            composer = "Roger Waters",
            year = "1979",
            genre = "Progressive Rock",
            trackNumber = 6,
            discNumber = 2,
            container = "flac",
            runTimeTicks = 3820000000L,
            size = 45182910L
        )

        assertEquals("jf-item-001", item.id)
        assertEquals("Comfortably Numb", item.name)
        assertEquals("Pink Floyd", item.artist)
        assertEquals("The Wall", item.album)
        assertEquals("Pink Floyd", item.albumArtist)
        assertEquals("Roger Waters", item.composer)
        assertEquals("1979", item.year)
        assertEquals("Progressive Rock", item.genre)
        assertEquals(6, item.trackNumber)
        assertEquals(2, item.discNumber)
        assertEquals("flac", item.container)
        assertEquals(3820000000L, item.runTimeTicks)
        assertEquals(45182910L, item.size)

        // Verifying aliases mirror the values
        assertEquals(6, item.indexNumber)
        assertEquals(2, item.parentIndexNumber)
        assertEquals(45182910L, item.fileSize)
    }

    @Test
    fun testJellyfinMediaItem_nullableFieldsDoNotCauseNpe() {
        val item = JellyfinMediaItem(year = null, genre = null)

        assertNull(item.year)
        assertNull(item.genre)

        // Mutation to null and safe fallback operations
        item.year = null
        item.genre = null
        assertNull(item.year)
        assertNull(item.genre)

        val displayYear = item.year ?: "Unknown Year"
        val displayGenre = item.genre ?: "Unknown Genre"
        assertEquals("Unknown Year", displayYear)
        assertEquals("Unknown Genre", displayGenre)

        // Mutation to non-null and back to null
        item.year = "2024"
        item.genre = "Rock"
        assertEquals("2024", item.year)
        assertEquals("Rock", item.genre)

        item.year = null
        item.genre = null
        assertNull(item.year)
        assertNull(item.genre)
    }

    @Test
    fun testJellyfinMediaItem_allFieldsAndAliasesBidirectional() {
        val item = JellyfinMediaItem()

        // Test trackNumber <-> indexNumber
        item.trackNumber = 5
        assertEquals(5, item.trackNumber)
        assertEquals(5, item.indexNumber)

        item.indexNumber = 10
        assertEquals(10, item.trackNumber)
        assertEquals(10, item.indexNumber)

        // Test discNumber <-> parentIndexNumber
        item.discNumber = 2
        assertEquals(2, item.discNumber)
        assertEquals(2, item.parentIndexNumber)

        item.parentIndexNumber = 4
        assertEquals(4, item.discNumber)
        assertEquals(4, item.parentIndexNumber)

        // Test size <-> fileSize
        item.size = 1024L * 1024L
        assertEquals(1048576L, item.size)
        assertEquals(1048576L, item.fileSize)

        item.fileSize = 2048L * 1024L
        assertEquals(2097152L, item.size)
        assertEquals(2097152L, item.fileSize)

        // Test runTimeTicks
        item.runTimeTicks = 5555555L
        assertEquals(5555555L, item.runTimeTicks)

        // Test container
        item.container = "mp3"
        assertEquals("mp3", item.container)
    }

    // ---------------------------------------------------------------------------------------------
    // 3. SyncManager pure state
    // ---------------------------------------------------------------------------------------------

    @Test
    fun testSyncManager_defaultState() {
        val syncManager = SyncManager()

        // Check default state (isSyncing == false, currentState == Idle)
        assertFalse(syncManager.isSyncing)
        assertEquals(SyncManager.SyncState.IDLE, syncManager.currentState)
    }

    @Test
    fun testSyncManager_cancelSyncSetsIsSyncingFalse() {
        val syncManager = SyncManager()

        // Check when isSyncing was initially true
        syncManager.isSyncing = true
        assertTrue(syncManager.isSyncing)

        syncManager.cancelSync()

        assertFalse(syncManager.isSyncing)
        assertEquals(SyncManager.SyncState.CANCELLED, syncManager.currentState)

        // Check when cancelSync is called while already false
        syncManager.cancelSync()
        assertFalse(syncManager.isSyncing)
        assertEquals(SyncManager.SyncState.CANCELLED, syncManager.currentState)
    }

    @Test
    fun testSyncManager_callbacksAndListenersCanBeSetToNullSafely() {
        val syncManager = SyncManager()

        // Explicitly set all callbacks to null
        syncManager.syncListener = null
        syncManager.batchUpdateCallback = null
        syncManager.playbackCheckCallback = null

        assertNull(syncManager.syncListener)
        assertNull(syncManager.batchUpdateCallback)
        assertNull(syncManager.playbackCheckCallback)

        // Operations like cancelSync must not throw NPE when listeners are null
        syncManager.cancelSync()
        assertFalse(syncManager.isSyncing)
        assertEquals(SyncManager.SyncState.CANCELLED, syncManager.currentState)

        // Set non-null listener, verify invocation, then reset back to null safely
        var reportedState: SyncManager.SyncState? = null
        var reportedMessage: String? = null
        syncManager.syncListener = object : SyncManager.SyncListener {
            override fun onStateChanged(state: SyncManager.SyncState, message: String?) {
                reportedState = state
                reportedMessage = message
            }

            override fun onProgress(
                libraryName: String,
                currentItem: Int,
                totalItems: Int,
                currentTitle: String,
                bytesRead: Long,
                bytesTotal: Long
            ) {}
        }

        syncManager.cancelSync()
        assertEquals(SyncManager.SyncState.CANCELLED, reportedState)
        assertEquals("Cancelled by user", reportedMessage)

        // Reset to null safely again
        syncManager.syncListener = null
        syncManager.cancelSync()
        assertFalse(syncManager.isSyncing)
    }
}
