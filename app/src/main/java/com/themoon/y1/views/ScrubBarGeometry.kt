package com.themoon.y1.views

/**
 * Geometry of the Now Playing scrub bar in the iPod Classic layout. No Android dependencies.
 *
 * The scrub row must show the bar at the same place as the progress row and only add the diamond.
 * The diamond is centred on the end of the fill and on the bar centre line. It is larger than the bar,
 * and at 0 % and 100 % it goes past the bar ends. So the SeekBar view is the bar plus an outset on each
 * side: the outset is the view padding (the bar is drawn inside the padding), and negative margins of
 * the same size keep the bar at the progress bar position.
 */
object ScrubBarGeometry {
    /** Drawable levels are 0..10000 (ProgressBar.MAX_LEVEL). */
    const val MAX_LEVEL = 10000

    data class Box(val left: Float, val top: Float, val right: Float, val bottom: Float) {
        val width: Float get() = right - left
        val height: Float get() = bottom - top
        val centreX: Float get() = (left + right) / 2f
        val centreY: Float get() = (top + bottom) / 2f

        fun offset(dx: Float, dy: Float) = Box(left + dx, top + dy, right + dx, bottom + dy)
        fun contains(b: Box) = b.left >= left && b.top >= top && b.right <= right && b.bottom <= bottom
    }

    /** Space at the left and at the right of the bar for half the diamond. */
    fun outsetX(diamondWidth: Int): Int = (diamondWidth + 1) / 2

    /** Space above and below the bar for the part of the diamond that is taller than the bar. */
    fun outsetY(diamondHeight: Int, barHeight: Int): Int = maxOf(0, (diamondHeight - barHeight + 1) / 2)

    /** The scrub view: the bar rectangle plus the outsets (in the coordinates of [bar]). */
    fun viewFrame(bar: Box, diamondWidth: Int, diamondHeight: Int): Box {
        val ox = outsetX(diamondWidth).toFloat()
        val oy = outsetY(diamondHeight, bar.height.toInt()).toFloat()
        return Box(bar.left - ox, bar.top - oy, bar.right + ox, bar.bottom + oy)
    }

    /** The bar inside a view of [viewWidth] x [viewHeight] with the padding [padX], [padY] on each side. */
    fun barInView(viewWidth: Int, viewHeight: Int, padX: Int, padY: Int): Box =
        Box(padX.toFloat(), padY.toFloat(), (viewWidth - padX).toFloat(), (viewHeight - padY).toFloat())

    /** Top of a child of [childHeight] centred in a horizontal LinearLayout of [rowHeight] (gravity center_vertical). */
    fun centredTop(rowHeight: Int, childHeight: Int): Int = (rowHeight - childHeight) / 2

    /** The level that ProgressBar gives the progress drawable (API 19: (int) (progress / max * MAX_LEVEL)). */
    fun level(progress: Int, max: Int): Int {
        if (max <= 0) return 0
        val p = progress.coerceIn(0, max)
        return (p.toFloat() / max * MAX_LEVEL).toInt().coerceIn(0, MAX_LEVEL)
    }

    /** Width of the fill that the ClipDrawable of the bar draws at [level] (API 19 ClipDrawable.draw, integer maths). */
    fun fillWidth(barWidth: Int, level: Int): Int {
        if (level <= 0 || barWidth <= 0) return 0
        return barWidth - barWidth * (MAX_LEVEL - level.coerceAtMost(MAX_LEVEL)) / MAX_LEVEL
    }

    /** X of the end of the fill. */
    fun fillEnd(bar: Box, progress: Int, max: Int): Float =
        bar.left + fillWidth(bar.width.toInt(), level(progress, max))

    /** The diamond: centred on the end of the fill and on the bar centre line. */
    fun diamond(bar: Box, progress: Int, max: Int, diamondWidth: Int, diamondHeight: Int): Box {
        val cx = fillEnd(bar, progress, max)
        val cy = bar.centreY
        val hw = diamondWidth / 2f
        val hh = diamondHeight / 2f
        return Box(cx - hw, cy - hh, cx + hw, cy + hh)
    }
}
