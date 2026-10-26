package com.themoon.y1.controllers.player

import android.content.Context
import android.content.SharedPreferences

/**
 * Settings → Album Cover Tilt. SettingsController writes [KEY] to the [PREFS_NAME] preferences;
 * Now Playing reads the same key, so the setting has an effect.
 */
object AlbumTiltSetting {
    const val PREFS_NAME = "AppPrefs"
    const val KEY = "album_tilt_enabled"
    const val TILT_DEGREES = 18f

    fun isEnabled(prefs: SharedPreferences): Boolean = prefs.getBoolean(KEY, true)

    fun isEnabled(context: Context): Boolean =
        isEnabled(context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE))

    /** rotationY of the Now Playing art: tilted when the setting is on, flat when it is off. */
    fun rotationY(enabled: Boolean): Float = if (enabled) TILT_DEGREES else 0f
}
