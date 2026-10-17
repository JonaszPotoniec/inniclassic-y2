package com.themoon.y1.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

class AudioVisualizerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var fftData: ByteArray? = null
    private val barCount = 40
    private val currentHeights = FloatArray(barCount)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        strokeCap = Paint.Cap.ROUND
    }

    fun updateVisualizer(fft: ByteArray?, color: Int) {
        this.fftData = fft
        paint.color = color
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()
        val barWidth = w / barCount.toFloat()
        paint.strokeWidth = barWidth * 0.4f

        val data = fftData
        if (data != null) {
            for (i in 0 until barCount) {
                if (i * 2 + 3 < data.size) {
                    val rfk = data[i * 2 + 2].toDouble()
                    val ifk = data[i * 2 + 3].toDouble()
                    val magnitude = Math.hypot(rfk, ifk).toFloat()

                    // Cap bar height to 85% of view height
                    val targetHeight = minOf(h * 0.85f, (magnitude * h) / 100f)
                    currentHeights[i] += (targetHeight - currentHeights[i]) * 0.15f
                }
            }
        }

        for (i in 0 until barCount) {
            val x = i * barWidth + (barWidth / 2f)
            canvas.drawLine(x, h, x, h - currentHeights[i], paint)
        }

        if (visibility == View.VISIBLE) {
            postInvalidateDelayed(16)
        }
    }
}
