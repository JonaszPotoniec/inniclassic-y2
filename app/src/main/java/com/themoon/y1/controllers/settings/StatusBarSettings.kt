package com.themoon.y1.controllers.settings

import android.content.Context
import android.content.SharedPreferences
import android.view.View
import android.view.ViewGroup
import com.themoon.y1.MainActivity

/** Settings → Status Bar: the Battery row (show or hide the battery icon), and the grey look of a row that cannot be used. */
object StatusBarSettings {
    const val KEY_SHOW_BATTERY = "show_battery_icon"
    const val DEFAULT_SHOW_BATTERY = true

    /**
     * Alpha of the title and the value of a row that cannot be used now (Battery Percentage when the battery is hidden,
     * Vibration Strength when Button Vibration is off).
     */
    const val DISABLED_ALPHA = 0.4f

    fun isBatteryShown(prefs: SharedPreferences): Boolean = prefs.getBoolean(KEY_SHOW_BATTERY, DEFAULT_SHOW_BATTERY)

    fun setBatteryShown(main: MainActivity?, prefs: SharedPreferences, shown: Boolean) {
        prefs.edit().putBoolean(KEY_SHOW_BATTERY, shown).commit()
        main?.let { applyBattery(it) }
    }

    /** Shows or hides the battery icon of the status bar. At app start and after each change. */
    fun applyBattery(main: MainActivity) {
        val prefs = main.getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
        main.batteryIconView?.visibility = if (isBatteryShown(prefs)) View.VISIBLE else View.GONE
    }

    /** A disabled row is grey and the wheel does not stop on it (SettingsController.onWheel skips rows that are not focusable). */
    fun setRowEnabled(row: ViewGroup, enabled: Boolean) {
        row.isFocusable = enabled
        for (i in 0 until row.childCount) row.getChildAt(i).alpha = if (enabled) 1f else DISABLED_ALPHA
    }
}
