package com.themoon.y1.core.extensions

// On/off setting values. Several branches add this same file, byte for byte, so that their merges are clean.
// Change all copies together.

/**
 * Language key for the value of an on/off setting: "On" or "Off", as on the iPod Classic.
 * All languages in assets/languages must have both keys.
 */
fun onOffKey(enabled: Boolean): String = if (enabled) "On" else "Off"

/**
 * Invisible marks at the end of an on/off value. They tell a setting row that the value is an on/off value,
 * and which, so that the row can show a switch in place of the text. The row removes the mark before it
 * shows the text, so its text (for tests and accessibility) is still "On" or "Off".
 */
const val ON_MARK = '​' // zero-width space
const val OFF_MARK = '‌' // zero-width non-joiner

fun onOffMark(enabled: Boolean): Char = if (enabled) ON_MARK else OFF_MARK

/**
 * Translated value of an on/off setting, with its mark. Use it for every on/off row, so that all rows show
 * the same text. Give it to a setting row value, not to a plain TextView.
 */
fun onOff(enabled: Boolean, localize: (String) -> String): String = localize(onOffKey(enabled)) + onOffMark(enabled)

/** The state that the mark at the end of [text] gives, or null if [text] has no on/off mark. */
fun onOffState(text: CharSequence?): Boolean? = when (text?.lastOrNull()) {
    ON_MARK -> true
    OFF_MARK -> false
    else -> null
}

/** [text] without the on/off mark at its end. */
fun withoutOnOffMark(text: CharSequence?): CharSequence? =
    if (onOffState(text) != null) text!!.subSequence(0, text.length - 1) else text
