package com.themoon.y1.views

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NoticeTest {

    @Test
    fun shortTextShowsForTheMinimumTime() {
        assertEquals(Notice.MIN_MS, Notice.durationMs("Track removed.", long = false))
        assertEquals(Notice.MIN_MS, Notice.durationMs("", long = false))
    }

    @Test
    fun longerTextShowsLongerUpToTheMaximum() {
        val one = Notice.durationMs("Audio Connected to Living Room Speaker", long = false)
        assertTrue(one > Notice.MIN_MS && one < Notice.MAX_MS)
        assertEquals(Notice.MAX_MS, Notice.durationMs("x".repeat(500), long = false))
    }

    @Test
    fun aNoticeForAfterTheRecreateComesOnce() {
        assertNull(Notice.takePending())
        Notice.showAfterRecreate("Settings imported.")
        assertEquals("Settings imported.", Notice.takePending())
        assertNull(Notice.takePending())
    }

    @Test
    fun theBoxOfAClosedActivityIsReleased() {
        // A TextView without its constructor (the Android classes of unit tests are stubs)
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val unsafe = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
        val box = unsafeClass.getMethod("allocateInstance", Class::class.java)
            .invoke(unsafe, android.widget.TextView::class.java) as android.widget.TextView
        val old = Any()
        val new = Any()
        Notice.keep(old, box)
        Notice.release(new)
        assertTrue(Notice.holds(old))
        Notice.release(old)
        assertFalse(Notice.holds(old))
    }

    @Test
    fun errorsAndLongMessagesShowAtLeastTheLongTime() {
        assertEquals(Notice.LONG_MIN_MS, Notice.durationMs("Import failed.", long = true))
        assertEquals(Notice.MAX_MS, Notice.durationMs("x".repeat(500), long = true))
    }
}
