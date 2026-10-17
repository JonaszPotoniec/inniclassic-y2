package com.themoon.y1.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import com.themoon.y1.ThemeManager

class CircularBatteryView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val trackPaint: Paint
    private val progressPaint: Paint
    private val textPaint: Paint
    private var level = 100
    private var isCharging = false
    private val rectF = RectF()

    init {
        val density = resources.displayMetrics.density

        trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 8f * density
            color = ThemeManager.getTextColorSecondary()
            alpha = 60
        }

        progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 8f * density
            strokeCap = Paint.Cap.ROUND
            color = ThemeManager.getTextColorPrimary()
        }

        textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ThemeManager.getTextColorPrimary()
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(ThemeManager.getCustomFont(), Typeface.BOLD)
        }
    }

    fun setBatteryLevel(level: Int, isCharging: Boolean) {
        this.level = level
        this.isCharging = isCharging
        invalidate()
    }

    fun setCustomTextSize(size: Float) {
        textPaint.textSize = size
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()

        val stroke = minOf(w, h) * 0.08f
        trackPaint.strokeWidth = stroke
        progressPaint.strokeWidth = stroke

        val halfStroke = stroke / 2f
        rectF.set(halfStroke, halfStroke, w - halfStroke, h - halfStroke)

        if (isCharging) {
            progressPaint.color = 0xFF44FF44.toInt()
        } else if (level <= 15) {
            progressPaint.color = 0xFFFF4444.toInt()
        } else {
            progressPaint.color = ThemeManager.getTextColorPrimary()
        }

        canvas.drawArc(rectF, 0f, 360f, false, trackPaint)
        canvas.drawArc(rectF, -90f, 360f * level / 100f, false, progressPaint)

        val textY = (h / 2f) - ((textPaint.descent() + textPaint.ascent()) / 2f)
        canvas.drawText(level.toString(), w / 2f, textY, textPaint)
    }
}
