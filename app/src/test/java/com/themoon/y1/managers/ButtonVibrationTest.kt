package com.themoon.y1.managers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ButtonVibrationTest {

    @Test
    fun lengthsGoUpFromWeakToStrong() {
        assertEquals(15L, ButtonVibration.lengthMs(0))
        assertEquals(25L, ButtonVibration.lengthMs(1))
        assertEquals(40L, ButtonVibration.lengthMs(2))
        assertTrue(ButtonVibration.lengthMs(0) < ButtonVibration.lengthMs(1))
        assertTrue(ButtonVibration.lengthMs(1) < ButtonVibration.lengthMs(2))
    }

    @Test
    fun weakIsNotZero() {
        // Android ignores a vibration of 0 ms; Weak must still vibrate
        assertTrue(ButtonVibration.lengthMs(0) > 0L)
    }

    @Test
    fun unknownLevelIsNormal() {
        assertEquals(25L, ButtonVibration.lengthMs(-1))
        assertEquals(25L, ButtonVibration.lengthMs(7))
    }

    @Test
    fun labelsHaveNoRawKey() {
        assertEquals(listOf("Weak", "Normal", "Strong"), ButtonVibration.LABELS.toList())
    }
}
