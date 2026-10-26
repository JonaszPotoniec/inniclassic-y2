package com.themoon.y1.views

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Whole-pixel geometry of [BatteryIconView]: the outline, the nub, the fill, and the percentage digits with the
 * charging bolt. All edges are on whole pixels, so they are sharp at density 1.
 *
 * The digits: each digit outline is mapped onto a box of whole pixels (its ink fills the box exactly). The hinting
 * of Android 4.4 gave the digits different heights (the "1" of "100" was 1 px taller than the "0"), and the outlines
 * of round digits have an overshoot that is less than 0.2 px at this size, so all digits get the same height (as a
 * hinted font does). The fill touches the outline (no gap), so the digits get 2 px more height. The digit height is
 * the fill height minus 2: 1 px free above and below in the fill, and the same parity as the body, so the digits are
 * exactly on the centre line of the body (status bar: 10 px digits with the 2 px outline, 8 px with the 3 px one).
 *
 * Charging: the bolt is on the right of the digits (as on macOS), as tall as the digits (its flat tips need no
 * overshoot), with 1 px between the digits and the bolt, as between the digits. Its arms are as thick as the digit
 * stems (about 1.9 px at 10 px, 1.4 px at 8 px). The digits keep the height that they have when the battery does not
 * charge. At 100 % on the charger the battery shows the bolt alone, in the centre (as with Battery Percentage off):
 * "100" and the bolt do not fit (26 px in the 23 px fill of the 2 px outline), and the full fill says 100 %. When
 * other digits and the bolt are wider than the fill, the bolt is not shown. Only when the digits alone are wider
 * than the fill is the height decreased (2 px at a time).
 *
 * Colours: one digit colour, the theme's text colour, on the fill and on the empty part; the fill colours follow
 * it (see [fillColors]). So no part of a digit needs a second colour.
 *
 * No Android dependencies, so the unit tests can check the maths.
 */
object BatteryLayout {

    /** Space between two digits, and between the bolt and the digits. */
    const val GAP = 1

    /**
     * The battery in a view [width] x [height] with an outline of [outline] px. The ink ends 1 px before the right edge
     * of the view (as before). The shell is x 0..[shellRight], y 0..height; the nub is [nubLeft]..[nubRight] x
     * [nubTop]..[nubBottom], on the centre line; the fill area is the inside of the outline.
     */
    class Body(
        val outline: Int,
        val shellRight: Int,
        val nubLeft: Int,
        val nubTop: Int,
        val nubRight: Int,
        val nubBottom: Int,
        val fillLeft: Int,
        val fillTop: Int,
        val fillRight: Int,
        val fillBottom: Int
    ) {
        val fillWidth: Int get() = fillRight - fillLeft
        val fillHeight: Int get() = fillBottom - fillTop
    }

    fun body(width: Int, height: Int, outline: Int): Body {
        val nubWidth = max(1, (width * 0.08f).roundToInt())
        val right = width - 1
        val shellRight = right - nubWidth
        var nubHeight = (height * 0.4f).roundToInt()
        if ((height - nubHeight) % 2 != 0) nubHeight -= 1
        val nubTop = (height - nubHeight) / 2
        return Body(
            outline, shellRight, shellRight, nubTop, right, nubTop + nubHeight,
            outline, outline, shellRight - outline, height - outline
        )
    }

    /** Fill width in whole pixels: at least 1 px above 0 %, and full only at 100 %. */
    fun fillWidth(level: Int, maxWidth: Int): Int {
        if (level <= 0 || maxWidth <= 0) return 0
        if (level >= 100) return maxWidth
        return (maxWidth * level / 100f).roundToInt().coerceIn(1, max(1, maxWidth - 1))
    }

    /** Height of the digits in a fill [fillHeight] tall. */
    fun digitHeight(fillHeight: Int): Int = max(1, fillHeight - 2)

    /**
     * Widths of the digits of [text] at [height]. [inkRatios] = ink width / flat digit height of the digits 0-9.
     * All digits except "1" get one width (tabular, so the number does not change width when a digit changes): the
     * narrowest of their rounded widths. The "1" keeps its own width.
     */
    fun digitWidths(text: String, inkRatios: FloatArray, height: Int): IntArray {
        var common = Int.MAX_VALUE
        for (d in 0..9) if (d != 1 && d < inkRatios.size) common = min(common, (inkRatios[d] * height).roundToInt())
        if (common == Int.MAX_VALUE) common = max(1, (height * 0.6f).roundToInt())
        val one = if (inkRatios.size > 1) (inkRatios[1] * height).roundToInt() else common
        return IntArray(text.length) { i -> max(1, if (text[i] == '1') one else common) }
    }

    /** Width of the bolt beside digits [digitHeight] tall (the bolt is [boltHeight]): 5 px at 10 px, 4 px at 8 px. */
    fun boltWidth(digitHeight: Int): Int = max(1, (boltHeight(digitHeight) / 2f).roundToInt())

    fun boltHeight(digitHeight: Int): Int = digitHeight

    /**
     * The digits (and the bolt when [bolt]) as one group: heights, widths and the full width with the gaps.
     * [boltWidth] 0 = no bolt (also when the bolt does not fit).
     */
    class Group(val digitHeight: Int, val digitWidths: IntArray, val boltWidth: Int, val width: Int)

