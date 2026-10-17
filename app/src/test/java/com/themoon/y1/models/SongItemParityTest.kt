package com.themoon.y1.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SongItemParityTest {

    private val testFile = File("/storage/sdcard1/Music/test.mp3")

    // =========================================================================
    // 1. Constructors (4-arg, 6-arg, 7-arg, 8-arg)
    // =========================================================================

    @Test
    fun constructor_4args_initializesFieldsAndAppliesDefaults() {
        val song = SongItem(
            file = testFile,
            title = "Bohemian Rhapsody",
            artist = "Queen",
            album = "A Night at the Opera"
        )

        assertEquals(testFile, song.file)
        assertEquals("Bohemian Rhapsody", song.title)
        assertEquals("Queen", song.artist)
        assertEquals("A Night at the Opera", song.album)
        assertEquals("Unknown Year", song.year)
        assertEquals("Unknown Genre", song.genre)
        assertEquals("Queen", song.albumArtist)
        assertEquals("Unknown Composer", song.composer)
    }

    @Test
    fun constructor_6args_initializesFieldsAndAppliesDefaults() {
        val song = SongItem(
            file = testFile,
            title = "Time",
            artist = "Pink Floyd",
            album = "The Dark Side of the Moon",
            year = "1973",
            genre = "Progressive Rock"
        )

        assertEquals(testFile, song.file)
        assertEquals("Time", song.title)
        assertEquals("Pink Floyd", song.artist)
        assertEquals("The Dark Side of the Moon", song.album)
        assertEquals("1973", song.year)
        assertEquals("Progressive Rock", song.genre)
        assertEquals("Pink Floyd", song.albumArtist)
        assertEquals("Unknown Composer", song.composer)
    }

    @Test
    fun constructor_7args_initializesFieldsAndAlbumArtist() {
        val songWithExplicitAlbumArtist = SongItem(
            file = testFile,
            title = "Get Lucky",
            artist = "Daft Punk feat. Pharrell Williams",
            album = "Random Access Memories",
            year = "2013",
            genre = "Disco",
            albumArtist = "Daft Punk"
        )

        assertEquals(testFile, songWithExplicitAlbumArtist.file)
        assertEquals("Get Lucky", songWithExplicitAlbumArtist.title)
        assertEquals("Daft Punk feat. Pharrell Williams", songWithExplicitAlbumArtist.artist)
        assertEquals("Random Access Memories", songWithExplicitAlbumArtist.album)
        assertEquals("2013", songWithExplicitAlbumArtist.year)
        assertEquals("Disco", songWithExplicitAlbumArtist.genre)
        assertEquals("Daft Punk", songWithExplicitAlbumArtist.albumArtist)
        assertEquals("Unknown Composer", songWithExplicitAlbumArtist.composer)

        val songWithNullAlbumArtist = SongItem(
            file = testFile,
            title = "Get Lucky",
            artist = "Daft Punk",
            album = "Random Access Memories",
            year = "2013",
            genre = "Disco",
            albumArtist = null
        )
        // Null albumArtist must fall back to artist
        assertEquals("Daft Punk", songWithNullAlbumArtist.albumArtist)
    }

    @Test
    fun constructor_8args_initializesAllFieldsExplicitly() {
        val song = SongItem(
            file = testFile,
            title = "Symphony No. 5",
            artist = "Berlin Philharmonic",
            album = "Beethoven: Complete Symphonies",
            year = "1963",
            genre = "Classical",
            albumArtist = "Herbert von Karajan",
            composer = "Ludwig van Beethoven"
        )

        assertEquals(testFile, song.file)
        assertEquals("Symphony No. 5", song.title)
        assertEquals("Berlin Philharmonic", song.artist)
        assertEquals("Beethoven: Complete Symphonies", song.album)
        assertEquals("1963", song.year)
        assertEquals("Classical", song.genre)
        assertEquals("Herbert von Karajan", song.albumArtist)
        assertEquals("Ludwig van Beethoven", song.composer)
    }

    // =========================================================================
    // 2. Mutable property check (CRITICAL BUG PREVENTION)
    // =========================================================================

    @Test
    fun properties_areMutableVars_allowingDirectInPlaceUpdates() {
        val song = SongItem(
            file = testFile,
            title = "Initial Title",
            artist = "Initial Artist",
            album = "Initial Album"
        )

        val updatedFile = File("/storage/sdcard1/Music/updated.mp3")
        song.file = updatedFile
        song.title = "Updated Title"
        song.artist = "Updated Artist"
        song.album = "Updated Album"
        song.year = "2025"
        song.genre = "Rock"
        song.albumArtist = "Updated Album Artist"
        song.composer = "Updated Composer"

        assertEquals(updatedFile, song.file)
        assertEquals("Updated Title", song.title)
        assertEquals("Updated Artist", song.artist)
        assertEquals("Updated Album", song.album)
        assertEquals("2025", song.year)
        assertEquals("Rock", song.genre)
        assertEquals("Updated Album Artist", song.albumArtist)
        assertEquals("Updated Composer", song.composer)
    }

    @Test
    fun properties_simulateMetadataReconcilerInPlaceMutation() {
        // In Java original, MetadataReconciler mutated SongItem fields directly in-place:
        // matchedSong.title = remoteName;
        // matchedSong.artist = remoteArtist;
        // matchedSong.album = remoteAlbum;
        // etc.
        val original = SongItem(
            file = testFile,
            title = "Original Title",
            artist = "Original Artist",
            album = "Original Album"
        )

        // Keep a reference in a list to verify in-place mutation without reallocating
        val library = listOf(original)

        // MetadataReconciler in-place mutation
        val matchedSong = library[0]
        matchedSong.title = "Reconciled Title"
        matchedSong.artist = "Reconciled Artist"
        matchedSong.album = "Reconciled Album"
        matchedSong.year = "1999"
        matchedSong.genre = "Pop"
        matchedSong.albumArtist = "Reconciled Artist"
        matchedSong.composer = "Reconciled Composer"

        assertSame(original, library[0])
        assertEquals("Reconciled Title", library[0].title)
        assertEquals("Reconciled Artist", library[0].artist)
        assertEquals("Reconciled Album", library[0].album)
        assertEquals("1999", library[0].year)
        assertEquals("Pop", library[0].genre)
        assertEquals("Reconciled Artist", library[0].albumArtist)
        assertEquals("Reconciled Composer", library[0].composer)
    }

    @Test
    fun properties_reflectionCheck_allFieldsAreMutableProperties() {
        val propertyNames = listOf(
            "file",
            "title",
            "artist",
            "album",
            "year",
            "genre",
            "albumArtist",
            "composer"
        )

        val clazz = SongItem::class.java
        for (propName in propertyNames) {
            val capitalized = propName.replaceFirstChar { it.uppercase() }
            val getterName = "get$capitalized"
            val setterName = "set$capitalized"

            val hasGetter = clazz.methods.any { it.name == getterName }
            val hasSetter = clazz.methods.any { it.name == setterName }
            val isNonFinalField = runCatching {
                val field = clazz.getDeclaredField(propName)
                !java.lang.reflect.Modifier.isFinal(field.modifiers)
            }.getOrDefault(false)

            assertTrue(
                "Property $propName must have a setter ($setterName) or be a non-final field",
                hasSetter || isNonFinalField
            )
            assertTrue(
                "Property $propName must have a getter ($getterName) or exist as a field",
                hasGetter || isNonFinalField
            )
        }
    }

    // =========================================================================
    // 3. Helper method safe(value, fallback)
    // =========================================================================

    @Test
    fun safe_nullReturnsFallback() {
        assertEquals("Fallback", SongItem.safe(null, "Fallback"))
        assertEquals("Unknown Artist", SongItem.safe(null, "Unknown Artist"))
        assertEquals("", SongItem.safe(null, ""))
    }

    @Test
    fun safe_emptyStringReturnsFallback() {
        assertEquals("Fallback", SongItem.safe("", "Fallback"))
        assertEquals("Unknown Album", SongItem.safe("", "Unknown Album"))
    }

    @Test
    fun safe_blankWhitespaceReturnsFallback() {
        assertEquals("Fallback", SongItem.safe("   ", "Fallback"))
        assertEquals("Fallback", SongItem.safe("\t", "Fallback"))
        assertEquals("Fallback", SongItem.safe("\n\r \t", "Fallback"))
        assertEquals("Unknown Genre", SongItem.safe("      ", "Unknown Genre"))
    }

    @Test
    fun safe_validStringReturnsOriginalValue() {
        assertEquals("valid", SongItem.safe("valid", "Fallback"))
        assertEquals("Led Zeppelin", SongItem.safe("Led Zeppelin", "Unknown Artist"))
        assertEquals("1971", SongItem.safe("1971", "Unknown Year"))
    }

    @Test
    fun safe_valueWithSpacesPreservedWithoutTrimming() {
        // Matches Java original parity: (value != null && !value.trim().isEmpty()) ? value : fallback
        // The value returned is the original untrimmed string
        assertEquals("  valid with spaces  ", SongItem.safe("  valid with spaces  ", "Fallback"))
        assertEquals("   Rock   ", SongItem.safe("   Rock   ", "Unknown Genre"))
    }

    // =========================================================================
    // 4. Equality and HashCode (Parity with Java class - identity equality)
    // =========================================================================

    @Test
    fun equality_retainsReferenceIdentityEqualityParity() {
        val song1 = SongItem(
            file = testFile,
            title = "Song A",
            artist = "Artist A",
            album = "Album A",
            year = "2020",
            genre = "Genre A",
            albumArtist = "Artist A",
            composer = "Composer A"
        )
        val song2 = SongItem(
            file = testFile,
            title = "Song A",
            artist = "Artist A",
            album = "Album A",
            year = "2020",
            genre = "Genre A",
            albumArtist = "Artist A",
            composer = "Composer A"
        )

        // SongItem is NOT a data class. Parity with Java: identity equality.
        assertTrue(song1 == song1)
        assertTrue(song1.equals(song1))
        assertFalse(song1 == song2)
        assertFalse(song1.equals(song2))
        assertNotEquals(song1, song2)
    }

    @Test
    fun hashCode_retainsIdentityHashCodeBehavior() {
        val song1 = SongItem(testFile, "Song A", "Artist A", "Album A")
        val song2 = SongItem(testFile, "Song A", "Artist A", "Album A")

        assertEquals(song1.hashCode(), song1.hashCode())
        // Distinct objects in a set are treated as distinct items
        val set = hashSetOf(song1, song2)
        assertEquals(2, set.size)
        assertTrue(set.contains(song1))
        assertTrue(set.contains(song2))
    }

    // =========================================================================
    // 5. Default and fallback values for missing metadata
    // =========================================================================

    @Test
    fun defaults_missingArtist_usesUnknownArtist() {
        val songNull = SongItem(testFile, "Title", null, "Album")
        val songEmpty = SongItem(testFile, "Title", "", "Album")
        val songBlank = SongItem(testFile, "Title", "   ", "Album")

        assertEquals("Unknown Artist", songNull.artist)
        assertEquals("Unknown Artist", songEmpty.artist)
        assertEquals("Unknown Artist", songBlank.artist)

        // albumArtist also falls back to artist, which is "Unknown Artist"
        assertEquals("Unknown Artist", songNull.albumArtist)
        assertEquals("Unknown Artist", songEmpty.albumArtist)
        assertEquals("Unknown Artist", songBlank.albumArtist)
    }

    @Test
    fun defaults_missingAlbum_usesUnknownAlbum() {
        val songNull = SongItem(testFile, "Title", "Artist", null)
        val songEmpty = SongItem(testFile, "Title", "Artist", "")
        val songBlank = SongItem(testFile, "Title", "Artist", "   \t  ")

        assertEquals("Unknown Album", songNull.album)
        assertEquals("Unknown Album", songEmpty.album)
        assertEquals("Unknown Album", songBlank.album)
    }

    @Test
    fun defaults_missingYear_usesUnknownYear() {
        val song4Args = SongItem(testFile, "Title", "Artist", "Album")
        assertEquals("Unknown Year", song4Args.year)

        val song6ArgsNull = SongItem(testFile, "Title", "Artist", "Album", null, "Genre")
        val song6ArgsEmpty = SongItem(testFile, "Title", "Artist", "Album", "", "Genre")
        val song6ArgsBlank = SongItem(testFile, "Title", "Artist", "Album", "   ", "Genre")

        assertEquals("Unknown Year", song6ArgsNull.year)
        assertEquals("Unknown Year", song6ArgsEmpty.year)
        assertEquals("Unknown Year", song6ArgsBlank.year)

        val song8ArgsNull = SongItem(testFile, "Title", "Artist", "Album", null, "Genre", "AA", "Comp")
        val song8ArgsEmpty = SongItem(testFile, "Title", "Artist", "Album", "", "Genre", "AA", "Comp")
        val song8ArgsBlank = SongItem(testFile, "Title", "Artist", "Album", "   ", "Genre", "AA", "Comp")

        assertEquals("Unknown Year", song8ArgsNull.year)
        assertEquals("Unknown Year", song8ArgsEmpty.year)
        assertEquals("Unknown Year", song8ArgsBlank.year)
    }

    @Test
    fun defaults_missingGenre_usesUnknownGenre() {
        val song4Args = SongItem(testFile, "Title", "Artist", "Album")
        assertEquals("Unknown Genre", song4Args.genre)

        val song6ArgsNull = SongItem(testFile, "Title", "Artist", "Album", "2020", null)
        val song6ArgsEmpty = SongItem(testFile, "Title", "Artist", "Album", "2020", "")
        val song6ArgsBlank = SongItem(testFile, "Title", "Artist", "Album", "2020", "   ")

        assertEquals("Unknown Genre", song6ArgsNull.genre)
        assertEquals("Unknown Genre", song6ArgsEmpty.genre)
        assertEquals("Unknown Genre", song6ArgsBlank.genre)

        val song8ArgsNull = SongItem(testFile, "Title", "Artist", "Album", "2020", null, "AA", "Comp")
        val song8ArgsEmpty = SongItem(testFile, "Title", "Artist", "Album", "2020", "", "AA", "Comp")
        val song8ArgsBlank = SongItem(testFile, "Title", "Artist", "Album", "2020", "   ", "AA", "Comp")

        assertEquals("Unknown Genre", song8ArgsNull.genre)
        assertEquals("Unknown Genre", song8ArgsEmpty.genre)
        assertEquals("Unknown Genre", song8ArgsBlank.genre)
    }

    @Test
    fun defaults_missingAlbumArtist_fallsBackToArtist() {
        val song4Args = SongItem(testFile, "Title", "Solo Artist", "Album")
        assertEquals("Solo Artist", song4Args.albumArtist)

        val song6Args = SongItem(testFile, "Title", "Band Artist", "Album", "2020", "Rock")
        assertEquals("Band Artist", song6Args.albumArtist)

        val song7ArgsNull = SongItem(testFile, "Title", "Artist X", "Album", "2020", "Rock", null)
        val song7ArgsEmpty = SongItem(testFile, "Title", "Artist X", "Album", "2020", "Rock", "")
        val song7ArgsBlank = SongItem(testFile, "Title", "Artist X", "Album", "2020", "Rock", "   ")

        assertEquals("Artist X", song7ArgsNull.albumArtist)
        assertEquals("Artist X", song7ArgsEmpty.albumArtist)
        assertEquals("Artist X", song7ArgsBlank.albumArtist)

        val song8ArgsNull = SongItem(testFile, "Title", "Artist Y", "Album", "2020", "Rock", null, "Comp")
        val song8ArgsEmpty = SongItem(testFile, "Title", "Artist Y", "Album", "2020", "Rock", "", "Comp")
        val song8ArgsBlank = SongItem(testFile, "Title", "Artist Y", "Album", "2020", "Rock", "   ", "Comp")

        assertEquals("Artist Y", song8ArgsNull.albumArtist)
        assertEquals("Artist Y", song8ArgsEmpty.albumArtist)
        assertEquals("Artist Y", song8ArgsBlank.albumArtist)

        // If artist itself was missing, albumArtist should fallback to "Unknown Artist"
        val songBothMissing = SongItem(testFile, "Title", null, "Album", null, null, null, null)
        assertEquals("Unknown Artist", songBothMissing.albumArtist)
    }

    @Test
    fun defaults_missingComposer_usesUnknownComposer() {
        val song4Args = SongItem(testFile, "Title", "Artist", "Album")
        assertEquals("Unknown Composer", song4Args.composer)

        val song6Args = SongItem(testFile, "Title", "Artist", "Album", "2020", "Rock")
        assertEquals("Unknown Composer", song6Args.composer)

        val song7Args = SongItem(testFile, "Title", "Artist", "Album", "2020", "Rock", "Album Artist")
        assertEquals("Unknown Composer", song7Args.composer)

        val song8ArgsNull = SongItem(testFile, "Title", "Artist", "Album", "2020", "Rock", "AA", null)
        val song8ArgsEmpty = SongItem(testFile, "Title", "Artist", "Album", "2020", "Rock", "AA", "")
        val song8ArgsBlank = SongItem(testFile, "Title", "Artist", "Album", "2020", "Rock", "AA", "   ")

        assertEquals("Unknown Composer", song8ArgsNull.composer)
        assertEquals("Unknown Composer", song8ArgsEmpty.composer)
        assertEquals("Unknown Composer", song8ArgsBlank.composer)

        val song8ArgsValid = SongItem(testFile, "Title", "Artist", "Album", "2020", "Rock", "AA", "Hans Zimmer")
        assertEquals("Hans Zimmer", song8ArgsValid.composer)
    }
}
