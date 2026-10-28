package com.themoon.y1.views

import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView

/**
 * The focused row and the scroll position of a list, kept while a screen makes its rows again (live theme
 * change). The click wheel moves the focus, so the focus must stay on the same row.
 *
 * Two kinds of list: a [LinearLayout] of rows in a [ScrollView] (Settings, Music menu), and an
 * [AbsListView] (songs, albums, artists). [restore] does nothing when the list is not the same view or
 * has a different number of rows, because the screen then shows a different list.
 */
class ListPosition private constructor(
    private val list: ViewGroup,
    private val rowIndex: Int,
    private val rowCount: Int,
    private val scrollY: Int,
    private val rowTop: Int
) {
    companion object {
        /** The position of the list that holds the focused view under [root], or null if there is none. */
        fun of(root: View): ListPosition? {
            var child: View = root.findFocus() ?: return null
            var parent = child.parent
            while (parent is ViewGroup) {
                if (parent is AbsListView) {
                    val index = parent.getPositionForView(child)
                    if (index == AbsListView.INVALID_POSITION) return null
                    return ListPosition(parent, index, parent.count, 0, child.top)
                }
                val scroll = parent.parent as? ScrollView
                if (parent is LinearLayout && scroll != null) {
                    return ListPosition(parent, parent.indexOfChild(child), parent.childCount, scroll.scrollY, 0)
                }
                if (parent === root) return null
                child = parent
                parent = parent.parent
            }
            return null
        }
    }

    /** Puts the focus and the scroll position back, after the work that the new rows posted. */
    fun restore() {
        list.post {
            if (list.windowToken == null || list.visibility != View.VISIBLE) return@post
            if (list is AbsListView) {
                if (list.count != rowCount || rowIndex >= list.count) return@post
                // AbsListView.setSelectionFromTop is API 21: on Android 4.4 it stops the app (NoSuchMethodError).
                // Only a ListView-typed call goes to ListView.setSelectionFromTop (a smart cast is not enough).
                val listView: ListView? = list as? ListView
                if (listView != null) listView.setSelectionFromTop(rowIndex, rowTop) else list.setSelection(rowIndex)
                list.post { list.getChildAt(rowIndex - list.firstVisiblePosition)?.requestFocus() }
            } else {
                if (list.childCount != rowCount || rowIndex >= list.childCount) return@post
                (list.parent as? ScrollView)?.scrollTo(0, scrollY)
                list.getChildAt(rowIndex)?.requestFocus()
            }
        }
    }
}
