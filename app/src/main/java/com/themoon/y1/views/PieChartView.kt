package com.themoon.y1.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

class PieChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val paintBg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0x33FFFFFF
    }
    private val paintUsed = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private var percentage: Float = 0f

    fun setStorageData(used: Long, total: Long, themeColor: Int) {
        if (total > 0) percentage = used.toFloat() / total
        paintUsed.color = themeColor
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val padding = 10f
        val rect = RectF(padding, padding, w - padding, h - padding)

        canvas.drawArc(rect, 0f, 360f, true, paintBg)
        val sweepAngle = percentage * 360f
        canvas.drawArc(rect, -90f, sweepAngle, true, paintUsed)
    }
}
