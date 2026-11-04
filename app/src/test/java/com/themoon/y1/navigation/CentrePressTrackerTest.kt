package com.themoon.y1.navigation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CentrePressTrackerTest {
    private val menu = Any()
    private val songs = Any()

    @Test
    fun releaseOnSameScreenActs() {
        val t = CentrePressTracker()
        t.onPress(menu)
        t.onPressRouted()
        assertTrue(t.onRelease(menu))
        assertTrue(t.onReleaseRouted())
    }

    @Test
    fun releaseAfterScreenChangeIsDropped() {
        // Cover Flow opened the album on the press; the release must not play a song
        val t = CentrePressTracker()
        t.onPress(menu)
        t.onPressRouted()
        assertFalse(t.onRelease(songs))
    }

    @Test
    fun releaseWithoutPressIsDropped() {
        // The press woke the screen and did not get to the tracker
        val t = CentrePressTracker()
        assertFalse(t.onRelease(menu))
    }

    @Test
    fun releaseOfViewPressPassesToTheView() {
        // A focused row took the press: the row gets the release for its click
        val t = CentrePressTracker()
        t.onPress(menu)
        assertTrue(t.onRelease(menu))
    }

    @Test
    fun routedReleaseOfViewPressIsDropped() {
        // The row did a long press (for example reorder mode) and did not use the release
        val t = CentrePressTracker()
        t.onPress(menu)
        assertTrue(t.onRelease(menu))
        assertFalse(t.onReleaseRouted())
    }

    @Test
    fun eachPressIsUsedOnce() {
        val t = CentrePressTracker()
        t.onPress(menu)
        t.onPressRouted()
        assertTrue(t.onRelease(menu))
        assertTrue(t.onReleaseRouted())
        assertFalse(t.onRelease(menu))
        assertFalse(t.onReleaseRouted())
    }

    @Test
    fun releaseOfWakePressIsDropped() {
        // A long press turned the screen off; its release went to the screen-off code. The next press
        // wakes the screen (reset, no onPress), so its release must not pair with the old press.
        val t = CentrePressTracker()
        t.onPress(menu)
        t.reset()
        assertFalse(t.onRelease(menu))
    }

    @Test
    fun routedFlagNeedsAPress() {
        val t = CentrePressTracker()
        t.onPressRouted()
        assertFalse(t.onReleaseRouted())
    }
}
