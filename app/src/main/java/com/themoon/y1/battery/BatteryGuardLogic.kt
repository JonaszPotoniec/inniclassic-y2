package com.themoon.y1.battery

import kotlin.math.roundToInt

/**
 * The battery level from the voltage, and the shutdown before the cut-off (no Android dependencies, for the unit tests).
 *
 * The Y2 has the MediaTek software fuel gauge (no current sensor while discharging). Its % can stay high while the
 * battery empties: on one Y2 it showed 89 % at 3.78 V, and 69 % 20 min before the Y2 turned off. So:
 *
 * - Charging: the shown level is the gauge (the charger raises the voltage).
 * - Discharging: the shown level comes from the voltage ([percentAt], measured on a Y2 cell). The voltage of each
 *   sample gets the load allowance ([sagMv]) of the heaviest load of the last [LOAD_MEAN_MS], the median of the last
 *   [WINDOW_MS] gives the estimate, and a lower estimate counts only when it stays lower for [HOLD_MS]. The shown level
 *   never goes up while discharging, and it falls by at most 1 % each [STEP_MS] (more only after a time without
 *   samples, when the CPU slept, so the screen was off), so it has no jumps.
 * - At the unplug, and at the first start of the app without a saved level, it starts from the gauge and steps down
 *   to the estimate. After the unplug the kernel's voltage (a mean of some minutes) still has the charging voltage in
 *   it, so the estimate is too high and the level waits for it.
 * - Shutdown: when the median of the raw voltage (no allowance: the kernel's own cut-off is on the raw voltage) is at
 *   or below [SHUTDOWN_MV] for [SHUTDOWN_HOLD_MS], [shutdownDue] becomes true, one time in each discharge.
 *
 * Times are elapsed real time in ms (with the time in deep sleep).
 */
class BatteryGuardLogic {
    /** The load during a sample: the screen, the music or the radio, the CPU (all cores, % of the awake time), Wi-Fi. */
    data class Load(val screenOn: Boolean, val playing: Boolean = false, val cpuPct: Int = 0, val wifi: Boolean = false)

    /** The shown level in %, -1 before the first sample. */
    var shown = -1
        private set

    var charging = false
        private set

    /** True from the time the raw voltage was low for [SHUTDOWN_HOLD_MS] until the next charge. */
    var shutdownDue = false
        private set

    private class Point(val t: Long, val v: Float)

    private val equivalent = ArrayList<Point>()
    private val raw = ArrayList<Point>()
    private val estimates = ArrayList<Point>()
    private val sags = ArrayList<Point>()
    private var started = false
    private var lastDrop = Long.MIN_VALUE / 2
    private var lastSample = Long.MIN_VALUE / 2
    private var lowSince = NONE

    /**
     * Starts with a level that the app saved before (same boot, discharging), so a restart of the app does not show
     * the gauge again. Call before the first [onSample].
     */
    fun restore(level: Int) {
        if (!started && level in 0..100) shown = level
    }

    /**
     * A battery sample: [mV] the kernel's voltage, [gauge] the gauge level in %. Returns true when [shown] or
     * [shutdownDue] changed.
     */
    fun onSample(t: Long, mV: Int, gauge: Int, charging: Boolean, load: Load): Boolean {
        val before = shown
        val dueBefore = shutdownDue
        if (charging) {
            this.charging = true
            started = true
            if (gauge in 0..100) shown = gauge
            clear()
            shutdownDue = false
            return shown != before || dueBefore
        }
        if (this.charging || !started) {
            // The unplug (or the first sample of the app while discharging): start from the gauge, or from the
            // saved level when it is lower.
            this.charging = false
            started = true
            // From here 1 % each STEP_MS, also when the estimate is much lower (no jump)
            lastDrop = t
            shown = when {
                gauge !in 0..100 -> shown
                shown < 0 -> gauge
                else -> minOf(shown, gauge)
            }
            clear()
        }
        val previous = lastSample
        lastSample = t
        if (mV < MIN_VALID_MV || mV > MAX_VALID_MV) return shown != before

        add(raw, t, mV.toFloat())
        if (median(raw) <= SHUTDOWN_MV) {
            if (lowSince == NONE) lowSince = t
            if (t - lowSince >= SHUTDOWN_HOLD_MS) shutdownDue = true
        } else {
            lowSince = NONE
        }

        // The kernel's voltage is a mean of ~5 min, so it still has the loads of that time in it (screen off: the
        // voltage comes back up in 3-5 min). The allowance is that of the heaviest of them, also of the last sample before
        // that time (after a sleep the voltage can be old). So the estimate can be too high for some minutes, which the
        // level ignores, but not too low, which it would keep because it never goes up.
        add(sags, t, sagMv(load).toFloat(), keepBefore = LOAD_MEAN_MS)
        add(equivalent, t, mV + sags.maxOf { it.v } - REFERENCE_SAG_MV)
        add(estimates, t, percentAt(median(equivalent)), keepBefore = HOLD_MS)

        // The estimate that was in effect for all of the hold time: a short sag does not count.
        val held = heldEstimate(t)
        if (held == Float.MAX_VALUE) return shown != before || shutdownDue != dueBefore
        if (shown < 0) shown = held.roundToInt()
        val target = held.roundToInt()
        if (target < shown && t - lastDrop >= STEP_MS) {
            // 1 % each STEP_MS: when the samples are further apart (the CPU sleeps, so the screen is off), more.
            val steps = ((t - maxOf(previous, lastDrop)) / STEP_MS).coerceIn(1L, 100L).toInt()
            shown = maxOf(target, shown - steps)
            lastDrop = t
        }
        return shown != before || shutdownDue != dueBefore
    }

