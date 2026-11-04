package com.themoon.y1.managers

import kotlin.math.pow

/**
 * Settings → Click Volume: the level of the button click (FX_KEY_CLICK), independent of the music volume.
 *
 * The music sets its volume with the player gain and keeps the Android index at the top ([VolumeSplit.SOFTWARE]),
 * so the click ([ClickSound], on STREAM_MUSIC) always gets the same hardware level. Its level is a linear gain on
 * the click sound (1.0 = the full sound), as the `volume` of `AudioManager.playSoundEffect(FX_KEY_CLICK, volume)`.
 *
 * Levels, in dB of that gain: Low -26, Medium -16, High -6. High is Android's default click gain (-6 dB), the click
 * that the user heard on each centre push until Android's own view click was turned off. Each level is 10 dB from
 * the next, a clear step also on the small speaker (in the mixer the click peaks are then -9, -19 and -29 dBFS).
 *
 * Headphones (wired and Bluetooth): the click is not more than [HEADPHONE_ABOVE_MUSIC_DB] above the music level,
 * so it is not loud in the ears when the music is quiet; and not below [HEADPHONE_FLOOR_DB], so it stays audible
 * (also when the music is muted). The speaker uses the level as it is.
 */
object ClickVolume {
    const val PREF = "click_volume"
    const val LOW = 0
    const val MEDIUM = 1
    const val HIGH = 2
    const val DEFAULT = MEDIUM

    val LABELS = arrayOf("Low", "Medium", "High")
    val LEVEL_DB = doubleArrayOf(-26.0, -16.0, -6.0)

    const val HEADPHONE_ABOVE_MUSIC_DB = 6.0
    const val HEADPHONE_FLOOR_DB = -40.0

    fun validLevel(level: Int): Int = if (level in LEVEL_DB.indices) level else DEFAULT

    /** The next level when the row is pushed: Low → Medium → High → Low. */
    fun next(level: Int): Int = (validLevel(level) + 1) % LEVEL_DB.size

    /** The click level in dB (gain of the click sound) for [level], the [output] and the music [musicStep] (0..30). */
    fun clickDb(level: Int, output: AudioOutput, musicStep: Int): Double {
        val db = LEVEL_DB[validLevel(level)]
        if (output == AudioOutput.SPEAKER) return db
        val musicDb = if (musicStep > 0) VolumeSteps.targetDb(musicStep.coerceAtMost(VolumeSteps.STEPS)) else Double.NEGATIVE_INFINITY
        val cap = maxOf(musicDb + HEADPHONE_ABOVE_MUSIC_DB, HEADPHONE_FLOOR_DB)
        return minOf(db, cap)
    }

    /** The linear gain of the click sound (0..1). */
    fun clickGain(level: Int, output: AudioOutput, musicStep: Int): Float =
        10.0.pow(clickDb(level, output, musicStep) / 20.0).toFloat()
}
