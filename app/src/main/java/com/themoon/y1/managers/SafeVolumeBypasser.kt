package com.themoon.y1.managers

import android.content.Context
import android.media.AudioManager
import android.os.IBinder
import android.provider.Settings
import android.util.Log

/**
 * Bypasses Android's Safe Media Volume limit (~80% volume cap on headphones).
 */
object SafeVolumeBypasser {
    const val TAG = "SafeVolumeBypasser"
    const val KEY_AUDIO_SAFE_VOLUME_STATE = "audio_safe_volume_state"
    const val VALUE_SAFE_VOLUME_STATE_DISABLED = 2
    const val KEY_SAFE_MEDIA_VOLUME_ENABLED = "safe_media_volume_enabled"
    const val VALUE_SAFE_MEDIA_VOLUME_DISABLED = 0

    @Volatile
    private var sRootInitialized: Boolean = false

    val isRootInitialized: Boolean
        get() = sRootInitialized

    /**
     * Programmatic bypass using reflection on AudioManager, IAudioService, and Settings.
     */

    fun bypass(context: Context?, audioManager: AudioManager?) {
        if (context == null) return

        if (audioManager != null) {
            try {
                val m = AudioManager::class.java.getMethod("disableSafeMediaVolume")
                m.isAccessible = true
                m.invoke(audioManager)
            } catch (t1: Throwable) {
                try {
                    val m = AudioManager::class.java.getMethod("disableSafeMediaVolume", String::class.java)
                    m.isAccessible = true
                    m.invoke(audioManager, context.packageName)
                } catch (ignored: Throwable) {}
            }
        }

        try {
            val smClass = Class.forName("android.os.ServiceManager")
            val getService = smClass.getMethod("getService", String::class.java)
            val binder = getService.invoke(null, Context.AUDIO_SERVICE) as? IBinder
            if (binder != null) {
                val stubClass = Class.forName("android.media.IAudioService\$Stub")
                val asInterface = stubClass.getMethod("asInterface", IBinder::class.java)
                val audioService = asInterface.invoke(null, binder)
                if (audioService != null) {
                    try {
                        val m = audioService.javaClass.getMethod("disableSafeMediaVolume", String::class.java)
                        m.isAccessible = true
                        m.invoke(audioService, context.packageName)
                    } catch (e: NoSuchMethodException) {
                        val m = audioService.javaClass.getMethod("disableSafeMediaVolume")
                        m.isAccessible = true
                        m.invoke(audioService)
                    }
                }
            }
        } catch (ignored: Throwable) {}

        try {
            Settings.Global.putInt(context.contentResolver, KEY_AUDIO_SAFE_VOLUME_STATE, VALUE_SAFE_VOLUME_STATE_DISABLED)
        } catch (ignored: Throwable) {}
        try {
            Settings.Global.putInt(context.contentResolver, KEY_SAFE_MEDIA_VOLUME_ENABLED, VALUE_SAFE_MEDIA_VOLUME_DISABLED)
        } catch (ignored: Throwable) {}
        try {
            Settings.System.putInt(context.contentResolver, KEY_AUDIO_SAFE_VOLUME_STATE, VALUE_SAFE_VOLUME_STATE_DISABLED)
        } catch (ignored: Throwable) {}
        try {
            Settings.System.putInt(context.contentResolver, KEY_SAFE_MEDIA_VOLUME_ENABLED, VALUE_SAFE_MEDIA_VOLUME_DISABLED)
        } catch (ignored: Throwable) {}
    }

    /**
     * Executes root commands asynchronously in the background. Never blocks the UI thread.
     */

    fun bypassWithRoot(context: Context?, audioManager: AudioManager?) {
        if (sRootInitialized) return
        Thread({
            try {
                val pkg = context?.packageName ?: "com.themoon.y1"
                val cmd = "settings put global $KEY_AUDIO_SAFE_VOLUME_STATE $VALUE_SAFE_VOLUME_STATE_DISABLED && " +
                        "settings put system $KEY_AUDIO_SAFE_VOLUME_STATE $VALUE_SAFE_VOLUME_STATE_DISABLED && " +
                        "settings put global $KEY_SAFE_MEDIA_VOLUME_ENABLED $VALUE_SAFE_MEDIA_VOLUME_DISABLED && " +
                        "settings put system $KEY_SAFE_MEDIA_VOLUME_ENABLED $VALUE_SAFE_MEDIA_VOLUME_DISABLED && " +
                        "pm grant $pkg android.permission.STATUS_BAR_SERVICE 2>/dev/null && " +
                        "sync"
                runSu(cmd)
                bypass(context, audioManager)
                sRootInitialized = true
            } catch (t: Throwable) {
                Log.w(TAG, "Root safe volume bypass error: " + t.message)
            }
        }, "safe-vol-root-bypass").start()
    }

    /**
     * Sets stream volume directly and non-blockingly without any UI freeze.
     */

    fun setStreamVolumeWithBypass(
        context: Context?,
        audioManager: AudioManager?,
        stream: Int,
        targetVol: Int,
        flags: Int
    ) {
        audioManager?.setStreamVolume(stream, targetVol, flags)
    }

    /**
     * Initializes safe volume bypass on application boot/launch.
     */

    fun init(context: Context?, audioManager: AudioManager?) {
        bypass(context, audioManager)
        bypassWithRoot(context, audioManager)
    }

    private fun runSu(cmd: String) {
        var p: Process? = null
        try {
            p = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
            val proc = p
            val deadline = System.currentTimeMillis() + 1500
            while (System.currentTimeMillis() < deadline) {
                try {
                    proc.exitValue()
                    break
                } catch (running: IllegalThreadStateException) {
                    try {
                        Thread.sleep(50)
                    } catch (ie: InterruptedException) {
                        Thread.currentThread().interrupt()
                        break
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "su exec failed: " + e.message)
        } finally {
            if (p != null) {
                try {
                    p.destroy()
                } catch (ignored: Exception) {}
            }
        }
    }
}
