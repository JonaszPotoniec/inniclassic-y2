package com.themoon.y1.controllers

import android.view.KeyEvent
import android.view.ViewGroup
import com.themoon.y1.navigation.ScreenState

/**
 * Common lifecycle and hardware input interface implemented by every screen view.
 */
interface ScreenController {
    val rootView: ViewGroup

    fun onEnter(state: ScreenState) {}
    fun onExit() {}
    fun onRefreshUI() {}

    fun onWheel(delta: Int, event: KeyEvent): Boolean = false
    fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean = false
    fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean = false
    fun onBackPressed(): Boolean = false
}
