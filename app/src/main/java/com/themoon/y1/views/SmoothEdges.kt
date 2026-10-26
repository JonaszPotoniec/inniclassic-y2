package com.themoon.y1.views

import android.view.View
import android.view.ViewGroup
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Smooth edges for album art that is drawn with a 3D view transform (rotationY).
 *
 * Android does not anti-alias the edges of a bitmap drawn with a perspective transform: the
 * top and bottom edges of a tilted cover show steps. The GPU does filter the inside of the
 * texture, so [SmoothEdgeImageView] draws the art from a bitmap with a transparent border of
 * [BORDER_DP] around it. The visible edge is then inside the texture and bilinear filtering
 * smooths it on every frame, for any transform and during animations.
 *
 * The border is drawn outside the view bounds, so the view keeps its size and position, and
 * the cover keeps its visible size. [drawOutsideParents] stops the parents from clipping it.
 *
 * The maths here has no Android dependencies, so it can be unit-tested.
 */
object SmoothEdges {
    /** Transparent border around the art, in dp (1 dp = 1 px on the Y2). */
    const val BORDER_DP = 2f

    /** Border in pixels: at least 1 px, so the edge is always inside the texture. */
    fun borderPx(density: Float): Int = max(1, (BORDER_DP * density).roundToInt())

    /** Integer rectangle, left and top inclusive, right and bottom exclusive. */
    data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val width: Int get() = right - left
        val height: Int get() = bottom - top
    }

    /**
     * Where ImageView.ScaleType.CENTER_CROP puts a [srcWidth] x [srcHeight] image in a
     * [width] x [height] view: scaled to cover the view, centred, so it can extend past the view.
     * An image without a size fills the view.
     */
    fun centerCropBounds(srcWidth: Int, srcHeight: Int, width: Int, height: Int): Box {
        if (srcWidth <= 0 || srcHeight <= 0) return Box(0, 0, width, height)
        val scale = max(width.toFloat() / srcWidth, height.toFloat() / srcHeight)
        val w = srcWidth * scale
        val h = srcHeight * scale
        val left = ((width - w) / 2f).roundToInt()
        val top = ((height - h) / 2f).roundToInt()
        return Box(left, top, left + w.roundToInt(), top + h.roundToInt())
    }

    /**
     * Rows of a view (in its own coordinates) that show inside its parent, and the rows where a
     * reflection fade starts (opaque, at the parent's top) and ends (clear, at the parent's bottom).
     */
    data class Strip(val top: Int, val bottom: Int, val opaqueY: Float, val clearY: Float)

    /**
     * The visible part of a view [viewHeight] tall at [viewTop] in a parent [parentHeight] tall.
     * A [flipped] view (scaleY = -1 about [pivotY], like a reflection) shows its rows upside down.
     * Returns null when no row is inside the parent.
     */
    fun visibleStrip(viewTop: Int, viewHeight: Int, parentHeight: Int, flipped: Boolean, pivotY: Float): Strip? {
        // Parent row p shows view row y: p = viewTop + y, or p = viewTop + 2 * pivotY - y when flipped.
        fun rowAt(p: Float): Float = if (flipped) 2f * pivotY - (p - viewTop) else p - viewTop
        val a = rowAt(0f)
        val b = rowAt(parentHeight.toFloat())
        val top = max(0, floor(min(a, b)).toInt())
        val bottom = min(viewHeight, ceil(max(a, b)).toInt())
        if (bottom <= top) return null
        return Strip(top, bottom, a, b)
    }

    /** Size of the bitmap for [width] x [height] pixels of art with a [border] on every side. */
    fun borderedSize(width: Int, height: Int, border: Int): Pair<Int, Int> =
        Pair(width + 2 * border, height + 2 * border)

    /**
     * Lets [view] draw its border outside its bounds: its parent and grandparent stop clipping
     * their children. Each child view still draws only its own content.
     */
    fun drawOutsideParents(view: View) {
        var parent = view.parent
        repeat(2) {
            val group = parent as? ViewGroup ?: return
            group.clipChildren = false
            parent = group.parent
        }
    }
}
