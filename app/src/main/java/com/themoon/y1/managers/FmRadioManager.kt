package com.themoon.y1.managers

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import dalvik.system.PathClassLoader
import java.io.File
import java.lang.reflect.Method

/**
 * FM Radio manager communicating with MediaTek FMRadioNative JNI driver,
 * audio routing via MediaPlayer and wake lock management.
 */
class FmRadioManager private constructor(context: Context) {

    private val context: Context = context.applicationContext
    private val audioManager: AudioManager? = this.context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    var isDeviceOpen: Boolean = false
    var isPowerUp: Boolean = false
    var currentFreq: Float = 87.5f
    var isSpeakerOn: Boolean = false
    var lastError: String = ""

    private var fmNativeClass: Class<*>? = null
    private var fmPlayer: MediaPlayer? = null
    private var radioWakeLock: PowerManager.WakeLock? = null
    private var currentMasterVolume: Float = 1.0f

    /**
     * Called (main thread) before the FM audio starts, with the start as argument. MainActivity sets the volume for
     * FM (the index does the attenuation) and runs the start when the index is down. Null = start at once.
     */
    var volumeGate: ((Runnable) -> Unit)? = null

    /** Called after FM powers down (MainActivity sets the music volume again). */
    var onPoweredDown: (() -> Unit)? = null

    init {
        try {
            fmNativeClass = Class.forName("com.mediatek.FMRadio.FMRadioNative")
        } catch (t1: Throwable) {
            val jarPaths = arrayOf(
                "/system/framework/mediatek-framework.jar",
                "/system/framework/custom_ext.jar",
                "/system/framework/com.mediatek.hardware.jar"
            )
            for (path in jarPaths) {
                if (File(path).exists()) {
                    try {
                        val cl = PathClassLoader(path, ClassLoader.getSystemClassLoader())
                        fmNativeClass = Class.forName("com.mediatek.FMRadio.FMRadioNative", true, cl)
                        break
                    } catch (t2: Throwable) {}
                }
            }
            if (fmNativeClass == null) {
                val packages = arrayOf("com.mediatek.FMRadio", "com.innioasis.fm", "com.innioasis.y1")
                for (pkg in packages) {
                    try {
                        val info = this.context.packageManager.getApplicationInfo(pkg, 0)
                        val cl = PathClassLoader(info.sourceDir, ClassLoader.getSystemClassLoader())
                        fmNativeClass = Class.forName("com.mediatek.FMRadio.FMRadioNative", true, cl)
                        break
                    } catch (t3: Throwable) {}
                }
            }
        }

        if (fmNativeClass == null) {
            lastError = "FMRadioNative Driver completely missing."
        } else {
            try {
                System.loadLibrary("fmjni")
            } catch (t: Throwable) {
                try {
                    System.load("/system/lib/libfmjni.so")
                } catch (ignore: Throwable) {}
            }
        }
    }

    fun setMasterVolume(volume: Float) {
        this.currentMasterVolume = Math.max(0.0f, Math.min(1.0f, volume))
        applyMasterVolume()
    }

    fun getMasterVolume(): Float {
        return currentMasterVolume
    }

    fun applyMasterVolume() {
        val player = fmPlayer ?: return
        try {
            player.setVolume(currentMasterVolume, currentMasterVolume)
        } catch (ignored: Throwable) {}
    }

    /** Stream used for FM volume keys / UI (MediaPlayer path -> MUSIC). */
    fun getFmStreamType(): Int {
        return AudioManager.STREAM_MUSIC
    }

