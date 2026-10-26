package com.themoon.y1.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The tick column of the selection lists (Theme, Language, EQ preset, Time Zone, Wi-Fi, Bluetooth...).
 * The column has a fixed width, so the text starts at the same x in all these lists. The tick is as tall as the
 * capital letters of the row text, stands on its baseline, and has a stroke about as heavy as the letters.
 * Rows without the tick make this view INVISIBLE: the column stays.
 */
class CheckMarkView(context: Context) : View(context) {
    /** The row text: the tick takes its cap height and its baseline. */
    var textView: TextView? = null

    var color: Int
        get() = paint.color
        set(value) {
            if (paint.color != value) {
                paint.color = value
                invalidate()
            }
        }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val path = Path()
    private val bounds = Rect()
    // The path is made again only when the text size or the typeface changes (not in each draw).
    private var pathTextSize = 0f
    private var pathTypeface: Any? = null

    init {
        val d = resources.displayMetrics.density
        layoutParams = LinearLayout.LayoutParams(Math.round(CheckMark.COLUMN_DP * d), ViewGroup.LayoutParams.MATCH_PARENT)
    }

    override fun onDraw(canvas: Canvas) {
        val tv = textView ?: return
        val textPaint = tv.paint
        if (textPaint.textSize != pathTextSize || textPaint.typeface !== pathTypeface) {
            pathTextSize = textPaint.textSize
            pathTypeface = textPaint.typeface
            textPaint.getTextBounds("H", 0, 1, bounds)
            val capHeight = bounds.height().toFloat()
            val pts = CheckMark.points(capHeight)
            path.reset()
            path.moveTo(pts[0], pts[1])
            path.lineTo(pts[2], pts[3])
            path.lineTo(pts[4], pts[5])
            path.offset(0f, -capHeight)
            paint.strokeWidth = CheckMark.strokeWidth(capHeight)
        }
        // The bottom of the tick on the baseline of the text (the text view and this view are in the same row).
        val baseline = (tv.top + tv.baseline - top).toFloat()
        canvas.save()
        canvas.translate(0f, baseline)
        canvas.drawPath(path, paint)
        canvas.restore()
    }
}

/** Shape and size of the tick ([CheckMarkView]). Pure functions, for unit tests. */
object CheckMark {
    /** Width of the tick column: from the left edge of the tick to the text. Same as an 18 dp icon + 10 dp margin. */
    const val COLUMN_DP = 28f

    /** Stroke width as a part of the cap height. The stems of Nimbus Sans Bold are ~0.2 of the cap height. */
    const val STROKE_RATIO = 0.19f

    /** Centre line of the tick in a box of height 1: left end, bottom corner, right end. Width = 1.25 × height. */
    private val SHAPE = floatArrayOf(0f, 0.55f, 0.42f, 1f, 1.25f, 0f)

    fun strokeWidth(capHeight: Float): Float = capHeight * STROKE_RATIO

    /**
     * The centre line (x0, y0, x1, y1, x2, y2) of a tick as tall as [capHeight]. With round caps and joins, the
     * outer edge of the stroke touches x = 0, y = 0 (top) and y = [capHeight] (bottom, the baseline).
     */
    fun points(capHeight: Float): FloatArray {
        val stroke = strokeWidth(capHeight)
        val h = capHeight - stroke
        return FloatArray(SHAPE.size) { i -> stroke / 2 + SHAPE[i] * h }
    }

    /** Width of the tick with its stroke. */
    fun width(capHeight: Float): Float {
        val stroke = strokeWidth(capHeight)
        return SHAPE[4] * (capHeight - stroke) + stroke
    }
}
