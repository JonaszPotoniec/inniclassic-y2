package com.themoon.y1.battery

import com.themoon.y1.battery.BatteryGuardLogic.Load
import com.themoon.y1.managers.LowBatteryWarnings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BatteryGuardLogicTest {
    private val screen = Load(screenOn = true, cpuPct = 25, wifi = true)
    private val min = 60_000L

    /** Feeds one sample each 30 s from [from] to [to] (exclusive) with the voltage [mV]. Returns the next time. */
    private fun run(g: BatteryGuardLogic, from: Long, to: Long, mV: Int, gauge: Int = 80, load: Load = screen): Long {
        var t = from
        while (t < to) {
            g.onSample(t, mV, gauge, false, load)
            t += 30_000L
        }
        return t
    }

    @Test
    fun theTableGoesFromTheCutOffToFull() {
        assertEquals(0f, BatteryGuardLogic.percentAt(3400f), 0f)
        assertEquals(10f, BatteryGuardLogic.percentAt(3512f), 0f)
        assertEquals(100f, BatteryGuardLogic.percentAt(4250f), 0f)
        var last = -1f
        for (mV in 3300..4300 step 5) {
            val p = BatteryGuardLogic.percentAt(mV.toFloat())
            assertTrue("not monotonic at $mV", p >= last)
            last = p
        }
    }

    @Test
    fun theLoadAllowanceIsZeroAtTheLoadOfTheTable() {
        assertEquals(BatteryGuardLogic.REFERENCE_SAG_MV, BatteryGuardLogic.sagMv(screen))
        // Screen off, music, no Wi-Fi: a light load, so the same voltage means less charge.
        assertTrue(BatteryGuardLogic.sagMv(Load(screenOn = false, playing = true)) < BatteryGuardLogic.REFERENCE_SAG_MV)
        // A Wi-Fi sync with the CPU at 100 %: the sag is added back.
        assertTrue(BatteryGuardLogic.sagMv(Load(screenOn = true, cpuPct = 100, wifi = true)) > BatteryGuardLogic.REFERENCE_SAG_MV)
    }

    @Test
    fun chargingShowsTheGauge() {
        val g = BatteryGuardLogic()
        g.onSample(0, 4100, 57, true, screen)
        assertEquals(57, g.shown)
        g.onSample(30_000, 3500, 58, true, screen)
        assertEquals(58, g.shown)
        assertFalse(g.shutdownDue)
    }

    @Test
    fun theUnplugAndTheFirstStartStepDownFromTheGauge() {
        // The first start of the app while discharging (no saved level) starts from the gauge.
        val first = BatteryGuardLogic()
        first.onSample(0, 3700, 96, false, screen)
        assertEquals(96, first.shown)
        val g = BatteryGuardLogic()
        g.onSample(0, 4200, 90, true, screen)
        // Unplugged: the kernel's mean still has the charging voltage in it, so the level stays at the gauge.
        var t = run(g, 30_000, 30_000 + BatteryGuardLogic.LOAD_MEAN_MS, 4200, gauge = 90)
        assertEquals(90, g.shown)
        // Then the level falls by 1 % each 30 s towards the estimate (~32 %): no jump.
        var prev = g.shown
        while (t < 90 * min) {
            g.onSample(t, 3700, 90, false, screen)
            assertTrue("jump at ${t / 1000} s", prev - g.shown in 0..1)
            prev = g.shown
            t += 30_000L
        }
        assertEquals(Math.round(BatteryGuardLogic.percentAt(3700f)), g.shown)
    }

    @Test
    fun theLevelNeverGoesUpWhileDischarging() {
        val g = BatteryGuardLogic()
        g.restore(40)
        var t = run(g, 0, 30 * min, 3720)
        val low = g.shown
        // The load stops and the voltage comes back up: no change.
        t = run(g, t, t + 30 * min, 3900, load = Load(screenOn = false))
        assertEquals(low, g.shown)
        // The gauge falls below the shown level: the voltage model still decides.
        run(g, t, t + 10 * min, 3900, gauge = 5)
        assertEquals(low, g.shown)
    }

    @Test
    fun aShortSagDoesNotLowerTheLevel() {
        val g = BatteryGuardLogic()
        g.restore(40)
        var t = run(g, 0, 10 * min, 3770)
        assertEquals(40, g.shown)
        // A Wi-Fi burst: 90 s at a lower voltage.
        t = run(g, t, t + 90_000, 3600)
        t = run(g, t, t + 10 * min, 3770)
        assertEquals(40, g.shown)
    }

    @Test
    fun oneSampleAfterALongSleepWaitsForTheHoldTime() {
        val g = BatteryGuardLogic()
        g.restore(40)
        g.onSample(0, 3770, 80, false, screen)
        g.onSample(60 * min, 3600, 80, false, screen)
        assertEquals(40, g.shown)
        g.onSample(60 * min + BatteryGuardLogic.HOLD_MS, 3600, 80, false, screen)
        // 2 min since the sample before (the CPU slept): 4 steps of 30 s.
        assertEquals(36, g.shown)
    }

    /**
     * 2 hours at a steady drain (3.90 → 3.80 V at the load of the curve), the screen on for [onMin] min and off (music)
     * for [offMin] min, again and again. The kernel's voltage moves to the voltage of the new load in 5 min. The level
     * must fall smoothly and as much as the curve: not more because of the screen changes, and with no freeze.
     */
    private fun assertFollowsTheCurve(onMin: Long, offMin: Long, maxError: Float) {
        val on = Load(screenOn = true, cpuPct = 25, wifi = true)
        val off = Load(screenOn = false, playing = true, cpuPct = 10, wifi = true)
        // At a steady load the equivalent voltage is the voltage of the curve's load.
        val offRise = BatteryGuardLogic.REFERENCE_SAG_MV - BatteryGuardLogic.sagMv(off)
        fun ref(t: Long) = 3900f - 100f * t / (120 * min)
        val g = BatteryGuardLogic()
        val start = Math.round(BatteryGuardLogic.percentAt(ref(0)))
        g.restore(start)
        var offset = 0f
        var prev = start
        var t = 0L
        while (t <= 120 * min) {
            val screenOn = (t / min) % (onMin + offMin) < onMin
            val target = if (screenOn) 0f else offRise.toFloat()
            // The kernel's mean: 1/10 of the way each 30 s step, so ~5 min to the new value.
            val step = offRise / 10f
            offset = if (offset < target) minOf(target, offset + step) else maxOf(target, offset - step)
            g.onSample(t, Math.round(ref(t) + offset), 90, false, if (screenOn) on else off)
            assertTrue("went up at ${t / min} min", g.shown <= prev)
            assertTrue("jump at ${t / min} min", prev - g.shown <= 1)
            prev = g.shown
            t += 30_000L
        }
        val modelFall = BatteryGuardLogic.percentAt(ref(0)) - BatteryGuardLogic.percentAt(ref(120 * min))
        val fall = start - g.shown
        assertTrue("fall $fall, model $modelFall", Math.abs(fall - modelFall) <= maxError)
    }

    @Test
    fun screenOnOffChangesDoNotPushTheLevelDown() = assertFollowsTheCurve(onMin = 8, offMin = 12, maxError = 2f)

    /** The load changes faster than the kernel's mean settles: the level still falls (it must not freeze). */
    @Test
    fun fastLoadChangesDoNotFreezeTheLevel() {
        assertFollowsTheCurve(onMin = 2, offMin = 2, maxError = 6f)
        // The CPU crosses 60 % each 2 min: a small load change, so a small error.
        val g = BatteryGuardLogic()
        g.restore(Math.round(BatteryGuardLogic.percentAt(3900f)))
        var t = 0L
        while (t <= 120 * min) {
            val cpu = if ((t / (2 * min)) % 2 == 0L) 70 else 30
            g.onSample(t, Math.round(3900f - 100f * t / (120 * min)), 90, false, Load(screenOn = true, cpuPct = cpu, wifi = true))
            t += 30_000L
        }
        val expected = BatteryGuardLogic.percentAt(3800f)
        assertTrue("shown ${g.shown}, curve $expected", Math.abs(g.shown - expected) <= 3f)
    }

    /**
     * The bug of a sample at a load change, after a sleep: the screen goes off with the voltage low from the screen-on
     * load, and the CPU sleeps, so the next samples have the old voltage at the light load. The level must not fall.
     */
    @Test
    fun anOldVoltageAtALighterLoadDoesNotPushTheLevelDown() {
        val light = Load(screenOn = false, playing = true, cpuPct = 10, wifi = true)
        val g = BatteryGuardLogic()
        g.restore(Math.round(BatteryGuardLogic.percentAt(3800f)))
        val start = g.shown
        var t = run(g, 0, 20 * min, 3800)
        t += 31 * min
        g.onSample(t, 3800, 90, false, light)
        t += 20 * min
        g.onSample(t, 3800, 90, false, light)
        assertEquals(start, g.shown)
    }

    @Test
    fun aLowVoltageForTheHoldTimeShutsDownOneTime() {
        val g = BatteryGuardLogic()
        g.restore(5)
        var t = run(g, 0, 10 * min, 3460)
        assertFalse(g.shutdownDue)
        // A sag of 1 min at the shutdown voltage: the median of 3 min stays above it.
        t = run(g, t, t + min, 3420)
        t = run(g, t, t + 5 * min, 3460)
        assertFalse(g.shutdownDue)
        t = run(g, t, t + 3 * min, 3425)
        assertFalse("needs the hold time", g.shutdownDue)
        run(g, t, t + 3 * min, 3425)
        assertTrue(g.shutdownDue)
    }

    @Test
    fun noShutdownWhileCharging() {
        val g = BatteryGuardLogic()
        var t = 0L
        while (t < 20 * min) {
            g.onSample(t, 3420, 3, true, screen)
            t += 30_000
        }
        assertFalse(g.shutdownDue)
        // A charge also ends a shutdown that was due.
        val d = BatteryGuardLogic()
        d.restore(3)
        run(d, 0, 10 * min, 3420)
        assertTrue(d.shutdownDue)
        d.onSample(10 * min, 3600, 3, true, screen)
        assertFalse(d.shutdownDue)
    }

    @Test
    fun badVoltagesAreIgnored() {
        val g = BatteryGuardLogic()
        g.restore(50)
        run(g, 0, 20 * min, 0)
        assertEquals(50, g.shown)
        assertFalse(g.shutdownDue)
    }

    /**
     * The end of the discharge of 2026-10-31 (power log: seconds of the day, mV, screen, CPU %, gauge %). The Y2 turned
     * off at 33984 s (09:26:24) when the gauge was 0 %. The gauge showed 89 % at 07:45 and 69 % 20 min before the end.
     */
    @Test
    fun theDischargeOf31OctoberWarnsAndShutsDownInTime() {
        val trace = intArrayOf(
            27729, 3845, 0, 48, 89, 27919, 3833, 0, 53, 89, 28099, 3833, 0, 46, 89, 28267, 3819, 0, 47, 89,
            28436, 3819, 0, 48, 89, 28621, 3819, 0, 43, 89, 28781, 3805, 0, 45, 89, 28909, 3805, 0, 47, 89,
            29049, 3805, 0, 45, 89, 29211, 3805, 0, 52, 89, 29350, 3792, 0, 44, 89, 29514, 3792, 0, 48, 89,
            29679, 3792, 0, 47, 89, 29850, 3778, 0, 51, 89, 29927, 3717, 1, 27, 88, 29987, 3612, 1, 19, 87,
            30047, 3570, 1, 10, 86, 30108, 3547, 1, 7, 86, 30168, 3536, 1, 26, 85, 30302, 3535, 0, 36, 84,
            30486, 3535, 0, 51, 84, 30685, 3535, 0, 48, 84, 30860, 3535, 0, 53, 84, 31012, 3535, 0, 46, 84,
            31159, 3535, 0, 43, 84, 31320, 3535, 0, 52, 84, 31472, 3535, 0, 47, 84, 31532, 3532, 0, 26, 83,
            31664, 3531, 0, 52, 83, 31831, 3531, 0, 53, 83, 31918, 3526, 0, 0, 83, 31979, 3516, 1, 31, 82,
            32040, 3508, 1, 12, 81, 32101, 3501, 1, 5, 80, 32162, 3497, 1, 3, 79, 32222, 3495, 1, 3, 78,
            32283, 3495, 1, 3, 77, 32344, 3492, 1, 21, 76, 32354, 3492, 1, 0, 76, 32415, 3486, 1, 38, 75,
            32475, 3482, 1, 26, 75, 32536, 3477, 1, 24, 74, 32596, 3472, 1, 33, 73, 32658, 3468, 1, 21, 72,
            32719, 3465, 1, 40, 71, 32720, 3465, 1, 0, 71, 32780, 3461, 1, 39, 70, 32841, 3457, 1, 32, 69,
            32902, 3453, 1, 38, 69, 32958, 3450, 1, 0, 67, 33018, 3448, 1, 40, 61, 33079, 3444, 1, 36, 55,
            33139, 3440, 1, 38, 49, 33200, 3438, 1, 34, 43, 33266, 3434, 1, 0, 37, 33326, 3427, 1, 42, 31,
            33387, 3426, 1, 13, 25, 33447, 3425, 1, 3, 18, 33557, 3426, 0, 34, 16, 33613, 3425, 0, 0, 10,
            33674, 3424, 1, 40, 4, 33734, 3423, 1, 33, 1, 33795, 3418, 1, 27, 1, 33855, 3412, 1, 35, 1,
            33915, 3405, 1, 32, 1, 33976, 3401, 1, 38, 1
        )
        val off = 33984 * 1000L
        val g = BatteryGuardLogic()
        // The level that the voltage model had at 07:45 (simulation of the whole night from the unplug, see the record).
        g.restore(47)
        var lowAt = -1L
        var lastAt = -1L
        var shutdownAt = -1L
        var prevShown = 47
        var prevT = 0L
        var i = 0
        while (i < trace.size) {
            val t = trace[i] * 1000L
            g.onSample(t, trace[i + 1], trace[i + 4], false, Load(screenOn = trace[i + 2] == 1, cpuPct = trace[i + 3], wifi = true))
            assertTrue("went up at ${trace[i]}", g.shown <= prevShown)
            if (g.shown < prevShown && prevT > 0) {
                val allowed = maxOf(1L, (t - prevT) / BatteryGuardLogic.STEP_MS)
                assertTrue("too fast at ${trace[i]}", prevShown - g.shown <= allowed)
            }
            prevT = t
            prevShown = g.shown
            if (lowAt < 0 && g.shown <= LowBatteryWarnings.BANNER_LEVEL) lowAt = t
            if (lastAt < 0 && g.shown <= BatteryGuardLogic.LAST_LEVEL) lastAt = t
            if (shutdownAt < 0 && g.shutdownDue) shutdownAt = t
            i += 5
        }
        // The gauge was at about 80 % when the low warning shows, and at 25 % at the last notice. With the curve of
        // calibration run 1 the warning comes ~28 min before the end of this heavy-load trace (~38 min in run 1).
        assertTrue("low warning ${(off - lowAt) / min} min before the end", lowAt in 0..(off - 25 * min))
        assertTrue("last notice ${(off - lastAt) / min} min before the end", lastAt in 0..(off - 8 * min))
        assertTrue("shutdown ${(off - shutdownAt) / min} min before the end", shutdownAt in lastAt..(off - 5 * min))
    }
}
