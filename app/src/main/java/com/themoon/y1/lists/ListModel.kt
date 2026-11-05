package com.themoon.y1.lists

import android.view.View
import android.widget.ImageView
import android.widget.ListAdapter
import com.themoon.y1.views.RowGlyphs

/**
 * One list screen as data: a screen gives a [ListSpec], and [ListScreen] makes the rows and does the common work
 * (first row, empty row, wheel step with Wheel Loop Scroll and List Edges, title, click feedback, holds, the row
 * after a theme change). The screen keeps only its content: what the rows are and what they do.
 */

/** How a row looks. Each look is one row factory of [com.themoon.y1.views.ListItemViewFactory]. */
enum class RowLook {
    /** Label; the chevron shows on the focused row (Music lists, Playlists). */
    MENU,

    /** Label and a value on the right (Settings rows; On/Off shows as a switch with Toggle Style = Switch). */
    VALUE,

    /** Tick column, label and status glyphs on the right (Wi-Fi networks, Bluetooth devices). */
    CHOICE,

    /**
     * Cover art (or a thumbnail), a title and a second line ([RowSpec.value]); the chevron on the focused row
     * ([ArtRow]: album rows, videos).
     */
    ART,

    /** A view that the screen made itself ([RowSpec.view]); the renderer only places it and sets the actions. */
    CUSTOM,

    /** A section title ("━ Paired Devices ━": small, grey, centred). The wheel skips it. */
    HEADER,

    /** Grey text, on more lines when it is long (a message, a hint, a progress line). The wheel skips it. */
    INFO
}

/**
 * One row. [key] identifies the row for the remembered row ([Focus.LAST]) and for the row that stays selected
 * when the rows change ([ListScreen.update]); by default it is the label.
 */
class RowSpec(
    val label: String,
    val look: RowLook = RowLook.MENU,
    /** [RowLook.VALUE]: the value on the right. [RowLook.ART]: the second line (none when empty). */
    val value: String = "",
    val checked: Boolean = false,
    val glyphs: List<RowGlyphs.Slot> = emptyList(),
    /** Text colour of a [RowLook.MENU] row; 0 = the theme colour. */
    val color: Int = 0,
    val id: Int = View.NO_ID,
    val view: View? = null,
    /** [RowLook.ART]: puts the art into the row's image (at once, or later from a worker thread); null = no image. */
    val image: ((ImageView) -> Unit)? = null,
    val key: String = label,
    /** False: the row is grey and the wheel skips it (a setting that cannot be used now). */
    val enabled: Boolean = true,
    /** True: the click feedback comes after [onClick] (a row that changes the click sound or vibration plays the new one). */
    val feedbackAfter: Boolean = false,
    val onClick: (() -> Unit)? = null,
    val onHold: (() -> Unit)? = null
)

/** The row that a list selects when it opens going down. Back always selects the row that opened the deeper list. */
enum class Focus {
    /** The first row (the iPod rule). */
    FIRST,

    /** The row that the user last opened in this list ([ListSpec.id]), else the first row (Playlists, C-6). */
    LAST,

    /**
     * No row: the list itself has the focus, and the first wheel step selects the first row. Then one centre push
     * cannot do a destructive action by accident (action screens).
     */
    NONE,

    /** The first row with [RowSpec.checked] (the chosen value of a value list), else the first row. */
    CHECKED
}

/**
 * A list screen. Short lists give [rows] (views in a LinearLayout); long lists give an [adapter] (a ListView that
 * makes views only for the rows on the screen). With no rows, the list shows one row with [empty].
 */
class ListSpec(
    val title: String?,
    val rows: List<RowSpec> = emptyList(),
    val adapter: ListAdapter? = null,
    val empty: String? = null,
    val focus: Focus = Focus.FIRST,
    /** Identifies the list for [Focus.LAST]. */
    val id: String = title.orEmpty(),
    /** The split screen: the list at the left, the art pane at the right. Null = the list has the full width. */
    val pane: Pane? = null,
    /**
     * An A–Z list: a fast wheel turn starts the iPod letter jump ([com.themoon.y1.navigation.LetterJump]). Rows give
     * their letter by their label; an [adapter] gives it with [RowTexts]. Rows that are not in A–Z order step one by one.
     */
    val letters: Boolean = false
) {
    val isLong: Boolean get() = adapter != null && adapter.count > 0
}

/** The adapter of a long list with [ListSpec.letters]: the text of each row for its letter (null = no letter). */
interface RowTexts {
    fun rowText(position: Int): String?
}

/** The letter box of the letter jump: it shows the letter while the wheel jumps from letter to letter. */
interface LetterBox {
    fun show(letter: String)
    fun hide()
}

/** The pure rules of the renderer. */
object ListModel {

    /**
     * The row to select when the list opens: the remembered row ([lastKey]) for [Focus.LAST], the [checked] row
     * (index, -1 = none) for [Focus.CHECKED], else the first row that can be selected ([selectable]: not a header
     * or an info row). -1 = no row.
     */
    fun firstRow(
        keys: List<String>, focus: Focus, lastKey: String?, checked: Int = -1, selectable: (Int) -> Boolean = { true }
    ): Int {
        if (focus == Focus.NONE) return -1
        if (focus == Focus.CHECKED && checked in keys.indices && selectable(checked)) return checked
        if (focus == Focus.LAST && lastKey != null) {
            val i = keys.indexOf(lastKey)
            if (i >= 0 && selectable(i)) return i
        }
        return keys.indices.firstOrNull(selectable) ?: -1
    }

    /**
     * The row to select after the rows changed: the row with the same key, else the row at the same place
     * (the last row if the list is now shorter), else none (-1).
     */
    fun keptRow(keys: List<String>, oldKey: String?, oldIndex: Int, selectable: (Int) -> Boolean = { true }): Int {
        if (keys.isEmpty() || oldIndex < 0) return -1
        if (oldKey != null) {
            val i = keys.indexOf(oldKey)
            if (i >= 0 && selectable(i)) return i
        }
        // The row at the same place, else the next row that can be selected, else the one before it
        val place = oldIndex.coerceAtMost(keys.size - 1)
        return (place until keys.size).firstOrNull(selectable) ?: (place - 1 downTo 0).firstOrNull(selectable) ?: -1
    }

    /** The rows that show: [ListSpec.rows], or the one [ListSpec.empty] row. */
    fun shownRows(spec: ListSpec): List<RowSpec> =
        if (spec.rows.isEmpty() && spec.empty != null && !spec.isLong) listOf(RowSpec(spec.empty, key = EMPTY_KEY)) else spec.rows

    /** The text of each shown row for the letter jump: the label (none for the empty row). */
    fun letterTexts(rows: List<RowSpec>): List<String?> = rows.map { if (it.key == EMPTY_KEY) null else it.label }

    const val EMPTY_KEY = "\u0000empty"
}
