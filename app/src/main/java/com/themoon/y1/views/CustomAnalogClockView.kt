package com.themoon.y1.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import com.themoon.y1.ThemeManager
import java.util.Calendar

class CustomAnalogClockView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG).apply {
        strokeCap = Paint.Cap.ROUND
    }
    private var isAttached = false
    private var clockBgColor = 0

    private val ticker = object : Runnable {
        override fun run() {
            invalidate()
            if (isAttached) postDelayed(this, 1000)
        }
    }

    fun setClockBackgroundColor(color: Int) {
        this.clockBgColor = color
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isAttached = true
        ticker.run()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        isAttached = false
        removeCallbacks(ticker)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width
        val h = height
        val cx = w / 2f
        val cy = h / 2f
        val padding = (10f * resources.displayMetrics.density)
        val radius = minOf(cx, cy) - padding

        if (clockBgColor != 0) {
            paint.style = Paint.Style.FILL
            paint.color = clockBgColor
            canvas.drawCircle(cx, cy, radius, paint)
        }

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = radius * 0.06f
        paint.color = ThemeManager.getTextColorPrimary()
        canvas.drawCircle(cx, cy, radius, paint)

        val cal = Calendar.getInstance()
        val sec = cal.get(Calendar.SECOND).toFloat()
        val min = cal.get(Calendar.MINUTE) + sec / 60f
        val hr = (cal.get(Calendar.HOUR) % 12) + min / 60f

        // Hour hand
        paint.strokeWidth = radius * 0.08f
        val hrAngle = Math.toRadians((hr * 30.0)).toFloat()
        canvas.drawLine(
            cx, cy,
            cx + Math.sin(hrAngle.toDouble()).toFloat() * radius * 0.5f,
            cy - Math.cos(hrAngle.toDouble()).toFloat() * radius * 0.5f,
            paint
        )

        // Minute hand
        paint.strokeWidth = radius * 0.05f
        val minAngle = Math.toRadians((min * 6.0)).toFloat()
        canvas.drawLine(
            cx, cy,
            cx + Math.sin(minAngle.toDouble()).toFloat() * radius * 0.7f,
            cy - Math.cos(minAngle.toDouble()).toFloat() * radius * 0.7f,
            paint
        )

        // Second hand
        paint.strokeWidth = radius * 0.02f
        paint.color = Color.RED
        val secAngle = Math.toRadians((sec * 6.0)).toFloat()
        canvas.drawLine(
            cx, cy,
            cx + Math.sin(secAngle.toDouble()).toFloat() * radius * 0.8f,
            cy - Math.cos(secAngle.toDouble()).toFloat() * radius * 0.8f,
            paint
        )
    }
}
