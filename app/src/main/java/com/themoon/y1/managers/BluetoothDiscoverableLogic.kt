package com.themoon.y1.managers

import java.util.Locale

/** Pure rules for the Discoverable row of the Bluetooth screen and for the Bluetooth name. */
object BluetoothDiscoverableLogic {

    /** How long the Y2 stays discoverable after the user turns it on. */
    const val DURATION_S = 120

    /** The Bluetooth name of the Y2 (the name in a car list). The app sets it; there is no setting. */
    const val DEVICE_NAME = "Innioasis Y2"

    /** True if discoverable mode must stop: the Y2 is discoverable, but not from a press on the row (no timer). */
    fun mustTurnOff(discoverable: Boolean, timerRunning: Boolean): Boolean = discoverable && !timerRunning

    /** Time left until [endAtMs], or null if no timer runs ([endAtMs] <= 0). */
    fun remainingMs(endAtMs: Long, nowMs: Long): Long? =
        if (endAtMs <= 0L) null else maxOf(0L, endAtMs - nowMs)

    /** "m:ss", rounded up to the next second: 120 s shows 2:00, 0.2 s shows 0:01. */
    fun formatRemaining(ms: Long): String {
        val totalS = (maxOf(0L, ms) + 999L) / 1000L
        return String.format(Locale.US, "%d:%02d", totalS / 60, totalS % 60)
    }

    /** Time left on the left of the On/Off value ("1:45"), or null: not discoverable, or no timer of ours. */
    fun timeLeftText(discoverable: Boolean, remainingMs: Long?): String? =
        if (!discoverable || remainingMs == null) null else formatRemaining(remainingMs)

    /**
     * Top padding that puts the centre of the capital letters (and digits) on the centre of a box of [boxHeight]
     * px, with gravity TOP and includeFontPadding = false (baseline at paddingTop - [ascent]).
     * Same rule as the row titles of the iPod Classic layout.
     */
    fun capCentredPaddingTop(boxHeight: Int, ascent: Int, capHeight: Float): Int =
        Math.round(boxHeight / 2f + ascent + capHeight / 2f).coerceAtLeast(0)

    /** The name to give the adapter, or null if it already has [DEVICE_NAME]. */
    fun nameToSet(current: String?): String? = if (current == DEVICE_NAME) null else DEVICE_NAME
}
