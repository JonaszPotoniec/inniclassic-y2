package com.themoon.y1.managers

import kotlin.math.pow

/** The audio output that plays STREAM_MUSIC. Each output has its own volume step, as in Android. */
enum class AudioOutput(val prefKey: String) {
    A2DP("volume_step_a2dp"),
    WIRED("volume_step_wired"),
    SPEAKER("volume_step_speaker");

    companion object {
        /**
         * The output that plays music now. On Android 4.4, a wired headset takes music off A2DP
         * (AudioService turns A2DP off for media when a headset is plugged in), so wired comes first.
         */
        fun current(wiredHeadsetOn: Boolean, bluetoothA2dpOn: Boolean): AudioOutput = when {
            wiredHeadsetOn -> WIRED
            bluetoothA2dpOn -> A2DP
            else -> SPEAKER
        }
    }
}

/**
 * The attenuation in dB of each Android STREAM_MUSIC index (0..max) for one output.
 * [db] has one value for each index; index 0 is mute and its value is not used.
 */
class VolumeCurve(private val db: DoubleArray) {
    init {
        require(db.size >= 2) { "a curve needs index 0 (mute) and at least index 1" }
        for (i in 2 until db.size) require(db[i] > db[i - 1]) { "dB must increase with the index" }
    }

    val maxIndex: Int get() = db.size - 1

    fun dbAt(index: Int): Double {
        require(index in 1..maxIndex) { "no dB value for index $index" }
        return db[index]
    }
}

/**
 * Measured index→dB tables, one per output. All in one place: replace a TODO-measure table with the
 * values from the device.
 *
 * How to measure: set the index (`adb shell media volume --stream 3 --set N` or the volume keys), then read
 * "stream volumes in dB" for STREAM_MUSIC in `adb shell dumpsys media.audio_flinger` for the active output.
 */
object VolumeCurves {
    private val NOT_USED = Double.NEGATIVE_INFINITY

    /**
     * A2DP, measured on the Y2 on 2026-10-25 (AudioFlinger "stream volumes in dB", STREAM_MUSIC):
     * index 15:0, 14:-2, 13:-4, 12:-6, 11:-8.5, 10:-11, 9:-14, 8:-17, 7:-21, 6:-25, 5:-28, 4:-33,
     * 3:-36, 2:-39, 1:-42, 0:mute.
     */
    val A2DP = VolumeCurve(
        doubleArrayOf(
            NOT_USED, -42.0, -39.0, -36.0, -33.0, -28.0, -25.0, -21.0,
            -17.0, -14.0, -11.0, -8.5, -6.0, -4.0, -2.0, 0.0
        )
    )

    /**
     * Wired jack, from the ROM code on 2026-10-25 (`records/mtk-volume-tables-2026-10-25.md` in our notes):
     * the vendor audio HAL sends the music index directly to the Cirrus CS43131 DAC (PCM volume),
     * 2 dB per index. Values are relative to index 15, like A2DP: index 15…1 = 0, -2, … -28 dB
     * (absolute -3 … -31 dB re DAC full scale). The MediaTek NVRAM table is not used on this output.
     */
    val WIRED = VolumeCurve(DoubleArray(16) { i -> if (i == 0) NOT_USED else -2.0 * (15 - i) })

    /**
     * Speaker: the same CS43131 path and table as [WIRED] (the speaker amplifier gets its signal from the DAC).
     * Note: in speaker mode the driver writes 0x090004 = 0x05 (PCM channel select Left-Left), so the speaker
     * probably plays the left channel only. Nothing to do here; it changes the level of stereo music, not the steps.
     */
    val SPEAKER = WIRED

    fun forOutput(output: AudioOutput): VolumeCurve = when (output) {
        AudioOutput.A2DP -> A2DP
        AudioOutput.WIRED -> WIRED
        AudioOutput.SPEAKER -> SPEAKER
    }
}

/**
 * How a volume step is split between the Android STREAM_MUSIC index and the software gain of the players.
 *
 * On the Y2 the vendor audio HAL sends the music index to the CS43131 DAC, and that DAC volume also applies to
 * the button click (STREAM_SYSTEM) and all other sounds. A2DP applies the index as a mixer gain to all streams too.
 */
enum class VolumeSplit {
    /**
     * Music: the index stays at the top, the software gain does all the attenuation. Thus the click and other
     * system sounds have the same level at each music volume.
     */
    SOFTWARE,

