package com.themoon.y1.core.prefs

import android.content.SharedPreferences
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

class AppPreferences(val prefs: SharedPreferences) {
    var isVibrationEnabled: Boolean by booleanPref("VIBRATION_ENABLED", true)
    var vibrationStrength: Int by intPref("VIBRATION_STRENGTH", 25)
    var isLoopScrollingEnabled: Boolean by booleanPref("LOOP_SCROLL", false)
    var isHideListBoxEnabled: Boolean by booleanPref("HIDE_LIST_BOX", false)
    var isSoftware10BandEq: Boolean by booleanPref("SW_10BAND_EQ_ENABLED", false)
    var backlightTimeoutSec: Int by intPref("BACKLIGHT_TIMER_SEC", 30)
    var currentThemeName: String by stringPref("CURRENT_THEME_NAME", "Classic Light")
}

fun booleanPref(key: String, defaultValue: Boolean = false): ReadWriteProperty<AppPreferences, Boolean> =
    object : ReadWriteProperty<AppPreferences, Boolean> {
        override fun getValue(thisRef: AppPreferences, property: KProperty<*>): Boolean =
            thisRef.prefs.getBoolean(key, defaultValue)

        override fun setValue(thisRef: AppPreferences, property: KProperty<*>, value: Boolean) {
            thisRef.prefs.edit().putBoolean(key, value).apply()
        }
    }

fun intPref(key: String, defaultValue: Int = 0): ReadWriteProperty<AppPreferences, Int> =
    object : ReadWriteProperty<AppPreferences, Int> {
        override fun getValue(thisRef: AppPreferences, property: KProperty<*>): Int =
            thisRef.prefs.getInt(key, defaultValue)

        override fun setValue(thisRef: AppPreferences, property: KProperty<*>, value: Int) {
            thisRef.prefs.edit().putInt(key, value).apply()
        }
    }

fun stringPref(key: String, defaultValue: String = ""): ReadWriteProperty<AppPreferences, String> =
    object : ReadWriteProperty<AppPreferences, String> {
        override fun getValue(thisRef: AppPreferences, property: KProperty<*>): String =
            thisRef.prefs.getString(key, defaultValue) ?: defaultValue

        override fun setValue(thisRef: AppPreferences, property: KProperty<*>, value: String) {
            thisRef.prefs.edit().putString(key, value).apply()
        }
    }

fun longPref(key: String, defaultValue: Long = 0L): ReadWriteProperty<AppPreferences, Long> =
    object : ReadWriteProperty<AppPreferences, Long> {
        override fun getValue(thisRef: AppPreferences, property: KProperty<*>): Long =
            thisRef.prefs.getLong(key, defaultValue)

        override fun setValue(thisRef: AppPreferences, property: KProperty<*>, value: Long) {
            thisRef.prefs.edit().putLong(key, value).apply()
        }
    }

fun floatPref(key: String, defaultValue: Float = 0f): ReadWriteProperty<AppPreferences, Float> =
    object : ReadWriteProperty<AppPreferences, Float> {
        override fun getValue(thisRef: AppPreferences, property: KProperty<*>): Float =
            thisRef.prefs.getFloat(key, defaultValue)

        override fun setValue(thisRef: AppPreferences, property: KProperty<*>, value: Float) {
            thisRef.prefs.edit().putFloat(key, value).apply()
        }
    }
