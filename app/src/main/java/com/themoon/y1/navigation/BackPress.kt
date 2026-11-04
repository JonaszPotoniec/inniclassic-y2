package com.themoon.y1.navigation

/**
 * The Back (Menu) button acts on release, like the other buttons: a press only arms it, so a press that is
 * held does nothing yet. A release whose press went elsewhere (screen wake, a dialog, a screen's own Back
 * handling) does not go back.
 */
internal class BackPress {
    private var armed = false

    /** Every new press: forget a press whose release never came to the router. */
    fun reset() {
        armed = false
    }

    /** The router got the press (no screen used it). Key repeats of a hold do not arm it again. */
    fun onPress(repeatCount: Int) {
        if (repeatCount == 0) armed = true
    }

    /** True when this release must go back. */
    fun onRelease(canceled: Boolean): Boolean {
        val act = armed && !canceled
        armed = false
        return act
    }
}
