package com.themoon.y1.views

import com.themoon.y1.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VolumeBarTest {

    @Test
    fun nowPlayingAndVideoShowTheBarInPlaceOfTheirTimeRow() {
        assertEquals(VolumeBar.Place.NOW_PLAYING, VolumeBar.placeFor(MainActivity.STATE_PLAYER))
        assertEquals(VolumeBar.Place.VIDEO, VolumeBar.placeFor(MainActivity.STATE_VIDEO_PLAYER))
    }

    @Test
    fun otherScreensShowTheBarInTheStatusBar() {
        for (state in intArrayOf(MainActivity.STATE_MENU, MainActivity.STATE_BROWSER, MainActivity.STATE_SETTINGS,
                MainActivity.STATE_COVERFLOW, MainActivity.STATE_FM_RADIO)) {
            assertEquals(VolumeBar.Place.STATUS_BAR, VolumeBar.placeFor(state))
        }
    }

    private fun activity(): MainActivity {
        // An activity without its constructor (the Android classes of unit tests are stubs)
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val unsafe = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
        return unsafeClass.getMethod("allocateInstance", Class::class.java).invoke(unsafe, MainActivity::class.java) as MainActivity
    }

    @Test
    fun aClosedActivityReleasesOnlyItsOwnBar() {
        val old = activity()
        val new = activity()
        VolumeBar.keep(VolumeBar.Place.NOW_PLAYING, new)
        VolumeBar.release(old)
        assertTrue(VolumeBar.shows(new))
        VolumeBar.keep(VolumeBar.Place.NOW_PLAYING, old)
        VolumeBar.release(old)
        assertFalse(VolumeBar.shows(old))
    }

    @Test
    fun statusBarWidthFitsTheTitleSpace() {
        // Full-width status bar: the bar stops at its maximum.
        assertEquals(220, VolumeBar.statusBarWidth(350, 50, 220, 60))
        // Half-width status bar (split screens): the title space less the icons.
        assertEquals(110, VolumeBar.statusBarWidth(160, 50, 220, 60))
        // No space: no bar.
        assertEquals(0, VolumeBar.statusBarWidth(100, 50, 220, 60))
    }
}
