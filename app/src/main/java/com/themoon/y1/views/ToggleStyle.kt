package com.themoon.y1.views

import android.content.Context
import android.view.View
import com.themoon.y1.core.extensions.onOffState
import java.util.Collections
import java.util.WeakHashMap

/**
 * The "Toggle Style" setting: how on/off setting rows show their value.
 * - Text (default): the text "On" or "Off".
 * - Switch: a small switch that [SettingValueView] draws. The row text stays "On"/"Off" (tests, accessibility).
 *
 * A value is on/off when it comes from `onOff()` (see Localization.kt). A change applies at once to all rows.
 */
object ToggleStyle {
    const val PREF_KEY = "toggle_style"
    const val TEXT = "text"
    const val SWITCH = "switch"
    private const val PREFS_NAME = "AppPrefs"

    /** What a setting value shows. */
    enum class Look { TEXT, SWITCH_ON, SWITCH_OFF }

    /** [onOffState] is null for a value that is not an on/off value (for example "Weak" or "5 Min"). */
    fun look(isSwitchStyle: Boolean, onOffState: Boolean?): Look = when {
        !isSwitchStyle || onOffState == null -> Look.TEXT
        onOffState -> Look.SWITCH_ON
        else -> Look.SWITCH_OFF
    }

    /**
     * The on/off state of a setting value after a new text: the state of the text's mark. A text without a mark
     * that is the same as the shown text keeps the state: TextView sets its own text again in some calls.
     * Other texts without a mark ("Wait...", "Weak") have no state.
     */
    fun nextState(previous: Boolean?, newText: CharSequence?, shownText: CharSequence?): Boolean? =
        onOffState(newText)
            ?: if (previous != null && newText?.toString() == shownText?.toString()) previous else null

    /** The stored value is [SWITCH] or [TEXT]; any other value (or none) is Text. */
    fun isSwitchPref(value: String?): Boolean = value == SWITCH

    fun prefValue(isSwitch: Boolean): String = if (isSwitch) SWITCH else TEXT

    /** Language key of the row value. "Switch" alone is a verb in the language files, so the keys are separate. */
    fun labelKey(isSwitch: Boolean): String = if (isSwitch) "Switch (Toggle Style)" else "Text (Toggle Style)"

    /**
     * Row value: "Text" or "Switch", translated. English (the default) loads no language file, so the
     * translation of the key is the key itself: then use the English word.
     */
    fun label(isSwitch: Boolean, localize: (String) -> String): String {
        val key = labelKey(isSwitch)
        val text = localize(key)
        return if (text == key) (if (isSwitch) "Switch" else "Text") else text
    }

    class SwitchColors(val track: Int, val knob: Int)

    /**
     * Colours of the switch. Normal row: On = [accent] track, white knob; Off = grey track, white knob.
     * Selected row (its background is the accent): On = track in the selected text colour, accent knob;
     * Off = pale track, knob in the selected text colour.
     */
    fun colors(on: Boolean, rowSelected: Boolean, accent: Int, selectedText: Int, secondaryText: Int): SwitchColors =
        SwitchColors(
            trackColor(on, rowSelected, accent, selectedText, secondaryText),
            knobColor(on, rowSelected, accent, selectedText)
        )

    /** The track colour of [colors] (no object: [SettingValueView] calls it in each animation frame). */
    fun trackColor(on: Boolean, rowSelected: Boolean, accent: Int, selectedText: Int, secondaryText: Int): Int =
        when {
            rowSelected && on -> opaque(selectedText)
            rowSelected -> withAlpha(selectedText, 0x66)
            on -> opaque(accent)
            else -> withAlpha(grey(secondaryText), 0x99)
        }

    /** The knob colour of [colors]. */
    fun knobColor(on: Boolean, rowSelected: Boolean, accent: Int, selectedText: Int): Int =
        when {
            rowSelected && on -> opaque(accent)
            rowSelected -> opaque(selectedText)
            else -> WHITE
        }

    // Animation of a change (a click): the knob slides and the colours cross-fade. Knob position: 0 = Off, 1 = On.

    /** Duration of a full Off-to-On change. */
    const val ANIMATION_MS = 200L

