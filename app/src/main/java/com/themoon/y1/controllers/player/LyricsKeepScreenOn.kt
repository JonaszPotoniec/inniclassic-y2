package com.themoon.y1.controllers.player

import android.content.Context
import android.os.PowerManager
import android.os.SystemClock
import com.themoon.y1.MainActivity

/**
 * Settings → Now Playing → Lyrics Keep Screen On (default On): the screen stays on while the lyrics view shows on
 * Now Playing and music plays. On pause, or when the lyrics view or Now Playing closes, the Backlight Timer applies again.
 */
object LyricsKeepScreenOn {
    /** AppPrefs key. */
    const val PREF = "lyrics_keep_screen_on"
    const val DEFAULT = true

    fun shouldHold(setting: Boolean, lyricsVisible: Boolean, playing: Boolean): Boolean =
        setting && lyricsVisible && playing
}

/**
 * Holds the screen on for [LyricsKeepScreenOn] with a screen wake lock, not with FLAG_KEEP_SCREEN_ON: the web server,
 * USB storage and the test interface set and clear that flag, so a clear by one of them would end the hold of another.
 *
 * The lock has a time limit and each progress tick renews it, so it also ends when the ticks stop. ON_AFTER_RELEASE
 * starts Android's screen timeout again at the release: after a pause the screen stays on for the full Backlight Timer.
 * While the lock holds, the app's own backlight timer (PowerBacklightManager) also starts again at each renewal.
 */
class LyricsScreenHold(private val context: Context) {
    private var lock: PowerManager.WakeLock? = null
    private var renewedAt = 0L

    val isHeld: Boolean
        get() = try {
            lock?.isHeld == true
        } catch (_: Throwable) {
            false
        }

    fun update(hold: Boolean) {
        if (hold) renew() else release()
    }

    private fun renew() {
        val now = SystemClock.uptimeMillis()
        if (isHeld && now - renewedAt < RENEW_MS) return
        renewedAt = now
        try {
            @Suppress("DEPRECATION") // SCREEN_BRIGHT_WAKE_LOCK: the only screen lock without a window flag (API 19)
            val l = lock ?: (context.getSystemService(Context.POWER_SERVICE) as PowerManager).newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ON_AFTER_RELEASE,
                "Y1:LyricsKeepScreenOn"
            ).also {
                it.setReferenceCounted(false)
                lock = it
            }
            l.acquire(LOCK_MS)
        } catch (_: Throwable) {}
        restartAppTimer()
    }

    private fun release() {
        if (!isHeld) return
        try {
            lock?.release()
        } catch (_: Throwable) {}
        restartAppTimer()
    }

    /** As after a key: only while the app shows its screen (not after a screen off). */
    private fun restartAppTimer() {
        val main = MainActivity.instance ?: return
        if (!main.isScreenSleeping && !main.isFakeScreenOff) main.powerBacklightManager?.resetTimer()
    }

    private companion object {
        /** Progress ticks come every 500 ms while music plays; the lock and the app timer renew less often. */
        const val RENEW_MS = 5_000L
        const val LOCK_MS = 15_000L
    }
}
