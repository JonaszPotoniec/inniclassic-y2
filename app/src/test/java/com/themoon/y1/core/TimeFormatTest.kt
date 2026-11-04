package com.themoon.y1.core

import org.junit.Assert.assertEquals
import org.junit.Test

class TimeFormatTest {

    @Test
    fun formatsLikeIpodClassic() {
        assertEquals("0:00", TimeFormat.format(0))
        assertEquals("0:59", TimeFormat.format(59_000))
        assertEquals("2:08", TimeFormat.format(128_000))
        assertEquals("59:59", TimeFormat.format(3_599_000))
        assertEquals("1:00:00", TimeFormat.format(3_600_000))
        assertEquals("10:02:08", TimeFormat.format(36_128_000L))
    }

    @Test
    fun dropsMillisecondsAndClampsNegative() {
        assertEquals("2:08", TimeFormat.format(128_999))
        assertEquals("0:00", TimeFormat.format(-5_000))
    }
}
