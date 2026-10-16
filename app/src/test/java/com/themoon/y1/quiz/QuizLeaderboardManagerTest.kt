package com.themoon.y1.quiz

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class QuizLeaderboardManagerTest {

    private lateinit var mockPrefs: MockSharedPreferences
    private lateinit var manager: QuizLeaderboardManager

    @Before
    fun setup() {
        mockPrefs = MockSharedPreferences()
        manager = QuizLeaderboardManager(mockPrefs)
    }

    @Test
    fun testEmptyState() {
        assertTrue(manager.getTopEntries().isEmpty())
        assertEquals(0, manager.getHighScore())
    }

    @Test
    fun testRecordScore_highScoreAndSorting() {
        // Record first score
        var isHigh = manager.recordScore(score = 2500, round = 3, timestamp = 1000L)
        assertTrue(isHigh)
        assertEquals(2500, manager.getHighScore())

        // Record lower score
        isHigh = manager.recordScore(score = 1200, round = 2, timestamp = 2000L)
        assertFalse(isHigh)
        assertEquals(2500, manager.getHighScore())

        // Record higher score
        isHigh = manager.recordScore(score = 5000, round = 5, timestamp = 3000L)
        assertTrue(isHigh)
        assertEquals(5000, manager.getHighScore())

        val entries = manager.getTopEntries()
        assertEquals(3, entries.size)
        assertEquals(5000, entries[0].score)
        assertEquals(2500, entries[1].score)
        assertEquals(1200, entries[2].score)
    }

    @Test
    fun testMaxEntriesLimit_prunesToTen() {
        for (i in 1..15) {
            manager.recordScore(score = i * 100, round = 1, timestamp = i * 1000L)
        }

        val entries = manager.getTopEntries()
        assertEquals(QuizLeaderboardManager.MAX_ENTRIES, entries.size)
        // Top score should be 1500, lowest in top 10 should be 600
        assertEquals(1500, entries.first().score)
        assertEquals(600, entries.last().score)
    }

    @Test
    fun testClearLeaderboard() {
        manager.recordScore(score = 3000, round = 4)
        assertFalse(manager.getTopEntries().isEmpty())
        assertEquals(3000, manager.getHighScore())

        manager.clearLeaderboard()
        assertTrue(manager.getTopEntries().isEmpty())
        assertEquals(0, manager.getHighScore())
    }

    @Test
    fun testTieBreakByTimestamp() {
        manager.recordScore(score = 2000, round = 3, timestamp = 1000L)
        manager.recordScore(score = 2000, round = 3, timestamp = 5000L)

        val entries = manager.getTopEntries()
        assertEquals(2, entries.size)
        assertEquals(5000L, entries[0].timestamp)
        assertEquals(1000L, entries[1].timestamp)
    }

    private class MockSharedPreferences : SharedPreferences {
        private val map = mutableMapOf<String, Any?>()

        override fun getAll(): MutableMap<String, *> = HashMap(map)
        override fun getString(key: String?, defValue: String?): String? = (map[key] as? String) ?: defValue
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
            (map[key] as? MutableSet<String>) ?: defValues
        override fun getInt(key: String?, defValue: Int): Int = (map[key] as? Int) ?: defValue
        override fun getLong(key: String?, defValue: Long): Long = (map[key] as? Long) ?: defValue
        override fun getFloat(key: String?, defValue: Float): Float = (map[key] as? Float) ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = (map[key] as? Boolean) ?: defValue
        override fun contains(key: String?): Boolean = map.containsKey(key)
        override fun edit(): SharedPreferences.Editor = MockEditor(map)
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}

        private class MockEditor(private val backingMap: MutableMap<String, Any?>) : SharedPreferences.Editor {
            private val pending = mutableMapOf<String, Any?>()
            private val toRemove = mutableSetOf<String>()
            private var clearAll = false

            override fun putString(key: String?, value: String?): SharedPreferences.Editor {
                if (key != null) pending[key] = value
                return this
            }
            override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor {
                if (key != null) pending[key] = values
                return this
            }
            override fun putInt(key: String?, value: Int): SharedPreferences.Editor {
                if (key != null) pending[key] = value
                return this
            }
            override fun putLong(key: String?, value: Long): SharedPreferences.Editor {
                if (key != null) pending[key] = value
                return this
            }
            override fun putFloat(key: String?, value: Float): SharedPreferences.Editor {
                if (key != null) pending[key] = value
                return this
            }
            override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor {
                if (key != null) pending[key] = value
                return this
            }
            override fun remove(key: String?): SharedPreferences.Editor {
                if (key != null) toRemove.add(key)
                return this
            }
            override fun clear(): SharedPreferences.Editor {
                clearAll = true
                return this
            }
            override fun commit(): Boolean {
                apply()
                return true
            }
            override fun apply() {
                if (clearAll) backingMap.clear()
                toRemove.forEach { backingMap.remove(it) }
                backingMap.putAll(pending)
            }
        }
    }
}
