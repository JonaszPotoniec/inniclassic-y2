package com.themoon.y1.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import com.themoon.y1.ThemeManager

class EqSliderView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val trackPaint: Paint
    private val activeTrackPaint: Paint
    private val thumbPaint: Paint
    private val textPaint: Paint

    private var min = -1500
    private var max = 1500
    private var level = 0
    private var isSliderFocused = false
    private var isAdjusting = false
    private var themeColor = 0xFF00FFFF.toInt()

    init {
        try {
            themeColor = ThemeManager.getListButtonFocusedBg() or 0xFF000000.toInt()
        } catch (_: Exception) {
        }

        trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = 0xFF555555.toInt()
        }

        activeTrackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }

        thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }

        textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT_BOLD
        }
    }

    fun setRange(min: Int, max: Int) {
        this.min = min
        this.max = max
        invalidate()
    }

    fun setLevel(level: Int) {
        this.level = level
        invalidate()
    }

    fun setFocused(focused: Boolean) {
        this.isSliderFocused = focused
        invalidate()
    }

    fun setAdjusting(adjusting: Boolean) {
        this.isAdjusting = adjusting
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val padY = 40f
        val trackX = w / 2f
        val trackHeight = h - (padY * 2)
        val trackTop = padY
        val trackBottom = h - padY

        // Background track line
        trackPaint.strokeWidth = 6f
        canvas.drawLine(trackX, trackTop, trackX, trackBottom, trackPaint)
        // Center 0dB tick
        canvas.drawLine(trackX - 10f, trackTop + trackHeight / 2f, trackX + 10f, trackTop + trackHeight / 2f, trackPaint)

        val ratio = (level - min).toFloat() / (max - min)
        val thumbY = trackBottom - (ratio * trackHeight)

        activeTrackPaint.color = if (isAdjusting) {
            0xFFFF8800.toInt()
        } else if (isSliderFocused) {
            themeColor
        } else {
            0xFFAAAAAA.toInt()
        }
        activeTrackPaint.strokeWidth = 8f
        canvas.drawLine(trackX, trackTop + trackHeight / 2f, trackX, thumbY, activeTrackPaint)

        thumbPaint.color = if (isAdjusting) {
            0xFFFF8800.toInt()
        } else if (isSliderFocused) {
            themeColor
        } else {
            0xFFDDDDDD.toInt()
        }
        canvas.drawCircle(trackX, thumbY, 10f, thumbPaint)

        textPaint.color = 0xFFFFFFFF.toInt()
        textPaint.textSize = 22f
        val dbStr = (if (level > 0) "+" else "") + (level / 100)
        canvas.drawText(dbStr, trackX, thumbY - 25f, textPaint)
    }
}
