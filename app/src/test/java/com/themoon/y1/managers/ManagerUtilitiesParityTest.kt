package com.themoon.y1.managers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest
import java.util.HashMap
import java.util.TreeMap

/**
 * Unit and parity tests for pure utility methods across InniClassic managers:
 * 1. [LanguageManager.t]: Reflection-injected dictionary, null/known/unknown keys, whitespace padding.
 * 2. [LastFmScrobbler]: Pure static/companion methods (tsv, md5, sign).
 * 3. [AlbumCoverManager]: Companion methods (getAlbumKey, getHashKey).
 * 4. [AudiobookManager]: Companion/public pure method (isAudioFile).
 */
class ManagerUtilitiesParityTest {

    @Rule
    @JvmField
    val tempFolder = TemporaryFolder()

    // -------------------------------------------------------------------------
    // Helper: LanguageManager instance with reflection-injected dictionary
    // -------------------------------------------------------------------------
    private fun createLanguageManager(): Pair<LanguageManager, HashMap<String, String>> {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val unsafeField = unsafeClass.getDeclaredField("theUnsafe")
        unsafeField.isAccessible = true
        val unsafe = unsafeField.get(null)

        val allocateMethod = unsafeClass.getMethod("allocateInstance", Class::class.java)
        val manager = allocateMethod.invoke(unsafe, LanguageManager::class.java) as LanguageManager

        val dictField = LanguageManager::class.java.getDeclaredField("dictionary")
        dictField.isAccessible = true
        val dict = HashMap<String, String>()
        dictField.set(manager, dict)

        return Pair(manager, dict)
    }

    // =========================================================================
    // 1. LanguageManager.t(originalText: String?): String
    // =========================================================================

    @Test
    fun testLanguageManager_nullReturnsEmptyString() {
        val (manager, dict) = createLanguageManager()
        dict["Music"] = "Muzyka"

        // null -> ""
        assertEquals("", manager.t(null))
    }

    @Test
    fun testLanguageManager_knownKeyReturnsTranslatedValue() {
        val (manager, dict) = createLanguageManager()
        dict["Music"] = "Muzyka"
        dict["Playlists"] = "Listy odtwarzania"
        dict["Settings"] = "Ustawienia"
        dict["Now Playing"] = "Teraz odtwarzane"

        assertEquals("Muzyka", manager.t("Music"))
        assertEquals("Listy odtwarzania", manager.t("Playlists"))
        assertEquals("Ustawienia", manager.t("Settings"))
        assertEquals("Teraz odtwarzane", manager.t("Now Playing"))
    }

    @Test
    fun testLanguageManager_unknownKeyReturnsOriginalTextUnchanged() {
        val (manager, dict) = createLanguageManager()
        dict["Music"] = "Muzyka"

        assertEquals("Unknown Key", manager.t("Unknown Key"))
        assertEquals("Podcasts", manager.t("Podcasts"))
        assertEquals("Albums", manager.t("Albums"))
        assertEquals("", manager.t(""))
    }

    @Test
    fun testLanguageManager_paddedKeyPreservesOuterPadding() {
        val (manager, dict) = createLanguageManager()
        dict["Padded Key"] = "Translated"
        dict["Volume"] = "Głośność"

        // Both leading and trailing spaces
        assertEquals("  Translated  ", manager.t("  Padded Key  "))

        // Single leading space
        assertEquals(" Translated", manager.t(" Padded Key"))

        // Multiple trailing spaces
        assertEquals("Translated    ", manager.t("Padded Key    "))

        // Leading and trailing tabs / newlines
        assertEquals("\tTranslated\n", manager.t("\tPadded Key\n"))
        assertEquals("   Głośność   ", manager.t("   Volume   "))
    }

    @Test
    fun testLanguageManager_emptyDictionaryReturnsOriginalText() {
        val (manager, dict) = createLanguageManager()
        assertTrue("Dictionary must initially be empty", dict.isEmpty())

        assertEquals("Music", manager.t("Music"))
        assertEquals("Settings", manager.t("Settings"))
        assertEquals("  Padded Key  ", manager.t("  Padded Key  "))
        assertEquals("", manager.t(""))
        assertEquals("", manager.t(null))
    }

