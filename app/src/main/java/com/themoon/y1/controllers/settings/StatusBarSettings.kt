package com.themoon.y1.controllers.settings

import android.content.Context
import android.content.SharedPreferences
import android.view.View
import com.themoon.y1.MainActivity

/** Settings → Status Bar: the Battery row (show or hide the battery icon). */
object StatusBarSettings {
    const val KEY_SHOW_BATTERY = "show_battery_icon"
    const val DEFAULT_SHOW_BATTERY = true

    fun isBatteryShown(prefs: SharedPreferences): Boolean = prefs.getBoolean(KEY_SHOW_BATTERY, DEFAULT_SHOW_BATTERY)

    /** Shows or hides the battery icon of the status bar. At app start and after each change. */
    fun applyBattery(main: MainActivity) {
        val prefs = main.getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
        main.batteryIconView?.visibility = if (isBatteryShown(prefs)) View.VISIBLE else View.GONE
    }
}
