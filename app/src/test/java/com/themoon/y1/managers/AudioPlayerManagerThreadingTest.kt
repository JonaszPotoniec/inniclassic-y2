package com.themoon.y1.managers

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class AudioPlayerManagerThreadingTest {

    @Test
    fun testIsPlayingAndIsFileCurrentlyPlayingFromBackgroundThread() {
        val apm = AudioPlayerManager.getInstance()

        apm.isPlayingActive = false
        apm.currentlyPlayingFilePath = "/storage/sdcard1/Music/track.mp3"

        val latch = CountDownLatch(1)
        val backgroundIsPlaying = AtomicBoolean(false)
        val backgroundIsFilePlaying = AtomicBoolean(false)
        val threadThrewException = AtomicBoolean(false)

        val thread = Thread {
            try {
                backgroundIsPlaying.set(apm.isPlaying())
                backgroundIsFilePlaying.set(apm.isFileCurrentlyPlaying(File("/storage/sdcard1/Music/track.mp3")))
            } catch (t: Throwable) {
                threadThrewException.set(true)
            } finally {
                latch.countDown()
            }
        }
        thread.start()
        assertTrue("Thread should complete in time", latch.await(2, TimeUnit.SECONDS))
        assertFalse("Background thread should never throw wrong thread exception", threadThrewException.get())
        assertFalse("isPlaying should be false when not active", backgroundIsPlaying.get())
        assertFalse("isFileCurrentlyPlaying should be false when isPlaying is false", backgroundIsFilePlaying.get())

        // Now test when playback is active
        apm.isPlayingActive = true
        val latch2 = CountDownLatch(1)
        val thread2 = Thread {
            try {
                backgroundIsPlaying.set(apm.isPlaying())
                backgroundIsFilePlaying.set(apm.isFileCurrentlyPlaying(File("/storage/sdcard1/Music/track.mp3")))
            } catch (t: Throwable) {
                threadThrewException.set(true)
            } finally {
                latch2.countDown()
            }
        }
        thread2.start()
        assertTrue("Thread 2 should complete in time", latch2.await(2, TimeUnit.SECONDS))
        assertFalse("Background thread should not throw", threadThrewException.get())
        assertTrue("isPlaying should be true on background thread", backgroundIsPlaying.get())
        assertTrue("isFileCurrentlyPlaying should be true on background thread for matching file", backgroundIsFilePlaying.get())

        // Test non-matching file
        val latch3 = CountDownLatch(1)
        val nonMatchingResult = AtomicBoolean(true)
        Thread {
            nonMatchingResult.set(apm.isFileCurrentlyPlaying(File("/storage/sdcard1/Music/other.mp3")))
            latch3.countDown()
        }.start()
        assertTrue("Thread 3 should complete in time", latch3.await(2, TimeUnit.SECONDS))
        assertFalse("isFileCurrentlyPlaying should be false for non-matching file", nonMatchingResult.get())

        // Reset state
        apm.isPlayingActive = false
        apm.currentlyPlayingFilePath = null
    }
}
