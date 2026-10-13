package com.themoon.y1.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import com.themoon.y1.ThemeManager

class BatteryIconView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var level = 100
    private var isCharging = false
    private var color = Color.WHITE
    private var showPercentage = false

    private val paintFill: Paint
    private val paintStroke: Paint
    private val paintTerminal: Paint
    private val paintText: Paint
    private val paintBolt: Paint
    private val rectShell = RectF()
    private val rectFill = RectF()
    private val rectTerminal = RectF()
    private val textBounds = Rect()
    private val boltPath = Path()

    init {
        paintFill = Paint(Paint.ANTI_ALIAS_FLAG)

        paintStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 3f
        }

        paintTerminal = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }

        paintText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.LEFT
            try {
                val tf = ThemeManager.getCustomFontBold()
                typeface = tf ?: Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            } catch (_: Exception) {
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            }
        }

        paintBolt = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = 0xFF2E7D32.toInt()
        }
    }

    fun setShowPercentage(showPercentage: Boolean) {
        if (this.showPercentage != showPercentage) {
            this.showPercentage = showPercentage
            invalidate()
        }
    }

    fun isShowPercentage(): Boolean = showPercentage

    fun setBatteryLevel(level: Int, isCharging: Boolean) {
        this.level = maxOf(0, minOf(100, level))
        this.isCharging = isCharging
        invalidate()
    }

    fun setColor(color: Int) {
        this.color = color
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()
        if (w == 0f || h == 0f) return

        val terminalWidth = w * 0.08f
        val shellWidth = w - terminalWidth
        val pillRadius = h * 0.12f

        var currentColor = color
        if (isCharging) {
            currentColor = Color.parseColor("#4CAF50")
        } else if (level <= 20) {
            currentColor = Color.parseColor("#F44336")
        }

        paintStroke.color = 0xFF3C3C43.toInt()
        paintTerminal.color = 0xFF3C3C43.toInt()
        paintFill.color = currentColor

        if (level <= 20 && !isCharging) {
            paintFill.shader = null
        } else {
            val topColor = if (isCharging) "#8FE070" else "#D0E9B7"
            val bottomColor = if (isCharging) "#4CAF50" else "#7CB342"
            paintFill.shader = LinearGradient(
                0f, 0f, 0f, h,
                Color.parseColor(topColor), Color.parseColor(bottomColor), Shader.TileMode.CLAMP
            )
        }

        rectShell.set(2f, 2f, shellWidth - 2f, h - 2f)
        canvas.drawRoundRect(rectShell, pillRadius, pillRadius, paintStroke)

        val terminalHeight = h * 0.4f
        rectTerminal.set(shellWidth, (h - terminalHeight) / 2f, w - 2f, (h + terminalHeight) / 2f)
        canvas.drawRoundRect(rectTerminal, 2f, 2f, paintTerminal)

        val padX = 4f
        val padY = 3.5f
        val maxFillWidth = shellWidth - (padX * 2f)
        val currentFillWidth = maxFillWidth * (level / 100f)

        if (currentFillWidth > 0) {
            rectFill.set(padX, padY, padX + currentFillWidth, h - padY)
            canvas.drawRoundRect(
                rectFill,
                maxOf(0f, pillRadius - 1f),
                maxOf(0f, pillRadius - 1f),
                paintFill
            )
        }

        if (showPercentage) {
            try {
                val tf = ThemeManager.getCustomFontBold()
                if (tf != null) paintText.typeface = tf
            } catch (_: Exception) {
            }

            val text = level.toString()
            val baseTextSize = if (isCharging) (h * 0.50f) else (h * 0.58f)
            paintText.textSize = baseTextSize

            val maxContentWidth = shellWidth - (padX * 2f)

            if (isCharging) {
                val boltW = h * 0.28f
                val boltH = h * 0.52f
                val gap = 2f

                paintText.getTextBounds(text, 0, text.length, textBounds)
                val availableForText = maxContentWidth - boltW - gap
                if (textBounds.width() > availableForText && textBounds.width() > 0) {
                    paintText.textSize = paintText.textSize * (availableForText / textBounds.width())
                    paintText.getTextBounds(text, 0, text.length, textBounds)
                }

                val totalWidth = boltW + gap + textBounds.width()
                val startX = rectShell.centerX() - (totalWidth / 2f)
                val boltLeft = startX
                val boltTop = rectShell.centerY() - (boltH / 2f)

                buildBoltPath(boltLeft, boltTop, boltW, boltH)

                val textX = startX + boltW + gap - textBounds.left
                val textY = rectShell.centerY() - textBounds.exactCenterY()

                var emptyColor = color
                if (color == Color.WHITE || Color.red(color) > 200) {
                    emptyColor = 0xFF1C1C1E.toInt()
                }
                paintText.color = emptyColor
                paintBolt.color = emptyColor
                canvas.drawPath(boltPath, paintBolt)
                canvas.drawText(text, textX, textY, paintText)

                if (currentFillWidth > 0) {
                    val filledColor = 0xFF1C1C1E.toInt()
                    paintText.color = filledColor
                    paintBolt.color = filledColor
                    canvas.save()
                    canvas.clipRect(padX, 0f, padX + currentFillWidth, h)
                    canvas.drawPath(boltPath, paintBolt)
                    canvas.drawText(text, textX, textY, paintText)
                    canvas.restore()
                }
            } else {
                val textWidth = paintText.measureText(text)
                if (textWidth > maxContentWidth && textWidth > 0) {
                    paintText.textSize = paintText.textSize * (maxContentWidth / textWidth)
                }

                paintText.getTextBounds(text, 0, text.length, textBounds)
                val textX = rectShell.centerX() - textBounds.exactCenterX()
                val textY = rectShell.centerY() - textBounds.exactCenterY()

                var emptyTextColor = color
                if (color == Color.WHITE || Color.red(color) > 200) {
                    emptyTextColor = 0xFF1C1C1E.toInt()
                }
                paintText.color = emptyTextColor
                canvas.drawText(text, textX, textY, paintText)

                if (currentFillWidth > 0) {
                    val filledTextColor = if (level <= 20) Color.WHITE else 0xFF1C1C1E.toInt()
                    paintText.color = filledTextColor
                    canvas.save()
                    canvas.clipRect(padX, 0f, padX + currentFillWidth, h)
                    canvas.drawText(text, textX, textY, paintText)
                    canvas.restore()
                }
            }
        } else if (isCharging) {
            val boltW = shellWidth * 0.34f
            val boltH = h * 0.72f
            val boltLeft = (shellWidth - boltW) / 2f
            val boltTop = (h - boltH) / 2f

            buildBoltPath(boltLeft, boltTop, boltW, boltH)

            paintBolt.color = 0xFF2E7D32.toInt()
            canvas.drawPath(boltPath, paintBolt)
        }
    }

    private fun buildBoltPath(boltLeft: Float, boltTop: Float, boltW: Float, boltH: Float) {
        boltPath.reset()
        boltPath.moveTo(boltLeft + boltW * 0.62f, boltTop)
        boltPath.lineTo(boltLeft + boltW * 0.10f, boltTop + boltH * 0.58f)
        boltPath.lineTo(boltLeft + boltW * 0.42f, boltTop + boltH * 0.58f)
        boltPath.lineTo(boltLeft + boltW * 0.38f, boltTop + boltH)
        boltPath.lineTo(boltLeft + boltW * 0.90f, boltTop + boltH * 0.42f)
        boltPath.lineTo(boltLeft + boltW * 0.58f, boltTop + boltH * 0.42f)
        boltPath.close()
    }
}
