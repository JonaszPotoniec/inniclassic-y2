package com.themoon.y1.managers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanWakeLockTest {

    private class FakeLock(private val failAcquire: Boolean = false) : ScanWakeLock.Lock {
        var timeouts = mutableListOf<Long>()
        var releases = 0
        override var isHeld = false
        override fun acquire(timeoutMs: Long) {
            if (failAcquire) throw SecurityException("no WAKE_LOCK permission")
            timeouts += timeoutMs
            isHeld = true
        }
        override fun release() {
            releases++
            isHeld = false
        }
    }

    @Test
    fun acquiresWithTheSafetyTimeoutAndReleasesOnce() {
        val lock = FakeLock()
        val scan = ScanWakeLock(lock)
        scan.acquire()
        assertEquals(listOf(ScanWakeLock.TIMEOUT_MS), lock.timeouts)
        assertTrue(lock.isHeld)
        scan.release()
        scan.release()
        assertEquals(1, lock.releases)
        assertFalse(lock.isHeld)
    }

    @Test
    fun releaseAfterTheTimeoutEndedTheLockDoesNothing() {
        val lock = FakeLock()
        val scan = ScanWakeLock(lock)
        scan.acquire()
        lock.isHeld = false
        scan.release()
        assertEquals(0, lock.releases)
    }

    @Test
    fun noLockOrAFailingLockDoesNotStopTheScan() {
        ScanWakeLock(null).apply { acquire(); release() }
        val failing = FakeLock(failAcquire = true)
        ScanWakeLock(failing).apply { acquire(); release() }
        assertEquals(0, failing.releases)
    }

    @Test
    fun theTimeoutIsLongButNotEndless() {
        assertTrue(ScanWakeLock.TIMEOUT_MS in 10 * 60 * 1000L..2 * 60 * 60 * 1000L)
    }
}
