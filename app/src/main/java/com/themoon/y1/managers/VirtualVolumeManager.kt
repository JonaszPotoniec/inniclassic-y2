package com.themoon.y1.managers

import android.media.MediaPlayer
import java.lang.ref.WeakReference
import java.util.ArrayList

/**
 * Central manager and coordinator for iPod 30-step virtual volume and digital software gain attenuation.
 *
 * Provides a unified software master volume pipeline across all media playback subsystems:
 * - Music, Podcasts, Audiobooks (AudioPlayerManager)
 * - Movies & Videos (VideoPlayerManager / LibVLC)
 * - Music Quiz preview playback (MediaPlayer)
 * - FM Radio (FmRadioManager / MediaPlayer)
 * - Any future media players / engines via registration or MasterVolumeObserver
 */
class VirtualVolumeManager private constructor() {

    interface MasterVolumeObserver {
        fun onMasterVolumeChanged(gain: Float)
    }

    private var currentMasterGain = 1.0f
    private val registeredMediaPlayers = ArrayList<WeakReference<MediaPlayer>>()
    private val registeredObservers = ArrayList<WeakReference<MasterVolumeObserver>>()

    @Synchronized
    fun getMasterGain(): Float {
        return currentMasterGain
    }

    /**
     * Sets the global digital master volume gain and dispatches it to all playback engines.
     */
    @Synchronized
    fun setMasterGain(gain: Float) {
        this.currentMasterGain = Math.max(0.0f, Math.min(1.0f, gain))
        applyMasterGain()
    }

    /**
     * Applies the current master gain to all known playback engines, registered MediaPlayers,
     * and custom observers.
     */
    @Synchronized
    fun applyMasterGain() {
        // 1. AudioPlayerManager (Music, Podcasts, Audiobooks)
        try {
            val apm = AudioPlayerManager.getInstance()
            apm?.setMasterVolume(currentMasterGain)
        } catch (ignored: Throwable) {}

        // 2. VideoPlayerManager (Movies / Videos)
        try {
            val vpm = VideoPlayerManager.getInstance()
            vpm?.setMasterVolume(currentMasterGain)
        } catch (ignored: Throwable) {}

        // 3. FmRadioManager (Radio)
        try {
            val fm = FmRadioManager.getInstance()
            fm?.setMasterVolume(currentMasterGain)
        } catch (ignored: Throwable) {}

        // 4. Registered MediaPlayers (e.g. Music Quiz preview player, FM Radio player)
        val playerIt = registeredMediaPlayers.iterator()
        while (playerIt.hasNext()) {
            val mp = playerIt.next().get()
            if (mp == null) {
                playerIt.remove()
            } else {
                try {
                    mp.setVolume(currentMasterGain, currentMasterGain)
                } catch (ignored: Throwable) {}
            }
        }

        // 5. Registered observers
        val observerIt = registeredObservers.iterator()
        while (observerIt.hasNext()) {
            val obs = observerIt.next().get()
            if (obs == null) {
                observerIt.remove()
            } else {
                try {
                    obs.onMasterVolumeChanged(currentMasterGain)
                } catch (ignored: Throwable) {}
            }
        }
    }

    /**
     * Registers a MediaPlayer and immediately applies the current master gain to it.
     */
    @Synchronized
    fun applyTo(player: MediaPlayer?) {
        if (player == null) return
        registerMediaPlayer(player)
        try {
            player.setVolume(currentMasterGain, currentMasterGain)
        } catch (ignored: Throwable) {}
    }

    @Synchronized
    fun registerMediaPlayer(player: MediaPlayer?) {
        if (player == null) return
        val it = registeredMediaPlayers.iterator()
        while (it.hasNext()) {
            val p = it.next().get()
            if (p == null || p == player) {
                it.remove()
            }
        }
        registeredMediaPlayers.add(WeakReference(player))
        try {
            player.setVolume(currentMasterGain, currentMasterGain)
        } catch (ignored: Throwable) {}
    }

    @Synchronized
    fun unregisterMediaPlayer(player: MediaPlayer?) {
        if (player == null) return
        val it = registeredMediaPlayers.iterator()
        while (it.hasNext()) {
            val p = it.next().get()
            if (p == null || p == player) {
                it.remove()
            }
        }
    }

    @Synchronized
    fun registerObserver(observer: MasterVolumeObserver?) {
        if (observer == null) return
        val it = registeredObservers.iterator()
        while (it.hasNext()) {
            val obs = it.next().get()
            if (obs == null || obs == observer) {
                it.remove()
            }
        }
        registeredObservers.add(WeakReference(observer))
        try {
            observer.onMasterVolumeChanged(currentMasterGain)
        } catch (ignored: Throwable) {}
    }

    @Synchronized
    fun unregisterObserver(observer: MasterVolumeObserver?) {
        if (observer == null) return
        val it = registeredObservers.iterator()
        while (it.hasNext()) {
            val obs = it.next().get()
            if (obs == null || obs == observer) {
                it.remove()
            }
        }
    }

    companion object {
        const val VIRTUAL_VOLUME_STEPS = 30

        @Volatile
        private var instance: VirtualVolumeManager? = null

        @JvmStatic
        @Synchronized
        fun getInstance(): VirtualVolumeManager {
            var inst = instance
            if (inst == null) {
                inst = VirtualVolumeManager()
                instance = inst
            }
            return inst
        }

        /**
         * Maps 30-step virtual volume (0..30) to digital software attenuation gain (0.0f..1.0f).
         * Steps 1..5 provide fine-grained sub-steps down to -28 dBFS for sensitive headphones at hardware volume 1.
         */
        @JvmStatic
        fun virtualToSoftwareGain(virtualVol: Int): Float {
            if (virtualVol <= 0) return 0.0f
            return when (virtualVol) {
                1 -> 0.04f // -28 dBFS: ultra-whisper quiet for sensitive headphones in silent room
                2 -> 0.10f // -20 dBFS: very quiet
                3 -> 0.20f // -14 dBFS
                4 -> 0.35f // -9.1 dBFS
                5 -> 0.60f // -4.4 dBFS
                else -> 1.0f // 0 dBFS (unattenuated sys vol 1 at step 6+)
            }
        }

        /**
         * Maps 30-step virtual volume to Android hardware/system volume (0..sysMaxVol, typically 15).
         */
        @JvmStatic
        fun virtualToSystemVolume(virtualVol: Int, sysMaxVol: Int): Int {
            if (virtualVol <= 0) return 0
            if (virtualVol <= 6) return 1
            if (sysMaxVol <= 1) return 1
            var sys = 2 + Math.round((virtualVol - 7).toFloat() * (sysMaxVol - 2) / 23.0f)
            if (sys > sysMaxVol) sys = sysMaxVol
            return sys
        }

        /**
         * Converts Android system volume to closest 30-step virtual volume.
         */
        @JvmStatic
        fun systemToVirtualVolume(sysVol: Int, sysMaxVol: Int, currentVirtualVol: Int): Int {
            if (sysVol <= 0) return 0
            if (sysVol == 1) {
                if (currentVirtualVol in 1..6) {
                    return currentVirtualVol
                }
                return 6
            }
            if (sysMaxVol <= 2) return VIRTUAL_VOLUME_STEPS
            if (sysVol >= sysMaxVol) return VIRTUAL_VOLUME_STEPS
            val ratio = (sysVol - 2).toFloat() / (sysMaxVol - 2)
            var v = 7 + Math.round(ratio * 23.0f)
            if (v > VIRTUAL_VOLUME_STEPS) v = VIRTUAL_VOLUME_STEPS
            return v
        }
    }
}
