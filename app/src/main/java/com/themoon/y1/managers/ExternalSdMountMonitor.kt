package com.themoon.y1.managers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import com.themoon.y1.StoragePaths
import java.io.BufferedReader
import java.io.File
import java.io.FileReader
import java.io.InputStreamReader
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Y2 MicroSD watchdog: detects when /storage/sdcard1 is not usable and
 * attempts to remount via vdc/fuse_sdcard1 (requires root).
 *
 * Safe on Y1: if the secondary path does not exist, the monitor is a no-op.
 */
class ExternalSdMountMonitor(context: Context) {

    interface Listener {
        /** Called on a background thread after a successful remount. */
        fun onSecondaryStorageReady()
    }

    private val appContext: Context = context.applicationContext
    private var bgThread: HandlerThread? = null
    private var bgHandler: Handler? = null
    private val running = AtomicBoolean(false)
    private val mountInFlight = AtomicBoolean(false)
    private val suspended = AtomicBoolean(false)
    private var listener: Listener? = null
    private var mediaReceiver: BroadcastReceiver? = null
    private var usbReceiver: BroadcastReceiver? = null
    private var lastMountAttemptMs: Long = 0L
    private var lastPriReady = false
    private var lastSecReady = false

    /** While true (USB storage shared with PC) the watchdog never remounts/starts FUSE. */
    fun setSuspended(s: Boolean) {
        suspended.set(s)
        if (!s) {
            // Re-baseline so the remount after UMS is reported as "newly mounted" exactly once.
            lastSecReady = false
            bgHandler?.postDelayed({
                checkAndMaybeMount("ums_resume")
            }, 1500)
        }
    }

    private val pollTask = object : Runnable {
        override fun run() {
            if (!running.get()) return
            checkAndMaybeMount("poll")
            if (bgHandler != null && running.get()) {
                bgHandler?.postDelayed(this, POLL_MS)
            }
        }
    }

    fun setListener(listener: Listener?) {
        this.listener = listener
    }

    fun start() {
        if (!running.compareAndSet(false, true)) return

        lastPriReady = isPrimaryReady()
        lastSecReady = isSecondaryReady()

        if (bgThread == null || bgThread?.isAlive != true) {
            val thread = HandlerThread("y1-sd-monitor-bg")
            thread.start()
            bgThread = thread
            bgHandler = Handler(thread.looper)
        }

        registerMediaReceiver()
        registerUsbReceiver()
        bgHandler?.postDelayed(pollTask, POLL_MS)
    }

    fun stop() {
        running.set(false)
        bgHandler?.removeCallbacks(pollTask)
        bgHandler = null
        bgThread?.quit()
        bgThread = null
        unregisterMediaReceiver()
        unregisterUsbReceiver()
    }

    private fun checkAndMaybeMount(reason: String) {
        if (suspended.get()) return
        val priReady = isPrimaryReady()
        val secReady = isSecondaryReady()

        val newlyMounted = (priReady && !lastPriReady) || (secReady && !lastSecReady)

        lastPriReady = priReady
        lastSecReady = secReady

        if (newlyMounted) {
            Log.i(TAG, "Storage volume newly mounted (pri=$priReady, sec=$secReady, reason=$reason)")
            StoragePaths.invalidate()
            notifyReady()
        }

        val secNeeded = hasSecondarySlot() && !secReady
        if (!secNeeded) return

        val isBroadcastEvent = reason.startsWith("broadcast:") || reason.startsWith("usb_disconnect:")
        val now = System.currentTimeMillis()
        if (!isBroadcastEvent && (now - lastMountAttemptMs < MOUNT_COOLDOWN_MS)) return
        if (!mountInFlight.compareAndSet(false, true)) return
        lastMountAttemptMs = now

        try {
            Log.i(TAG, "Secondary storage not ready ($reason) — attempting mount")
            attemptRemount()
            val afterSec = isSecondaryReady()
            if (afterSec && !secReady) {
                Log.i(TAG, "Secondary remount succeeded")
                StoragePaths.invalidate()
                lastSecReady = afterSec
                notifyReady()
            }
        } finally {
            mountInFlight.set(false)
        }
    }

    private fun attemptRemount(): Boolean {
        if (hasSecondarySlot() && !isSecondaryReady()) {
            runSuTimed("vdc volume mount sdcard1 2>/dev/null; start fuse_sdcard1 2>/dev/null")
        }
        return isSecondaryReady()
    }

    private fun notifyReady() {
        val l = listener ?: return
        Thread({
            try {
                l.onSecondaryStorageReady()
            } catch (e: Exception) {
                Log.w(TAG, "listener failed", e)
            }
        }, "y1-sd-ready").start()
    }