    /**
     * FM radio: the index does most of the attenuation (the quietest index that is not quieter than the step),
     * the software gain the rest. The FM audio may not go through the player gain, so FM needs the index.
     */
    INDEX
}

/**
 * 30 volume steps, all [STEP_DB] apart: step s (1..30) is [TOP_DB] - (30 - s) × [STEP_DB]; step 0 is mute.
 * Each step is an Android index and a software gain (≤ 1.0, through [VirtualVolumeManager]); [VolumeSplit]
 * says which part does the attenuation.
 */
object VolumeSteps {
    const val STEPS = 30
    const val TOP_DB = 0.0
    const val STEP_DB = 2.0

    /** Pref with the Volume Limit as a step (1..30); -1 or missing = no limit. Before version 2 it was an Android index. */
    const val PREF_LIMIT = "volume_limit_max"

    /** Pref with the version of the volume prefs. 2 = steps of 2 dB, a step per output. */
    const val PREF_VERSION = "volume_prefs_version"
    const val VERSION = 2

    /**
     * The Y2 kernel (CS43131 driver) ignores a volume decrease of more than one index when it comes less than
     * 200 ms after the previous change. Thus the index goes down one index at a time, at least this far apart.
     */
    const val MIN_DECREASE_GAP_MS = 220L

    /**
     * An index increase comes this long after the gain change that makes up for it. The player applies a new gain
     * a little later than the call (ExoPlayer sets it on its playback thread), so the index never goes up first.
     */
    const val INCREASE_AFTER_GAIN_MS = 100L

    private const val EPSILON = 1e-9

    data class Setting(val index: Int, val gain: Float)

    /** One write: set the Android [index] and the software [gain] [delayMs] after the previous write. */
    data class IndexChange(val index: Int, val gain: Float, val delayMs: Long)

    fun targetDb(step: Int): Double = TOP_DB - (STEPS - step) * STEP_DB

    /**
     * The Android index and the software gain for [step].
     * [VolumeSplit.SOFTWARE]: the top index for all steps; step 0 (or less) = gain 0 (the index does not move).
     * [VolumeSplit.INDEX]: step 0 (or less) = index 0, gain 0.
     */
    fun settingFor(
        step: Int,
        curve: VolumeCurve,
        sysMaxIndex: Int = curve.maxIndex,
        split: VolumeSplit = VolumeSplit.SOFTWARE
    ): Setting {
        val maxIndex = minOf(curve.maxIndex, sysMaxIndex).coerceAtLeast(1)
        if (split == VolumeSplit.SOFTWARE) {
            return Setting(maxIndex, gainAt(step, maxIndex, curve))
        }
        if (step <= 0) return Setting(0, 0f)
        val target = targetDb(step.coerceAtMost(STEPS))
        var index = maxIndex
        for (i in 1..maxIndex) {
            if (curve.dbAt(i) >= target - EPSILON) {
                index = i
                break
            }
        }
        val rest = (target - curve.dbAt(index)).coerceAtMost(0.0)
        return Setting(index, 10.0.pow(rest / 20.0).toFloat())
    }

    /**
     * The writes that take the Android index from [fromIndex] to the index of [step].
     * - No index change: one write (gain only) at once.
     * - Up: the gain of the step at once (the level goes down or stays), then the index [INCREASE_AFTER_GAIN_MS]
     *   later. Thus the level never goes above the step, also when the player applies the gain late.
     * - Down: the gain goes to the level of the step at once (first write, index not changed), then the index
     *   goes down one index at a time, [MIN_DECREASE_GAP_MS] apart (the first one also that far after the last
     *   index write, [msSinceLastWrite]). At each write the gain makes up for the higher index, so the level
     *   stays at the step and only the hardware/software split changes.
     * This also covers a fast wheel: two decreases never come less than [MIN_DECREASE_GAP_MS] apart.
     */
    fun indexSchedule(
        fromIndex: Int,
        step: Int,
        curve: VolumeCurve,
        sysMaxIndex: Int = curve.maxIndex,
        msSinceLastWrite: Long = Long.MAX_VALUE,
        split: VolumeSplit = VolumeSplit.SOFTWARE
    ): List<IndexChange> {
        val final = settingFor(step, curve, sysMaxIndex, split)
        val start = fromIndex.coerceIn(0, minOf(curve.maxIndex, sysMaxIndex).coerceAtLeast(1))
        if (start == final.index) return listOf(IndexChange(final.index, final.gain, 0L))
        if (start < final.index) {
            return listOf(IndexChange(start, final.gain, 0L), IndexChange(final.index, final.gain, INCREASE_AFTER_GAIN_MS))
        }
        val out = ArrayList<IndexChange>()
        out.add(IndexChange(start, gainAt(step, start, curve), 0L))
        var delay = (MIN_DECREASE_GAP_MS - msSinceLastWrite).coerceIn(0L, MIN_DECREASE_GAP_MS)
        for (k in start - 1 downTo final.index) {
            out.add(IndexChange(k, if (k == final.index) final.gain else gainAt(step, k, curve), delay))
            delay = MIN_DECREASE_GAP_MS
        }
        return out
    }

