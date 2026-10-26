package com.themoon.y1.views

import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * iPod classic UI metrics for the 480 x 360 screen, in dp (1 dp = 1 px on the Y2).
 *
 * Values come from Apple's own 320 x 240 screen images in the iPod classic guides, scaled by 1.5.
 * This object has no Android dependencies, so the layout maths can be unit-tested.
 */
object IpodClassicMetrics {
    // Status bar: 20 px on the iPod, including a 1 px dark line at the bottom.
    const val STATUS_BAR_HEIGHT = 30
    const val STATUS_BAR_RULE = 1
    const val STATUS_TITLE_SP = 19f
    const val STATUS_PADDING_LEFT = 10
    const val STATUS_PADDING_RIGHT = 6
    // All status bar items share one box with the capitals of the text: 14 px = the cap height of the
    // 19 px title font (Nimbus Sans Bold, 13.85 px), so the space above and below the ink is the same
    // for the text, the icons and the battery. The ink of each icon goes from the top row to the bottom
    // row of the box (StatusIcons, IpodPlayStateView).
    const val STATUS_ICON_HEIGHT = 14
    // Round shapes (headphones, Wi-Fi, Bluetooth) look smaller than flat ones of the same height, as the
    // round letters of a font. They go 1 px above the box (optical overshoot); the bottom stays on the box.
    const val STATUS_ROUND_ICON_HEIGHT = STATUS_ICON_HEIGHT + 1
    // The battery has a 2 px outline at this size (BatteryIconView.setThinOutline), so the digits stay legible.
    // Its thin grey outline looks shorter than the bold blue icons: it goes 1 px above and below the box
    // (optical overshoot), on the same centre line.
    const val BATTERY_WIDTH = 30
    const val BATTERY_OVERSHOOT = 1
    const val BATTERY_HEIGHT = STATUS_ICON_HEIGHT + 2 * BATTERY_OVERSHOOT
    const val PLAY_ICON_WIDTH = 12
    // Space between the ink of two items (the iPod has 5 px between the play icon and the battery).
    const val STATUS_ITEM_GAP = 8

    // Lists: 25 px rows, cap height 11, text at x = 7 on the iPod.
    const val ROW_HEIGHT = 38
    const val LIST_TEXT_SP = 23f
    const val ROW_PADDING_LEFT = 10
    const val ROW_PADDING_RIGHT = 12
    const val CHEVRON_WIDTH = 10
    const val CHEVRON_HEIGHT = 16
    const val CHEVRON_STROKE = 3
    const val PANE_WIDTH = 240

    // Now Playing.
    const val ART_LEFT = 15
    const val ART_TOP = 52
    const val ART_SIZE = 204
    const val REFLECTION_HEIGHT = 30
    const val TEXT_LEFT = 240
    const val TEXT_RIGHT_PADDING = 10
    const val NP_TITLE_SP = 23f
    const val NP_DETAIL_SP = 19f
    const val NP_TITLE_BASELINE = 106
    const val NP_ARTIST_BASELINE = 133
    const val NP_ALBUM_BASELINE = 160
    const val NP_RATING_CENTRE = 181
    const val NP_RATING_HEIGHT = 14
    const val NP_COUNT_BASELINE = 214
    const val MODE_ICON_BOX = 28
    const val MODE_ICON_CENTRE_Y = 45
    const val MODE_ICON_RIGHT = 10
    const val PROGRESS_LEFT = 87
    const val PROGRESS_TOP = 309
    const val PROGRESS_WIDTH = 306
    const val PROGRESS_HEIGHT = 21
    const val TIME_SP = 19f
    const val TIME_LEFT = 33
    const val TIME_RIGHT = 455
    // Time labels are taller than the bar and centred on it, so their caps can be centred exactly.
    const val TIME_BOX_HEIGHT = 41
    // Scrub diamond: centred on the end of the fill and on the bar centre line, 2 px above and below the bar.
    // Height - bar height is even, so the diamond centre is exactly on the bar centre.
    const val SCRUB_DIAMOND_WIDTH = 22
    const val SCRUB_DIAMOND_HEIGHT = 25

    // Album art tilt. Apple's image: left edge y 35..170, right edge y 44..162, width x 10..145.
    const val TILT_RIGHT_EDGE_RATIO = 119f / 136f
    const val TILT_WIDTH_RATIO = 1f
    const val TILT_ANGLE_DEG = 18f

    /**
     * Top of the status item box. The items are centred above the 1 px line (LinearLayout gravity
     * CENTER_VERTICAL with a bottom margin of the line), which gives this value.
     */
    fun statusItemTop(): Int = (STATUS_BAR_HEIGHT - STATUS_BAR_RULE - STATUS_ICON_HEIGHT) / 2

