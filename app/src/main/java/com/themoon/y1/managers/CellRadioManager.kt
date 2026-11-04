package com.themoon.y1.managers

import android.content.Context
import android.database.ContentObserver
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log

/**
 * Keeps the cell (mobile) radio off on the Y2: it has no SIM card, so the modem only searches for a network.
 * Airplane mode powers the modem off on its ROM (`ril.flightmode.poweroffMD=1`). Airplane mode is set to affect
 * only the cell radio first, so Wi-Fi, Bluetooth and FM continue to work. The state is always read from the
 * system settings, never stored, and the system keeps it after a reboot.
 *
 * The app checks once at each start, and again when the settings change. There is no timer and no polling.
 */
object CellRadioManager {
    private const val TAG = "Y1CellRadio"
    private const val CELL = "cell"
    private const val Y2_MODEL = "Y2"

    /** Android's default list when `airplane_mode_radios` is not set. */
    private const val DEFAULT_RADIOS = "cell,bluetooth,wifi,nfc,wimax"

    /** If the radio comes on again this soon after our last try, another tool wants it on: stop. */
    internal const val FIGHT_WINDOW_MS = 60_000L

    /** Root shell command that turns the cell radio off. The radio list comes first, so Wi-Fi and Bluetooth never drop. */
    internal const val TURN_OFF_COMMAND =
        "settings put global airplane_mode_radios $CELL; " +
            "settings put global airplane_mode_on 1; " +
            "am broadcast -a android.intent.action.AIRPLANE_MODE --ez state true"

    private var observer: ContentObserver? = null

    @Volatile
    private var isBusy = false

    @Volatile
    private var stopped = false

    /** Time of our last try (elapsed real time), or null if there was no try since the process started. */
    @Volatile
    private var lastTryAt: Long? = null

    fun parseRadios(radios: String?): List<String> =
        (if (radios.isNullOrBlank()) DEFAULT_RADIOS else radios)
            .split(',').map { it.trim() }.filter { it.isNotEmpty() }

    /** True when airplane mode turns off only the cell radio. */
    fun isCellOnly(radios: String?): Boolean = parseRadios(radios).toSet() == setOf(CELL)

    /** True when the cell radio is on for this airplane-mode state. */
    fun isCellRadioOn(airplaneModeOn: Boolean, radios: String?): Boolean =
        !airplaneModeOn || CELL !in parseRadios(radios)

    /** True on a device where we know that airplane mode powers the modem off and that FM, Wi-Fi and Bluetooth stay on. */
    fun keepsCellRadioOff(model: String?): Boolean = model?.trim() == Y2_MODEL

    internal enum class Action { NONE, TURN_OFF, STOP }

    /**
     * What to do when the app starts or a setting changes.
     * [isBusy]: our command still runs; its own settings changes must not start another command.
     * [stopped]: root failed or another tool wants the radio on; no more tries until the next app start.
     * [msSinceLastTry]: null if there was no try since the process started.
     */
    internal fun nextAction(cellRadioOn: Boolean, isBusy: Boolean, stopped: Boolean, msSinceLastTry: Long?): Action =
        when {
            !cellRadioOn || isBusy || stopped -> Action.NONE
            msSinceLastTry != null && msSinceLastTry < FIGHT_WINDOW_MS -> Action.STOP
            else -> Action.TURN_OFF
        }

    fun readRadios(context: Context): String? =
        try {
            Settings.Global.getString(context.contentResolver, Settings.Global.AIRPLANE_MODE_RADIOS)
        } catch (_: Throwable) {
            null
        }

    fun isCellRadioOn(context: Context): Boolean {
        val airplaneOn = try {
            Settings.Global.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) != 0
        } catch (_: Throwable) {
            false
        }
        return isCellRadioOn(airplaneOn, readRadios(context))
    }

    /** True when airplane mode does not affect FM, so FM need not turn it off. */
    fun airplaneModeSparesFm(context: Context): Boolean = isCellOnly(readRadios(context))

    /** Call at app start, on the main thread. It checks once and then watches the settings until [stop]. */
    fun start(context: Context) {
        if (!keepsCellRadioOff(Build.MODEL) || observer != null) return
        val appContext = context.applicationContext
        val resolver = appContext.contentResolver
        val changed = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) = check(appContext)
        }
        resolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.AIRPLANE_MODE_ON), false, changed)
        resolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.AIRPLANE_MODE_RADIOS), false, changed)
        observer = changed
        check(appContext)
    }

    fun stop(context: Context) {
        val changed = observer ?: return
        context.applicationContext.contentResolver.unregisterContentObserver(changed)
        observer = null
    }

    private fun check(appContext: Context) {
        val now = SystemClock.elapsedRealtime()
        when (nextAction(isCellRadioOn(appContext), isBusy, stopped, lastTryAt?.let { now - it })) {
            Action.NONE -> {}
            Action.STOP -> {
                stopped = true
                Log.w(TAG, "The cell radio came on again after our last try: no more tries until the next app start")
            }
            Action.TURN_OFF -> turnOff(appContext, now)
        }
    }

    /** One try off the main thread (root is necessary for the protected AIRPLANE_MODE broadcast). */
    private fun turnOff(appContext: Context, now: Long) {
        isBusy = true
        lastTryAt = now
        Thread({
            try {
                val ran = FmRadioManager.runSuQuiet(TURN_OFF_COMMAND)
                if (!ran || isCellRadioOn(appContext)) {
                    stopped = true
                    Log.w(TAG, "Could not turn the cell radio off (root?): no more tries until the next app start")
                } else {
                    Log.i(TAG, "Turned the cell radio off")
                }
            } finally {
                isBusy = false
            }
        }, "CellRadio").start()
    }
}
