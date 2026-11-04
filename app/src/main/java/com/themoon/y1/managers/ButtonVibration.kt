package com.themoon.y1.managers

/**
 * Settings → Controls → Vibration Strength: the length of the one vibration for each push and each wheel step
 * (clickFeedback). A longer pulse feels stronger on the Y2's motor, so the lengths go up from Weak to Strong.
 * Android's own key vibration is off (HAPTIC_FEEDBACK_ENABLED = 0), so a push does not vibrate twice.
 */
object ButtonVibration {
    const val PREF = "vibrate_strength"
    const val DEFAULT = 1

    /** The labels of the levels (translation keys). */
    val LABELS = arrayOf("Weak", "Normal", "Strong")
    private val LENGTH_MS = longArrayOf(15L, 25L, 40L)

    fun validLevel(level: Int): Int = if (level in LENGTH_MS.indices) level else DEFAULT

    fun lengthMs(level: Int): Long = LENGTH_MS[validLevel(level)]

    // The vibrator call waits for the system server (about 8 ms on the Y2): a push does not wait for it on the main thread
    private val worker = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "y1-vibrate") }

    /** Starts the vibration of one push at [level], on the vibration thread. */
    fun vibrate(vibrator: android.os.Vibrator, level: Int) {
        val ms = lengthMs(level)
        worker.execute {
            try {
                vibrator.vibrate(ms)
            } catch (_: Exception) {
            }
        }
    }
}