    /** The centre line of all status bar items, from the top of the bar: the icons, the battery and the capitals. */
    fun statusCentreLine(): Float = statusItemTop() + STATUS_ICON_HEIGHT / 2f

    /**
     * Top padding that puts the centre of the capital letters on the centre of a box of [boxHeight].
     * Use with gravity TOP and includeFontPadding = false, where the baseline is at paddingTop - ascent.
     *
     * @param ascent Paint.FontMetricsInt.ascent (negative).
     * @param capHeight height of "H" above the baseline.
     */
    fun capCentredPaddingTop(boxHeight: Float, ascent: Int, capHeight: Float): Int =
        (boxHeight / 2f + ascent + capHeight / 2f).roundToInt()

    /** Top of a single-line text box (includeFontPadding = false) whose baseline is at [baseline]. */
    fun textTopForBaseline(baseline: Int, ascent: Int): Int = baseline + ascent

    /** Baseline that puts the centre of a glyph with bounds [glyphTop]..[glyphBottom] (relative to baseline) on [centre]. */
    fun baselineForGlyphCentre(centre: Int, glyphTop: Int, glyphBottom: Int): Int =
        centre - ((glyphTop + glyphBottom) / 2f).roundToInt()

    /**
     * Top margins for views stacked in a vertical LinearLayout that starts at [columnTop],
     * so that view i starts at [tops][i]. [heights][i] is the height of view i.
     */
    fun stackTopMargins(columnTop: Int, tops: IntArray, heights: IntArray): IntArray {
        require(tops.size == heights.size)
        val margins = IntArray(tops.size)
        var bottom = columnTop
        for (i in tops.indices) {
            margins[i] = tops[i] - bottom
            bottom = tops[i] + heights[i]
        }
        return margins
    }

    /** Text size that makes a glyph [targetHeight] tall, given that it is [measuredHeight] tall at [currentSize]. */
    fun scaleTextSizeToHeight(currentSize: Float, measuredHeight: Float, targetHeight: Float): Float =
        if (measuredHeight <= 0f) currentSize else currentSize * targetHeight / measuredHeight

    /**
     * View transform for the tilted album art: rotationY about the left edge (pivotX = 0, pivotY = centre),
     * with scaleX applied before the rotation and a camera at [cameraDistancePx] in front of the view.
     */
    data class ArtTilt(val rotationY: Float, val scaleX: Float, val cameraDistancePx: Float)

    /**
     * Solves the tilt that keeps the left edge [side] tall, makes the right edge [rightEdgeRatio] * [side] tall,
     * and keeps the projected width at [widthRatio] * [side], like Apple's Now Playing image.
     *
     * With the pivot on the left edge, a point at x = u projects to X = sx cos(a) u D / (D + sx sin(a) u),
     * so the right edge scale is r = D / (D + sx sin(a) side) and the projected width is sx cos(a) side r.
     * Any angle gives the same picture; [angleDeg] only picks one member of the family.
     */
    fun artTilt(
        side: Float,
        rightEdgeRatio: Float = TILT_RIGHT_EDGE_RATIO,
        widthRatio: Float = TILT_WIDTH_RATIO,
        angleDeg: Float = TILT_ANGLE_DEG
    ): ArtTilt {
        require(side > 0f && rightEdgeRatio > 0f && rightEdgeRatio < 1f && angleDeg > 0f && angleDeg < 90f)
        val a = Math.toRadians(angleDeg.toDouble())
        val sx = widthRatio / (rightEdgeRatio * cos(a))
        val distance = sx * side * sin(a) / (1.0 / rightEdgeRatio - 1.0)
        return ArtTilt(angleDeg, sx.toFloat(), distance.toFloat())
    }

    /** Value for View.setCameraDistance(): Android puts the camera at -distance / densityDpi inches, 72 px per inch. */
    fun cameraDistanceSetting(distancePx: Float, densityDpi: Int): Float = distancePx * densityDpi / 72f

    /** Projects a point at x = [u] from the pivot (left edge); returns (projected x, vertical scale). */
    fun projectTilted(tilt: ArtTilt, u: Float): Pair<Float, Float> {
        val a = Math.toRadians(tilt.rotationY.toDouble())
        val x = tilt.scaleX * u * cos(a)
        val z = tilt.scaleX * u * sin(a)
        val k = tilt.cameraDistancePx / (tilt.cameraDistancePx + z)
        return Pair((x * k).toFloat(), k.toFloat())
    }
}
