package com.themoon.y1.views

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Geometry of the lock, signal and device glyphs of the Wi-Fi and Bluetooth rows ([RowGlyphsView]). */
class RowGlyphsTest {
    private val eps = 1e-3f
    // Y2 (density 1): 21 px and 23 px Nimbus Sans Bold have cap heights of ~15 px and ~17 px.
    private val caps = floatArrayOf(15f, 17f, 25.5f)

    /** Top and bottom of a glyph in its box (0 = top of the capitals, h = baseline). */
    private fun assertCapBand(name: String, h: Float, top: Float, bottom: Float) {
        assertEquals("$name top at cap $h", 0f, top, eps)
        assertEquals("$name bottom at cap $h", h, bottom, eps)
        // So the glyph is centred on the cap band of the text.
        assertEquals("$name centre at cap $h", h / 2, (top + bottom) / 2, eps)
    }

    @Test
    fun lockFillsTheCapBand() {
        for (h in caps) {
            val g = RowGlyphs.lock(h)
            assertCapBand("lock", h, g.shackleTop, h) // the body stands on the baseline
            // The shackle ends in the body and is narrower than the body.
            assertTrue(g.shackleArcCentreY < g.bodyTop)
            assertTrue(2 * g.shackleRadius + g.shackleStroke < g.width)
            assertTrue("lock is narrower than tall", g.width < h)
        }
    }

    @Test
    fun wifiFillsTheCapBandAndIsALittleWider() {
        for (h in caps) {
            val g = RowGlyphs.wifi(h)
            val cy = h - g.dotRadius // centre of the dot and of the arcs
            val outer = g.arcRadius.last() + g.arcStroke / 2
            assertCapBand("wifi", h, cy - outer, cy + g.dotRadius)
            // Round ends of the outer arc at ±45°: the glyph width.
            val sin = Math.sin(Math.toRadians(RowGlyphs.WIFI_HALF_ANGLE.toDouble())).toFloat()
            assertEquals(g.width, 2 * (g.arcRadius.last() * sin + g.arcStroke / 2), eps)
        }
        assertTrue(RowGlyphs.WIFI_WIDTH_RATIO in 1.1f..1.4f)
    }

    @Test
    fun wifiArcsHaveVisibleGaps() {
        // On the Y2 (cap 15 px), a gap of at least 1 px between the dot and the arcs and between the arcs.
        val g = RowGlyphs.wifi(15f)
        var innerEdge = g.dotRadius
        for (r in g.arcRadius) {
            assertTrue("gap at radius $r", r - g.arcStroke / 2 - innerEdge >= 1f)
            innerEdge = r + g.arcStroke / 2
        }
    }

    @Test
    fun deviceGlyphsFillTheCapBand() {
        for (h in caps) {
            val hp = RowGlyphs.headphones(h)
            assertCapBand("headphones", h, hp.bandCentreY - hp.bandRadius - hp.bandStroke / 2, h)
            // The band ends in the ear cups (the cups stand on the baseline).
            assertTrue(hp.bandCentreY <= h - hp.cupHeight + hp.cupCorner)
            assertTrue(hp.bandStroke < hp.cupWidth)

            val p = RowGlyphs.bluetoothPoints(h)
            val half = RowGlyphs.bluetoothStroke(h) / 2
            val ys = p.filterIndexed { i, _ -> i % 2 == 1 }
            val xs = p.filterIndexed { i, _ -> i % 2 == 0 }
            assertCapBand("bluetooth", h, ys.minOrNull()!! - half, ys.maxOrNull()!! + half)
            assertEquals(0f, xs.minOrNull()!! - half, eps)
            assertEquals(RowGlyphs.BLUETOOTH_WIDTH_RATIO * h, xs.maxOrNull()!! + half, eps)
        }
    }

    @Test
    fun wifiColumnIsTheSameWithAndWithoutTheLock() {
        val h = 17f
        val secured = RowGlyphs.wifiSlots(true, 3).map { it.widthRatio }.toFloatArray()
        val open = RowGlyphs.wifiSlots(false, 0).map { it.widthRatio }.toFloatArray()
        assertEquals(secured.toList(), open.toList())
        // The signal is the last slot: its right edge is the right edge of the column (the row padding).
        val signalLeft = RowGlyphs.slotLeft(h, secured, 1)
        assertEquals(RowGlyphs.columnWidth(h, secured), signalLeft + RowGlyphs.WIFI_WIDTH_RATIO * h, eps)
        // About one cap height between the lock and the signal, and between the name and the column.
        assertEquals(h, signalLeft - RowGlyphs.LOCK_WIDTH_RATIO * h, eps)
        assertEquals(h, RowGlyphs.viewWidth(h, secured) - RowGlyphs.columnWidth(h, secured), eps)
        assertTrue(RowGlyphs.wifiSlots(false, 0)[0].glyph == null)
    }

    @Test
    fun deviceGlyphsAreCentredInOneSlot() {
        val h = 17f
        for (kind in listOf(RowGlyphs.Kind.HEADPHONES, RowGlyphs.Kind.BLUETOOTH)) {
            val slots = RowGlyphs.deviceSlots(kind)
            val ratios = slots.map { it.widthRatio }.toFloatArray()
            val glyph = slots[0].glyph!!
            val left = RowGlyphs.glyphLeft(h, ratios, 0, glyph.widthRatio)
            assertEquals(RowGlyphs.columnWidth(h, ratios) / 2, left + glyph.widthRatio * h / 2, eps)
            assertTrue(left >= 0f)
        }
    }

    @Test
    fun signalLevelFillsTheInnerArcs() {
        assertEquals(RowGlyphs.WIFI_ARCS, 3)
        assertEquals(2, RowGlyphs.wifiSlots(true, 2)[1].glyph!!.level)
    }
}
