package com.themoon.y1

import com.themoon.y1.migrations.MoveMisplacedPrefsTest.FakePrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PrefsTest {

    @Test
    fun eachKeyIsDefinedOnce() {
        val keys = Prefs.ALL.map { it.key }
        assertEquals(keys.toSet().size, keys.size)
        val prefixes = Prefs.FAMILIES.map { it.prefix }
        assertEquals(prefixes.toSet().size, prefixes.size)
        // No key is also in a family, and no family prefix starts another (each key has one owner)
        for (k in keys) assertNull(k, Prefs.familyOf(k))
        for (a in prefixes) for (b in prefixes) if (a != b) assertFalse("$a / $b", b.startsWith(a))
    }

    @Test
    fun eachPrefFieldIsInTheList() {
        val fields = Prefs::class.java.declaredFields.filter { PrefKey::class.java.isAssignableFrom(it.type) }
            .map { it.isAccessible = true; it.get(Prefs) as PrefKey }
        assertEquals(fields.toSet(), Prefs.ALL.toSet())
        val families = Prefs::class.java.declaredFields.filter { it.type == PrefFamily::class.java }
            .map { it.isAccessible = true; it.get(Prefs) as PrefFamily }
        assertEquals(families.toSet(), Prefs.FAMILIES.toSet())
    }

    /** The keys that resetAllAppSettings removed before the model. Reset must not change. */
    @Test
    fun resetRemovesTheSameKeysAsBefore() {
        val before = setOf(
            "is_24h_format", "time_in_title_bar", "show_battery_percentage", "show_battery_icon", "volume_limit_max",
            "toggle_style", "sound_check_enabled", "video_stretch_fill", "album_tilt_enabled", "lyrics_keep_screen_on",
            "show_total_time", "show_spectrum", "lyrics_script", "backlight_timer_index", "click_volume",
            "screen_off_control", "double_click_favorite", "repeat_mode", "loop_scroll_on", "list_edges_fade",
            "eq_preset", "eq_profile_id", "group_albums_by_artist", "cover_pan_zoom"
        )
        assertEquals(before, Prefs.RESETTABLE.map { it.key }.toSet())
    }

    @Test
    fun resetAllRemovesOnlyResettableKeys() {
        val map = mutableMapOf<String, Any?>(
            "repeat_mode" to 2, "shuffle" to true, "sound" to false, "eq_preset" to "x", "rating_/a.mp3" to 5
        )
        val prefs = FakePrefs(map)
        assertTrue(Prefs.resetAll(prefs))
        assertEquals(setOf("shuffle", "sound", "rating_/a.mp3"), map.keys)
        assertEquals(0, Prefs.REPEAT_MODE.get(prefs))
    }

    /** SettingsBackup before the model: EXCLUDED_KEYS and EXCLUDED_PREFIXES. */
    @Test
    fun backupExclusionsAreTheSameAsBefore() {
        assertEquals(
            setOf("lastfm_username", "lastfm_password", "has_saved_playback_state", "last_lang_version",
                "last_installed_theme_version", "reboot_to_theme"),
            Prefs.ALL.filter { !it.inBackup }.map { it.key }.toSet()
        )
        assertEquals(setOf("last_played_", "album_art_"), Prefs.FAMILIES.filter { !it.inBackup }.map { it.prefix }.toSet())
    }

    @Test
    fun settingKeysAreSettings() {
        val settings = listOf(
            "sound", "vibrate", "vibrate_strength", "double_click_favorite", "time_in_title_bar", "is_24h_format",
            "screen_off_control", "loop_scroll_on", "list_edges_fade", "app_theme_name", "current_theme_name",
            "album_tilt_enabled", "shuffle", "repeat_mode", "backlight_timer_index", "volume_limit_max",
            "app_language", "auto_theme_enabled"
        )
        for (k in settings) assertTrue(k, Prefs.isSetting(k))
        assertFalse(Prefs.isSetting("last_played_song_path"))
        assertFalse(Prefs.isSetting("lastfm_username"))
    }

    @Test
    fun typedPrefsReadDefaultsAndWrite() {
        val map = mutableMapOf<String, Any?>()
        val prefs = FakePrefs(map)
        assertEquals(false, Prefs.SHUFFLE.get(prefs))
        assertEquals(true, Prefs.BUTTON_SOUND.get(prefs))
        assertEquals(-1, Prefs.VOLUME_LIMIT.get(prefs))
        assertEquals("preset_0", Prefs.EQ_PROFILE.get(prefs))
        assertEquals(ClockSettings.DEFAULT_24_HOUR_FORMAT, Prefs.CLOCK_24_HOUR.get(prefs))
        assertEquals(true, Prefs.SHUFFLE.toggle(prefs))
        assertEquals(true, map["shuffle"])
        Prefs.REPEAT_MODE.setAsync(prefs, 2)
        assertEquals(2, map["repeat_mode"])
        Prefs.EQ_PROFILE.set(prefs, "custom_Mine")
        assertEquals("custom_Mine", Prefs.EQ_PROFILE.get(prefs))
        assertEquals("hide_btn_btn_videos", Prefs.MAIN_MENU_HIDDEN.key("btn_videos"))
    }
}
