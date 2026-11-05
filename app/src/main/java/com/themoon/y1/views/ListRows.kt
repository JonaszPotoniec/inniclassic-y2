package com.themoon.y1.views

import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.AbsListView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView
import com.themoon.y1.models.SongItem
import com.themoon.y1.navigation.RowKey

/**
 * The selected row of the list on a screen, as a [RowKey], and the selection of a row from a [RowKey].
 * Two kinds of list: an [AbsListView] (songs, albums, artists) and a [LinearLayout] of rows in a
 * [ScrollView] (main menu, Music menu, Settings).
 */
object ListRows {

    /** Changes at each [select] and [cancelPending]: a selection that waits for a layout runs only while it is the last. */
    private var generation = 0

    /** Another list opens: a [select] that still waits must not select a row in it. */
    fun cancelPending() {
        generation++
    }

    /** The row that has the focus under [root], or null if the focus is not in a list that shows. */
    fun selected(root: View): RowKey? {
        val focused = root.findFocus() ?: return null
        var row: View = focused
        var parent = row.parent
        while (parent is ViewGroup) {
            if (parent is AbsListView) {
                if (!parent.isShown) return null
                val index = parent.getPositionForView(row)
                if (index == AbsListView.INVALID_POSITION) return null
                return RowKey(rowText(row), index, row.top)
            }
            val scroll = parent.parent as? ScrollView
            if (parent is LinearLayout && scroll != null) {
                if (!parent.isShown) return null
                return RowKey(rowText(row), parent.indexOfChild(row), row.top - scroll.scrollY)
            }
            if (parent === root) return null
            row = parent
            parent = parent.parent
        }
        return null
    }

    /**
     * Selects the row [key] in the list that shows under [root], with the row at the same height as before,
     * after the screen made its rows. The screen can set its own first focus in its layout pass or in a
     * posted step; this runs after both, so the row that opened the deeper screen is selected at the end.
     */
    fun select(root: View, key: RowKey) {
        val observer = root.viewTreeObserver
        if (!observer.isAlive) return
        val mine = ++generation
        val step = Runnable { if (mine == generation) apply(root, key) }
        observer.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                @Suppress("DEPRECATION")
                root.viewTreeObserver.removeGlobalOnLayoutListener(this)
                step.run()
                root.post { step.run(); root.post(step) }
            }
        })
        root.requestLayout()
    }

    /** Selects [key] in the shown list under [root] now. False if there is no such list. */
    fun apply(root: View, key: RowKey): Boolean {
        if (!root.isShown) return false
        val list = shownList(root) ?: return false
        if (list is AbsListView) {
            val adapter = list.adapter ?: return false
            val texts = ArrayList<String?>(adapter.count)
            // Rows that are not on the screen have no view: take the text of the item when the adapter gives one
            for (i in 0 until adapter.count) {
                val visible = list.getChildAt(i - list.firstVisiblePosition)
                texts.add(if (visible != null) rowText(visible) else itemText(adapter.getItem(i)))
            }
            val index = key.indexIn(texts)
            if (index < 0) return false
            val child = list.getChildAt(index - list.firstVisiblePosition)
            if (child != null && child.hasFocus()) return true
            val rowHeight = list.getChildAt(0)?.height ?: 0
            // A ListView-typed call: on the smart-cast list the call goes to AbsListView.setSelectionFromTop
            // (API 21), which Android 4.4 does not have (NoSuchMethodError stops the app)
            val listView: ListView? = list as? ListView
            if (listView != null) listView.setSelectionFromTop(index, key.topIn(list.height, rowHeight))
            else list.setSelection(index)
            list.post { list.getChildAt(index - list.firstVisiblePosition)?.requestFocus() }
            return true
        }
        val rows = list as LinearLayout
        val texts = (0 until rows.childCount).map { rowText(rows.getChildAt(it)) }
        val index = key.indexIn(texts)
        if (index < 0) return false
        val row = rows.getChildAt(index)
        if (row.hasFocus()) return true
        val scroll = rows.parent as ScrollView
        scroll.scrollTo(0, (row.top - key.topIn(scroll.height, row.height)).coerceAtLeast(0))
        row.requestFocus()
        return true
    }

    /** The first list under [root] that shows: an AbsListView with rows, or the rows of a ScrollView. */
    private fun shownList(root: View): ViewGroup? {
        if (root.visibility != View.VISIBLE) return null
        if (root is AbsListView) return if (root.count > 0) root else null
        if (root is ScrollView) {
            val rows = root.getChildAt(0) as? LinearLayout
            return if (rows != null && rows.childCount > 0) rows else null
        }
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) shownList(root.getChildAt(i))?.let { return it }
        }
        return null
    }

    /** The text of a list item that has no row view now: a song title or a name ("__..." items have no text). */
    private fun itemText(item: Any?): String? = when (item) {
        is SongItem -> item.title
        is String -> item.takeUnless { it.startsWith("__") }
        else -> item?.toString()
    }

    /**
     * The text of a row: its first text that is not an icon. Icons are characters of the Private Use Area
     * (Material and Tabler icon fonts). Texts that show only on the focused row come later in the row.
     */
    fun rowText(row: View): String? {
        if (row is TextView) {
            val text = row.text?.toString()?.trim()
            return if (text.isNullOrEmpty() || text.all { it in ''..'' }) null else text
        }
        if (row is ViewGroup) {
            for (i in 0 until row.childCount) rowText(row.getChildAt(i))?.let { return it }
        }
        return null
    }
}
