package com.themoon.y1.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import com.themoon.y1.ThemeManager

class WidgetBatteryBarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }
    private var level = 100
    private var isCharging = false
    private var baseColor = 0xFFFFFFFF.toInt()
    private var customTextSize = -1f

    fun setBatteryLevel(level: Int, isCharging: Boolean) {
        this.level = level
        this.isCharging = isCharging
        invalidate()
    }

    fun setColor(color: Int) {
        this.baseColor = color
        invalidate()
    }

    fun setCustomTextSize(size: Float) {
        this.customTextSize = size
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()

        val highlightColor = try {
            ThemeManager.getListButtonFocusedBg() or 0xFF000000.toInt()
        } catch (_: Exception) {
            baseColor
        }

        if (isCharging) {
            progressPaint.color = 0xFF44FF44.toInt()
        } else if (level <= 15) {
            progressPaint.color = 0xFFFF4444.toInt()
        } else {
            progressPaint.color = highlightColor
        }

        bgPaint.color = baseColor and 0x22FFFFFF

        val radius = h / 2f

        val bgRect = RectF(0f, 0f, w, h)
        canvas.drawRoundRect(bgRect, radius, radius, bgPaint)

        val progressWidth = w * (level / 100f)
        val progressRect = RectF(0f, 0f, progressWidth, h)
        canvas.drawRoundRect(progressRect, radius, radius, progressPaint)

        textPaint.color = 0xFFFFFFFF.toInt()
        if (customTextSize > 0) {
            textPaint.textSize = customTextSize
        } else {
            textPaint.textSize = h * 0.6f
        }

        val textY = bgRect.centerY() - ((textPaint.descent() + textPaint.ascent()) / 2f)
        val text = if (isCharging) "+ $level%" else "$level%"
        canvas.drawText(text, bgRect.centerX(), textY, textPaint)
    }
}
