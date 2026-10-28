package com.themoon.y1.managers

import android.app.Activity
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.provider.Settings
import android.util.Log

/**
 * The Power screen (Restart, Power Off) in place of Android's power menu, and its two actions.
 *
 * Android 4.4 opens its power menu (GlobalActions) on a long press of the Power button. Just before, PhoneWindowManager
 * sends ACTION_CLOSE_SYSTEM_DIALOGS with the reason "globalactions". GlobalActions closes its menu when it gets the
 * same broadcast with any other reason. So [PowerMenuInterceptor] sends the broadcast again with our reason and
 * shows our screen. The wheel cannot move in Android's menu (it sends DPAD_LEFT/RIGHT), so ours is the only usable one.
 */
object PowerMenu {
    private const val TAG = "PowerMenu"

    const val EXTRA_REASON = "reason"
    const val REASON_GLOBAL_ACTIONS = "globalactions"
    const val REASON_OURS = "inniclassic-power"

    /**
     * Android makes its menu while it sends the broadcast, adds the window 20-100 ms later and draws it 100-250 ms
     * after that (Y2, 2026-10-28). A close that comes before the window is added removes it before the first frame;
     * a later close waits for the first frame, so the menu shows for one frame (plus the window exit animation).
     * So the first close goes out at once from a background thread; the later ones are for slow cases.
     */
    val CLOSE_AGAIN_MS = longArrayOf(100L, 250L, 500L)

    /** `setprop debug.y1.power_dry_run 1` (adb): log the command, do not run it (test of Power Off). */
    const val DRY_RUN_PROP = "debug.y1.power_dry_run"

    /**
     * [commands] run with su, in turn, until one succeeds. `svc power` shuts down through Android (it sends
     * ACTION_SHUTDOWN, so the app and Android save their state); `reboot` is the fallback for ROMs without it.
     * [intentAction]: the last fallback without su (Android's ShutdownActivity; needs a system app).
     */
    enum class Action(val label: String, val commands: List<String>, val intentAction: String) {
        RESTART("Restart", listOf("svc power reboot", "reboot"), Intent.ACTION_REBOOT),
        POWER_OFF("Power Off", listOf("svc power shutdown", "reboot -p"), "android.intent.action.ACTION_REQUEST_SHUTDOWN")
    }

    fun isAndroidPowerMenu(reason: String?): Boolean = reason == REASON_GLOBAL_ACTIONS

    /**
     * Android's menu window has a fade-out animation of ~100 ms, which shows the menu even when we close it at once.
     * With window_animation_scale 0 it goes at once: measured on the Y2 (2026-10-28), 20 long presses showed the menu
     * in 1 frame of 1 press, else 0 (with the default scale 1: ~100-130 ms in 5 of 12 presses).
     * Android reads the value only at boot, so it starts to work after the next restart.
     */
    fun turnOffWindowAnimations(context: Context) {
        val old = Settings.Global.getString(context.contentResolver, WINDOW_ANIMATION_SCALE)
        if (old?.toFloatOrNull() == 0f) return
        val ok = runAsRoot("settings put global $WINDOW_ANIMATION_SCALE 0")
        Log.w(TAG, "$WINDOW_ANIMATION_SCALE ${old ?: "(not set)"} -> 0: ${if (ok) "set, used after the next restart" else "failed"}")
    }

    private const val WINDOW_ANIMATION_SCALE = "window_animation_scale"

    /** Closes Android's power menu (and the other system dialogs). */
    fun closeAndroidMenu(context: Context) {
        context.sendBroadcast(Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS).putExtra(EXTRA_REASON, REASON_OURS))
    }

    /** Pauses the music, saves the playback state (as at a shutdown), then restarts or powers off. */
    fun run(context: Context, action: Action, onFailed: () -> Unit) {
        val player = AudioPlayerManager.getInstance()
        if (player.isPlaying()) player.playOrPauseMusic()
        player.savePlaybackState(sync = true)

        val dryRun = isDryRun()
        Log.w(TAG, "${action.label}: su -c '${action.commands.first()}'${if (dryRun) " (dry run: not run)" else ""}")
        if (dryRun) return

        val app = context.applicationContext
        Thread({
            if (action.commands.none { runAsRoot(it) } && !startShutdownActivity(app, action)) {
                Handler(Looper.getMainLooper()).post(onFailed)
            }
        }, "y1-power").start()
    }

    private fun runAsRoot(command: String): Boolean = try {
        Runtime.getRuntime().exec(arrayOf("su", "-c", command)).waitFor() == 0
    } catch (e: Exception) {
        Log.w(TAG, "su -c '$command' failed: ${e.message}")
        false
    }

    private fun startShutdownActivity(context: Context, action: Action): Boolean = try {
        context.startActivity(
            Intent(action.intentAction)
                .putExtra("android.intent.extra.KEY_CONFIRM", false)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        true
    } catch (e: Exception) {
        Log.w(TAG, "${action.intentAction} failed: ${e.message}")
        false
    }

    private fun isDryRun(): Boolean = try {
        Class.forName("android.os.SystemProperties")
            .getMethod("get", String::class.java)
            .invoke(null, DRY_RUN_PROP) == "1"
    } catch (_: Exception) {
        false
    }
}

/**
 * Replaces Android's power menu with ours ([onShow]). Register it in onCreate and unregister it in onDestroy.
 * It also works when another app (for example the Settings pairing dialog) is in front: then it brings our activity
 * to the front.
 */
class PowerMenuInterceptor(private val activity: Activity, private val onShow: () -> Unit) {

    private val main = Handler(Looper.getMainLooper())
    // The receiver runs here, not on the main thread, so the close is not late when the UI thread is busy
    private val thread = HandlerThread("y1-power-menu").apply { start() }
    private val background = Handler(thread.looper)
    private var resumed = false
    private val closeAgain = Runnable { PowerMenu.closeAndroidMenu(activity) }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (!PowerMenu.isAndroidPowerMenu(intent.getStringExtra(PowerMenu.EXTRA_REASON))) return
            PowerMenu.closeAndroidMenu(activity)
            for (delay in PowerMenu.CLOSE_AGAIN_MS) background.postDelayed(closeAgain, delay)
            main.post {
                onShow()
                if (!resumed) {
                    activity.startActivity(
                        Intent(activity, activity.javaClass)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                    )
                }
            }
        }
    }

    private val lifecycle = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityResumed(a: Activity) { if (a === activity) resumed = true }
        override fun onActivityPaused(a: Activity) { if (a === activity) resumed = false }
        override fun onActivityCreated(a: Activity, b: Bundle?) {}
        override fun onActivityStarted(a: Activity) {}
        override fun onActivityStopped(a: Activity) {}
        override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
        override fun onActivityDestroyed(a: Activity) {}
    }

    fun register() {
        activity.registerReceiver(receiver, IntentFilter(Intent.ACTION_CLOSE_SYSTEM_DIALOGS), null, background)
        activity.application.registerActivityLifecycleCallbacks(lifecycle)
        background.post { PowerMenu.turnOffWindowAnimations(activity.applicationContext) }
    }

    fun unregister() {
        background.removeCallbacks(closeAgain)
        try {
            activity.unregisterReceiver(receiver)
        } catch (_: Exception) {}
        activity.application.unregisterActivityLifecycleCallbacks(lifecycle)
        thread.quit()
    }
}
