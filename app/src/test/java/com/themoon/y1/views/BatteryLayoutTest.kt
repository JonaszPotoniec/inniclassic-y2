package com.themoon.y1.views

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BatteryLayoutTest {

    // Nimbus Sans Bold (units per em 1000), ink width / flat digit height (709): "0" 488, "1" 310, "2" 485, ...
    private val nimbus = floatArrayOf(
        488f, 310f, 485f, 487f, 498f, 490f, 487f, 499f, 503f, 488f
    ).map { it / 709f }.toFloatArray()

    // The status bar battery: 30 x 16, 2 px outline (Classic themes, iPod layout) or 3 px (the other themes).
    private val thin = BatteryLayout.body(30, 16, 2)
    private val wide = BatteryLayout.body(30, 16, 3)

    @Test
    fun bodyIsOnWholePixelsAndCentred() {
        // Shell 0..27, nub 27..29 (the ink ends at 29, as before), nub rows 5..11 around the centre line 8.
        assertEquals(27, thin.shellRight)
        assertEquals(29, thin.nubRight)
        assertEquals(5, thin.nubTop)
        assertEquals(11, thin.nubBottom)
        // The fill touches the 2 px outline: x 2..25, y 2..14.
        assertEquals(2, thin.fillLeft)
        assertEquals(25, thin.fillRight)
        assertEquals(2, thin.fillTop)
        assertEquals(14, thin.fillBottom)
        assertEquals(3, wide.fillLeft)
        assertEquals(13, wide.fillBottom)
        for (b in arrayOf(thin, wide)) {
            assertEquals(16, b.fillTop + b.fillBottom)
            assertEquals(16, b.nubTop + b.nubBottom)
            // The fill is centred in the shell.
            assertEquals(b.fillLeft, b.shellRight - b.fillRight)
        }
    }

    @Test
    fun fillWidthIsWholePixelsAndFullOnlyAt100() {
        assertEquals(0, BatteryLayout.fillWidth(0, 21))
        assertEquals(1, BatteryLayout.fillWidth(1, 21))
        assertEquals(20, BatteryLayout.fillWidth(99, 21))
        assertEquals(21, BatteryLayout.fillWidth(100, 21))
        assertEquals(11, BatteryLayout.fillWidth(50, 21))
    }

    @Test
    fun digitsHaveOneHeightCentredOnTheBody() {
        // 10 px digits (rows 3..13) in the thin battery, 8 px (rows 4..12) in the wide one: centre line 8 in both.
        for ((b, height) in listOf(thin to 10, wide to 8)) {
            assertEquals(height, BatteryLayout.digitHeight(b.fillHeight))
            val top = BatteryLayout.digitTop(b.fillTop, b.fillHeight, height)
            assertEquals(16, 2 * top + height)
        }
    }

    @Test
    fun allDigitsButOneShareOneWidth() {
        // At 8 px the rounded ink widths are 5 or 6 px: all take the narrowest (5); "1" is 3 px.
        assertArrayEquals(intArrayOf(3, 5, 5), BatteryLayout.digitWidths("100", nimbus, 8))
        assertArrayEquals(intArrayOf(5, 5), BatteryLayout.digitWidths("86", nimbus, 8))
        assertArrayEquals(intArrayOf(3, 4, 4), BatteryLayout.digitWidths("100", nimbus, 6))
        // At 10 px: 7 px digits, "1" 4 px.
        assertArrayEquals(intArrayOf(4, 7, 7), BatteryLayout.digitWidths("100", nimbus, 10))
    }

    @Test
    fun chargingKeepsTheDigitHeightAtAllLevels() {
        for (b in arrayOf(thin, wide)) {
            val normal = BatteryLayout.digitHeight(b.fillHeight)
            for (level in 0..100) {
                for (bolt in booleanArrayOf(false, true)) {
                    val g = BatteryLayout.group(level.toString(), nimbus, b.fillWidth, b.fillHeight, bolt)
                    assertEquals("level $level bolt $bolt", normal, g.digitHeight)
                    assertTrue("level $level bolt $bolt fits", g.width <= b.fillWidth)
                    // The bolt is always shown while charging, except "100" in the thin battery.
                    val dropped = b === thin && level == 100
                    assertEquals("level $level bolt $bolt", bolt && !dropped, g.boltWidth > 0)
                }
            }
        }
        // "100" + bolt at 10 px is 4 + 1 + 7 + 1 + 7 + 1 + 5 = 26 px, wider than the 23 px fill: no bolt.
        val g = BatteryLayout.group("100", nimbus, thin.fillWidth, thin.fillHeight, true)
        assertEquals(0, g.boltWidth)
        assertEquals(20, g.width)
        // "99" + bolt: 7 + 1 + 7 + 1 + 5 = 21 px.
        assertEquals(21, BatteryLayout.group("99", nimbus, thin.fillWidth, thin.fillHeight, true).width)
        // In the wide battery "100" + bolt fits at 8 px: 3 + 1 + 5 + 1 + 5 + 1 + 4 = 20 px in 21 px.
        assertEquals(4, BatteryLayout.group("100", nimbus, wide.fillWidth, wide.fillHeight, true).boltWidth)
    }

    @Test
    fun boltIsAloneAt100PercentWhenCharging() {
        assertTrue(BatteryLayout.showsBoltAlone(100, true, true))
        assertTrue(BatteryLayout.showsBoltAlone(63, true, false))
        assertTrue(!BatteryLayout.showsBoltAlone(99, true, true))
        assertTrue(!BatteryLayout.showsBoltAlone(100, false, true))
        assertTrue(!BatteryLayout.showsBoltAlone(100, false, false))
    }

    @Test
    fun boltIsOnTheRightOfTheDigitsAndTheGroupStaysCentred() {
        for (b in arrayOf(thin, wide)) {
            for (level in 0..100) {
                val g = BatteryLayout.group(level.toString(), nimbus, b.fillWidth, b.fillHeight, true)
                if (g.boltWidth == 0) continue
                val left = BatteryLayout.groupLeft(b.fillLeft, b.fillWidth, g.width)
                val boltLeft = BatteryLayout.boltLeft(left, g)
                // One gap after the last digit, and the bolt ends where the group ends.
                assertEquals(left + g.digitWidths.sum() + BatteryLayout.GAP * (g.digitWidths.size - 1) + 1, boltLeft)
                assertEquals(left + g.width, boltLeft + g.boltWidth)
                assertTrue("level $level inside the fill", left >= b.fillLeft && boltLeft + g.boltWidth <= b.fillRight)
            }
        }
        // "99" at 10 px in the thin battery: digits from x 3 (7 + 1 + 7 px), 1 px gap, bolt 19..24 (5 px).
        val g = BatteryLayout.group("99", nimbus, thin.fillWidth, thin.fillHeight, true)
        assertEquals(3, BatteryLayout.groupLeft(thin.fillLeft, thin.fillWidth, g.width))
        assertEquals(19, BatteryLayout.boltLeft(3, g))
    }

    private fun contrast(a: Int, b: Int): Double {
        val la = BatteryLayout.luminance(a)
        val lb = BatteryLayout.luminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    @Test
    fun oneDigitColourReadsOnEveryFill() {
        // Classic Light text 1C1C1E; Classic Dark text white (and F2F2F7, a slightly darker white).
        val texts = intArrayOf(0xFF1C1C1E.toInt(), 0xFFFFFFFF.toInt(), 0xFFF2F2F7.toInt())
        assertTrue(!BatteryLayout.isLight(texts[0]))
        assertTrue(BatteryLayout.isLight(texts[1]) && BatteryLayout.isLight(texts[2]))
        for (text in texts) {
            for (charging in booleanArrayOf(false, true)) {
                for (level in intArrayOf(0, 1, 9, 10, 20, 21, 35, 63, 99, 100)) {
                    for (fill in BatteryLayout.fillColors(BatteryLayout.isLight(text), charging, level)) {
                        assertTrue(
                            "text ${Integer.toHexString(text)} fill ${Integer.toHexString(fill)}",
                            contrast(text, fill) >= 3.6
                        )
                    }
                }
            }
        }
    }

    @Test
    fun fillIsRedOnlyAtALowLevelWhenNotCharging() {
        for (light in booleanArrayOf(false, true)) {
            val low = BatteryLayout.fillColors(light, false, 20)
            assertEquals(low[0], low[1]) // flat
            assertTrue((low[0] shr 16 and 0xFF) > 2 * (low[0] shr 8 and 0xFF)) // red
            val normal = BatteryLayout.fillColors(light, false, 21)
            val charging = BatteryLayout.fillColors(light, true, 5)
            for (c in normal + charging) assertTrue((c shr 8 and 0xFF) > (c shr 16 and 0xFF)) // green
        }
    }

    @Test
    fun groupBecomesSmallerOnlyWhenTheDigitsDoNotFit() {
        // "100" + bolt at 8 px (20 px) does not fit in 19 px: the bolt goes, the digits (15 px) keep 8 px.
        val g = BatteryLayout.group("100", nimbus, 19, 10, true)
        assertEquals(8, g.digitHeight)
        assertEquals(0, g.boltWidth)
        assertEquals(15, g.width)
        // The digits alone (15 px at 8 px) do not fit in 14 px: at 6 px they are 13 px.
        val small = BatteryLayout.group("100", nimbus, 14, 10, false)
        assertEquals(6, small.digitHeight)
        assertEquals(13, small.width)
    }

    @Test
    fun groupIsCentredWithTheOddPixelOnTheRight() {
        assertEquals(5, BatteryLayout.groupLeft(3, 21, 17))
        assertEquals(6, BatteryLayout.groupLeft(3, 21, 15))
        assertEquals(2, BatteryLayout.groupLeft(3, 21, 23))
    }

    @Test
    fun boltIsSymmetricAndInItsBox() {
        for ((w, h) in listOf(5 to 10, 4 to 8, 7 to 12, 5 to 12)) {
            val p = BatteryLayout.boltPoints(w, h)
            assertEquals(16, p.size)
            var minX = Float.MAX_VALUE; var maxX = -1f; var minY = Float.MAX_VALUE; var maxY = -1f
            for (i in 0 until 8) {
                val x = p[2 * i]; val y = p[2 * i + 1]
                minX = minOf(minX, x); maxX = maxOf(maxX, x); minY = minOf(minY, y); maxY = maxOf(maxY, y)
                // Turned 180° about the centre of the box, each point is the point 4 places later.
                val j = (i + 4) % 8
                assertEquals(w - x, p[2 * j], 1e-4f)
                assertEquals(h - y, p[2 * j + 1], 1e-4f)
            }
            assertEquals(0f, minX, 0f); assertEquals(w.toFloat(), maxX, 0f)
            assertEquals(0f, minY, 0f); assertEquals(h.toFloat(), maxY, 0f)
        }
    }

    @Test
    fun boltIsAsTallAsTheDigits() {
        assertEquals(10, BatteryLayout.boltHeight(10))
        assertEquals(8, BatteryLayout.boltHeight(8))
        assertEquals(5, BatteryLayout.boltWidth(10))
        assertEquals(4, BatteryLayout.boltWidth(8))
    }

    /** Thickness of the upper arm of the bolt: distance of the top right point from the left edge of the arm. */
    private fun armThickness(p: FloatArray): Double {
        val dx = (p[0] - p[14]).toDouble(); val dy = (p[1] - p[15]).toDouble()
        return Math.abs((p[2] - p[14]) * dy - (p[3] - p[15]) * dx) / Math.hypot(dx, dy)
    }

    @Test
    fun boltArmsAreAsThickAsTheDigitStems() {
        // Measured stems of Nimbus Sans Bold on these pixel boxes: 10 px "1" 1.8 px, "0" 2.0 px; 8 px 1.4 px.
        val ten = armThickness(BatteryLayout.boltPoints(5, 10))
        val eight = armThickness(BatteryLayout.boltPoints(4, 8))
        assertTrue("10 px arm $ten", ten in 1.7..2.1)
        assertTrue("8 px arm $eight", eight in 1.3..1.6)
    }

    @Test
    fun soloBoltIsExactlyCentred() {
        // Battery Percentage off: the fill height minus 2; 10 px in the thin battery, 8 px in the wide one; both 5 px wide.
        assertEquals(5, BatteryLayout.soloBoltWidth(10, thin.fillWidth))
        assertEquals(5, BatteryLayout.soloBoltWidth(8, wide.fillWidth))
        for (b in arrayOf(thin, wide)) {
            val w = BatteryLayout.soloBoltWidth(b.fillHeight - 2, b.fillWidth)
            assertEquals(0, (b.fillWidth - w) % 2)
        }
    }
}