    private fun registerMediaReceiver() {
        if (mediaReceiver != null) return
        mediaReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val action = intent?.action ?: return
                if (Intent.ACTION_MEDIA_MOUNTED == action ||
                    Intent.ACTION_MEDIA_UNMOUNTED == action ||
                    Intent.ACTION_MEDIA_REMOVED == action ||
                    Intent.ACTION_MEDIA_BAD_REMOVAL == action ||
                    Intent.ACTION_MEDIA_EJECT == action ||
                    Intent.ACTION_MEDIA_NOFS == action ||
                    Intent.ACTION_MEDIA_UNMOUNTABLE == action ||
                    "android.intent.action.MEDIA_CHECKING" == action
                ) {
                    StoragePaths.invalidate()
                    Thread({
                        checkAndMaybeMount("broadcast:$action")
                    }, "y1-sd-bcast").start()
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_MEDIA_MOUNTED)
            addAction(Intent.ACTION_MEDIA_UNMOUNTED)
            addAction(Intent.ACTION_MEDIA_REMOVED)
            addAction(Intent.ACTION_MEDIA_BAD_REMOVAL)
            addAction(Intent.ACTION_MEDIA_EJECT)
            addAction(Intent.ACTION_MEDIA_NOFS)
            addAction(Intent.ACTION_MEDIA_UNMOUNTABLE)
            addAction("android.intent.action.MEDIA_CHECKING")
            addDataScheme("file")
        }
        try {
            appContext.registerReceiver(mediaReceiver, filter)
        } catch (e: Exception) {
            Log.w(TAG, "registerMediaReceiver failed", e)
        }
    }

    private fun unregisterMediaReceiver() {
        if (mediaReceiver == null) return
        try {
            appContext.unregisterReceiver(mediaReceiver)
        } catch (ignored: Exception) {}
        mediaReceiver = null
    }

    private fun registerUsbReceiver() {
        if (usbReceiver != null) return
        usbReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val action = intent?.action ?: return
                var trigger = false
                if ("android.hardware.usb.action.USB_STATE" == action) {
                    val connected = intent.getBooleanExtra("connected", false)
                    if (!connected) {
                        trigger = true
                    }
                } else if ("android.intent.action.UMS_DISCONNECTED" == action) {
                    trigger = true
                }

                if (trigger) {
                    Log.i(TAG, "USB disconnected event ($action) — scheduling self-healing check in 500ms")
                    bgHandler?.postDelayed({
                        checkAndMaybeMount("usb_disconnect:$action")
                    }, 500)
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction("android.hardware.usb.action.USB_STATE")
            addAction("android.intent.action.UMS_DISCONNECTED")
        }
        try {
            appContext.registerReceiver(usbReceiver, filter)
        } catch (e: Exception) {
            Log.w(TAG, "registerUsbReceiver failed", e)
        }
    }

    private fun unregisterUsbReceiver() {
        if (usbReceiver == null) return
        try {
            appContext.unregisterReceiver(usbReceiver)
        } catch (ignored: Exception) {}
        usbReceiver = null
    }

    companion object {
        private const val TAG = "Y1SdMount"
        private const val POLL_MS = 12000L
        private const val MOUNT_COOLDOWN_MS = 30000L
        private const val CMD_TIMEOUT_MS = 8000

        /** True when primary storage is usable and readable. */
        @JvmStatic
        fun isPrimaryReady(): Boolean {
            return try {
                val pri = StoragePaths.getPrimaryRoot()
                pri != null && pri.exists() && pri.isDirectory && pri.canRead()
            } catch (e: Exception) {
                false
            }
        }

        /** True when apps can list the secondary volume (/storage/sdcard1). */
        @JvmStatic
        fun isSecondaryReady(): Boolean {
            if (!hasSecondarySlot()) return false
            return try {
                val sec = File(StoragePaths.SECONDARY_PATH)
                if (!sec.exists() || !sec.isDirectory || !sec.canRead()) return false
                val list = sec.list()
                list != null
            } catch (e: Exception) {
                false
            }
        }

        /** True only if a physical secondary SD card device exists in hardware. */
        @JvmStatic
        fun hasSecondarySlot(): Boolean {
            return try {
                File("/sys/block/mmcblk1").exists() || File("/dev/block/mmcblk1").exists()
            } catch (e: Exception) {
                false
            }
        }

        @JvmStatic
        fun mountsContain(path: String): Boolean {
            var br: BufferedReader? = null
            try {
                br = BufferedReader(FileReader("/proc/mounts"))
                var line: String?
                while (br.readLine().also { line = it } != null) {
                    val parts = line!!.split(" ")
                    if (parts.size > 1 && parts[1] == path) {
                        return true
                    }
                }
            } catch (ignored: Exception) {
            } finally {
                try { br?.close() } catch (ignored: Exception) {}
            }
            return false
        }

        @JvmStatic
        fun readProp(key: String): String {
            var p: Process? = null
            var br: BufferedReader? = null
            return try {
                p = Runtime.getRuntime().exec(arrayOf("getprop", key))
                br = BufferedReader(InputStreamReader(p.inputStream))
                val line = br.readLine()
                p.waitFor()
                line?.trim() ?: ""
            } catch (e: Exception) {
                ""
            } finally {
                try { br?.close() } catch (ignored: Exception) {}
                p?.destroy()
            }
        }

        @JvmStatic
        fun runSuTimed(cmd: String): String {
            var p: Process? = null
            val out = StringBuilder()
            try {
                p = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
                val proc = p
                val reader = Thread({
                    var br: BufferedReader? = null
                    try {
                        br = BufferedReader(InputStreamReader(proc.inputStream))
                        var line: String?
                        while (br.readLine().also { line = it } != null) {
                            if (out.isNotEmpty()) {
                                out.append('\n')
                            }
                            out.append(line)
                        }
                    } catch (ignored: Exception) {
                    } finally {
                        try { br?.close() } catch (ignored: Exception) {}
                    }
                }, "y1-sd-su-out")
                reader.start()

                val deadline = System.currentTimeMillis() + CMD_TIMEOUT_MS
                while (System.currentTimeMillis() < deadline) {
                    try {
                        proc.exitValue()
                        break
                    } catch (stillRunning: IllegalThreadStateException) {
                        try {
                            Thread.sleep(100)
                        } catch (ie: InterruptedException) {
                            Thread.currentThread().interrupt()
                            break
                        }
                    }
                }
                try {
                    proc.exitValue()
                } catch (stillRunning: IllegalThreadStateException) {
                    proc.destroy()
                    Log.w(TAG, "su command timed out")
                }
                try {
                    reader.join(500)
                } catch (ignored: InterruptedException) {}
            } catch (e: Exception) {
                Log.w(TAG, "su failed: " + e.message)
            } finally {
                p?.destroy()
            }
            return out.toString()
        }
    }
}
