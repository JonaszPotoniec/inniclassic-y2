package com.themoon.y1.managers

import android.media.MediaPlayer
import java.lang.ref.WeakReference
import java.util.ArrayList

/**
 * Central manager and coordinator for iPod 30-step virtual volume and digital software gain attenuation.
 * The step → (Android index, software gain) mapping is in [VolumeSteps].
 *
 * Provides a unified software master volume pipeline across all media playback subsystems:
 * - Music, Podcasts, Audiobooks (AudioPlayerManager)
 * - Movies & Videos (VideoPlayerManager / LibVLC)
 * - Music Quiz preview playback (MediaPlayer)
 * - FM Radio (FmRadioManager / MediaPlayer)
 * - Any future MediaPlayer via [applyTo] or [registerMediaPlayer]
 */
class VirtualVolumeManager private constructor() {

    private var currentMasterGain = 1.0f
    private val registeredMediaPlayers = ArrayList<WeakReference<MediaPlayer>>()

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
     * Applies the current master gain to all known playback engines and registered MediaPlayers.
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

        // 4. Registered MediaPlayers (e.g. Music Quiz preview player)
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

    companion object {
        const val VIRTUAL_VOLUME_STEPS = VolumeSteps.STEPS

        @Volatile
        private var instance: VirtualVolumeManager? = null

        @Synchronized
        fun getInstance(): VirtualVolumeManager {
            var inst = instance
            if (inst == null) {
                inst = VirtualVolumeManager()
                instance = inst
            }
            return inst
        }
    }
}