    /**
     * Read airplane mode on both pre- and post-API-17 storage locations.
     */
    fun isAirplaneModeOn(): Boolean {
        if (Build.VERSION.SDK_INT >= 17) {
            try {
                if (Settings.Global.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) != 0) {
                    return true
                }
            } catch (ignored: Throwable) {}
        }
        try {
            if (Settings.System.getInt(context.contentResolver, Settings.System.AIRPLANE_MODE_ON, 0) != 0) {
                return true
            }
        } catch (ignored: Throwable) {}
        return false
    }

    /**
     * Force airplane mode OFF before engaging the FM chip.
     */
    fun ensureAirplaneModeOff(): Boolean {
        if (!isAirplaneModeOn()) return true

        Log.i(TAG, "Airplane mode is ON — disabling before FM (sdk=" + Build.VERSION.SDK_INT + ")")

        try {
            if (Build.VERSION.SDK_INT >= 17) {
                Settings.Global.putInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Global.putInt airplane failed: " + t.message)
        }
        try {
            Settings.System.putInt(context.contentResolver, Settings.System.AIRPLANE_MODE_ON, 0)
        } catch (t: Throwable) {
            Log.w(TAG, "System.putInt airplane failed: " + t.message)
        }

        try {
            val intent = Intent(Intent.ACTION_AIRPLANE_MODE_CHANGED)
            intent.putExtra("state", false)
            context.sendBroadcast(intent)
        } catch (t: Throwable) {
            Log.w(TAG, "AIRPLANE_MODE_CHANGED broadcast failed: " + t.message)
        }

        runSuQuiet(
            "settings put global airplane_mode_on 0; " +
                    "settings put system airplane_mode_on 0; " +
                    "setprop persist.radio.airplane_mode_on 0; " +
                    "setprop ril.flightmode 0; " +
                    "am broadcast -a android.intent.action.AIRPLANE_MODE --ez state false"
        )

        try {
            Thread.sleep(400)
        } catch (ie: InterruptedException) {
            Thread.currentThread().interrupt()
        }

        val stillOn = isAirplaneModeOn()
        if (stillOn) {
            lastError = "Could not disable Airplane Mode (required for FM)."
            Log.w(TAG, lastError)
        }
        return !stillOn
    }

    fun prepareFmHardware() {
        try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            am?.killBackgroundProcesses("com.mediatek.FMRadio")
            am?.killBackgroundProcesses("com.innioasis.fm")
            am?.killBackgroundProcesses("com.android.fmradio")
        } catch (ignored: Throwable) {}

        try {
            if (File("/system/xbin/daemonsu").exists()) {
                Runtime.getRuntime().exec(arrayOf("/system/xbin/daemonsu", "--auto-daemon"))
            }
        } catch (ignored: Throwable) {}

        val unblockCmd =
            "am force-stop com.mediatek.FMRadio 2>/dev/null; " +
                    "am force-stop com.innioasis.fm 2>/dev/null; " +
                    "am force-stop com.android.fmradio 2>/dev/null; " +
                    "kill $(pidof com.mediatek.FMRadio) 2>/dev/null; " +
                    "kill $(pidof com.innioasis.fm) 2>/dev/null; " +
                    "pkill -9 -f FMRadio 2>/dev/null; " +
                    "killall -9 com.mediatek.FMRadio 2>/dev/null; " +
                    "[ ! -c /dev/fm ] && mknod /dev/fm c 193 0 2>/dev/null; " +
                    "chmod 666 /dev/fm 2>/dev/null; " +
                    "chmod 666 /dev/FM50AF 2>/dev/null; " +
                    "chown system:media /dev/fm 2>/dev/null" +
                    (if (CellRadioManager.airplaneModeSparesFm(context)) "" else
                        "; setprop persist.radio.airplane_mode_on 0 2>/dev/null" +
                        "; setprop ril.flightmode 0 2>/dev/null")

        runSuQuiet(unblockCmd)
    }

    @Throws(NoSuchMethodException::class)
    private fun getNativeMethod(name: String, vararg parameterTypes: Class<*>): Method {
        var clazz = fmNativeClass
        while (clazz != null) {
            try {
                val m = clazz.getDeclaredMethod(name, *parameterTypes)
                m.isAccessible = true
                return m
            } catch (e: NoSuchMethodException) {
                clazz = clazz.superclass
            }
        }
        throw NoSuchMethodException(name)
    }

    fun routeFmAudio() {
        try {
            val setForceUse = Class.forName("android.media.AudioSystem")
                .getDeclaredMethod("setForceUse", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            setForceUse.isAccessible = true
            setForceUse.invoke(null, 5, 1)
        } catch (t: Throwable) {
            Log.w(TAG, "routeFmAudio failed: " + t.message)
        }
    }

    private fun startFmAudio() {
        try {
            if (fmPlayer != null) {
                fmPlayer?.release()
            }
            val player = MediaPlayer()
            fmPlayer = player
            player.setWakeMode(context, PowerManager.PARTIAL_WAKE_LOCK)

            try {
                val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
                if (pm != null) {
                    if (radioWakeLock == null) {
                        radioWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Y1:FmRadioLock")
                    }
                    if (radioWakeLock?.isHeld != true) {
                        radioWakeLock?.acquire()
                    }
                }
            } catch (ignored: Exception) {}

            player.setDataSource("MEDIATEK://MEDIAPLAYER_PLAYERTYPE_FM")

            var streamFm = 10
            try {
                streamFm = AudioManager::class.java.getDeclaredField("STREAM_FM").get(null) as Int
            } catch (ignored: Exception) {}

            player.setAudioStreamType(streamFm)
            // VirtualVolumeManager gives each later gain change through setMasterVolume
            setMasterVolume(VirtualVolumeManager.getInstance().getMasterGain())
            player.prepare()
            val start = Runnable {
                if (fmPlayer !== player || !isPowerUp) return@Runnable
                try {
                    player.start()
                    applyMasterVolume()
                    routeFmAudio()
                } catch (t: Throwable) {
                    lastError = "Audio Routing Failed: " + t.message
                }
            }
            val gate = volumeGate
            if (gate != null) gate(start) else start.run()
        } catch (t: Throwable) {
            lastError = "Audio Routing Failed: " + t.message
        }
    }

    private fun stopFmAudio() {
        try {
            if (radioWakeLock?.isHeld == true) {
                radioWakeLock?.release()
            }
        } catch (ignored: Exception) {}

        val player = fmPlayer
        if (player != null) {
            try {
                if (player.isPlaying) player.stop()
                player.release()
            } catch (ignored: Throwable) {}
            fmPlayer = null
        }
    }

    fun powerUp(freq: Float): Boolean {
        if (fmNativeClass == null) {
            if (lastError.isEmpty()) lastError = "Driver class is null."
            return false
        }
        AudioSources.app.radioStarts()
        try {
            if (CellRadioManager.airplaneModeSparesFm(context)) {
                Log.i(TAG, "Airplane mode affects only the cell radio; FM keeps it")
            } else if (!ensureAirplaneModeOff()) {
                Log.w(TAG, "Continuing FM powerUp despite airplane-mode clear uncertainty")
            }

            prepareFmHardware()
            Thread.sleep(200)

            try {
                val closeDev = getNativeMethod("closedev")
                closeDev.invoke(null)
            } catch (ignore: Throwable) {}

            isDeviceOpen = false

            val openDev = getNativeMethod("opendev")
            for (attempt in 0 until 3) {
                try {
                    isDeviceOpen = openDev.invoke(null) as Boolean
                } catch (t: Throwable) {
                    Log.w(TAG, "opendev exception attempt $attempt: " + t.message)
                }
                if (isDeviceOpen) break

                try {
                    val closeDev = getNativeMethod("closedev")
                    closeDev.invoke(null)
                } catch (ignore: Throwable) {}

                prepareFmHardware()
                Thread.sleep(250)
            }

            if (!isDeviceOpen) {
                val fmDev = File("/dev/fm")
                lastError = "Failed to open /dev/fm (exists=" + fmDev.exists() +
                        ", r=" + fmDev.canRead() + ", w=" + fmDev.canWrite() + ")"
                Log.e(TAG, lastError)
                return false
            }

            val powerUpMethod = getNativeMethod("powerup", Float::class.javaPrimitiveType!!)
            isPowerUp = powerUpMethod.invoke(null, freq) as Boolean

            if (isPowerUp) {
                currentFreq = freq
                setMute(false)
                startFmAudio()
            } else {
                try {
                    val switchAntenna = getNativeMethod("switchAntenna", Int::class.javaPrimitiveType!!)
                    switchAntenna.invoke(null, 1)

                    isPowerUp = powerUpMethod.invoke(null, freq) as Boolean
                    if (isPowerUp) {
                        currentFreq = freq
                        setMute(false)
                        startFmAudio()
                    } else {
                        lastError = "Power up rejected by Hardware."
                    }
                } catch (ex: Throwable) {
                    lastError = "Earphones required and Antenna bypass failed."
                }
            }
            return isPowerUp
        } catch (t: Throwable) {
            lastError = "Exception: " + t.javaClass.simpleName + " - " + t.message
        }
        return false
    }

    fun powerDown() {
        if (fmNativeClass == null || !isPowerUp) return
        try {
            stopFmAudio()
            setMute(true)
            val powerDownMethod = getNativeMethod("powerdown", Int::class.javaPrimitiveType!!)
            powerDownMethod.invoke(null, 0)

            val closeDev = getNativeMethod("closedev")
            closeDev.invoke(null)

            isPowerUp = false
            isDeviceOpen = false
        } catch (ignored: Throwable) {}
        if (!isPowerUp) onPoweredDown?.invoke()
    }

    fun tune(freq: Float): Boolean {
        val roundedFreq = Math.round(freq * 10.0f) / 10.0f
        currentFreq = roundedFreq
        if (fmNativeClass == null || !isPowerUp) return false
        return try {
            val tuneMethod = getNativeMethod("tune", Float::class.javaPrimitiveType!!)
            tuneMethod.invoke(null, roundedFreq) as Boolean
        } catch (e: Throwable) {
            false
        }
    }

    fun seekStation(isUp: Boolean): Float {
        if (fmNativeClass == null || !isPowerUp) return currentFreq
        try {
            val seekMethod = getNativeMethod("seek", Float::class.javaPrimitiveType!!, Boolean::class.javaPrimitiveType!!)
            var found = seekMethod.invoke(null, currentFreq, isUp) as Float
            if (found > 0) {
                found = Math.round(found * 10.0f) / 10.0f
                currentFreq = found
                return found
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Hardware seek failed: " + t.message)
        }
        return currentFreq
    }

    fun autoScan(): FloatArray? {
        if (fmNativeClass == null || !isPowerUp) {
            lastError = "Turn on the radio first."
            return null
        }
        try {
            val autoScanMethod = getNativeMethod("autoscan")
            val result = autoScanMethod.invoke(null) as? ShortArray

            if (result != null && result.isNotEmpty()) {
                val freqs = FloatArray(result.size)
                for (i in result.indices) {
                    freqs[i] = Math.round((result[i] / 10.0f) * 10.0f) / 10.0f
                }
                return freqs
            }
        } catch (t: Throwable) {
            lastError = "AutoScan failed: " + t.message
        }
        return null
    }

    fun setMute(mute: Boolean) {
        if (fmNativeClass == null) return
        try {
            val setMuteMethod = getNativeMethod("setmute", Boolean::class.javaPrimitiveType!!)
            setMuteMethod.invoke(null, mute)
        } catch (ignored: Throwable) {}
    }

    fun setSpeaker(useSpeaker: Boolean) {
        routeFmAudio()
        isSpeakerOn = useSpeaker
    }

    companion object {
        private const val TAG = "Y1FmRadio"

        @Volatile
        private var instance: FmRadioManager? = null
        @Synchronized
        fun getInstance(context: Context): FmRadioManager {
            var inst = instance
            if (inst == null) {
                inst = FmRadioManager(context)
                instance = inst
            }
            return inst
        }
        fun getInstance(): FmRadioManager? {
            return instance
        }

        internal fun runSuQuiet(cmd: String): Boolean {
            val suCandidates = arrayOf("su", "/system/xbin/su", "/system/bin/su")
            for (suBin in suCandidates) {
                var p: Process? = null
                try {
                    p = Runtime.getRuntime().exec(arrayOf(suBin, "-c", cmd))
                    val deadline = System.currentTimeMillis() + 4000
                    while (System.currentTimeMillis() < deadline) {
                        try {
                            val code = p.exitValue()
                            return code == 0
                        } catch (stillRunning: IllegalThreadStateException) {
                            Thread.sleep(50)
                        }
                    }
                    p.destroy()
                } catch (t: Throwable) {
                    p?.destroy()
                }
            }
            return false
        }
    }
}
