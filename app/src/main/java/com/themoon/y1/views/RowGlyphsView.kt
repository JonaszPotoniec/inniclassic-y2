package com.themoon.y1.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The status glyphs on the right of the Wi-Fi and Bluetooth rows (lock, signal, device type), like the Wi-Fi list
 * of macOS: solid glyphs as tall as the capital letters of the row text, on the same cap band and baseline as the
 * text, in the text colour. The glyphs are in fixed slots from the right edge of the row, so they line up in all
 * rows of a list; a slot without a glyph (open network) stays empty.
 */
class RowGlyphsView(context: Context, private val slots: List<RowGlyphs.Slot>) : View(context) {
    /** The row text: the glyphs take its cap height and its baseline. */
    var textView: TextView? = null

    var color: Int = 0
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val bounds = Rect()
    private val rect = RectF()
    private val path = Path()
    private val ratios = FloatArray(slots.size) { slots[it].widthRatio }

    init {
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }

    /** Cap height of the row text in px (as [CheckMarkView]: the bounds of "H"). */
    private fun capHeight(): Float {
        val tv = textView ?: return 0f
        tv.paint.getTextBounds("H", 0, 1, bounds)
        return bounds.height().toFloat()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = Math.ceil(RowGlyphs.viewWidth(capHeight(), ratios).toDouble()).toInt()
        setMeasuredDimension(width, getDefaultSize(suggestedMinimumHeight, heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        val tv = textView ?: return
        val cap = capHeight()
        if (cap <= 0f) return
        // The bottom of each glyph on the baseline of the text (the text view and this view are in the same row).
        val baseline = (tv.top + tv.baseline - top).toFloat()
        val columnLeft = width - RowGlyphs.columnWidth(cap, ratios)
        for ((i, slot) in slots.withIndex()) {
            val glyph = slot.glyph ?: continue
            val x = Math.round(columnLeft + RowGlyphs.glyphLeft(cap, ratios, i, glyph.widthRatio)).toFloat()
            when (glyph.kind) {
                RowGlyphs.Kind.LOCK -> drawLock(canvas, x, baseline, cap)
                RowGlyphs.Kind.WIFI -> drawWifi(canvas, x, baseline, cap, glyph.level)
                RowGlyphs.Kind.HEADPHONES -> drawHeadphones(canvas, x, baseline, cap)
                RowGlyphs.Kind.BLUETOOTH -> drawBluetooth(canvas, x, baseline, cap)
            }
        }
    }

    // Overlapping shapes are drawn one after the other (no Path.op: it can hang Android 4.4).

    private fun drawLock(canvas: Canvas, x: Float, baseline: Float, h: Float) {
        val g = RowGlyphs.lock(h)
        val top = baseline - h
        stroke.color = color
        stroke.strokeWidth = g.shackleStroke
        stroke.strokeCap = Paint.Cap.BUTT
        val cx = x + g.width / 2
        val arcCy = top + g.shackleArcCentreY
        path.reset()
        path.moveTo(cx - g.shackleRadius, top + g.bodyTop + g.shackleStroke)
        path.lineTo(cx - g.shackleRadius, arcCy)
        rect.set(cx - g.shackleRadius, arcCy - g.shackleRadius, cx + g.shackleRadius, arcCy + g.shackleRadius)
        path.arcTo(rect, 180f, 180f, false)
        path.lineTo(cx + g.shackleRadius, top + g.bodyTop + g.shackleStroke)
        canvas.drawPath(path, stroke)
        fill.color = color
        rect.set(x, top + g.bodyTop, x + g.width, baseline)
        canvas.drawRoundRect(rect, g.bodyCorner, g.bodyCorner, fill)
    }

    private fun drawWifi(canvas: Canvas, x: Float, baseline: Float, h: Float, level: Int) {
        val g = RowGlyphs.wifi(h)
        val cx = x + g.width / 2
        val cy = baseline - g.dotRadius
        val faint = RowGlyphs.faintColor(color)
        fill.color = color
        canvas.drawCircle(cx, cy, g.dotRadius, fill)
        stroke.strokeWidth = g.arcStroke
        stroke.strokeCap = Paint.Cap.ROUND
        for (i in 0 until RowGlyphs.WIFI_ARCS) {
            val r = g.arcRadius[i]
            stroke.color = if (i < level) color else faint
            rect.set(cx - r, cy - r, cx + r, cy + r)
            canvas.drawArc(rect, 270f - RowGlyphs.WIFI_HALF_ANGLE, 2 * RowGlyphs.WIFI_HALF_ANGLE, false, stroke)
        }
    }

    private fun drawHeadphones(canvas: Canvas, x: Float, baseline: Float, h: Float) {
        val g = RowGlyphs.headphones(h)
        val top = baseline - h
        val half = g.bandStroke / 2
        val cupTop = baseline - g.cupHeight
        stroke.color = color
        stroke.strokeWidth = g.bandStroke
        stroke.strokeCap = Paint.Cap.BUTT
        val r = g.bandRadius
        val cy = top + g.bandCentreY
        path.reset()
        path.moveTo(x + half, cupTop + g.cupCorner)
        path.lineTo(x + half, cy)
        rect.set(x + half, cy - r, x + g.width - half, cy + r)
        path.arcTo(rect, 180f, 180f, false)
        path.lineTo(x + g.width - half, cupTop + g.cupCorner)
        canvas.drawPath(path, stroke)
        fill.color = color
        rect.set(x, cupTop, x + g.cupWidth, baseline)
        canvas.drawRoundRect(rect, g.cupCorner, g.cupCorner, fill)
        rect.set(x + g.width - g.cupWidth, cupTop, x + g.width, baseline)
        canvas.drawRoundRect(rect, g.cupCorner, g.cupCorner, fill)
    }

    private fun drawBluetooth(canvas: Canvas, x: Float, baseline: Float, h: Float) {
        val p = RowGlyphs.bluetoothPoints(h)
        val top = baseline - h
        stroke.color = color
        stroke.strokeWidth = RowGlyphs.bluetoothStroke(h)
        stroke.strokeCap = Paint.Cap.ROUND
        stroke.strokeJoin = Paint.Join.ROUND
        path.reset()
        path.moveTo(x + p[0], top + p[1])
        var i = 2
        while (i < p.size) {
            path.lineTo(x + p[i], top + p[i + 1])
            i += 2
        }
        canvas.drawPath(path, stroke)
    }
}

/**
 * Sizes and shapes of [RowGlyphsView], in px for a cap height `h`. A glyph box is `width × h`: its top is the top of
 * the capital letters and its bottom is the baseline. Pure functions, for unit tests.
 */
object RowGlyphs {
    enum class Kind { LOCK, WIFI, HEADPHONES, BLUETOOTH }

    /** A glyph: [level] is the number of full arcs of [Kind.WIFI] (0..[WIFI_ARCS]). */
    class Glyph(val kind: Kind, val level: Int = 0) {
        val widthRatio: Float
            get() = when (kind) {
                Kind.LOCK -> LOCK_WIDTH_RATIO
                Kind.WIFI -> WIFI_WIDTH_RATIO
                Kind.HEADPHONES -> HEADPHONES_WIDTH_RATIO
                Kind.BLUETOOTH -> BLUETOOTH_WIDTH_RATIO
            }
    }

    /** A fixed slot of the right column; [glyph] null = an empty slot (it keeps its width). */
    class Slot(val widthRatio: Float, val glyph: Glyph?)

    /** Space between two slots, and between the text and the column, as a part of the cap height (macOS: ~1). */
    const val GAP_RATIO = 1f

    /** Slots of a Wi-Fi row: lock (only secured networks), then the signal. */
    fun wifiSlots(secured: Boolean, level: Int): List<Slot> = listOf(
        Slot(LOCK_WIDTH_RATIO, if (secured) Glyph(Kind.LOCK) else null),
        Slot(WIFI_WIDTH_RATIO, Glyph(Kind.WIFI, level)),
    )

    /** Slot of a Bluetooth row: the device type, centred in a slot as wide as the widest device glyph. */
    fun deviceSlots(kind: Kind): List<Slot> =
        listOf(Slot(Math.max(HEADPHONES_WIDTH_RATIO, BLUETOOTH_WIDTH_RATIO), Glyph(kind)))

    /** Width of the slots with the gaps between them. The right edge of the last slot is the right edge of the view. */
    fun columnWidth(h: Float, ratios: FloatArray): Float = h * (ratios.sum() + GAP_RATIO * (ratios.size - 1))

    /** Width of [RowGlyphsView]: the gap after the text, then the column. */
    fun viewWidth(h: Float, ratios: FloatArray): Float = h * GAP_RATIO + columnWidth(h, ratios)

    /** Left edge of slot [i], from the left edge of the column. */
    fun slotLeft(h: Float, ratios: FloatArray, i: Int): Float {
        var x = 0f
        for (j in 0 until i) x += h * (ratios[j] + GAP_RATIO)
        return x
    }

    /** Left edge of a glyph of [glyphRatio] centred in slot [i], from the left edge of the column. */
    fun glyphLeft(h: Float, ratios: FloatArray, i: Int, glyphRatio: Float): Float =
        slotLeft(h, ratios, i) + h * (ratios[i] - glyphRatio) / 2

    // Lock (macOS "lock.fill"): a stroked shackle on a filled body with round corners.
    const val LOCK_WIDTH_RATIO = 0.75f
    private const val LOCK_BODY_HEIGHT = 0.58f
    private const val LOCK_CORNER = 0.12f
    private const val LOCK_SHACKLE_STROKE = 0.14f
    private const val LOCK_SHACKLE_RADIUS = 0.2f   // centre line of the shackle

    class LockShape(
        val width: Float, val bodyTop: Float, val bodyCorner: Float,
        val shackleStroke: Float, val shackleRadius: Float, val shackleArcCentreY: Float,
    ) {
        /** Top of the outer edge of the shackle (0 = the top of the capitals). */
        val shackleTop: Float get() = shackleArcCentreY - shackleRadius - shackleStroke / 2
    }

    fun lock(h: Float): LockShape {
        val stroke = LOCK_SHACKLE_STROKE * h
        val radius = LOCK_SHACKLE_RADIUS * h
        return LockShape(LOCK_WIDTH_RATIO * h, h - LOCK_BODY_HEIGHT * h, LOCK_CORNER * h, stroke, radius, stroke / 2 + radius)
    }

    // Wi-Fi (macOS "wifi"): a dot and [WIFI_ARCS] thick arcs with round ends, all on the centre of the dot.
    const val WIFI_ARCS = 3
    const val WIFI_HALF_ANGLE = 45f
    private const val WIFI_DOT_RADIUS = 0.13f
    private const val WIFI_ARC_STROKE = 0.15f
    // The dot, three arcs and three gaps fill the cap height: 2 * dot + 3 * (gap + stroke) = 1.
    private const val WIFI_GAP = (1f - 2 * WIFI_DOT_RADIUS) / WIFI_ARCS - WIFI_ARC_STROKE
    private val WIFI_HALF_ANGLE_SIN = Math.sin(Math.toRadians(WIFI_HALF_ANGLE.toDouble())).toFloat()
    /** The outer arc: its ends are at ±[WIFI_HALF_ANGLE] from the vertical, with a round cap. */
    val WIFI_WIDTH_RATIO: Float =
        2 * ((1f - WIFI_DOT_RADIUS - WIFI_ARC_STROKE / 2) * WIFI_HALF_ANGLE_SIN + WIFI_ARC_STROKE / 2)

    class WifiShape(val width: Float, val dotRadius: Float, val arcStroke: Float, val arcRadius: FloatArray)

    fun wifi(h: Float): WifiShape {
        val dot = WIFI_DOT_RADIUS * h
        val stroke = WIFI_ARC_STROKE * h
        val gap = WIFI_GAP * h
        val radii = FloatArray(WIFI_ARCS) { i -> dot + gap + stroke / 2 + i * (stroke + gap) }
        return WifiShape(WIFI_WIDTH_RATIO * h, dot, stroke, radii)
    }

    /** Percent of the alpha of the glyph colour for the arcs above the signal level. */
    const val WIFI_FAINT_ALPHA_PERCENT = 40

    fun faintColor(color: Int): Int =
        (((color ushr 24) * WIFI_FAINT_ALPHA_PERCENT / 100) shl 24) or (color and 0x00FFFFFF)

    // Headphones (macOS "headphones"): a stroked band and two filled ear cups.
    const val HEADPHONES_WIDTH_RATIO = 1f
    private const val HEADPHONES_BAND_STROKE = 0.13f
    private const val HEADPHONES_CUP_WIDTH = 0.3f
    private const val HEADPHONES_CUP_HEIGHT = 0.5f
    private const val HEADPHONES_CUP_CORNER = 0.1f

    class HeadphonesShape(
        val width: Float, val bandStroke: Float, val cupWidth: Float, val cupHeight: Float, val cupCorner: Float,
    ) {
        /** The band: a half circle on the centre line of the stroke; its outer edge touches the top of the box. */
        val bandRadius: Float get() = (width - bandStroke) / 2
        val bandCentreY: Float get() = bandStroke / 2 + bandRadius
    }

    fun headphones(h: Float) = HeadphonesShape(
        HEADPHONES_WIDTH_RATIO * h, HEADPHONES_BAND_STROKE * h, HEADPHONES_CUP_WIDTH * h,
        HEADPHONES_CUP_HEIGHT * h, HEADPHONES_CUP_CORNER * h,
    )

    // Bluetooth rune (other devices): the Tabler outline, stroked like the letters, with round ends.
    private const val BLUETOOTH_STROKE = 0.14f
    /** The rune of TablerIcons.BLUETOOTH (x 7..17, y 4..20 in its 24-unit box). */
    private val RUNE = floatArrayOf(7f, 8f, 17f, 16f, 12f, 20f, 12f, 4f, 17f, 8f, 7f, 16f)
    val BLUETOOTH_WIDTH_RATIO: Float = 10f / 16f * (1f - BLUETOOTH_STROKE) + BLUETOOTH_STROKE

    fun bluetoothStroke(h: Float): Float = BLUETOOTH_STROKE * h

    /** Centre line of the rune (x0, y0, x1, y1...) in the glyph box: with the round ends, it touches 0 and h. */
    fun bluetoothPoints(h: Float): FloatArray {
        val stroke = bluetoothStroke(h)
        val scale = (h - stroke) / 16f
        return FloatArray(RUNE.size) { i ->
            stroke / 2 + (RUNE[i] - if (i % 2 == 0) 7f else 4f) * scale
        }
    }
}
