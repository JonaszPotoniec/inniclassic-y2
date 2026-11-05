package com.themoon.y1.controllers

import android.view.KeyEvent
import android.view.ViewGroup
import com.themoon.y1.navigation.RowKey
import com.themoon.y1.navigation.ScreenState
import com.themoon.y1.views.ListRows

/**
 * Common lifecycle and hardware input interface implemented by every screen view.
 */
interface ScreenController {
    val rootView: ViewGroup

    fun onEnter(state: ScreenState) {}
    fun onExit() {}
    fun onRefreshUI() {}

    /** The status bar title of this screen, or null: then MainActivity gives the title of the screen kind. */
    fun title(): String? = null

    /** The library cache finished loading after the screen was built: show the library data again. */
    fun onLibraryLoaded() {}

    /** The theme changed while this screen shows: make it again in the new theme, with the same focus. */
    fun onThemeChanged() {
        onRefreshUI()
    }

    /**
     * The selected row of the list on this screen, or null. [com.themoon.y1.navigation.NavigationManager]
     * keeps it when a deeper screen opens, and gives it to [selectRow] when Back comes back here.
     */
    fun selectedRow(): RowKey? = ListRows.selected(rootView)

    /** Back came back to this screen (after [onEnter]): select [row], the row that opened the deeper screen. */
    fun selectRow(row: RowKey) = ListRows.select(rootView, row)

    fun dispatchKeyEvent(event: KeyEvent): Boolean = false
    fun onWheel(delta: Int, event: KeyEvent): Boolean = false
    fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean = false
    fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean = false
    fun onKeyLongPress(keyCode: Int, event: KeyEvent): Boolean = false
    fun onBackPressed(): Boolean = false
}