    private fun heldEstimate(t: Long): Float {
        var max = -1f
        var lastBefore: Point? = null
        for (p in estimates) {
            if (p.t > t - HOLD_MS) max = maxOf(max, p.v) else lastBefore = p
        }
        // The value in effect at the start of the hold time (a sample before it), so one new sample after a
        // long sleep does not lower the level at once. No estimate that old yet: no fall.
        if (lastBefore == null) return Float.MAX_VALUE
        return maxOf(max, lastBefore.v)
    }

    private fun add(list: ArrayList<Point>, t: Long, v: Float, keepBefore: Long = WINDOW_MS) {
        list.add(Point(t, v))
        // Keep the points of the time span, and the last one at or before its start (the value in effect then).
        while (list.size > 1 && list[1].t <= t - keepBefore) list.removeAt(0)
    }

    /** The median of the points in the window (the point before the window counts until a newer one is there). */
    private fun median(list: List<Point>): Float {
        val t = list.last().t
        val values = list.filter { it.t >= t - WINDOW_MS }.map { it.v }.ifEmpty { listOf(list.last().v) }.sorted()
        val n = values.size
        return if (n % 2 == 1) values[n / 2] else (values[n / 2 - 1] + values[n / 2]) / 2f
    }

    private fun clear() {
        equivalent.clear()
        raw.clear()
        estimates.clear()
        lowSince = NONE
        sags.clear()
    }

    companion object {
        const val WINDOW_MS = 3 * 60_000L
        const val HOLD_MS = 2 * 60_000L
        const val STEP_MS = 30_000L
        const val SHUTDOWN_HOLD_MS = 2 * 60_000L
        /** After a load change, the voltage settles in 3-5 min (Y2 logs: screen on/off with the CPU awake). */
        const val LOAD_MEAN_MS = 5 * 60_000L

        /** The level of the last notice ("Please charge"); the banner keeps its level (LowBatteryWarnings). */
        const val LAST_LEVEL = 3

        /**
         * Shutdown at this raw voltage. The kernel sets the gauge to 0 % at 3.40 V (then Android turns off at once);
         * on a Y2 the voltage stayed at 3.42-3.43 V for ~8 min before that.
         */
        const val SHUTDOWN_MV = 3430

        private const val MIN_VALID_MV = 2800
        private const val MAX_VALID_MV = 4500
        private const val NONE = Long.MIN_VALUE

        /** The load of the curve: the screen on, Wi-Fi on, the CPU at 25 %. */
        const val REFERENCE_SAG_MV = 35

        /**
         * The voltage → real charge curve: mV (at the load of [REFERENCE_SAG_MV]) and %, both going up. Measured on a
         * Y2 (2026-11-01): one full discharge at a constant load (screen on, music, Wi-Fi on), from Full to Android's
         * shutdown at 3.40 V. The share of the time left is the share of the charge left.
         */
        private val CURVE_MV = intArrayOf(
            3411, 3441, 3463, 3512, 3546, 3584, 3611, 3644, 3681, 3716, 3736,
            3752, 3781, 3829, 3865, 3904, 3944, 4004, 4042, 4090, 4135, 4220
        )
        private val CURVE_PCT = intArrayOf(0, 3, 5, 10, 15, 20, 25, 30, 35, 40, 45, 50, 55, 60, 65, 70, 75, 80, 85, 90, 95, 100)

        /** The real charge in % at [mV] (at the load of the curve), linear between the points of the curve. */
        fun percentAt(mV: Float): Float {
            if (mV <= CURVE_MV[0]) return CURVE_PCT[0].toFloat()
            for (i in 1 until CURVE_MV.size) {
                if (mV <= CURVE_MV[i]) {
                    return CURVE_PCT[i - 1] +
                        (CURVE_PCT[i] - CURVE_PCT[i - 1]) * (mV - CURVE_MV[i - 1]) / (CURVE_MV[i] - CURVE_MV[i - 1])
                }
            }
            return CURVE_PCT.last().toFloat()
        }

        /**
         * The voltage drop of a load, in mV (estimated from screen on/off changes in the logs: ~25 mV for the screen).
         * The CPU part is for Wi-Fi syncs and scans (CPU at 100 %: 20 mV).
         */
        fun sagMv(load: Load): Int =
            (if (load.screenOn) 25 else 0) + (if (load.playing) 5 else 0) +
                load.cpuPct.coerceIn(0, 100) / 5 + (if (load.wifi) 5 else 0)
    }
}
