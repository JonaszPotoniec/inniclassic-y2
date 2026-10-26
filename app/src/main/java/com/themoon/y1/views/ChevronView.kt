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
import com.themoon.y1.ThemeManager

/**
 * The disclosure chevron at the right of a setting row that opens a deeper screen (as macOS System Settings): a thin
 * open "›", a little shorter than the capital letters of the row title, centred on their cap band. Its right edge is the
 * right edge of the row content, as the value text and the switches. Grey (the value colour) in a normal row, the
 * selected text colour in the selected row.
 */
class ChevronView(context: Context, private val title: TextView) : View(context) {

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
        // The row has the focus: take its state, so the chevron draws again in the selected colour.
        isDuplicateParentStateEnabled = true
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }

    private fun capHeight(): Float {
        title.paint.getTextBounds("H", 0, 1, bounds)
        return bounds.height().toFloat()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = Math.ceil(DisclosureChevron.viewWidth(capHeight()).toDouble()).toInt()
        setMeasuredDimension(width, getDefaultSize(suggestedMinimumHeight, heightMeasureSpec))
    }

    override fun drawableStateChanged() {
        super.drawableStateChanged()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val textPaint = title.paint
        val cap = capHeight()
        if (cap <= 0f) return
        if (textPaint.textSize != pathTextSize || textPaint.typeface !== pathTypeface) {
            pathTextSize = textPaint.textSize
            pathTypeface = textPaint.typeface
            val p = DisclosureChevron.points(cap)
            path.reset()
            path.moveTo(p[0], p[1])
            path.lineTo(p[2], p[3])
            path.lineTo(p[4], p[5])
            paint.strokeWidth = DisclosureChevron.strokeWidth(cap)
        }
        val selected = (parent as? View)?.isFocused == true
        paint.color = if (selected) ThemeManager.getListButtonFocusedTextColor() else ThemeManager.getTextColorSecondary()
        // Centred on the cap band of the title (same row): from the top of the capitals to the baseline.
        val baseline = (title.top + title.baseline - top).toFloat()
        canvas.save()
        canvas.translate(width - DisclosureChevron.width(cap), Math.round(baseline - cap + DisclosureChevron.top(cap)).toFloat())
        canvas.drawPath(path, paint)
        canvas.restore()
    }
}

/** Shape, size and text rules of [ChevronView]. Pure functions, for unit tests. */
object DisclosureChevron {
    // Measured on macOS System Settings → General (cap height of "A" 20 px): the chevron is 18 px high, 11 px wide,
    // with a ~2.7 px stroke, centred on the cap band.

    /** Height of the chevron (with its stroke) as a part of the cap height. */
    const val HEIGHT_RATIO = 0.9f

    /** Width of the chevron (with its stroke) as a part of the cap height. */
    const val WIDTH_RATIO = 0.55f

    /** Stroke width as a part of the cap height: thinner than the letters (the tick has 0.19). */
    const val STROKE_RATIO = 0.13f

    /** Space between the value text and the chevron, as a part of the cap height. */
    const val GAP_RATIO = 0.5f

    private val MARKS = charArrayOf('〉', '›')

    fun strokeWidth(capHeight: Float): Float = Math.max(1f, capHeight * STROKE_RATIO)

    fun width(capHeight: Float): Float = capHeight * WIDTH_RATIO

    fun height(capHeight: Float): Float = capHeight * HEIGHT_RATIO

    /** Top of the chevron below the top of the capitals: the chevron is centred on the cap band. */
    fun top(capHeight: Float): Float = (capHeight - height(capHeight)) / 2

    /** Width of [ChevronView]: the gap after the value text, then the chevron. */
    fun viewWidth(capHeight: Float): Float = capHeight * GAP_RATIO + width(capHeight)

    /**
     * The centre line (x0, y0, x1, y1, x2, y2) of the chevron for a text with [capHeight]: top end, tip, bottom end.
     * With round caps and join, the outer edge of the stroke touches x = 0, x = [width], y = 0 and y = [height].
     */
    fun points(capHeight: Float): FloatArray {
        val half = strokeWidth(capHeight) / 2
        val w = width(capHeight)
        val h = height(capHeight)
        return floatArrayOf(half, half, w - half, h / 2, half, h - half)
    }

    /**
     * A row value with the old text chevron ("Edit 〉", "〉 ") → the value text without it, and true. Other values
     * stay as they are, with false.
     */
    fun split(value: String): Pair<String, Boolean> {
        val trimmed = value.trimEnd()
        if (trimmed.isEmpty() || trimmed.last() !in MARKS) return value to false
        return trimmed.dropLast(1).trimEnd() to true
    }
}
