package com.themoon.y1.managers

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.view.Window
import android.view.WindowManager

/**
 * Settings → Backlight Timer: the one screen timer of the app and of Android.
 *
 * The value goes to Android's SCREEN_OFF_TIMEOUT, so Android turns the screen off, also while music or FM radio
 * plays (both hold a partial wake lock and keep playing with the screen off). Before, the app had its own timer
 * and Settings had a second row (Screen Timeout) for SCREEN_OFF_TIMEOUT; the shorter of the two always won.
 *
 * The app's own timer runs only while the web server holds the screen on (FLAG_KEEP_SCREEN_ON stops Android's
 * timer). Then it blanks the screen after the same time (FakeScreenOff: black overlay, lowest brightness).
 */
object BacklightTimer {
    /** 0 = Always On. */
    val OPTIONS_MS = longArrayOf(10000, 20000, 30000, 60000, 120000, 0)
    val LABELS = arrayOf("10 Seconds", "20 Seconds", "30 Seconds", "1 Minute", "2 Minutes", "Always On")
    const val PREF_INDEX = "backlight_timer_index"
    const val DEFAULT_INDEX = 3

    /**
     * SCREEN_OFF_TIMEOUT for Always On. Android has no "never" value; Int.MAX_VALUE ms is 24.8 days.
     * A large timeout, not FLAG_KEEP_SCREEN_ON: the web server, USB storage and the test interface clear that flag
     * when they stop, and the flag works only while our window is in front.
     */
    const val ALWAYS_ON_TIMEOUT_MS = Int.MAX_VALUE

    fun validIndex(index: Int): Int = if (index in OPTIONS_MS.indices) index else DEFAULT_INDEX

    /** The SCREEN_OFF_TIMEOUT (ms) for the Backlight Timer option [index]. */
    fun screenOffTimeoutMs(index: Int): Int {
        val ms = OPTIONS_MS[validIndex(index)]
        return if (ms > 0) ms.toInt() else ALWAYS_ON_TIMEOUT_MS
    }

    /** The delay of the app's own timer, or 0 for no app timer: it runs only while the app holds the screen on. */
    fun appTimerMs(index: Int, appHoldsScreenOn: Boolean): Long =
        if (appHoldsScreenOn) OPTIONS_MS[validIndex(index)] else 0L

    /**
     * How the app turns the screen off (timer or long press): blank it (FakeScreenOff) only while it holds the
     * screen on for the web server; else a real screen off. Music and FM radio continue with the screen off.
     */
    fun useFakeScreenOff(appHoldsScreenOn: Boolean): Boolean = appHoldsScreenOn
}

/**
 * Applies the Backlight Timer ([BacklightTimer]), runs the app's own timer while the web server holds the screen
 * on, and does the FakeScreenOff blank screen and its Wi-Fi sleep savings.
 */
class PowerBacklightManager private constructor(private val context: Context) {

    companion object {
        val BACKLIGHT_TIMER_OPTIONS_MS = BacklightTimer.OPTIONS_MS
        val BACKLIGHT_TIMER_LABELS = BacklightTimer.LABELS

        @Volatile
        private var instance: PowerBacklightManager? = null
        fun getInstance(context: Context): PowerBacklightManager {
            return instance ?: synchronized(this) {
                instance ?: PowerBacklightManager(context.applicationContext).also { instance = it }
            }
        }
    }

    var backlightTimerIndex: Int = BacklightTimer.DEFAULT_INDEX

    /** True while the app holds the screen on with FLAG_KEEP_SCREEN_ON (the web server). Set by MainActivity. */
    var appHoldsScreenOn: () -> Boolean = { false }
    var isFakeScreenOff: Boolean = false
        set(value) {
            field = value
            com.themoon.y1.views.CoverPane.setBlanked(value)
        }
    var wasWifiOnBeforeSleep: Boolean = false

