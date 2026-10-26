package com.themoon.y1.views

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Optical spacing of the status bar items, like the side bearings of a font. The space between two items looks
 * larger when their facing sides are mostly empty (the narrow wedge of the Wi-Fi fan, the rune) than between two flat
 * sides (pause bars, battery), at the same ink gap. So each icon gets an optical bearing per side: the mean, over the
 * rows of the status item box, of the empty space from the bitmap edge to the ink of the row, each row limited to
 * [CAP] px (deep empty rows do not count more). The gap between two items is [TARGET] minus the two facing bearings:
 * flat items keep the gap of 8 px. No Android dependencies, for the unit tests.
 */
object StatusSpacing {
    /** Gap between the ink of two flat items (IpodClassicMetrics.STATUS_ITEM_GAP). */
    const val TARGET = 8f
    /** Smallest ink gap. */
    const val MIN_GAP = 3
    /** Largest empty space of one row that counts. */
    const val CAP = 3f
    /** Alpha from which a pixel is ink (half covered). */
    const val INK_ALPHA = 128

    /**
     * Optical bearings (left, right) of an icon: [pixels] = ARGB, [width] x rows; only the rows [rowTop] until
     * [rowBottom] (the status item box) count. A row without ink counts as [CAP].
     */
    fun bearings(pixels: IntArray, width: Int, rowTop: Int, rowBottom: Int): FloatArray {
        val rows = pixels.size / max(1, width)
        val top = rowTop.coerceIn(0, rows)
        val bottom = rowBottom.coerceIn(top, rows)
        if (width <= 0 || bottom <= top) return floatArrayOf(0f, 0f)
        var left = 0f
        var right = 0f
        for (y in top until bottom) {
            var l = width
            for (x in 0 until width) {
                if (pixels[y * width + x] ushr 24 >= INK_ALPHA) {
                    l = x
                    break
                }
            }
            var r = width
            for (x in width - 1 downTo 0) {
                if (pixels[y * width + x] ushr 24 >= INK_ALPHA) {
                    r = width - 1 - x
                    break
                }
            }
            left += min(l.toFloat(), CAP)
            right += min(r.toFloat(), CAP)
        }
        val n = (bottom - top).toFloat()
        return floatArrayOf(left / n, right / n)
    }

    /**
     * Optical bearings of a status icon. With [sideDots] (the rune of a connected device: a dot left and right at
     * mid-height, at the bitmap edges) both sides count as flat (0): the dot fills the middle rows, which are empty
     * in the rune alone, so the gap from the fan to the left dot (and from the right dot to play/pause) is the gap
     * of a flat side. Without that, the bearings of the rune alone would bring the fan close to the left dot.
     */
    fun iconBearings(pixels: IntArray, width: Int, rowTop: Int, rowBottom: Int, sideDots: Boolean): FloatArray =
        if (sideDots) floatArrayOf(0f, 0f) else bearings(pixels, width, rowTop, rowBottom)

    /** Ink gap between an item with the optical bearing [rightOfFirst] and the next item with [leftOfNext]. */
    fun gap(rightOfFirst: Float, leftOfNext: Float): Int =
        max(MIN_GAP, (TARGET - rightOfFirst - leftOfNext).roundToInt())

    /**
     * First and last column of [pixels] (ARGB, [width] wide) with any ink (alpha > 0), or null when there is none.
     * For the side bearings of the drawn text: also faint edge pixels count, so no part of a letter is cut.
     */
    fun inkColumns(pixels: IntArray, width: Int): IntArray? {
        if (width <= 0) return null
        val rows = pixels.size / width
        var first = -1
        var last = -1
        for (x in 0 until width) {
            for (y in 0 until rows) {
                if (pixels[y * width + x] ushr 24 != 0) {
                    if (first < 0) first = x
                    last = x
                    break
                }
            }
        }
        return if (first < 0) null else intArrayOf(first, last)
    }
}
