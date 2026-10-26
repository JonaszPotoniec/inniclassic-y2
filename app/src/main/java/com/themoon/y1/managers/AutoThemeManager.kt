package com.themoon.y1.managers

import android.app.Activity
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.Build
import android.view.View
import com.themoon.y1.MainActivity
import com.themoon.y1.ThemeManager
import com.themoon.y1.changeTheme
import com.themoon.y1.views.TablerIcons
import java.util.TimeZone

/**
 * Settings → Theme → Auto: Classic Light from sunrise to sunset, Classic Dark at night (see [SunTimes]).
 *
 * The theme changes live (MainActivity.changeTheme: no restart, playback continues), at the exact time:
 * an exact AlarmManager RTC alarm at the next sunrise or sunset. RTC does not wake the device: while the
 * CPU is awake (screen on, or music playing with the screen off) the alarm comes on time, else at the
 * next wake-up. App start, screen-on, time and time zone changes and the minute tick check again.
 *
 * The resolved theme is saved as the normal selected theme (app_theme_name), so code that reads
 * the theme name works as before.
 */
object AutoThemeManager {

    const val PREF_KEY = "auto_theme_enabled"
    const val DAY_THEME = "Classic Light"
    const val NIGHT_THEME = "Classic Dark"
    const val DEFAULT_THEME = "Classic Light"

    private const val ACTION_SWITCH = "com.themoon.y1.AUTO_THEME_SWITCH"

    // The alarm comes this much after the sunrise or sunset, so that SunTimes.isDaytime already has the new value
    private const val ALARM_MARGIN_MS = 1000L

    /** Time of the next switch (epoch ms) that the alarm is set for, or -1. */
    @Volatile
    var nextSwitchMs: Long = -1L
        private set

    private var receiver: BroadcastReceiver? = null

    fun isEnabled(prefs: SharedPreferences): Boolean = prefs.getBoolean(PREF_KEY, false)

    fun setEnabled(prefs: SharedPreferences, enabled: Boolean) {
        prefs.edit().putBoolean(PREF_KEY, enabled).commit()
    }

    fun themeNameFor(isDay: Boolean): String = if (isDay) DAY_THEME else NIGHT_THEME

    fun resolvedThemeName(
        nowMs: Long = System.currentTimeMillis(),
        zoneId: String = TimeZone.getDefault().id
    ): String = themeNameFor(SunTimes.isDaytime(zoneId, nowMs))

    /** App start, before the views are made: select the theme for the time now. */
    fun applyAtStart(prefs: SharedPreferences) {
        if (isEnabled(prefs)) ThemeManager.selectTheme(prefs, resolvedThemeName())
    }

    /**
     * End of MainActivity.onCreate: listen for the alarm and the time events, and set the alarm.
     * The receiver is on the application context, so a new activity (language change) replaces it
     * and onDestroy needs no hook.
     */
    fun start(main: MainActivity) {
        val app = main.applicationContext
        receiver?.let {
            try {
                app.unregisterReceiver(it)
            } catch (_: Exception) {}
        }
        val r = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val activity = MainActivity.instance ?: return
                if (intent.action == Intent.ACTION_TIME_TICK) onTimeTick(activity) else evaluate(activity)
            }
        }
        val filter = IntentFilter().apply {
            addAction(ACTION_SWITCH)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_TIME_TICK)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
        }
        app.registerReceiver(r, filter)
        receiver = r
        evaluate(main)
    }

    /**
     * Applies the theme for [nowMs] if it is not the current one, and sets the alarm for the next
     * sunrise or sunset. When Auto is off, it only removes the alarm.
     */
    fun evaluate(main: MainActivity, nowMs: Long = System.currentTimeMillis()) {
        if (main.isFinishing) return
        if (!isEnabled(main.prefs)) {
            cancelAlarm(main)
            return
        }
        val zoneId = TimeZone.getDefault().id
        val target = resolvedThemeName(nowMs, zoneId)
        if (ThemeManager.getCurrentTheme().name != target) main.changeTheme(target)
        setAlarm(main, SunTimes.nextTransitionMs(zoneId, nowMs))
    }

    /** Minute tick: one compare, in case the alarm did not come. */
    private fun onTimeTick(main: MainActivity) {
        val next = nextSwitchMs
        if (next >= 0 && System.currentTimeMillis() >= next) evaluate(main)
    }

    private fun setAlarm(context: Context, atMs: Long) {
        nextSwitchMs = atMs
        val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val trigger = atMs + ALARM_MARGIN_MS
        // set() is inexact from API 19 on; RTC (not RTC_WAKEUP) does not wake the device
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            am.setExact(AlarmManager.RTC, trigger, alarmIntent(context))
        } else {
            am.set(AlarmManager.RTC, trigger, alarmIntent(context))
        }
    }

    private fun cancelAlarm(context: Context) {
        nextSwitchMs = -1L
        val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        am.cancel(alarmIntent(context))
    }

    // Only this app gets the broadcast (setPackage); a broadcast from another app can only start a check
    private fun alarmIntent(context: Context): PendingIntent {
        val intent = Intent(ACTION_SWITCH).setPackage(context.packageName)
        var flags = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags = flags or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(context, 0, intent, flags)
    }

    /** Value of the Theme row in Settings, e.g. "Auto (Dark)". */
    fun settingsRowValue(prefs: SharedPreferences, localize: (String) -> String): String {
        val current = ThemeManager.getCurrentTheme().name
        if (!isEnabled(prefs)) return current
        val mode = when (current) {
            DAY_THEME -> localize("Light")
            NIGHT_THEME -> localize("Dark")
            else -> localize(current)
        }
        return localize("Auto") + " (" + mode + ")"
    }

    /** The "Auto" row of the theme list. */
    fun createSelectorRow(
        activity: Activity,
        prefs: SharedPreferences,
        createTablerListButton: (Array<String>?, String, Int, Int) -> View,
        clickFeedback: () -> Unit,
        localize: (String) -> String
    ): View {
        val isSelected = isEnabled(prefs)
        val row = createTablerListButton(
            if (isSelected) TablerIcons.CHECK else null,
            localize("Auto"),
            ThemeManager.getTextColorPrimary(),
            ThemeManager.getListButtonFocusedTextColor()
        )
        row.setOnClickListener {
            clickFeedback()
            val main = activity as? MainActivity ?: return@setOnClickListener
            setEnabled(prefs, true)
            // Also when the theme stays the same: the theme list is made again with the check on Auto
            main.changeTheme(resolvedThemeName())
            evaluate(main)
        }
        return row
    }

    /** A theme was chosen by hand: Auto off, no alarm. */
    fun turnOff(activity: Activity, prefs: SharedPreferences) {
        setEnabled(prefs, false)
        cancelAlarm(activity)
    }

    /** Reset All Settings: turn Auto off and go back to the default theme. */
    fun reset(activity: Activity, prefs: SharedPreferences) {
        if (!isEnabled(prefs)) return
        turnOff(activity, prefs)
        (activity as? MainActivity)?.changeTheme(DEFAULT_THEME)
    }
}