    fun autoManageWifiPower(isGoingToSleep: Boolean, isServerRunning: Boolean, isRadioActive: Boolean) {
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager ?: return
        if (isGoingToSleep) {
            val isWifiActive = wm.isWifiEnabled || wm.wifiState == android.net.wifi.WifiManager.WIFI_STATE_ENABLING
            if (isWifiActive) {
                wasWifiOnBeforeSleep = true
                if (!isServerRunning && !isRadioActive) {
                    wm.isWifiEnabled = false
                }
            } else {
                wasWifiOnBeforeSleep = false
            }
        } else {
            if (wasWifiOnBeforeSleep && !wm.isWifiEnabled) {
                wm.isWifiEnabled = true
            }
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var onTimeoutAction: (() -> Unit)? = null

    private val timeoutRunnable = Runnable {
        onTimeoutAction?.invoke()
    }

    fun setTimeoutAction(action: () -> Unit) {
        this.onTimeoutAction = action
    }

    /** Starts the app's timer again (after a key or a screen-on). It runs only while [appHoldsScreenOn]. */
    fun resetTimer() {
        handler.removeCallbacks(timeoutRunnable)
        readIndex()
        val ms = BacklightTimer.appTimerMs(backlightTimerIndex, appHoldsScreenOn())
        if (ms > 0) {
            handler.postDelayed(timeoutRunnable, ms)
        }
    }

    /**
     * Writes the Backlight Timer value to Android's SCREEN_OFF_TIMEOUT (when it is different), then starts the
     * app's timer again. Call at start and after a change of the setting.
     */
    fun applyBacklightSetting() {
        readIndex()
        val timeout = BacklightTimer.screenOffTimeoutMs(backlightTimerIndex)
        try {
            val cr = context.contentResolver
            if (Settings.System.getInt(cr, Settings.System.SCREEN_OFF_TIMEOUT, -1) != timeout) {
                Settings.System.putInt(cr, Settings.System.SCREEN_OFF_TIMEOUT, timeout)
            }
        } catch (_: Exception) {}
        resetTimer()
    }

    /** The setting is in AppPrefs (written by Settings → Backlight Timer). */
    private fun readIndex() {
        try {
            val prefs = context.getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
            if (prefs.contains(BacklightTimer.PREF_INDEX)) {
                backlightTimerIndex = prefs.getInt(BacklightTimer.PREF_INDEX, backlightTimerIndex)
            }
        } catch (_: Exception) {}
        backlightTimerIndex = BacklightTimer.validIndex(backlightTimerIndex)
    }

    fun cancelTimer() {
        handler.removeCallbacks(timeoutRunnable)
    }

    fun enterFakeScreenOff(
        window: Window,
        overlayView: View?,
        onWifiSleep: (Boolean) -> Unit
    ) {
        isFakeScreenOff = true
        onWifiSleep(true)

        overlayView?.let { overlay ->
            overlay.setBackgroundColor(0xFF000000.toInt())
            overlay.alpha = 0.0f
            overlay.visibility = View.VISIBLE

            val fadeHandler = Handler(Looper.getMainLooper())
            fadeHandler.post(object : Runnable {
                var alpha = 0.0f
                override fun run() {
                    if (!isFakeScreenOff) return
                    alpha += 0.08f
                    if (alpha >= 1.0f) {
                        overlay.alpha = 1.0f
                        try {
                            val lp = window.attributes
                            lp.screenBrightness = 0.01f
                            window.attributes = lp
                        } catch (ignored: Exception) {}
                    } else {
                        overlay.alpha = alpha
                        fadeHandler.postDelayed(this, 25)
                    }
                }
            })
        }
    }

    fun wakeFromFakeScreenOff(
        window: Window,
        overlayView: View?,
        systemBrightness: Int,
        onWifiSleep: (Boolean) -> Unit
    ) {
        isFakeScreenOff = false
        onWifiSleep(false)

        overlayView?.let { overlay ->
            overlay.visibility = View.GONE
            overlay.setBackgroundColor(0xDD000000.toInt())
        }

        try {
            val lp = window.attributes
            lp.screenBrightness = systemBrightness / 255.0f
            window.attributes = lp
        } catch (ignored: Exception) {}
    }
}
