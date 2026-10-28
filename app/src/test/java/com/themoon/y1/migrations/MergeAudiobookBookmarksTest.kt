package com.themoon.y1.migrations

import com.themoon.y1.migrations.MoveMisplacedPrefsTest.FakePrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class MergeAudiobookBookmarksTest {

    private val book = "/storage/sdcard0/Audiobooks/Book/01.mp3"

    @Test
    fun copiesPositionsAndLastBookThatOnlyTheOldFileHas() {
        val legacy = FakePrefs(mutableMapOf("POS_$book" to 61000, "CHAP_$book" to 0, "LAST_AUDIOBOOK_PATH" to book))
        val app = FakePrefs()

        assertEquals(2, Migration005MergeAudiobookBookmarks.mergeBookmarks(legacy, app))
        assertEquals(61000, app.getInt("book_pos_$book", 0))
        assertEquals(book, app.getString("last_played_audiobook_path", null))
        assertFalse(app.contains("CHAP_$book"))
        assertEquals(emptyMap<String, Any?>(), legacy.all)
    }

    @Test
    fun appPrefsValuesWin() {
        val other = "/storage/sdcard0/Audiobooks/Other/01.mp3"
        val legacy = FakePrefs(mutableMapOf("POS_$book" to 1000, "LAST_AUDIOBOOK_PATH" to book))
        val app = FakePrefs(mutableMapOf("book_pos_$book" to 90000, "last_played_audiobook_path" to other))

        assertEquals(0, Migration005MergeAudiobookBookmarks.mergeBookmarks(legacy, app))
        assertEquals(90000, app.getInt("book_pos_$book", 0))
        assertEquals(other, app.getString("last_played_audiobook_path", null))
        assertEquals(emptyMap<String, Any?>(), legacy.all)
    }

    @Test
    fun podcastIsNotTheLastAudiobookAndZeroPositionsAreDropped() {
        val podcast = "/storage/sdcard0/Podcasts/Show/ep1.mp3"
        val legacy = FakePrefs(mutableMapOf("POS_$podcast" to 5000, "POS_$book" to 0, "LAST_AUDIOBOOK_PATH" to podcast))
        val app = FakePrefs()

        assertEquals(1, Migration005MergeAudiobookBookmarks.mergeBookmarks(legacy, app))
        assertEquals(mapOf("book_pos_$podcast" to 5000), app.all)
    }
}
