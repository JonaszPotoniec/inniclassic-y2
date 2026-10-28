package com.themoon.y1.migrations

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class MoveMisplacedPrefsTest {

    @Test
    fun movesBackgroundPathToAppPrefs() {
        val legacy = FakePrefs(mutableMapOf("bg_path" to "/sdcard/bg.png", "shuffle" to true))
        val app = FakePrefs()

        val copied = Migration004MoveMisplacedPrefs.moveKeys(legacy, app) { it in Migration004MoveMisplacedPrefs.MOVED_KEYS }

        assertEquals(1, copied)
        assertEquals("/sdcard/bg.png", app.getString("bg_path", null))
        assertFalse(legacy.contains("bg_path"))
        assertEquals(true, legacy.getBoolean("shuffle", false))
        assertFalse(app.contains("shuffle"))
    }

    @Test
    fun keepsTheValueThatAppPrefsAlreadyHas() {
        val legacy = FakePrefs(mutableMapOf("bg_path" to "/sdcard/old.png"))
        val app = FakePrefs(mutableMapOf("bg_path" to "THEME_DEFAULT"))

        val copied = Migration004MoveMisplacedPrefs.moveKeys(legacy, app) { it == "bg_path" }

        assertEquals(0, copied)
        assertEquals("THEME_DEFAULT", app.getString("bg_path", null))
        assertNull(legacy.getString("bg_path", null))
    }

    @Test
    fun keepsTheValueTypes() {
        val legacy = FakePrefs(mutableMapOf("a" to 3, "b" to 4L, "c" to 1.5f, "d" to false, "e" to setOf("x")))
        val app = FakePrefs()

        assertEquals(5, Migration004MoveMisplacedPrefs.moveKeys(legacy, app) { true })
        assertEquals(mapOf("a" to 3, "b" to 4L, "c" to 1.5f, "d" to false, "e" to setOf("x")), app.all)
        assertEquals(emptyMap<String, Any?>(), legacy.all)
    }

    @Test
    fun noMatchingKeysChangesNothing() {
        val legacy = FakePrefs(mutableMapOf("shuffle" to true))
        val app = FakePrefs()

        assertEquals(0, Migration004MoveMisplacedPrefs.moveKeys(legacy, app) { it == "bg_path" })
        assertEquals(mapOf("shuffle" to true), legacy.all)
    }

    @Test
    fun migrationIsRegistered() {
        val manager = MigrationManager(null, FakePrefs())
        manager.registerMigration(Migration004MoveMisplacedPrefs())
        assertEquals(Migration004MoveMisplacedPrefs.ID, manager.getRegisteredMigrations().single().id)
    }

    internal class FakePrefs(private val map: MutableMap<String, Any?> = mutableMapOf()) : SharedPreferences {
        override fun getAll(): Map<String, *> = HashMap(map)
        override fun getString(key: String, defValue: String?) = map[key] as? String ?: defValue
        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String, defValues: Set<String>?) = map[key] as? Set<String> ?: defValues
        override fun getInt(key: String, defValue: Int) = map[key] as? Int ?: defValue
        override fun getLong(key: String, defValue: Long) = map[key] as? Long ?: defValue
        override fun getFloat(key: String, defValue: Float) = map[key] as? Float ?: defValue
        override fun getBoolean(key: String, defValue: Boolean) = map[key] as? Boolean ?: defValue
        override fun contains(key: String) = map.containsKey(key)
        override fun edit(): SharedPreferences.Editor = Editor(map)
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {}
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {}

        private class Editor(private val target: MutableMap<String, Any?>) : SharedPreferences.Editor {
            private val puts = mutableMapOf<String, Any?>()
            private val removes = mutableSetOf<String>()
            private var clear = false
            override fun putString(key: String, value: String?) = apply { puts[key] = value }
            override fun putStringSet(key: String, values: Set<String>?) = apply { puts[key] = values }
            override fun putInt(key: String, value: Int) = apply { puts[key] = value }
            override fun putLong(key: String, value: Long) = apply { puts[key] = value }
            override fun putFloat(key: String, value: Float) = apply { puts[key] = value }
            override fun putBoolean(key: String, value: Boolean) = apply { puts[key] = value }
            override fun remove(key: String) = apply { removes.add(key) }
            override fun clear() = apply { clear = true }
            override fun commit(): Boolean {
                if (clear) target.clear()
                removes.forEach { target.remove(it) }
                target.putAll(puts)
                return true
            }
            override fun apply() { commit() }
        }
    }
}
