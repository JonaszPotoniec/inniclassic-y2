package com.themoon.y1.controllers.coverflow

import kotlin.math.acos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Cover Flow geometry of the original iPod, for the 480 x 360 screen (values in dp; 1 dp = 1 px on the Y2).
 *
 * Values come from Apple's own 320 x 240 Cover Flow image in the iPod nano 4G guide (the 2007 classic and
 * nano 3G photos agree to 1 px), scaled by 1.5. This object has no Android dependencies, so the layout
 * maths can be unit-tested.
 */
object CoverFlowGeometry {
    // Centre cover: 128 x 128 on the iPod, 22 px below a 20 px status bar.
    const val COVER_SIZE = 192

    // Reflection: directly under the cover, 52 px on the iPod, ~28 % opaque at the top, linear to 0.
    const val REFLECTION_HEIGHT = 78
    const val REFLECTION_TOP_ALPHA = 0x47

    // Text: album (bold, cap 12 on the iPod), then artist (cap 11-12), baselines below the cover top.
    const val TITLE_SIZE = 25f
    const val ARTIST_SIZE = 24f
    const val TITLE_BASELINE = 245
    const val ARTIST_BASELINE = 273
    const val TEXT_WIDTH = 440

    // Below a 30 px status bar (330 px area) there are 33 px above the cover and 24 px below the
    // artist baseline. Other status bar heights share the difference in the same ratio.
    private const val GAP_ABOVE = 33
    private const val GAP_BELOW = 24

    // Side covers: 3 each side; visible strips of 60, 42 and 42 px; outer edge 130 and inner end
    // 106 px tall on the iPod for a 128 px cover (each cover hides the inner part of the next).
    const val SIDE_COUNT = 3
    const val FIRST_STRIP = 60
    const val NEXT_STRIP = 42
    const val OUTER_EDGE_RATIO = 130f / 128f
    const val INNER_EDGE_RATIO = 106f / 128f

    /**
     * Height of the pivot (the vanishing line of the turned covers) as a fraction of the cover height.
     * With it, the inner end of a side cover has its top 16 px (iPod) below the centre cover top, and its
     * bottom 6 px above the centre cover bottom, as in Apple's image.
     */
    const val PIVOT_Y_FRACTION = 0.727f

    /** Rows of a cover bitmap [coverRows] tall that its reflection shows (78/192 of the cover). */
    fun reflectionRows(coverRows: Int): Int = coverRows * REFLECTION_HEIGHT / COVER_SIZE

    /** Scale and offset of a transform x' = scaleX * x + translateX, y' = scaleY * y + translateY. */
    data class Affine(val scaleX: Float, val scaleY: Float, val translateX: Float, val translateY: Float)

    /**
     * Maps a cover bitmap ([coverWidth] x [coverHeight]) onto a reflection box ([left], [top], [width] x [height]):
     * the cover is mirrored, so its bottom row is at the top of the box, and the box shows the bottom
     * [reflectionRows] rows of the cover, at the same scale as the cover.
     */
    fun reflectionTransform(coverWidth: Int, coverHeight: Int, left: Float, top: Float, width: Float, height: Float): Affine {
        require(coverWidth > 0 && coverHeight > 0)
        val rows = reflectionRows(coverHeight).coerceAtLeast(1)
        val sx = width / coverWidth
        val sy = height / rows
        return Affine(sx, -sy, left, top + sy * coverHeight)
    }

    /** Top of the cover below the top of an area [areaHeight] tall (the screen below the status bar). */
    fun coverTop(areaHeight: Int): Int {
        val free = areaHeight - ARTIST_BASELINE
        return (free * GAP_ABOVE / (GAP_ABOVE + GAP_BELOW).toFloat()).roundToInt()
    }

    /** Tilt of a side cover: scale applied before the turn, turn angle, and camera distance (px). */
    data class Tilt(val scale: Float, val rotationY: Float, val cameraDistancePx: Float)

    /**
     * Solves the side cover tilt for a cover [size] px wide: the outer edge is [outerRatio] * size tall,
     * the inner end [innerRatio] * size, and the projected width is [stripWidth].
     *
     * The card turns about its centre. With scale s, angle a and camera distance D, a point at x = u from
     * the centre goes to depth z = s u sin(a) and projects to X = s u cos(a) k, with height scale
     * k = s D / (D + z). The edge height ratio gives q = (s w sin a) / D, the outer height gives s, and the
     * width gives a, then D.
     */
    fun sideTilt(
        size: Float = COVER_SIZE.toFloat(),
        outerRatio: Float = OUTER_EDGE_RATIO,
        innerRatio: Float = INNER_EDGE_RATIO,
        stripWidth: Float = FIRST_STRIP.toFloat()
    ): Tilt {
        require(size > 0f && outerRatio > innerRatio && innerRatio > 0f && stripWidth > 0f)
        val r = outerRatio.toDouble() / innerRatio
        val q = (r - 1.0) / (r + 1.0)
        val scale = outerRatio * (1.0 - q)
        val halfWidth = size / 2.0 * scale
        val projectedHalf = stripWidth * (1.0 - q * q) / 2.0
        val angle = acos(projectedHalf / halfWidth)
        val distance = halfWidth * sin(angle) / q
        return Tilt(scale.toFloat(), Math.toDegrees(angle).toFloat(), distance.toFloat())
    }

    /** Projected x of the inner end (negative) and of the outer edge, from the card centre. */
    fun projectedEdges(tilt: Tilt, size: Float = COVER_SIZE.toFloat()): Pair<Float, Float> {
        val a = Math.toRadians(tilt.rotationY.toDouble())
        val half = size / 2.0 * tilt.scale
        val x = half * kotlin.math.cos(a)
        val z = half * sin(a)
        val d = tilt.cameraDistancePx.toDouble()
        return Pair((-x * d / (d + z)).toFloat(), (x * d / (d - z)).toFloat())
    }

    /** Height scale (projected height / cover height) at the inner end and at the outer edge. */
    fun edgeHeightRatios(tilt: Tilt, size: Float = COVER_SIZE.toFloat()): Pair<Float, Float> {
        val z = size / 2.0 * tilt.scale * sin(Math.toRadians(tilt.rotationY.toDouble()))
        val d = tilt.cameraDistancePx.toDouble()
        return Pair((tilt.scale * d / (d + z)).toFloat(), (tilt.scale * d / (d - z)).toFloat())
    }

    /**
     * Horizontal distance from the screen centre to the centre of the card [dist] places from the centre.
     * The outer edge of side cover n is at the end of its strip; the cover after the last one is
     * one strip further out (hidden at the screen edge).
     */
    fun translationX(dist: Int, tilt: Tilt, size: Float = COVER_SIZE.toFloat()): Float {
        if (dist <= 0) return 0f
        val outerEdge = size / 2f + FIRST_STRIP + NEXT_STRIP * (dist - 1)
        return outerEdge - projectedEdges(tilt, size).second
    }
}
