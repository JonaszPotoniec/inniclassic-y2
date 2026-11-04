package com.themoon.y1.managers

import android.media.AudioManager
import android.media.SoundPool
import java.io.File

/**
 * The button click (Android's click sound, Effect_Tick.ogg), played by the app on STREAM_MUSIC.
 *
 * The Y2 ROM's PhoneWindowManager plays Android's click itself on the press of the wheel, centre, Menu and Play/Pause
 * keys (`IAudioService.playSoundEffect(FX_KEY_CLICK)`: default gain -6 dB, no setting check) on STREAM_SYSTEM. Thus
 * the app keeps STREAM_SYSTEM muted and plays its own click here: one for each push, on the release, at the Click
 * Volume level ([ClickVolume.clickGain], the same linear gain as the `volume` of `playSoundEffect`). STREAM_MUSIC stays
 * at the top index ([VolumeSplit.SOFTWARE]), so the click level does not follow the music volume.
 */
object ClickSound {
    const val FILE = "/system/media/audio/ui/Effect_Tick.ogg"

    private var pool: SoundPool? = null
    private var soundId = 0

    @Volatile
    private var loaded = false

    /** Loads the sound once (asynchronously); [play] does nothing until it is loaded. */
    @Suppress("DEPRECATION")
    fun load() {
        if (pool != null) return
        try {
            val p = SoundPool(2, AudioManager.STREAM_MUSIC, 0)
            p.setOnLoadCompleteListener { _, id, status -> if (status == 0 && id == soundId) loaded = true }
            pool = p
            if (File(FILE).canRead()) soundId = p.load(FILE, 1)
        } catch (_: Exception) {
        }
    }

    /** Plays the click at [gain] (linear, 0..1). */
    fun play(gain: Float) {
        val p = pool ?: return
        if (!loaded || gain <= 0f) return
        try {
            p.play(soundId, gain, gain, 1, 0, 1f)
        } catch (_: Exception) {
        }
    }
}
