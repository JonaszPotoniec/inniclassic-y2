package com.themoon.y1.battery

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import com.themoon.y1.DeviceModel
import com.themoon.y1.controllers.settings.DateTimeSync
import com.themoon.y1.managers.AudioPlayerManager
import com.themoon.y1.managers.FmRadioManager
import com.themoon.y1.managers.PowerMenu
import java.io.File

/**
 * The battery level that the app shows, and the shutdown before the battery is empty ([BatteryGuardLogic]), on the
 * Y2 only ([DeviceModel.isY2]); on other devices [percent] is the gauge and nothing shuts down.
 *
 * Samples: each battery broadcast, and a 30 s timer on the main thread. The timer has no wake lock, so it runs only
 * while the CPU is awake and never wakes the Y2. Use [percent] wherever the app shows the battery level, and
 * [addListener] to show a new level that comes without a battery broadcast.
 */
object BatteryGuard {
    private const val TAG = "BatteryGuard"
    private const val TICK_MS = 30_000L
    /** The "Battery empty" notice shows this long before the shutdown; a charger in this time stops it. */
    private const val NOTICE_MS = 5_000L
    private const val PREFS = "battery_guard"

    interface Listener {
        /** The shown level changed. */
        fun onLevelChanged() {}
        /** The battery is empty: the Y2 turns off in [NOTICE_MS] unless a charger comes. */
        fun onEmpty() {}
    }

    private val main = Handler(Looper.getMainLooper())
    private val listeners = ArrayList<Listener>()
    private var app: Context? = null
    private val logic = BatteryGuardLogic()
    private var lastIntent: Intent? = null
    private var cpuLast: LongArray? = null
    private var shutdownStarted = false

    fun start(context: Context) {
        if (app != null || !DeviceModel.isY2(Build.MODEL)) return
        val c = context.applicationContext
        app = c
        restore(c)
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        // ACTION_BATTERY_CHANGED is sticky: the first sample comes at once
        c.registerReceiver(receiver, filter)
        main.postDelayed(tick, TICK_MS)
    }

    fun addListener(l: Listener) {
        if (l !in listeners) listeners.add(l)
    }

    fun removeListener(l: Listener) {
        listeners.remove(l)
    }

    /** The level to show for a battery broadcast: the gauge while charging (or on other devices), else the voltage model. */
    fun percent(intent: Intent?): Int {
        if (intent == null) return -1
        val gauge = gaugePercent(intent)
        if (isCharging(intent)) return gauge
        val shown = logic.shown
        return if (shown >= 0 && !logic.charging) shown else gauge
    }

    /** Charging as the status bar shows it: charging, full, or on a charger. */
    fun isCharging(intent: Intent): Boolean {
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1)
        return status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL || plugged > 0
    }

    private fun gaugePercent(intent: Intent): Int {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        return if (level >= 0 && scale > 0) (level * 100 / scale).coerceIn(0, 100) else -1
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_BATTERY_CHANGED) {
                lastIntent = intent
            } else {
                // A charger, or the screen: the sticky broadcast has the new state.
                context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))?.let { lastIntent = it }
            }
            sample()
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            sample()
            main.postDelayed(this, TICK_MS)
        }
    }

    private fun sample() {
        val c = app ?: return
        val intent = lastIntent ?: return
        val charging = isCharging(intent)
        @Suppress("DEPRECATION")
        val screenOn = (c.getSystemService(Context.POWER_SERVICE) as? PowerManager)?.isScreenOn != false
        val load = BatteryGuardLogic.Load(screenOn, isPlaying(), cpuPercent(), DateTimeSync.isWifiConnected(c))
        val mV = intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0)
        val changed = logic.onSample(SystemClock.elapsedRealtime(), mV, gaugePercent(intent), charging, load)
        if (charging) shutdownStarted = false
        if (changed) {
            Log.i(TAG, "shown=${logic.shown} mV=$mV charging=$charging shutdownDue=${logic.shutdownDue}")
            save(c)
            for (l in ArrayList(listeners)) l.onLevelChanged()
        }
        if (logic.shutdownDue && !shutdownStarted && !charging) startShutdown(c)
    }

    /** The music or the FM radio plays. */
    private fun isPlaying(): Boolean =
        AudioPlayerManager.getInstance().isPlayingActive || FmRadioManager.getInstance()?.isPowerUp == true

    /** Busy CPU time of all cores, in % of the time since the sample before (from /proc/stat). */
    private fun cpuPercent(): Int = try {
        val parts = File("/proc/stat").bufferedReader().use { it.readLine() }.trim().split(Regex("\\s+")).drop(1)
            .mapNotNull { it.toLongOrNull() }
        val total = parts.sum()
        val idle = parts.getOrElse(3) { 0L } + parts.getOrElse(4) { 0L }
        val last = cpuLast
        cpuLast = longArrayOf(total, idle)
        if (last == null || total <= last[0]) 0
        else (100 * ((total - last[0]) - (idle - last[1])) / (total - last[0])).toInt().coerceIn(0, 100)
    } catch (_: Throwable) {
        0
    }

    /**
     * The battery is empty: the "Battery empty" notice (it turns the screen on), then after [NOTICE_MS] the music
     * pauses, the playback state and the queue are saved, and Android shuts down (the Power Off of the Power screen,
     * which also does the dry run of `debug.y1.power_dry_run`).
     */
    private fun startShutdown(c: Context) {
        shutdownStarted = true
        Log.w(TAG, "Battery empty: shutdown in ${NOTICE_MS / 1000} s")
        try {
            @Suppress("DEPRECATION")
            (c.getSystemService(Context.POWER_SERVICE) as? PowerManager)
                ?.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP, "y1:battery-empty")
                ?.acquire(NOTICE_MS + 5_000L)
        } catch (t: Throwable) {
            Log.w(TAG, "No wake lock: $t")
        }
        for (l in ArrayList(listeners)) l.onEmpty()
        main.postDelayed({
            val intent = c.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            if (intent != null && isCharging(intent)) {
                Log.w(TAG, "Charger connected: no shutdown")
                return@postDelayed
            }
            PowerMenu.run(c, PowerMenu.Action.POWER_OFF) { Log.w(TAG, "Power Off failed") }
        }, NOTICE_MS)
    }

    private fun save(c: Context) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt("shown", logic.shown)
            .putBoolean("charging", logic.charging)
            .putString("boot", bootId())
            .apply()
    }

    /** A restart of the app in the same boot and discharge keeps the shown level. */
    private fun restore(c: Context) {
        val p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (p.getBoolean("charging", true) || p.getString("boot", "") != bootId()) return
        logic.restore(p.getInt("shown", -1))
    }

    private fun bootId(): String = try {
        File("/proc/sys/kernel/random/boot_id").readText().trim()
    } catch (_: Throwable) {
        ""
    }
}