    /** The gain that gives the level of [step] at Android [index] (≤ 1.0; 0 for mute). */
    private fun gainAt(step: Int, index: Int, curve: VolumeCurve): Float {
        if (step <= 0 || index <= 0) return 0f
        val rest = (targetDb(step.coerceAtMost(STEPS)) - curve.dbAt(index)).coerceAtMost(0.0)
        return 10.0.pow(rest / 20.0).toFloat()
    }

    /** The loudest step that is not louder than Android [index]: for the first start of an output. */
    fun stepForIndex(index: Int, curve: VolumeCurve): Int {
        if (index <= 0) return 0
        val db = curve.dbAt(index.coerceAtMost(curve.maxIndex))
        var step = 1
        for (s in 1..STEPS) {
            if (targetDb(s) <= db + EPSILON) step = s
        }
        return step
    }

    /** [step] in 0..30, and not above [limitStep] (limit ≤ 0 = no limit). */
    fun clamp(step: Int, limitStep: Int): Int {
        val s = step.coerceIn(0, STEPS)
        return if (limitStep in 1..STEPS) minOf(s, limitStep) else s
    }

    /**
     * A change of the Android music index that the app did not make. With the screen off, Android gets the volume
     * keys (only while music plays) and moves the index by one for each push. With [VolumeSplit.SOFTWARE] the app
     * keeps the index at [keptIndex], so a change that starts there is a volume key: returns -1 (down) or +1 (up),
     * and the app moves its own step and puts the index back. At the top index ([maxIndex]) Android cannot go
     * higher, so an up push sends the same index again. Returns 0 for other changes (the app's own index writes
     * start below [keptIndex]).
     */
    fun stepFromIndexChange(prevIndex: Int, newIndex: Int, keptIndex: Int, maxIndex: Int): Int = when {
        prevIndex != keptIndex -> 0
        newIndex < prevIndex -> -1
        newIndex > prevIndex -> 1
        prevIndex == maxIndex -> 1
        else -> 0
    }
}

/** Small key-value store, so that the per-output logic has no Android dependency (prefs in the app). */
interface VolumeStore {
    fun contains(key: String): Boolean
    fun getInt(key: String, default: Int): Int
    fun putInt(key: String, value: Int)
}

/** The step of each output, and the migration of the old prefs. */
class VolumeMemory(private val store: VolumeStore) {

    /**
     * Converts the old prefs once. Version 1 had one volume for all outputs (in memory, from the Android index)
     * and the Volume Limit as an Android index (1..15). Version 2 keeps the limit as a step.
     */
    fun migrate() {
        if (store.getInt(VolumeSteps.PREF_VERSION, 1) >= VolumeSteps.VERSION) return
        val oldLimit = store.getInt(VolumeSteps.PREF_LIMIT, -1)
        if (oldLimit > 0) {
            store.putInt(VolumeSteps.PREF_LIMIT, VolumeSteps.stepForIndex(oldLimit, VolumeCurves.A2DP).coerceAtLeast(1))
        }
        store.putInt(VolumeSteps.PREF_VERSION, VolumeSteps.VERSION)
    }

    fun limitStep(): Int = store.getInt(VolumeSteps.PREF_LIMIT, -1)

    /**
     * The saved step of [output]. When the output has no saved step (first start, or the first time this output
     * is used), it is the step of the current Android index of that output, so the volume does not jump.
     */
    fun stepFor(output: AudioOutput, currentIndex: () -> Int): Int {
        val saved = if (store.contains(output.prefKey)) store.getInt(output.prefKey, -1) else -1
        val step = if (saved >= 0) saved else {
            VolumeSteps.stepForIndex(currentIndex(), VolumeCurves.forOutput(output)).also { save(output, it) }
        }
        return VolumeSteps.clamp(step, limitStep())
    }

    fun save(output: AudioOutput, step: Int) {
        store.putInt(output.prefKey, step.coerceIn(0, VolumeSteps.STEPS))
    }
}
