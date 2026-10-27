package com.themoon.y1.managers

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.Window
import android.view.WindowManager

/**
 * Coordinates backlight idle countdown, FakeScreenOff simulated blackout
 * when radio/webserver is active, and Wi-Fi sleep savings.
 */
class PowerBacklightManager private constructor(private val context: Context) {

    companion object {
        val BACKLIGHT_TIMER_OPTIONS_MS = longArrayOf(10000, 20000, 30000, 60000, 120000, 0)
        val BACKLIGHT_TIMER_LABELS = arrayOf("10 Seconds", "20 Seconds", "30 Seconds", "1 Minute", "2 Minutes", "Always On")

        @Volatile
        private var instance: PowerBacklightManager? = null
        fun getInstance(context: Context): PowerBacklightManager {
            return instance ?: synchronized(this) {
                instance ?: PowerBacklightManager(context.applicationContext).also { instance = it }
            }
        }
    }

    var backlightTimerIndex: Int = 3
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

    fun resetTimer() {
        handler.removeCallbacks(timeoutRunnable)
        try {
            val prefs = context.getSharedPreferences("Y1_SETTINGS", Context.MODE_PRIVATE)
            if (prefs.contains("backlight_timer_index")) {
                backlightTimerIndex = prefs.getInt("backlight_timer_index", backlightTimerIndex)
            }
        } catch (_: Exception) {}
        val idx = if (backlightTimerIndex in BACKLIGHT_TIMER_OPTIONS_MS.indices) {
            backlightTimerIndex
        } else {
            3
        }
        val ms = BACKLIGHT_TIMER_OPTIONS_MS[idx]
        if (ms > 0) {
            handler.postDelayed(timeoutRunnable, ms)
        }
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