    // =========================================================================
    // 2. LastFmScrobbler: tsv, md5, sign
    // =========================================================================

    @Test
    fun testLastFmScrobbler_tsvListFormatting() {
        // Standard list
        val input = listOf("The Beatles", "Abbey Road", "Come Together", "259")
        assertEquals("The Beatles\tAbbey Road\tCome Together\t259", LastFmScrobbler.tsv(input))

        // Empty list
        assertEquals("", LastFmScrobbler.tsv(emptyList<String>()))

        // Single item
        assertEquals("Solo Track", LastFmScrobbler.tsv(listOf("Solo Track")))

        // Vararg overload
        assertEquals("A\tB\tC", LastFmScrobbler.tsv("A", "B", "C"))
    }

    @Test
    fun testLastFmScrobbler_tsvSanitizesTabsAndNewlinesAndNulls() {
        // Tabs in values replaced with spaces to protect TSV format
        val withTabs = listOf("Artist\tWith\tTabs", "Album")
        assertEquals("Artist With Tabs\tAlbum", LastFmScrobbler.tsv(withTabs))

        // Newlines in values replaced with spaces
        val withNewlines = listOf("Line1\nLine2", "Normal Album")
        assertEquals("Line1 Line2\tNormal Album", LastFmScrobbler.tsv(withNewlines))

        // Null fields become empty strings
        val withNulls = listOf("Artist", null, "Title", null)
        assertEquals("Artist\t\tTitle\t", LastFmScrobbler.tsv(withNulls))
    }

