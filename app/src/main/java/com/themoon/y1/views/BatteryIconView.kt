package com.themoon.y1.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
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
    private var color = 0xFF1C1C1E.toInt()
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
    private val digitPath = Path()
    private val glyphPath = Path()
    private val glyphBounds = RectF()
    private val glyphMatrix = Matrix()
    private var digitHeightsSize = -1f
    private var digitHeightsTypeface: Typeface? = null
    private val digitHeights = FloatArray(10)

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
        if (this.color != color) {
            this.color = color
            invalidate()
        }
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

        val strokeColor = if (ThemeManager.isClassicLightTheme()) 0xFF3C3C43.toInt() else 0xFF8E8E93.toInt()
        paintStroke.color = strokeColor
        paintTerminal.color = strokeColor
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

                buildDigitPath(text, rectShell.centerY())
                digitPath.offset(Math.round(startX + boltW + gap - glyphBounds.left).toFloat(), 0f)

                paintText.color = color
                paintBolt.color = color
                canvas.drawPath(boltPath, paintBolt)
                canvas.drawPath(digitPath, paintText)

                if (currentFillWidth > 0) {
                    val filledColor = 0xFF1C1C1E.toInt()
                    paintText.color = filledColor
                    paintBolt.color = filledColor
                    canvas.save()
                    canvas.clipRect(padX, 0f, padX + currentFillWidth, h)
                    canvas.drawPath(boltPath, paintBolt)
                    canvas.drawPath(digitPath, paintText)
                    canvas.restore()
                }
            } else {
                val textWidth = paintText.measureText(text)
                if (textWidth > maxContentWidth && textWidth > 0) {
                    paintText.textSize = paintText.textSize * (maxContentWidth / textWidth)
                }

                buildDigitPath(text, rectShell.centerY())
                digitPath.offset(Math.round(rectShell.centerX() - glyphBounds.centerX()).toFloat(), 0f)

                paintText.color = color
                canvas.drawPath(digitPath, paintText)

                if (currentFillWidth > 0) {
                    val filledTextColor = if (level <= 20) Color.WHITE else 0xFF1C1C1E.toInt()
                    paintText.color = filledTextColor
                    canvas.save()
                    canvas.clipRect(padX, 0f, padX + currentFillWidth, h)
                    canvas.drawPath(digitPath, paintText)
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

    /**
     * Builds [digitPath] from the outlines of [text] (starting at x = 0), with each digit fitted to one box of
     * whole pixels centred on [centerY] (see [BatteryDigitFit]). Leaves the bounds of the path in [glyphBounds].
     * drawText() is not used: the hinting of Android 4.4 gave the digits different heights.
     */
    private fun buildDigitPath(text: String, centerY: Float) {
        val height = BatteryDigitFit.boxHeight(digitHeights())
        val baseline = BatteryDigitFit.baseline(centerY, height)
        digitPath.reset()
        var penX = 0f
        for (i in text.indices) {
            paintText.getTextPath(text, i, i + 1, Math.round(penX).toFloat(), 0f, glyphPath)
            glyphPath.computeBounds(glyphBounds, true)
            if (glyphBounds.height() > 0f) {
                glyphMatrix.setScale(1f, BatteryDigitFit.scale(glyphBounds.top, glyphBounds.bottom, height))
                glyphMatrix.postTranslate(0f, BatteryDigitFit.offset(glyphBounds.top, glyphBounds.bottom, baseline, height))
                glyphPath.transform(glyphMatrix)
                digitPath.addPath(glyphPath)
            }
            penX += paintText.measureText(text, i, i + 1)
        }
        digitPath.computeBounds(glyphBounds, true)
    }

    /** Heights above the baseline of the digits 0-9 at the current text size and typeface. */
    private fun digitHeights(): FloatArray {
        if (digitHeightsSize != paintText.textSize || digitHeightsTypeface !== paintText.typeface) {
            for (d in 0..9) {
                paintText.getTextPath(d.toString(), 0, 1, 0f, 0f, glyphPath)
                glyphPath.computeBounds(glyphBounds, true)
                digitHeights[d] = -glyphBounds.top
            }
            digitHeightsSize = paintText.textSize
            digitHeightsTypeface = paintText.typeface
        }
        return digitHeights
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
