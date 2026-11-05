package com.themoon.y1.lists

import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView
import com.themoon.y1.ThemeManager
import com.themoon.y1.navigation.LetterJump
import com.themoon.y1.navigation.RowFocus
import com.themoon.y1.views.ListItemViewFactory

/**
 * The one renderer of the list screens ([ListSpec]). Short lists are views in [rows] (a LinearLayout in a
 * ScrollView); long lists go into [list] (a ListView), and only one of the two shows.
 *
 * - [show]: makes the rows and selects the first row (or the remembered row, [Focus.LAST]). Back then selects
 *   the row that opened the deeper list ([com.themoon.y1.navigation.NavigationManager] after onEnter).
 * - [update]: makes the rows again and keeps the selected row (by key, else by place): live lists, theme change.
 * - [wheel]: one step ([RowFocus]: Wheel Loop Scroll, rows that cannot take the focus are skipped,
 *   List Edges fade in the ListView), with the click feedback.
 * - Rows: one click feedback, then the row's action; a hold (long press) does the row's hold action.
 * - [ListSpec.pane]: the split screen shows with the list, and goes when a list without it shows or the screen exits.
 * - [ListSpec.letters]: the iPod letter jump ([LetterJump.Mode]) in [wheel], with the letter in [letterBox]; the
 *   screen gives its keys to [key] (a centre press ends letter mode).
 */