    fun group(text: String, inkRatios: FloatArray, fillWidth: Int, fillHeight: Int, bolt: Boolean): Group {
        var height = digitHeight(fillHeight)
        while (true) {
            val widths = digitWidths(text, inkRatios, height)
            var width = widths.sum() + GAP * max(0, widths.size - 1)
            var boltWidth = if (bolt) boltWidth(height) else 0
            if (boltWidth > 0 && width + GAP + boltWidth > fillWidth) boltWidth = 0
            if (boltWidth > 0) width += boltWidth + GAP
            if (width <= fillWidth || height <= 4) return Group(height, widths, boltWidth, width)
            height -= 2
        }
    }

    /**
     * Fill colours (ARGB, top and bottom of the gradient; the same for a flat fill). The digits and the bolt have one
     * colour, the theme's text colour, on the fill and on the empty part. So the fill must read with that colour:
     * dark text (Classic Light) gets the light fills, light text (Classic Dark: white) gets darker fills. Each pair
     * has a contrast of at least 3.7:1 with its text colour (WCAG), and the fills still read as green and red.
     * Low level (20 % or less, not charging): flat red.
     */
    fun fillColors(lightText: Boolean, charging: Boolean, level: Int): IntArray = when {
        charging -> if (lightText) intArrayOf(0xFF388E3C.toInt(), 0xFF2E7D32.toInt())
        else intArrayOf(0xFF8FE070.toInt(), 0xFF4CAF50.toInt())
        level <= LOW_LEVEL -> if (lightText) intArrayOf(0xFFC62828.toInt(), 0xFFC62828.toInt())
        else intArrayOf(0xFFF44336.toInt(), 0xFFF44336.toInt())
        else -> if (lightText) intArrayOf(0xFF4E8A2E.toInt(), 0xFF2F6B1A.toInt())
        else intArrayOf(0xFFD0E9B7.toInt(), 0xFF7CB342.toInt())
    }

    /** The red fill up to this level, when the battery does not charge. */
    const val LOW_LEVEL = 20

    /** True when [argb] is a light colour (WCAG relative luminance above 0.5), for example white text. */
    fun isLight(argb: Int): Boolean = luminance(argb) > 0.5

    /** WCAG relative luminance of [argb] (alpha ignored). */
    fun luminance(argb: Int): Double {
        fun channel(shift: Int): Double {
            val c = ((argb shr shift) and 0xFF) / 255.0
            return if (c <= 0.04045) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * channel(16) + 0.7152 * channel(8) + 0.0722 * channel(0)
    }

    /** True when the battery shows only the bolt: charging, and Battery Percentage off or the level at 100 %. */
    fun showsBoltAlone(level: Int, charging: Boolean, showPercentage: Boolean): Boolean =
        charging && (!showPercentage || level >= 100)

    /** Left of the bolt: on the right of the digits of [group] (which start at [groupLeft]), after one gap. */
    fun boltLeft(groupLeft: Int, group: Group): Int =
        groupLeft + group.digitWidths.sum() + GAP * group.digitWidths.size

    /** Left of a group [groupWidth] wide, centred in the fill; an odd free pixel goes to the right. */
    fun groupLeft(fillLeft: Int, fillWidth: Int, groupWidth: Int): Int =
        fillLeft + half(fillWidth - groupWidth)

    /** Top of the digits, centred in the fill. */
    fun digitTop(fillTop: Int, fillHeight: Int, digitHeight: Int): Int =
        fillTop + half(fillHeight - digitHeight)

    /** Half of [n], rounded down also when [n] is negative (Math.floorDiv is not on Android 4.4). */
    private fun half(n: Int): Int = if (n >= 0) n / 2 else (n - 1) / 2

    /**
     * The bolt in a box [width] x [height] from (0, 0): x, y of 8 points. Flat tips read as a bolt at this size;
     * pointed tips gave a blurred diamond. The tip width sets the thickness of the arms: 2 px for 5 px wide (arm
     * 1.9 px), 1.5 px for 4 px wide (arm 1.4 px), so the arms are as thick as the digit stems. The shape is the same
     * when turned 180°, so its centre is the centre of the box.
     */
    fun boltPoints(width: Int, height: Int): FloatArray {
        val w = width.toFloat()
        val h = height.toFloat()
        val tip = when {
            width >= 5 -> 2f
            width == 4 -> 1.5f
            else -> 1f
        }
        val mid = h / 2f
        val jog = max(1, (height * 0.1f).roundToInt()).toFloat()
        return floatArrayOf(
            w - tip - 1f, 0f,
            w - 1f, 0f,
            w / 2f + 0.5f, mid - jog,
            w, mid - jog,
            tip + 1f, h,
            1f, h,
            w / 2f - 0.5f, mid + jog,
            0f, mid + jog
        )
    }

    /**
     * Width of the bolt alone (Battery Percentage off), [height] tall (the fill height minus 2): 0.6 of the height,
     * with the parity of the fill, so that it is exactly in the centre.
     */
    fun soloBoltWidth(height: Int, fillWidth: Int): Int {
        var width = max(1, (height * 0.6f).roundToInt())
        if ((fillWidth - width) % 2 != 0) width -= 1
        return max(1, width)
    }
}
