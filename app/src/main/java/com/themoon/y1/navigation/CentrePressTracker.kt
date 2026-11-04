package com.themoon.y1.navigation

/**
 * One centre push does one action, on release, as Android buttons do. A release may act only if its
 * press went to the same screen: a press can open another screen, wake the screen, or start the long
 * press of the focused row. Pure state: InputRouter gives it the centre key events in their order.
 */
internal class CentrePressTracker {
    private var pressScreen: Any? = null
    private var isPressed = false
    private var isPressRouted = false

    /** A new press that does not count (for example the press that wakes the screen): forget the last press. */
    fun reset() {
        isPressed = false
        isPressRouted = false
        pressScreen = null
    }

    /** First press event (no repeat), before the views. [screen]: the active screen. */
    fun onPress(screen: Any?) {
        isPressed = true
        isPressRouted = false
        pressScreen = screen
    }

    /** Release, before the views. False: drop it (no press, or the press went to another screen). */
    fun onRelease(screen: Any?): Boolean {
        val isSameScreen = isPressed && pressScreen === screen
        isPressed = false
        pressScreen = null
        return isSameScreen
    }

    /** No view took the press, so it came to InputRouter.onKeyDown. */
    fun onPressRouted() {
        if (isPressed) isPressRouted = true
    }

    /**
     * A release that no view took. False: drop it, because a view had the press (for its click or its
     * long press) and did not use the release.
     */
    fun onReleaseRouted(): Boolean {
        val wasRouted = isPressRouted
        isPressRouted = false
        return wasRouted
    }
}
