package com.themoon.y1.managers

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SettingsBackupTest {

    private fun sourcePrefs(): Map<String, SharedPreferences> {
        val app = MapPrefs()
        app.map["is_24h_format"] = true
        app.map["volume_limit_max"] = 12
        app.map["video_pos_/storage/sdcard1/Movies/a.mp4"] = 123456789012L
        app.map["radio_level"] = 0.75f
        app.map["app_theme_name"] = "Classic Dark"
        app.map["custom_menu_order_Classic Dark"] = "btn_music,btn_now"
        app.map["hide_btn_btn_videos"] = true
        app.map["rating_/storage/sdcard1/Music/Session Token/song.mp3"] = 5
        app.map["favorite_songs_set"] = setOf("/storage/sdcard1/Music/b.mp3", "/storage/sdcard1/Music/a.mp3")
        app.map["lastfm_username"] = "me"
        app.map["lastfm_password"] = "secret"
        app.map["last_played_song_path"] = "/x.mp3"
        app.map["has_saved_playback_state"] = true
        app.map["last_lang_version"] = 1011
        val music = MapPrefs()
        music.map["hide_music_btn_genres"] = true
        val jellyfin = MapPrefs()
        jellyfin.map["download_covers"] = false
        jellyfin.map["access_token"] = "tok"
        jellyfin.map["server_url"] = "http://server"
        jellyfin.map["password"] = "pw"
        return linkedMapOf("AppPrefs" to app, "y1_settings" to music, "Y1_JELLYFIN" to jellyfin)
    }

    @Test
    fun roundTripKeepsValuesAndTypes() {
        val json = SettingsBackup.toJson(sourcePrefs(), "1.0", 1000L).toString(2)
        val backup = SettingsBackup.parse(json)
        assertEquals(1000L, backup.createdMs)

        val target = MapPrefs()
        target.map["lastfm_password"] = "keep-me"
        target.map["old_setting"] = "remove-me"
        target.map["last_played_song_path"] = "/keep.mp3"
        val music = MapPrefs()
        val jellyfin = MapPrefs()
        jellyfin.map["access_token"] = "my-token"
        SettingsBackup.apply(backup, mapOf("AppPrefs" to target, "y1_settings" to music, "Y1_JELLYFIN" to jellyfin))

        val m = target.map
        assertEquals(true, m["is_24h_format"])
        assertEquals(12, m["volume_limit_max"])
        assertEquals(123456789012L, m["video_pos_/storage/sdcard1/Movies/a.mp4"])
        assertEquals(0.75f, m["radio_level"])
        assertEquals("Classic Dark", m["app_theme_name"])
        assertEquals("btn_music,btn_now", m["custom_menu_order_Classic Dark"])
        assertEquals(true, m["hide_btn_btn_videos"])
        assertEquals(5, m["rating_/storage/sdcard1/Music/Session Token/song.mp3"])
        assertEquals(setOf("/storage/sdcard1/Music/a.mp3", "/storage/sdcard1/Music/b.mp3"), m["favorite_songs_set"])
        // Secrets and device state stay as they are on the device.
        assertEquals("keep-me", m["lastfm_password"])
        assertEquals("/keep.mp3", m["last_played_song_path"])
        assertFalse(m.containsKey("lastfm_username"))
        assertFalse(m.containsKey("last_lang_version"))
        assertFalse(m.containsKey("has_saved_playback_state"))
        // Keys that the backup does not have are removed.
        assertFalse(m.containsKey("old_setting"))

        assertEquals(true, music.map["hide_music_btn_genres"])
        assertEquals(false, jellyfin.map["download_covers"])
        assertEquals("my-token", jellyfin.map["access_token"])
        assertNull(jellyfin.map["server_url"])
    }

    @Test
    fun exportHasNoSecrets() {
        val json = SettingsBackup.toJson(sourcePrefs()).toString()
        assertFalse(json.contains("secret"))
        assertFalse(json.contains("\"tok\""))
        assertFalse(json.contains("http://server"))
        assertFalse(json.contains("lastfm_username"))
        assertTrue(json.contains("\"format\":\"inniclassic-settings\""))
        assertTrue(json.contains("\"version\":1"))
    }

    @Test
    fun rejectsFilesThatAreNotBackups() {
        val bad = listOf(
            "not json",
            "{}",
            "{\"format\":\"other\",\"version\":1,\"prefs\":{}}",
            "{\"format\":\"inniclassic-settings\",\"version\":99,\"prefs\":{}}",
            "{\"format\":\"inniclassic-settings\",\"version\":1}",
            "{\"format\":\"inniclassic-settings\",\"version\":1,\"prefs\":{\"AppPrefs\":{\"a\":{\"type\":\"int\",\"value\":\"x\"}}}}",
            "{\"format\":\"inniclassic-settings\",\"version\":1,\"prefs\":{\"AppPrefs\":{\"a\":{\"type\":\"blob\",\"value\":1}}}}",
            "{\"format\":\"inniclassic-settings\",\"version\":1,\"prefs\":{\"AppPrefs\":{\"a\":{\"type\":\"boolean\",\"value\":\"yes\"}}}}"
        )
        for (text in bad) {
            try {
                SettingsBackup.parse(text)
                fail("Accepted: $text")
            } catch (_: SettingsBackup.InvalidBackupException) {
            }
        }
    }

    @Test
    fun ignoresUnknownFilesAndExcludedKeysOnImport() {
        val text = "{\"format\":\"inniclassic-settings\",\"version\":1,\"prefs\":{" +
            "\"Y1_LASTFM\":{\"session_key\":{\"type\":\"string\",\"value\":\"s\"}}," +
            "\"AppPrefs\":{\"lastfm_password\":{\"type\":\"string\",\"value\":\"p\"},\"shuffle\":{\"type\":\"boolean\",\"value\":true}}}}"
        val backup = SettingsBackup.parse(text)
        assertEquals(setOf("AppPrefs"), backup.prefs.keys)
        assertEquals(mapOf<String, Any>("shuffle" to true), backup.prefs["AppPrefs"])
    }

    /** SharedPreferences in memory. */
    private class MapPrefs : SharedPreferences {
        val map = LinkedHashMap<String, Any?>()
        override fun getAll(): MutableMap<String, *> = LinkedHashMap(map)
        override fun getString(key: String, defValue: String?) = map[key] as? String ?: defValue
        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String, defValues: MutableSet<String>?) = map[key] as? MutableSet<String> ?: defValues
        override fun getInt(key: String, defValue: Int) = map[key] as? Int ?: defValue
        override fun getLong(key: String, defValue: Long) = map[key] as? Long ?: defValue
        override fun getFloat(key: String, defValue: Float) = map[key] as? Float ?: defValue
        override fun getBoolean(key: String, defValue: Boolean) = map[key] as? Boolean ?: defValue
        override fun contains(key: String) = map.containsKey(key)
        override fun edit(): SharedPreferences.Editor = MapEditor(map)
        override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener) {}
        override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener) {}

        private class MapEditor(private val target: MutableMap<String, Any?>) : SharedPreferences.Editor {
            private val puts = LinkedHashMap<String, Any?>()
            private val removes = HashSet<String>()
            private var clear = false
            override fun putString(key: String, value: String?) = apply { puts[key] = value }
            override fun putStringSet(key: String, values: MutableSet<String>?) = apply { puts[key] = values?.toMutableSet() }
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
