package com.themoon.y1.controllers.settings

import android.content.SharedPreferences
import android.view.View
import com.themoon.y1.BoolPref
import com.themoon.y1.IntPref
import com.themoon.y1.core.extensions.onOff
import com.themoon.y1.lists.Focus
import com.themoon.y1.lists.ListSpec
import com.themoon.y1.lists.RowLook
import com.themoon.y1.lists.RowSpec

/** One row of a Settings page: its English label (the language key) and how to make it with the values of now. */
class SettingRow(val label: String, val make: () -> RowSpec)

/** A group of the Settings top level. A [direct] group has one row, and the top level shows that row itself (no group page). */
class SettingsGroup(val title: String, val rows: List<SettingRow>, val direct: Boolean = false)

/** One value of a value list ([SettingRows.valueList]). */
class SettingValue(val label: String, val checked: Boolean, val choose: () -> Unit)

/**
 * The kinds of Settings rows (A11). A row reads its value when its page is made and writes through the typed prefs
 * ([com.themoon.y1.Prefs]); after a change, [refresh] makes the page again, and the row stays selected.
 */
class SettingRows(
    private val prefs: SharedPreferences,
    private val localize: (String) -> String,
    private val refresh: () -> Unit
) {
    /** An On/Off row ([text]: other value texts, for example Fade/Cut). [onChange] gets the new value. */
    fun toggle(
        label: String, pref: BoolPref, text: ((Boolean) -> String)? = null, enabled: () -> Boolean = { true },
        feedbackAfter: Boolean = false, onChange: (Boolean) -> Unit = {}
    ): SettingRow = toggle(label, { pref.get(prefs) }, { pref.set(prefs, it) }, text, enabled, feedbackAfter, onChange)

    /** An On/Off row for a value that is not a pref of its own (it is in a manager). */
    fun toggle(
        label: String, get: () -> Boolean, set: (Boolean) -> Unit, text: ((Boolean) -> String)? = null,
        enabled: () -> Boolean = { true }, feedbackAfter: Boolean = false, onChange: (Boolean) -> Unit = {}
    ): SettingRow = SettingRow(label) {
        val on = get()
        RowSpec(localize(label), RowLook.VALUE, value = text?.invoke(on) ?: onOff(on, localize), enabled = enabled(),
            feedbackAfter = feedbackAfter, onClick = {
                val next = !get()
                set(next)
                onChange(next)
                refresh()
            })
    }

    /**
     * A row that goes to the next of [values] (language keys) at each push, after the last to the first.
     * [valid] gives the index of a stored value (a value that is not in the list: the first, or a default).
     */
    fun cycle(
        label: String, pref: IntPref, values: Array<String>, valid: (Int) -> Int = { if (it in values.indices) it else 0 },
        enabled: () -> Boolean = { true }, feedbackAfter: Boolean = false, onChange: (Int) -> Unit = {}
    ): SettingRow = cycle(label, { valid(pref.get(prefs)) }, { pref.set(prefs, it) }, values, enabled, feedbackAfter, onChange)

    /** A cycle row for a value that is not a pref of its own. [get] gives an index in [values]. */
    fun cycle(
        label: String, get: () -> Int, set: (Int) -> Unit, values: Array<String>, enabled: () -> Boolean = { true },
        feedbackAfter: Boolean = false, onChange: (Int) -> Unit = {}
    ): SettingRow = SettingRow(label) {
        RowSpec(localize(label), RowLook.VALUE, value = localize(values[get()]), enabled = enabled(),
            feedbackAfter = feedbackAfter, onClick = {
                val next = (get() + 1) % values.size
                set(next)
                onChange(next)
                refresh()
            })
    }

    /** A row that opens the list of its values ([valueList]): the chosen value shows on the right. */
    fun choice(label: String, value: () -> String, open: () -> Unit): SettingRow =
        SettingRow(label) { RowSpec(localize(label), RowLook.VALUE, value = value(), onClick = open) }

    /** A row that opens a screen (the chevron) or does an action. [value]: a status on the right in place of the chevron. */
    fun action(label: String, value: () -> String = { CHEVRON }, enabled: () -> Boolean = { true }, run: () -> Unit): SettingRow =
        SettingRow(label) { RowSpec(localize(label), RowLook.VALUE, value = value(), enabled = enabled(), onClick = run) }

    /**
     * The list of a [choice] row: one row for each value, and it opens at the chosen value (the iPod rule for a value list).
     * The rows are tick rows, or the views of [view] (the centred numbers of Volume Limit and Date & Time).
     */
    fun valueList(title: String, values: List<SettingValue>, focus: Focus = Focus.CHECKED, view: ((String) -> View)? = null): ListSpec =
        ListSpec(title, values.map { v ->
            if (view == null) RowSpec(v.label, RowLook.CHOICE, checked = v.checked, onClick = v.choose)
            else RowSpec(v.label, RowLook.CUSTOM, checked = v.checked, view = view(v.label), onClick = v.choose)
        }, focus = focus)

    companion object {
        /** The value of a row that opens a screen: a chevron (ChevronView). */
        const val CHEVRON = "〉 "
    }
}
