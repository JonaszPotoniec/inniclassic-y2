package com.themoon.y1.views

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.widget.ProgressBar

/**
 * The scrub diamond of the iPod Classic layout, used as the SeekBar thumb.
 *
 * The SeekBar has the bar inside its padding and a thumb offset of 0, so AbsSeekBar draws the thumb
 * with the canvas origin at the top-left corner of the bar. This drawable ignores the thumb bounds that
 * AbsSeekBar calculates (they are rounded and do not include the padding) and reads the progress from
 * the bar, so that its centre is on the end of the fill that the ClipDrawable draws ([ScrubBarGeometry]).
 * Its intrinsic size is 0: then AbsSeekBar gives the track all of the area inside the padding.
 *
 * The edge is drawn first and the fill on top of it (no Path.op: it can hang on Android 4.4).
 */
internal class ScrubDiamondDrawable(
    private val bar: ProgressBar,
    val diamondWidth: Int,
    val diamondHeight: Int
) : Drawable() {
    private val path = Path()
    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = EDGE_COLOR; style = Paint.Style.FILL }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = FILL_COLOR; style = Paint.Style.FILL }

    override fun draw(canvas: Canvas) {
        val barWidth = bar.width - bar.paddingLeft - bar.paddingRight
        val barHeight = bar.height - bar.paddingTop - bar.paddingBottom
        if (barWidth <= 0 || barHeight <= 0) return
        val box = ScrubBarGeometry.Box(0f, 0f, barWidth.toFloat(), barHeight.toFloat())
        val d = ScrubBarGeometry.diamond(box, bar.progress, bar.max, diamondWidth, diamondHeight)
        drawDiamond(canvas, d, 0f, edgePaint)
        drawDiamond(canvas, d, EDGE_WIDTH, fillPaint)
    }

    /** Draws the diamond in [d], made smaller by [inset] on each side (measured along the axes). */
    private fun drawDiamond(canvas: Canvas, d: ScrubBarGeometry.Box, inset: Float, paint: Paint) {
        // An inset t perpendicular to the edges moves the side tips by t * sqrt(1 + (w/h)^2)
        // and the top and bottom tips by t * sqrt(1 + (h/w)^2).
        val r = (d.width / d.height).toDouble()
        val hw = d.width / 2f - inset * Math.sqrt(1.0 + r * r).toFloat()
        val hh = d.height / 2f - inset * Math.sqrt(1.0 + 1.0 / (r * r)).toFloat()
        if (hw <= 0f || hh <= 0f) return
        path.reset()
        path.moveTo(d.centreX, d.centreY - hh)
        path.lineTo(d.centreX + hw, d.centreY)
        path.lineTo(d.centreX, d.centreY + hh)
        path.lineTo(d.centreX - hw, d.centreY)
        path.close()
        canvas.drawPath(path, paint)
    }

    override fun getIntrinsicWidth(): Int = 0
    override fun getIntrinsicHeight(): Int = 0

    override fun setAlpha(alpha: Int) {
        edgePaint.alpha = alpha
        fillPaint.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        edgePaint.colorFilter = colorFilter
        fillPaint.colorFilter = colorFilter
        invalidateSelf()
    }

    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    companion object {
        // The colours of the progress_bar_now_playing gradient: the dark end for the edge, the diamond
        // colour of the default seek_diamond_thumb for the fill.
        const val EDGE_COLOR = 0xFF2E5A8F.toInt()
        const val FILL_COLOR = 0xFF6194D1.toInt()
        const val EDGE_WIDTH = 1.5f
    }
}