class ListScreen(
    private val rows: LinearLayout,
    private val list: ListView? = null,
    private val clickFeedback: () -> Unit,
    /** Told the text of each focused [RowLook.MENU] row. */
    private val onRowFocus: ((String) -> Unit)? = null,
    /** Shows the letter of the letter jump ([ListSpec.letters]). */
    private val letterBox: LetterBox? = null
) {
    private val context = rows.context
    private val scroll: View? = rows.parent as? View

    /** The list that shows now, or null before the first [show]. */
    var spec: ListSpec? = null
        private set

    private var shown: List<RowSpec> = emptyList()
    private val lastKeys = HashMap<String, String>()
    private var pane: Pane? = null

    private val letterMode = LetterJump.Mode()
    private val letterIdle = Runnable { endLetters() }
    private var letters: List<String?>? = null
    private var lettersOf: ListSpec? = null

    /** The row of the long list that the wheel selected last (its view can still be on the way); -1 = none. */
    private var longIndex = -1

    /** The letter in the letter box during letter mode; null when the wheel steps row by row. */
    var jumpLetter: String? = null
        private set

    val title: String? get() = spec?.title

    fun show(spec: ListSpec) {
        endLetters()
        build(spec)
        if (spec.isLong) {
            val lv = list ?: return
            lv.setSelection(0)
            lv.post { if (lv.childCount > 0) lv.getChildAt(0).requestFocus() }
            return
        }
        (scroll as? ScrollView)?.scrollTo(0, 0)
        if (spec.focus == Focus.NONE) {
            // The list has the focus, not a row: the first wheel step selects the first row (RowFocus.step)
            rows.isFocusable = true
            focusNowAndAfter(rows)
            return
        }
        val last = lastKeys[spec.id]
        val i = ListModel.firstRow(shown.map { it.key }, spec.focus, last, shown.indexOfFirst { it.checked }, ::selectable)
        if (i < 0) return
        val row = rows.getChildAt(i)
        focusNowAndAfter(row)
        // A chosen value (Focus.CHECKED): the ScrollView brings the row into view (no centring)
        if (spec.focus == Focus.LAST && shown[i].key == last) centreAfterLayout(row)
    }

    /**
     * Focus [view] now (the slide draws the new screen with its row), and again after the posted steps of the screen
     * before (a first-row post of the old screen can take the focus back) if the list then has no focus.
     */
    private fun focusNowAndAfter(view: View) {
        view.requestFocus()
        rows.post { if ((view === rows || view.parent === rows) && !rows.hasFocus()) view.requestFocus() }
    }

    /** Selects the row with [key] (for example the row that a screen opens at). False if there is no such row. */
    fun select(key: String): Boolean {
        val i = shown.indexOfFirst { it.key == key }
        if (i < 0 || !selectable(i)) return false
        return rows.getChildAt(i).requestFocus()
    }

    /** Makes the rows of [spec] again and keeps the selected row (by its key, else at the same place). */
    fun update(spec: ListSpec) {
        val focused = focusedIndex()
        val listFocused = rows.isFocused
        val oldKey = shown.getOrNull(focused)?.key
        val scrollY = (scroll as? ScrollView)?.scrollY ?: 0
        build(spec)
        if (spec.isLong) return
        val i = ListModel.keptRow(shown.map { it.key }, oldKey, focused, ::selectable)
        if (i >= 0) {
            rows.getChildAt(i).requestFocus()
        } else if (listFocused) {
            // Still no row selected (Focus.NONE): the list keeps the focus
            rows.isFocusable = true
            rows.requestFocus()
        }
        (scroll as? ScrollView)?.scrollTo(0, scrollY)
    }

    /**
     * One wheel step in the list that shows, at [timeMs] (the key event time, for the letter jump). True when this
     * screen has a list (the wheel is used, also at an end).
     */
    fun wheel(delta: Int, timeMs: Long = SystemClock.uptimeMillis()): Boolean {
        val s = spec ?: return false
        if (s.letters && letterStep(s, delta, timeMs)) return true
        val moved = if (s.isLong) list?.let { stepLong(s, it, delta) } else RowFocus.step(rows, delta)
        if (moved != null) clickFeedback()
        return true
    }

    /**
     * [RowFocus.step] in the ListView. A fast wheel can come before the row of the last step has its view (and the
     * focus): then the step starts from that row ([longIndex]), not from the first row.
     */
    private fun stepLong(s: ListSpec, lv: ListView, delta: Int): Int? {
        val current = selectedIndex(s).takeIf { it >= 0 } ?: longIndex
        val adapter = lv.adapter
        val i = RowFocus.next(lv.count, current, delta, RowFocus.isLoopOn(lv.context)) { adapter?.isEnabled(it) != false }
            ?: return null
        RowFocus.showRow(lv, i)
        longIndex = i
        return i
    }

    /** A key of the screen ([com.themoon.y1.controllers.ScreenController.dispatchKeyEvent]). True = used (letter mode). */
    fun key(event: KeyEvent): Boolean =
        when (letterMode.onKey(event.keyCode, event.action == KeyEvent.ACTION_DOWN, event.repeatCount)) {
            LetterJump.Press.PASS -> false
            LetterJump.Press.DROP -> true
            LetterJump.Press.END -> { endLetters(); true }
            LetterJump.Press.END_AND_PASS -> { endLetters(); false }
        }

    /** Selects row [index] after [show] (a row that the screen chooses, for example the song that plays), centred. */
    fun select(index: Int) {
        val s = spec ?: return
        if (s.isLong) {
            list?.let { if (index in 0 until it.count) RowFocus.showRow(it, index) }
            return
        }
        val row = rows.getChildAt(index) ?: return
        row.requestFocus()
        centreAfterLayout(row)
    }

    /** Remove the rows and the split screen (the screen exits). The custom views go back to no parent, for the next [show]. */
    fun clear() {
        endLetters()
        pane?.hide()
        rows.removeAllViews()
        rows.isFocusable = false
        shown = emptyList()
    }

    /** Ends letter mode (idle wheel, a key, another screen): the list stays at the row of the letter, the box goes. */
    fun endLetters() {
        rows.removeCallbacks(letterIdle)
        letterMode.end()
        if (jumpLetter == null) return
        jumpLetter = null
        letterBox?.hide()
    }

    /**
     * Letter mode ([LetterJump.Mode]): selects the first row of the next or previous letter, shows the letter and
     * returns true. Else false: the wheel steps one row.
     */
    private fun letterStep(s: ListSpec, delta: Int, timeMs: Long): Boolean {
        if (letterMode.isIdle(timeMs)) endLetters() // the idle timer did not run yet
        // In letter mode the jump goes on from the letter row: its view (and the focus) can still be on the way
        val from = if (letterMode.isActive) letterMode.row else selectedIndex(s).takeIf { it >= 0 } ?: longIndex
        if (from < 0) return false
        val step = letterMode.onStep(delta, timeMs, from) {
            if (lettersOf !== s) {
                letters = LetterJump.lettersFor(rowTexts(s))
                lettersOf = s
            }
            letters
        }
        if (step == LetterJump.Step.ROW) return false
        jumpLetter = letters?.getOrNull(letterMode.row) // before the focus moves: the focused row can show the box
        if (step == LetterJump.Step.LETTER) {
            if (s.isLong) {
                list?.let { RowFocus.showRow(it, letterMode.row) }
                longIndex = letterMode.row
            } else {
                rows.getChildAt(letterMode.row)?.requestFocus()
            }
            clickFeedback()
        }
        jumpLetter?.let { letterBox?.show(it) }
        rows.removeCallbacks(letterIdle)
        rows.postDelayed(letterIdle, LetterJump.IDLE_END_MS)
        return true
    }

    private fun rowTexts(s: ListSpec): List<String?> {
        if (!s.isLong) return ListModel.letterTexts(shown)
        val adapter = s.adapter ?: return emptyList()
        val texts = adapter as? RowTexts
        return (0 until adapter.count).map { if (texts != null) texts.rowText(it) else adapter.getItem(it) as? String }
    }

    /** The index of the focused row, or -1. */
    private fun selectedIndex(s: ListSpec): Int {
        if (!s.isLong) return focusedIndex()
        val lv = list ?: return -1
        val focus = lv.findFocus() ?: return -1
        return if (focus === lv) -1 else lv.getPositionForView(focus)
    }

    private fun build(spec: ListSpec) {
        this.spec = spec
        longIndex = -1
        val newPane = spec.pane
        if (newPane != null) newPane.show() else pane?.hide()
        if (newPane != null) pane = newPane
        rows.removeAllViews()
        rows.isFocusable = false
        val long = spec.isLong
        scroll?.visibility = if (long) View.GONE else View.VISIBLE
        list?.visibility = if (long) View.VISIBLE else View.GONE
        if (long) {
            list?.adapter = spec.adapter
            shown = emptyList()
            return
        }
        shown = ListModel.shownRows(spec)
        for (row in shown) rows.addView(makeRow(spec, row))
    }

    private fun makeRow(spec: ListSpec, row: RowSpec): View {
        val view = when (row.look) {
            RowLook.MENU -> {
                val color = if (row.key == ListModel.EMPTY_KEY) ThemeManager.getTextColorSecondary() else row.color
                ListItemViewFactory.createListButtonWithIcon(context, "", row.label, color, onRowFocus)
            }
            RowLook.VALUE -> ListItemViewFactory.createSettingRow(context, row.label, row.value)
            RowLook.CHOICE ->
                if (row.glyphs.isEmpty()) ListItemViewFactory.createCheckListButton(context, row.label, row.checked)
                else ListItemViewFactory.createChoiceRowWithRightIcons(context, row.label, row.checked, row.glyphs)
            RowLook.ART -> ArtRow.of(null, context, inListView = false).also { art ->
                art.bind(row.label, row.value)
                val image = row.image
                if (image != null) image(art.image) else art.image.visibility = View.GONE
            }.view
            RowLook.CUSTOM -> row.view!!.also { (it.parent as? ViewGroup)?.removeView(it) }
            RowLook.HEADER -> headerRow(row.label)
            RowLook.INFO -> infoRow(row.label)
        }
        if (row.id != View.NO_ID) view.id = row.id
        if (!row.enabled) {
            view.isFocusable = false
            if (view is ViewGroup) for (i in 0 until view.childCount) view.getChildAt(i).alpha = DISABLED_ALPHA
        }
        val click = row.onClick
        if (click != null) {
            view.setOnClickListener {
                if (!row.feedbackAfter) clickFeedback()
                lastKeys[spec.id] = row.key
                click()
                if (row.feedbackAfter) clickFeedback()
            }
        }
        val hold = row.onHold
        if (hold != null) {
            view.isLongClickable = true
            view.setOnLongClickListener {
                clickFeedback()
                hold()
                true
            }
        }
        return view
    }

    /** A row that the wheel can select: it shows and can take the focus (not a header, an info row or a hidden row). */
    private fun selectable(i: Int): Boolean = RowFocus.isSelectable(rows.getChildAt(i))

    private fun headerRow(text: String): TextView = TextView(context).apply {
        val d = resources.displayMetrics.density
        this.text = "━ $text ━"
        setTextColor(ThemeManager.getTextColorSecondary())
        textSize = 12f
        gravity = Gravity.CENTER
        setPadding(0, (8 * d).toInt(), 0, (4 * d).toInt())
        isFocusable = false
    }

    private fun infoRow(text: String): TextView = TextView(context).apply {
        val d = resources.displayMetrics.density
        val classic = ThemeManager.isClassicTheme()
        val padX = ((if (classic) 8 else 12) * d).toInt()
        this.text = text
        setTextColor(ThemeManager.getTextColorSecondary())
        setTextSize(TypedValue.COMPLEX_UNIT_PX, (if (classic) 19f else 14f) * d)
        typeface = ThemeManager.getCustomFont()
        setPadding(padX, (6 * d).toInt(), padX, (6 * d).toInt())
        isFocusable = false
        isClickable = false
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun focusedIndex(): Int = (0 until rows.childCount).firstOrNull { rows.getChildAt(it).hasFocus() } ?: -1

    /** A remembered row in the middle of the list, after the rows have their places. */
    private fun centreAfterLayout(row: View) {
        val sv = scroll as? ScrollView ?: return
        sv.viewTreeObserver.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                @Suppress("DEPRECATION")
                sv.viewTreeObserver.removeGlobalOnLayoutListener(this)
                if (row.parent !== rows) return
                sv.scrollTo(0, (row.top - (sv.height / 2 - row.height / 2)).coerceAtLeast(0))
            }
        })
    }

    companion object {
        /** Alpha of the title and the value of a row that cannot be used now ([RowSpec.enabled] false). */
        const val DISABLED_ALPHA = 0.4f
    }

    /** The value text of the row with [key] (for a value that changes in place, for example "Wait..."). */
    fun valueView(key: String): TextView? {
        val i = shown.indexOfFirst { it.key == key }
        if (i < 0) return null
        return (rows.getChildAt(i) as? ViewGroup)?.getChildAt(1) as? TextView
    }
}
