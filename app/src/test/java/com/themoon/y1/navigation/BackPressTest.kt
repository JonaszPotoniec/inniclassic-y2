package com.themoon.y1.navigation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackPressTest {
    @Test
    fun pressThenReleaseGoesBackOnce() {
        val back = BackPress()
        back.onPress(0)
        assertTrue(back.onRelease(canceled = false))
        assertFalse(back.onRelease(canceled = false))
    }

    @Test
    fun heldPressGoesBackOnlyOnRelease() {
        val back = BackPress()
        back.onPress(0)
        back.onPress(1)
        back.onPress(2)
        assertTrue(back.onRelease(canceled = false))
    }

    @Test
    fun releaseWithoutPressDoesNothing() {
        // The press closed a dialog or woke the screen, so only the release comes to the router
        assertFalse(BackPress().onRelease(canceled = false))
    }

    @Test
    fun resetForgetsAPressFromBefore() {
        val back = BackPress()
        back.onPress(0)
        back.reset()
        assertFalse(back.onRelease(canceled = false))
    }

    @Test
    fun canceledReleaseDoesNothing() {
        val back = BackPress()
        back.onPress(0)
        assertFalse(back.onRelease(canceled = true))
    }
}
