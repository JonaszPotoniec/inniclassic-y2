package com.themoon.y1.managers

import android.content.Context
import android.os.PowerManager
import android.util.Log

/**
 * Keeps the CPU awake while the media library scan runs. Without it, the scan almost stops when the screen goes off
 * (the backlight timer or the Power button): Android lets the CPU sleep when no wake lock is held.
 * The scan releases it when it ends, also after an error; [TIMEOUT_MS] is a safety net for a scan that never ends.
 */
class ScanWakeLock(private val lock: Lock?) {

    interface Lock {
        val isHeld: Boolean
        fun acquire(timeoutMs: Long)
        fun release()
    }

    fun acquire() {
        try {
            lock?.acquire(TIMEOUT_MS)
        } catch (t: Throwable) {
            Log.w(TAG, "acquire: $t")
        }
    }

    fun release() {
        try {
            if (lock?.isHeld == true) lock.release()
        } catch (t: Throwable) {
            Log.w(TAG, "release: $t")
        }
    }

    companion object {
        private const val TAG = "ScanWakeLock"

        /** Longer than a full scan of a large card (tags of each file read on the device). */
        const val TIMEOUT_MS: Long = 60 * 60 * 1000L

        fun forScan(context: Context): ScanWakeLock {
            val pm = context.applicationContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
            val wakeLock = try {
                pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "y1:library-scan")?.apply { setReferenceCounted(false) }
            } catch (t: Throwable) {
                null
            }
            return ScanWakeLock(wakeLock?.let { w ->
                object : Lock {
                    override val isHeld: Boolean get() = w.isHeld
                    override fun acquire(timeoutMs: Long) = w.acquire(timeoutMs)
                    override fun release() = w.release()
                }
            })
        }
    }
}
