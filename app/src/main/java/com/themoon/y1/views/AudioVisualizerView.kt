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
    private var compensation = 1f
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        strokeCap = Paint.Cap.ROUND
    }

    /** [compensation]: factor for the magnitudes at the current volume ([SpectrumLevel.gainCompensation]). */
    fun updateVisualizer(fft: ByteArray?, color: Int, compensation: Float = 1f) {
        this.fftData = fft
        this.compensation = compensation
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
                    currentHeights[i] = SpectrumLevel.barHeight(currentHeights[i], magnitude, compensation, h)
                }
            }
        }

        // A bar is at least one dot (a round cap on the bottom edge), so the spectrum also shows in silence
        // (paused, or volume 0). 0.5 px more: a line of length 0 may draw nothing.
        val cap = paint.strokeWidth / 2f
        for (i in 0 until barCount) {
            val x = i * barWidth + (barWidth / 2f)
            canvas.drawLine(x, h - cap, x, h - maxOf(currentHeights[i], cap) - 0.5f, paint)
        }

        if (visibility == View.VISIBLE) {
            postInvalidateDelayed(16)
        }
    }
}