    @Test
    fun testLastFmScrobbler_md5Digest() {
        // Standard MD5 test vectors (RFC 1321)
        assertEquals("d41d8cd98f00b204e9800998ecf8427e", LastFmScrobbler.md5(""))
        assertEquals("9e107d9d372bb6826bd81d3542a419d6", LastFmScrobbler.md5("The quick brown fox jumps over the lazy dog"))
        assertEquals("5eb63bbbe01eeed093cb22bb8f5acdc3", LastFmScrobbler.md5("hello world"))

        // UTF-8 multi-byte characters
        val utf8Input = "Zażółć gęślą jaźń"
        val expectedMd5 = MessageDigest.getInstance("MD5")
            .digest(utf8Input.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        assertEquals(expectedMd5, LastFmScrobbler.md5(utf8Input))
    }

    @Test
    fun testLastFmScrobbler_signLastFmAlgorithm() {
        // Last.fm signature algorithm:
        // 1. Sort parameters alphabetically by key
        // 2. Concatenate key + value for all parameters
        // 3. Append secret
        // 4. Calculate MD5 hex digest
        val params = linkedMapOf(
            "method" to "auth.getMobileSession",
            "username" to "myUser",
            "api_key" to "myApiKey123",
            "password" to "mySecretPassword"
        )
        val secret = "mySharedSecret456"

        // Sorted alphabetical order: api_key, method, password, username
        val expectedConcat = "api_key" + "myApiKey123" +
                "method" + "auth.getMobileSession" +
                "password" + "mySecretPassword" +
                "username" + "myUser" +
                secret

        val expectedSig = LastFmScrobbler.md5(expectedConcat)
        val actualSig = LastFmScrobbler.sign(params, secret)

        assertEquals(expectedSig, actualSig)
    }

    @Test
    fun testLastFmScrobbler_signEmptyParamsOnlyAppendsSecret() {
        val secret = "superSecretToken"
        val actualSig = LastFmScrobbler.sign(emptyMap(), secret)
        assertEquals(LastFmScrobbler.md5(secret), actualSig)
    }

    // =========================================================================
    // 3. AlbumCoverManager: getAlbumKey, getHashKey
    // =========================================================================

    @Test
    fun testAlbumCoverManager_getAlbumKeyWithParentFolder() {
        val trackFile = File("/storage/sdcard0/Music/Queen/A Night at the Opera/01 - Death on Two Legs.mp3")
        val key = AlbumCoverManager.getAlbumKey(trackFile, "A Night at the Opera")
        assertEquals("/storage/sdcard0/Music/Queen/A Night at the Opera - A Night at the Opera", key)
    }

    @Test
    fun testAlbumCoverManager_getAlbumKeyWithNullOrMissingParent() {
        // File without parent
        val fileNoParent = File("track.mp3")
        val keyNoParent = AlbumCoverManager.getAlbumKey(fileNoParent, "My Album")
        assertEquals(" - My Album", keyNoParent)

        // Null file
        val keyNullFile = AlbumCoverManager.getAlbumKey(null, "My Album")
        assertEquals(" - My Album", keyNullFile)

        // Null album falls back to "Unknown Album"
        val trackFile = File("/storage/sdcard0/Music/Artist/01.mp3")
        val keyNullAlbum = AlbumCoverManager.getAlbumKey(trackFile, null)
        assertEquals("/storage/sdcard0/Music/Artist - Unknown Album", keyNullAlbum)

        // Both null
        val keyBothNull = AlbumCoverManager.getAlbumKey(null, null)
        assertEquals(" - Unknown Album", keyBothNull)
    }

    @Test
    fun testAlbumCoverManager_getHashKeyProducesValidMd5() {
        val emptyHash = AlbumCoverManager.getHashKey("")
        assertEquals("d41d8cd98f00b204e9800998ecf8427e", emptyHash)

        val nullHash = AlbumCoverManager.getHashKey(null)
        assertEquals("d41d8cd98f00b204e9800998ecf8427e", nullHash)

        val albumKey = "/storage/sdcard0/Music/Artist/Album - Album"
        val expected = MessageDigest.getInstance("MD5")
            .digest(albumKey.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        assertEquals(expected, AlbumCoverManager.getHashKey(albumKey))
        assertEquals(32, AlbumCoverManager.getHashKey(albumKey).length)
    }

    // =========================================================================
    // 4. AudiobookManager: isAudioFile
    // =========================================================================

    @Test
    fun testAudiobookManager_isAudioFileNullCheck() {
        assertFalse("null file must return false", AudiobookManager.isAudioFile(null))
    }

    @Test
    fun testAudiobookManager_isAudioFileSupportedFormats() {
        val supportedExtensions = listOf(
            "mp3", "m4a", "m4b", "aac", "flac", "ogg", "wav", "opus", "ape", "wma", "alac"
        )

        for (ext in supportedExtensions) {
            val file = File("test_audiobook.$ext")
            assertTrue("Expected .$ext to be recognized as audio file", AudiobookManager.isAudioFile(file))
        }
    }

    @Test
    fun testAudiobookManager_isAudioFileCaseInsensitive() {
        val testFiles = listOf(
            File("Chapter1.MP3"),
            File("Part2.M4B"),
            File("Book.M4A"),
            File("Track.AAC"),
            File("Audio.FLAC"),
            File("Story.OGG"),
            File("Voice.WAV"),
            File("Narrative.OPUS"),
            File("Record.APE"),
            File("Media.WMA"),
            File("Sound.ALAC")
        )

        for (file in testFiles) {
            assertTrue("Expected ${file.name} to match case-insensitively", AudiobookManager.isAudioFile(file))
        }
    }

    @Test
    fun testAudiobookManager_isAudioFileRejectsNonAudioAndDirectories() {
        val rejectedFiles = listOf(
            File("cover.jpg"),
            File("cover.jpeg"),
            File("cover.png"),
            File("summary.txt"),
            File("manual.pdf"),
            File("chapters.cue"),
            File("video.mp4"),
            File("archive.zip"),
            File("no_extension")
        )

        for (file in rejectedFiles) {
            assertFalse("Expected ${file.name} to be rejected", AudiobookManager.isAudioFile(file))
        }

        // Existing directory on disk must be rejected even if named with an audio extension
        val dirWithAudioName = tempFolder.newFolder("BookFolder.mp3")
        assertFalse("Directory named BookFolder.mp3 must return false", AudiobookManager.isAudioFile(dirWithAudioName))
    }
}
