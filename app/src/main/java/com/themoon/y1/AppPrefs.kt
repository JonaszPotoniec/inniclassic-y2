package com.themoon.y1

import android.content.Context
import android.content.SharedPreferences

/**
 * The preference file of the app settings. Builds before the Kotlin refactor used "y1_settings"
 * and "Y1_SETTINGS"; Migration004MoveMisplacedPrefs moves the keys that later code still wrote there.
 */
object AppPrefs {
    const val NAME = "AppPrefs"

    fun get(context: Context): SharedPreferences = context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
}