    /** A row that a controller removes and makes again within this time after a change continues the animation. */
    const val REBUILD_WINDOW_MS = 1000L

    /** Ease out (cubic, decelerates like the iOS switch): fast start, slow end. [t] in 0..1. */
    fun easeOut(t: Float): Float {
        val u = 1f - t.coerceIn(0f, 1f)
        return 1f - u * u * u
    }

    /** The time a change from knob position [from] to [on] takes: shorter when the knob is already part of the way. */
    fun durationMs(from: Float, on: Boolean): Long =
        Math.round(ANIMATION_MS * Math.abs((if (on) 1f else 0f) - from)).toLong()

    /** The knob position [elapsedMs] after the start of a change from position [from] to [on]. */
    fun knobPosition(from: Float, on: Boolean, elapsedMs: Long): Float {
        val to = if (on) 1f else 0f
        val duration = durationMs(from, on)
        if (elapsedMs >= duration) return to
        if (elapsedMs <= 0L) return from
        return from + (to - from) * easeOut(elapsedMs.toFloat() / duration)
    }

    /** [a] at [f] = 0 to [b] at [f] = 1, each ARGB channel separately (as ArgbEvaluator). */
    fun blend(a: Int, b: Int, f: Float): Int {
        if (f <= 0f) return a
        if (f >= 1f) return b
        var out = 0
        var shift = 24
        while (shift >= 0) {
            val ca = (a ushr shift) and 0xFF
            val cb = (b ushr shift) and 0xFF
            out = out or (Math.round(ca + (cb - ca) * f) shl shift)
            shift -= 8
        }
        return out
    }

    private class Removed(val position: Float, val timeMs: Long)
    private val removed = HashMap<String, Removed>()

    /** A switch row left the screen (its list is made again, or the screen closed): keep its knob position for a short time. */
    fun rememberRemoved(key: String, position: Float, nowMs: Long) {
        val it = removed.entries.iterator()
        while (it.hasNext()) if (nowMs - it.next().value.timeMs > REBUILD_WINDOW_MS) it.remove()
        removed[key] = Removed(position, nowMs)
    }

    /**
     * For a new row with the same [key]: the knob position of the removed row, if it was removed less than
     * [REBUILD_WINDOW_MS] ago and did not show [on]. Then the new row animates from there. Else null.
     */
    fun takeRemoved(key: String, on: Boolean, nowMs: Long): Float? {
        val r = removed.remove(key) ?: return null
        if (nowMs - r.timeMs > REBUILD_WINDOW_MS) return null
        return if (r.position == (if (on) 1f else 0f)) null else r.position
    }

    private const val WHITE = 0xFFFFFFFF.toInt()

    private fun opaque(color: Int): Int = color or 0xFF000000.toInt()

    private fun withAlpha(color: Int, alpha: Int): Int = (alpha shl 24) or (color and 0x00FFFFFF)

    /** A neutral grey with the luminance of [color]. */
    internal fun grey(color: Int): Int {
        val r = (color shr 16) and 0xFF
        val g = (color shr 8) and 0xFF
        val b = color and 0xFF
        val l = (299 * r + 587 * g + 114 * b + 500) / 1000
        return 0xFF000000.toInt() or (l shl 16) or (l shl 8) or l
    }

    private var switchStyle: Boolean? = null
    private val views: MutableSet<View> = Collections.newSetFromMap(WeakHashMap())

    fun isSwitch(context: Context): Boolean =
        switchStyle ?: isSwitchPref(
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(PREF_KEY, TEXT)
        ).also { switchStyle = it }

    /** Saves the setting and applies it at once to all setting rows. */
    fun setSwitch(context: Context, on: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putString(PREF_KEY, prefValue(on)).commit()
        reload(context)
    }

    /** Reads the setting again (for example after Reset All Settings) and applies it to all setting rows. */
    fun reload(context: Context) {
        switchStyle = null
        isSwitch(context)
        for (v in views.toList()) {
            v.requestLayout()
            v.invalidate()
        }
    }

    internal fun register(view: View) {
        views.add(view)
    }
}
