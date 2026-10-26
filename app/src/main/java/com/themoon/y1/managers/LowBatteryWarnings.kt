package com.themoon.y1.managers

/**
 * When to show the low-battery warnings of the app, which replace Android's low-battery dialog.
 *
 * - At [bannerLevel] % or less: a short banner ("Battery low: 10 %").
 * - At [screenLevel] % or less: a full screen with an empty battery ("Please charge").
 *
 * Each warning shows one time in each discharge; a warning that was closed comes back only at the next (lower) level
 * or in the next discharge. When the level goes below both levels at the same time, only the screen shows. Charging
 * starts a new discharge and removes a warning that shows. A warning never stops the use of the Y2: any key closes it
 * and does nothing else ([onKey]), and playback continues.
 *
 * No Android dependencies, for the unit tests. The views are in LowBatteryWarningView.
 */
class LowBatteryWarnings(
    val bannerLevel: Int = BANNER_LEVEL,
    val screenLevel: Int = SCREEN_LEVEL
) {
    enum class Warning { NONE, BANNER, SCREEN }

    /** The warning that shows (or that waits until the screen is on). */
    var warning = Warning.NONE
        private set

    /** The battery level of [warning], for its text. */
    var warningLevel = -1
        private set

    private var bannerDone = false
    private var screenDone = false
    private var heldKey = NO_KEY
    private var heldUntil = 0L

    /** A battery broadcast: [level] in %, -1 when not known. Returns true when [warning] changed. */
    fun onBattery(level: Int, charging: Boolean): Boolean {
        if (charging) {
            bannerDone = false
            screenDone = false
            return show(Warning.NONE, -1)
        }
        if (level < 0) return false
        if (level <= screenLevel && !screenDone) {
            screenDone = true
            bannerDone = true
            return show(Warning.SCREEN, level)
        }
        if (level <= bannerLevel && !bannerDone) {
            bannerDone = true
            return show(Warning.BANNER, level)
        }
        return false
    }

    /** The banner time ended. Returns true when [warning] changed. */
    fun onBannerTimeout(): Boolean = if (warning == Warning.BANNER) show(Warning.NONE, -1) else false

    /**
     * A key event while the screen is on. Returns true when the app must ignore the key: the first push of a key
     * closes the warning that shows, and that key does nothing else until it is released. The same key again within
     * [KEY_AFTER_MS] of its release is also ignored, so the other steps of one wheel turn do not move the list.
     */
    fun onKey(keyCode: Int, down: Boolean, repeatCount: Int, nowMs: Long): Boolean {
        if (down) {
            if (warning != Warning.NONE && repeatCount == 0) {
                show(Warning.NONE, -1)
                heldKey = keyCode
                heldUntil = HELD
                return true
            }
            if (keyCode != heldKey) return false
            if (heldUntil == HELD || nowMs <= heldUntil) {
                heldUntil = HELD
                return true
            }
            heldKey = NO_KEY
            return false
        }
        if (keyCode != heldKey || heldUntil != HELD) return false
        heldUntil = nowMs + KEY_AFTER_MS
        return true
    }

    private fun show(w: Warning, level: Int): Boolean {
        val changed = w != warning || (w != Warning.NONE && level != warningLevel)
        warning = w
        warningLevel = level
        return changed
    }

    companion object {
        const val BANNER_LEVEL = 10
        const val SCREEN_LEVEL = 2
        const val BANNER_MS = 3000L
        const val KEY_AFTER_MS = 300L
        private const val NO_KEY = Int.MIN_VALUE
        private const val HELD = Long.MAX_VALUE
    }
}
