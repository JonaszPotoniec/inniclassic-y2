package com.themoon.y1.managers

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.themoon.y1.MainActivity

/**
 * One audio source at a time, as on the iPod nano with FM radio: music (a song, a podcast or a Music Quiz clip)
 * that starts turns the radio off, a video also pauses the music, and the radio pauses the music when it starts.
 * (iPod nano user guide: the radio is cleared when "you exit Radio to play other media content".)
 *
 * While the radio plays or is paused, it is the source of the media buttons, from any screen (also on the Radio
 * screen when it is off): Play/Pause pauses and resumes it, Next/Previous tune to the next or previous saved station.
 * Paused radio is cleared (the buttons go back to the music) when music starts, or after 15 minutes, as on the iPod.
 */
class AudioSources internal constructor(
    private val radioOn: () -> Boolean,
    private val stopRadio: () -> Unit,
    private val musicPlaying: () -> Boolean,
    private val pauseMusic: () -> Unit,
    private val radio: Radio = Radio.NONE,
    private val music: Music = Music.NONE,
    private val onRadioScreen: () -> Boolean = { false },
    private val nowMs: () -> Long = { SystemClock.elapsedRealtime() }
) {
    /** The radio as the media buttons control it. */
    interface Radio {
        /** Turns the radio on (at its station) or off, as the Radio screen's centre button. */
        fun toggle()
        fun tuneSaved(next: Boolean)

        companion object {
            val NONE = object : Radio {
                override fun toggle() {}
                override fun tuneSaved(next: Boolean) {}
            }
        }
    }

    /** The music player as the media buttons control it. */
    interface Music {
        fun playPause()
        fun skip(next: Boolean)

        companion object {
            val NONE = object : Music {
                override fun playPause() {}
                override fun skip(next: Boolean) {}
            }
        }
    }

    /** Time (elapsed ms) when Play/Pause paused the radio; -1 = the radio is not paused. */
    private var radioPausedAtMs = -1L

    /** The radio was paused with Play/Pause less than 15 minutes ago, and no music started since. */
    val isRadioPaused: Boolean
        get() = radioPausedAtMs >= 0 && nowMs() - radioPausedAtMs < PAUSED_RADIO_KEEP_MS

    /** The media buttons control the radio. */
    val radioHasButtons: Boolean
        get() = radioOn() || isRadioPaused || onRadioScreen()

    /** Music starts, or is about to start: the radio goes off and paused radio is cleared. */
    fun musicStarts() {
        radioPausedAtMs = -1L
        if (!radioOn()) return
        Log.w(TAG, "Music starts: FM radio off")
        stopRadio()
    }

    /** A video starts: the radio goes off and the music pauses. */
    fun videoStarts() {
        musicStarts()
        if (musicPlaying()) {
            Log.w(TAG, "Video starts: music paused")
            pauseMusic()
        }
    }

    /** The radio is about to start: the music pauses. */
    fun radioStarts() {
        radioPausedAtMs = -1L
        if (musicPlaying()) {
            Log.w(TAG, "FM radio starts: music paused")
            pauseMusic()
        }
    }

    /** A Play/Pause press (any screen, also remote and screen-off keys). */
    fun playPauseKey() {
        if (!radioHasButtons) {
            music.playPause()
        } else if (radioOn()) {
            radio.toggle()
            if (!radioOn()) radioPausedAtMs = nowMs()
        } else {
            radio.toggle()
        }
    }

    /** A short Next ([next]) or Previous press. Paused radio plays again at the new station. */
    fun skipKey(next: Boolean) {
        if (!radioHasButtons) {
            music.skip(next)
            return
        }
        radio.tuneSaved(next)
        if (isRadioPaused) radio.toggle()
    }

    companion object {
        private const val TAG = "AudioSources"

        /** iPod nano: paused radio is cleared after 15 minutes. */
        const val PAUSED_RADIO_KEEP_MS = 15 * 60 * 1000L

        /** The rule for the app's players. Call it on the main thread (the music player lives there). */
        @JvmField
        val app = AudioSources(
            radioOn = { FmRadioManager.getInstance()?.isPowerUp == true },
            stopRadio = {
                FmRadioManager.getInstance()?.powerDown()
                // The Radio screen shows "stopped"
                MainActivity.instance?.let { main ->
                    if (main.currentScreenState == MainActivity.STATE_FM_RADIO) main.getActiveScreenController()?.onRefreshUI()
                }
            },
            musicPlaying = { AudioPlayerManager.getInstance().isPlaying() },
            pauseMusic = { onMain { AudioPlayerManager.getInstance().let { if (it.isPlaying()) it.playOrPauseMusic() } } },
            radio = object : Radio {
                override fun toggle() {
                    MainActivity.instance?.let { it.fmRadioController.toggleRadioPower() }
                }

                override fun tuneSaved(next: Boolean) {
                    MainActivity.instance?.let { it.fmRadioController.tuneToNextSavedRadioChannel(next) }
                }
            },
            music = object : Music {
                override fun playPause() = AudioPlayerManager.getInstance().playOrPauseMusic()
                override fun skip(next: Boolean) {
                    if (next) AudioPlayerManager.getInstance().nextTrack() else AudioPlayerManager.getInstance().prevTrack()
                }
            },
            onRadioScreen = { MainActivity.instance?.currentScreenState == MainActivity.STATE_FM_RADIO }
        )

        private fun onMain(block: () -> Unit) {
            if (Looper.myLooper() == Looper.getMainLooper()) block() else Handler(Looper.getMainLooper()).post(block)
        }
    }
}
