package com.themoon.y1.controllers.player

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

class AlbumTiltSettingTest {

    /** SharedPreferences that only answers getBoolean, from [values]. */
    private fun prefs(values: Map<String, Boolean>): SharedPreferences =
        Proxy.newProxyInstance(javaClass.classLoader, arrayOf(SharedPreferences::class.java)) { _, method, args ->
            when (method.name) {
                "getBoolean" -> values[args[0] as String] ?: args[1]
                else -> throw UnsupportedOperationException(method.name)
            }
        } as SharedPreferences

    @Test
    fun readsTheKeyAndFileThatSettingsWrites() {
        // SettingsController: AppPrefs, putBoolean("album_tilt_enabled", ...)
        assertEquals("AppPrefs", AlbumTiltSetting.PREFS_NAME)
        assertEquals("album_tilt_enabled", AlbumTiltSetting.KEY)
    }

    @Test
    fun tiltIsOnByDefault() {
        assertTrue(AlbumTiltSetting.isEnabled(prefs(emptyMap())))
    }

    @Test
    fun offInSettingsTurnsTheTiltOff() {
        assertFalse(AlbumTiltSetting.isEnabled(prefs(mapOf("album_tilt_enabled" to false))))
        assertEquals(0f, AlbumTiltSetting.rotationY(false), 0f)
        assertEquals(18f, AlbumTiltSetting.rotationY(true), 0f)
    }

    @Test
    fun oldUnusedKeyHasNoEffect() {
        assertTrue(AlbumTiltSetting.isEnabled(prefs(mapOf("album_tilt_effect" to false))))
    }
}
