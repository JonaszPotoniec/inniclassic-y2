package com.themoon.y1.battery

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import com.themoon.y1.ClockSettings
import com.themoon.y1.ThemeManager
import com.themoon.y1.views.BatteryLayout
import com.themoon.y1.views.ToggleStyle
import java.text.DateFormatSymbols
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.roundToInt

/**
 * Settings → Battery: the battery history as bars, as Settings → Battery of macOS and iOS.
 *
 * - At the top, the range: Last 24 Hours or Last 10 Days. The wheel (or the centre button) changes it.
 * - A line with the last charge ("Last charged to 100%, Today 19:50", or "Charging") and the level now.
 * - Battery Level (24 hours): one bar each 30 min, green (red at 20 % and less). While the charger was connected: a
 *   light green background behind the bars and a green band under them. Battery Used (10 days): the
 *   percent used each day.
 * - Screen On (selection blue) and Screen Off (music or radio playing with the screen off, a lighter blue), stacked:
 *   minutes of each hour (24 hours) or hours of each day (10 days); the legend has the totals.
 * - The time axis as macOS: dotted lines, each label just right of its line (hours each 3 hours, or weekday
 *   initials), a date under the first label and at each new day or week, where the line is solid.
 * - No bar where the device was off or there is no record yet. Bars have round top corners.
 *
 * The view draws all of it on its Canvas (one view; no chart library) in the colours and fonts of the theme.
 */
class BatteryUsageView(
    context: Context,
    private val localize: (String) -> String,
    private val is24Hour: () -> Boolean
) : View(context) {

    /** False: Last 24 Hours; true: Last 10 Days. */
    var tenDays = false
        set(value) {
            field = value
            invalidate()
        }

    private val d = resources.displayMetrics.density
    private var samples: List<BatterySample> = emptyList()
    private var now = 0L
    private var day: DayChart? = null
    private var tens: TenDayChart? = null

    private val text = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fill = Paint()
    private val round = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val capBounds = Rect()
    private val onChange: () -> Unit = { refresh() }

    init {
        isFocusable = true
        isClickable = true
    }

    /** One wheel step: down = Last 10 Days, up = Last 24 Hours. False at that end (no change). */
    fun step(delta: Int): Boolean {
        val want = delta > 0
        if (want == tenDays) return false
        tenDays = want
        return true
    }

    fun toggle() {
        tenDays = !tenDays
    }

    /** Reads the samples again (a new record, or the screen opens). */
    fun refresh() {
        samples = BatteryHistory.samples()
        now = System.currentTimeMillis()
        val zone = TimeZone.getDefault()
        day = BatteryHistoryLogic.day(samples, now, zone)
        tens = BatteryHistoryLogic.tenDays(samples, now, zone)
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        BatteryHistory.addListener(onChange)
        refresh()
    }

    override fun onDetachedFromWindow() {
        BatteryHistory.removeListener(onChange)
        super.onDetachedFromWindow()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = when (MeasureSpec.getMode(heightMeasureSpec)) {
            MeasureSpec.EXACTLY -> MeasureSpec.getSize(heightMeasureSpec)
            MeasureSpec.AT_MOST -> minOf(MeasureSpec.getSize(heightMeasureSpec), px(MIN_HEIGHT))
            else -> px(MIN_HEIGHT)
        }
        setMeasuredDimension(w, h)
    }

    // --- Colours of the theme ---

    private val darkTheme: Boolean get() = BatteryLayout.isLight(ThemeManager.getTextColorPrimary())
    private val green: Int get() = if (darkTheme) 0xFF30D158.toInt() else 0xFF34C759.toInt()
    private val red: Int get() = if (darkTheme) 0xFFFF453A.toInt() else 0xFFFF3B30.toInt()
    private val blue: Int get() = ThemeManager.getListButtonFocusedBg()
    /** Screen Off: the blue (opaque) mixed half with white (lighter in both themes, as the iOS Screen Off bars). */
    private val lightBlue: Int get() = ToggleStyle.blend(blue or 0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 0.5f)
    private val primary: Int get() = ThemeManager.getTextColorPrimary()
    private val secondary: Int get() = ThemeManager.getTextColorSecondary()
    private fun withAlpha(color: Int, alpha: Float): Int = (color and 0x00FFFFFF) or ((alpha * 255).roundToInt() shl 24)

    private fun px(dp: Int): Int = (dp * d).roundToInt()
    private fun pxf(dp: Float): Float = dp * d

    // --- Layout of one draw ---

    /** The chart columns: [left]..[right] (whole bars), the scale values right of it up to [outerRight]. */
    private var left = 0
    private var right = 0
    private var outerRight = 0
    private var slot = 1

    // --- Drawing ---

    override fun onDraw(canvas: Canvas) {
        val w = width
        // The bars fill whole pixels: the chart width is a multiple of the 48 bars; the chart and its scale are centred
        slot = ((w - 2 * px(PAD) - px(AXIS_WIDTH)) / BatteryHistoryLogic.DAY_SLOTS).coerceAtLeast(1)
        val chartW = slot * BatteryHistoryLogic.DAY_SLOTS
        left = (w - chartW - px(AXIS_WIDTH)) / 2
        right = left + chartW
        outerRight = right + px(AXIS_WIDTH)

        var y = px(PAD_TOP)
        drawRange(canvas, y)
        y += px(SEGMENT_HEIGHT) + px(SEGMENT_GAP)
        drawHeader(canvas, y + px(HEADER_BASELINE))
        y += px(HEADER_HEIGHT)

        // The two charts share the height that the fixed parts leave
        val free = (height - px(FIXED_HEIGHT)).coerceAtLeast(px(80))
        val chart1 = (free * 0.55f).roundToInt()
        val chart2 = free - chart1

        val title1Centre = y + px(TITLE_HEIGHT) / 2f
        y += px(TITLE_HEIGHT)
        val top1 = y
        val bottom1 = top1 + chart1
        val legendCentre = bottom1 + px(LABEL_HEIGHT) + px(TITLE_HEIGHT) / 2f
        val top2 = bottom1 + px(LABEL_HEIGHT) + px(TITLE_HEIGHT)
        val bottom2 = top2 + chart2
        val axisBottom1 = bottom1 + px(LABEL_HEIGHT)
        val axisBottom2 = bottom2 + px(LABEL_HEIGHT) + px(DATE_HEIGHT)

        if (tenDays) {
            val t = tens ?: return
            drawTitle(canvas, localize("Battery Used"), title1Centre)
            val scale = BatteryHistoryLogic.usedScale(t.used)
            drawGrid(canvas, top1, bottom1, "$scale%", "${scale / 2}%", "0%")
            drawDayLines(canvas, t, top1, axisBottom1, top2, axisBottom2)
            drawDayBars(canvas, top1, bottom1, t.used, null, scale.toFloat(), green)
            drawDayLabels(canvas, t, bottom1, dates = false)
            drawLegend(canvas, legendCentre, t.screenTotalMinutes, t.offTotalMinutes)
            val stacked = IntArray(t.screenMinutes.size) { maxOf(0, t.screenMinutes[it]) + maxOf(0, t.offMinutes[it]) }
            val hours = BatteryHistoryLogic.screenScaleHours(stacked)
            drawGrid(canvas, top2, bottom2, "${hours}h", BatteryHistoryLogic.durationText(hours * 30), "0h")
            drawDayBars(canvas, top2, bottom2, t.screenMinutes, t.offMinutes, hours * 60f, blue)
            drawDayLabels(canvas, t, bottom2, dates = true)
            drawEdge(canvas, top1, axisBottom1, top2, axisBottom2)
        } else {
            val c = day ?: return
            drawTitle(canvas, localize("Battery Level"), title1Centre)
            drawChargingTint(canvas, c, top1, bottom1)
            drawGrid(canvas, top1, bottom1, "100%", "50%", "0%")
            drawHourLines(canvas, c, top1, axisBottom1, top2, axisBottom2)
            drawLevelBars(canvas, c, top1, bottom1)
            drawChargingBand(canvas, c, bottom1)
            drawHourLabels(canvas, c, bottom1, dates = false)
            drawLegend(canvas, legendCentre, c.screenTotalMinutes, c.offTotalMinutes)
            drawGrid(canvas, top2, bottom2, "60m", "30m", "0m")
            drawScreenBars(canvas, c, top2, bottom2)
            drawHourLabels(canvas, c, bottom2, dates = true)
            drawEdge(canvas, top1, axisBottom1, top2, axisBottom2)
            if (c.levels.all { it < 0 }) drawEmpty(canvas, top1, bottom1)
        }
    }

    /** The range control: two halves, the chosen one in the selection colour of the lists. */
    private fun drawRange(canvas: Canvas, top: Int) {
        val width = minOf(px(SEGMENT_WIDTH), this.width - 2 * px(PAD))
        val x0 = (this.width - width) / 2f
        val h = px(SEGMENT_HEIGHT)
        val half = width / 2f
        val chosenLeft = if (tenDays) x0 + half else x0
        fill.style = Paint.Style.FILL
        fill.shader = if (ThemeManager.isClassicTheme())
            LinearGradient(0f, top.toFloat(), 0f, (top + h).toFloat(), intArrayOf(0xFF62B7F5.toInt(), 0xFF2588DC.toInt(), 0xFF0865C4.toInt()),
                null, Shader.TileMode.CLAMP)
        else null
        fill.color = ThemeManager.getListButtonFocusedBg()
        canvas.drawRect(chosenLeft, top.toFloat(), chosenLeft + half, (top + h).toFloat(), fill)
        fill.shader = null
        // Outline and the line between the halves: 1 px, on whole pixels
        fill.color = withAlpha(secondary, 0.7f)
        val l = x0.roundToInt().toFloat()
        val r = (x0 + width).roundToInt().toFloat()
        canvas.drawRect(l, top.toFloat(), r, top + 1f, fill)
        canvas.drawRect(l, (top + h - 1).toFloat(), r, (top + h).toFloat(), fill)
        canvas.drawRect(l, top.toFloat(), l + 1f, (top + h).toFloat(), fill)
        canvas.drawRect(r - 1f, top.toFloat(), r, (top + h).toFloat(), fill)
        val mid = (x0 + half).roundToInt().toFloat()
        canvas.drawRect(mid, top.toFloat(), mid + 1f, (top + h).toFloat(), fill)

        text.typeface = ThemeManager.getCustomFontBold()
        text.textSize = pxf(SEGMENT_TEXT)
        text.textAlign = Paint.Align.CENTER
        val baseline = capCentredBaseline(top + h / 2f)
        for (i in 0..1) {
            val chosen = (i == 1) == tenDays
            text.color = if (chosen) ThemeManager.getListButtonFocusedTextColor() else primary
            canvas.drawText(localize(if (i == 0) "Last 24 Hours" else "Last 10 Days"), x0 + half * i + half / 2f, baseline, text)
        }
    }

    /** "Last charged to 100%, Today 19:50" (or "Charging") on the left, the level now on the right. */
    private fun drawHeader(canvas: Canvas, baseline: Int) {
        val current = BatteryHistoryLogic.current(samples) ?: return
        text.textSize = pxf(LEVEL_TEXT)
        text.typeface = ThemeManager.getCustomFontBold()
        text.textAlign = Paint.Align.RIGHT
        text.color = primary
        val level = "${current.level}%"
        canvas.drawText(level, outerRight.toFloat(), baseline.toFloat(), text)
        val levelWidth = text.measureText(level)

        val last = BatteryHistoryLogic.lastCharge(samples)
        val line = when {
            current.plugged -> localize("Charging")
            last != null -> localize("Last charged to") + " ${last.level}% · " + whenText(last.t)
            else -> return
        }
        text.textSize = pxf(HEADER_TEXT)
        text.typeface = ThemeManager.getCustomFont()
        text.textAlign = Paint.Align.LEFT
        text.color = if (current.plugged) green else secondary
        val max = outerRight - left - levelWidth - pxf(8f)
        val shown = if (text.measureText(line) <= max) line
        else line.substring(0, text.breakText(line, true, max - text.measureText("…"), null)) + "…"
        canvas.drawText(shown, left.toFloat(), baseline.toFloat(), text)
    }

    /** "Today 19:50", "Yesterday 08:10", or "21 Oct 08:10". */
    private fun whenText(t: Long): String {
        val time = ClockSettings.formatStatusBarTime(Date(t), is24Hour())
        return when (BatteryHistoryLogic.daysAgo(t, now, TimeZone.getDefault())) {
            0 -> localize("Today") + " " + time
            1 -> localize("Yesterday") + " " + time
            else -> SimpleDateFormat("d MMM", Locale.getDefault()).format(Date(t)) + " " + time
        }
    }

    /** A chart title (bold) with its capitals centred on [centre]. */
    private fun drawTitle(canvas: Canvas, title: String, centre: Float) {
        text.textSize = pxf(TITLE_TEXT)
        text.typeface = ThemeManager.getCustomFontBold()
        text.textAlign = Paint.Align.LEFT
        text.color = primary
        canvas.drawText(title, left.toFloat(), capCentredBaseline(centre), text)
    }

    /** The title of the screen chart: a legend with the totals, "■ Screen On 7h 39m  ■ Screen Off 2h 10m". */
    private fun drawLegend(canvas: Canvas, centre: Float, onMinutes: Int, offMinutes: Int) {
        var x = left.toFloat()
        val box = pxf(LEGEND_BOX)
        for ((label, minutes, color) in listOf(Triple("Screen On", onMinutes, blue), Triple("Screen Off", offMinutes, lightBlue))) {
            round.color = color
            rect.set(x, centre - box / 2f, x + box, centre + box / 2f)
            canvas.drawRoundRect(rect, pxf(2f), pxf(2f), round)
            x += box + pxf(5f)
            text.textSize = pxf(TITLE_TEXT)
            text.typeface = ThemeManager.getCustomFontBold()
            text.textAlign = Paint.Align.LEFT
            text.color = primary
            val name = localize(label)
            canvas.drawText(name, x, capCentredBaseline(centre), text)
            x += text.measureText(name) + pxf(5f)
            text.typeface = ThemeManager.getCustomFont()
            text.color = secondary
            val total = BatteryHistoryLogic.durationText(minutes)
            canvas.drawText(total, x, capCentredBaseline(centre), text)
            x += text.measureText(total) + pxf(LEGEND_GAP)
        }
    }

    /** Lines at 0, 25, 50, 75 and 100 % of the chart, and the values of three of them on the right. */
    private fun drawGrid(canvas: Canvas, top: Int, bottom: Int, topText: String, midText: String, bottomText: String) {
        fill.style = Paint.Style.FILL
        for (q in 0..4) {
            val y = top + ((bottom - top) * q / 4f).roundToInt()
            fill.color = withAlpha(secondary, if (q == 4) 0.6f else 0.22f)
            canvas.drawRect(left.toFloat(), (y - 1).toFloat(), right.toFloat(), y.toFloat(), fill)
        }
        text.textSize = pxf(AXIS_TEXT)
        text.typeface = ThemeManager.getCustomFont()
        text.textAlign = Paint.Align.LEFT
        text.color = secondary
        for ((y, label) in listOf(top to topText, (top + bottom) / 2 to midText, bottom to bottomText)) {
            canvas.drawText(label, right + pxf(6f), capCentredBaseline(y - 0.5f), text)
        }
    }

    /** A vertical line from [top] to [bottom] at [x] (its pixel is left of x): dotted, or solid at a new day or week. */
    private fun vLine(canvas: Canvas, x: Int, top: Int, bottom: Int, solid: Boolean) {
        fill.color = withAlpha(secondary, if (solid) 0.45f else 0.4f)
        if (solid) {
            canvas.drawRect((x - 1).toFloat(), top.toFloat(), x.toFloat(), bottom.toFloat(), fill)
            return
        }
        var y = top
        while (y < bottom) {
            canvas.drawRect((x - 1).toFloat(), y.toFloat(), x.toFloat(), (y + 1).toFloat(), fill)
            y += px(3)
        }
    }

    /** The right edge of both charts, solid, down through the label rows (as macOS). */
    private fun drawEdge(canvas: Canvas, top1: Int, bottom1: Int, top2: Int, bottom2: Int) {
        vLine(canvas, right, top1, bottom1, solid = true)
        vLine(canvas, right, top2, bottom2, solid = true)
    }

    /** Lines at the labelled hours (each 3 hours), through both charts and their labels; solid at midnight. */
    private fun drawHourLines(canvas: Canvas, c: DayChart, top1: Int, bottom1: Int, top2: Int, bottom2: Int) {
        val hourW = slot * BatteryHistoryLogic.SLOTS_PER_HOUR
        for (h in 0 until 24) {
            if (c.hours[h] % 3 != 0) continue
            val x = left + h * hourW + if (h == 0) 1 else 0
            vLine(canvas, x, top1, bottom1, c.hours[h] == 0)
            vLine(canvas, x, top2, bottom2, c.hours[h] == 0)
        }
    }

    /** A light green background behind the bars while the charger was connected (as macOS). */
    private fun drawChargingTint(canvas: Canvas, c: DayChart, top: Int, bottom: Int) {
        fill.color = withAlpha(green, if (darkTheme) 0.22f else 0.18f)
        forEachChargingRun(c) { x0, x1 -> canvas.drawRect(x0, top.toFloat(), x1, bottom.toFloat(), fill) }
    }

    private inline fun forEachChargingRun(c: DayChart, draw: (Float, Float) -> Unit) {
        var k = 0
        while (k < c.charging.size) {
            if (!c.charging[k]) { k++; continue }
            val start = k
            while (k < c.charging.size && c.charging[k]) k++
            draw((left + start * slot).toFloat(), (left + k * slot).toFloat())
        }
    }

    /** One bar each 30 min, on whole pixels: the bar, then a 1 px gap (the 3-hour lines fall in a gap). */
    private fun drawLevelBars(canvas: Canvas, c: DayChart, top: Int, bottom: Int) {
        val gap = if (slot >= 3) 1 else 0
        val radius = (slot - gap) / 4f
        for (k in c.levels.indices) {
            val level = c.levels[k]
            if (level <= 0) continue
            val h = maxOf(px(MIN_BAR), ((bottom - top) * level / 100f).roundToInt())
            val x = left + k * slot
            bar(canvas, x.toFloat(), (bottom - h).toFloat(), (x + slot - gap).toFloat(), bottom.toFloat(),
                if (level <= BatteryLayout.LOW_LEVEL && !c.charging[k]) red else green, radius)
        }
    }

    /**
     * The charge periods under the Battery Level bars: a green band between the axis and the hour labels, at the place of
     * the charge on the time axis (the light green background shows it in the chart). It never meets the title.
     */
    private fun drawChargingBand(canvas: Canvas, c: DayChart, axis: Int) {
        val top = axis + pxf(1f)
        val bottom = top + pxf(BAND_THICK)
        round.color = green
        // The band ends at the right edge of the last bar (the gap after it stays empty)
        forEachChargingRun(c) { x0, x1 ->
            rect.set(x0, top, x1 - 1f, bottom)
            canvas.drawRoundRect(rect, pxf(BAND_THICK) / 2f, pxf(BAND_THICK) / 2f, round)
        }
    }

    /** Screen On (blue) with Screen Off (light blue) stacked on it: one bar an hour. */
    private fun drawScreenBars(canvas: Canvas, c: DayChart, top: Int, bottom: Int) {
        val hourW = slot * BatteryHistoryLogic.SLOTS_PER_HOUR
        val inset = maxOf(1, hourW / 16)
        for (h in 0 until 24) {
            val x = left + h * hourW
            stacked(canvas, (x + inset).toFloat(), (x + hourW - inset).toFloat(), top, bottom,
                maxOf(0, c.screenMinutes[h]), maxOf(0, c.offMinutes[h]), 60f)
        }
    }

    /** A stacked bar: [on] from the axis in [color], then [off] in light blue; the top part gets the round corners. */
    private fun stacked(canvas: Canvas, x0: Float, x1: Float, top: Int, bottom: Int, on: Int, off: Int, scale: Float, color: Int = blue) {
        if (on + off <= 0) return
        val chartH = bottom - top
        val hTotal = maxOf(px(MIN_BAR), (chartH * minOf(1f, (on + off) / scale)).roundToInt())
        val hOn = if (on <= 0) 0 else maxOf(px(MIN_BAR), (chartH * minOf(1f, on / scale)).roundToInt()).coerceAtMost(hTotal)
        val radius = (x1 - x0) / 4f
        if (hOn > 0) {
            if (hOn == hTotal) bar(canvas, x0, (bottom - hOn).toFloat(), x1, bottom.toFloat(), color, radius)
            else {
                fill.color = color
                canvas.drawRect(x0, (bottom - hOn).toFloat(), x1, bottom.toFloat(), fill)
            }
        }
        if (hTotal > hOn) bar(canvas, x0, (bottom - hTotal).toFloat(), x1, (bottom - hOn).toFloat(), lightBlue, radius)
    }

    /** A bar with round top corners of [radius] (at most half its width) and a square bottom on the axis. */
    private fun bar(canvas: Canvas, x0: Float, top: Float, x1: Float, bottom: Float, color: Int, radius: Float) {
        val r = minOf(radius, (x1 - x0) / 2f)
        if (r < 0.5f) {
            fill.color = color
            canvas.drawRect(x0, top, x1, bottom, fill)
            return
        }
        // A round rect that goes on below the bar, clipped at its bottom: a bar lower than its radius keeps a square
        // bottom too (else it is a pill that floats on the axis)
        round.color = color
        canvas.save()
        canvas.clipRect(x0, top, x1, bottom)
        rect.set(x0, top, x1, bottom + r)
        canvas.drawRoundRect(rect, r, r, round)
        canvas.restore()
    }

    /** Hour labels each 3 hours, just right of their line; under the screen chart also the date (first label, midnight). */
    private fun drawHourLabels(canvas: Canvas, c: DayChart, axis: Int, dates: Boolean) {
        val hourW = slot * BatteryHistoryLogic.SLOTS_PER_HOUR
        setLabelText()
        val baseline = labelBaseline(axis, dates)
        for (h in 0 until 24) {
            if (c.hours[h] % 3 != 0) continue
            val label = BatteryHistoryLogic.hourLabel(c.hours[h], is24Hour())
            val x = left + h * hourW + pxf(LABEL_INSET)
            // A label must end in the chart (as macOS): right of it are the scale values
            if (x + text.measureText(label) > right) continue
            canvas.drawText(label, x, baseline, text)
        }
        if (!dates) return
        val format = SimpleDateFormat("d MMM", Locale.getDefault())
        drawDates(canvas, axis, BatteryHistoryLogic.dateMarks(c.hours).map { h ->
            left + h * hourW to format.format(Date(c.start + h * BatteryHistoryLogic.HOUR_MS))
        })
    }

    /** The dates on the line under the labels; a date that would touch the one before it or pass the chart is left out. */
    private fun drawDates(canvas: Canvas, axis: Int, dates: List<Pair<Int, String>>) {
        val baseline = capCentredBaseline(axis + px(LABEL_HEIGHT) + px(DATE_HEIGHT) / 2f)
        var end = Float.NEGATIVE_INFINITY
        for ((x, label) in dates) {
            val x0 = x + pxf(LABEL_INSET)
            if (x0 < end + pxf(4f) || x0 + text.measureText(label) > right) continue
            canvas.drawText(label, x0, baseline, text)
            end = x0 + text.measureText(label)
        }
    }

    /** The baseline of the time labels; under the first chart a little lower, for the charging band. */
    private fun labelBaseline(axis: Int, secondChart: Boolean): Float =
        capCentredBaseline(axis + px(LABEL_HEIGHT) / 2f) + if (secondChart) 0f else pxf(LABEL_SHIFT)

    private fun setLabelText() {
        text.textSize = pxf(AXIS_TEXT)
        text.typeface = ThemeManager.getCustomFont()
        text.textAlign = Paint.Align.LEFT
        text.color = secondary
    }

    /** One line at the start of each day (dotted; solid on Monday), through both charts and their labels. */
    private fun drawDayLines(canvas: Canvas, t: TenDayChart, top1: Int, bottom1: Int, top2: Int, bottom2: Int) {
        val dayW = (right - left) / t.used.size.toFloat()
        for (i in t.used.indices) {
            val x = (left + dayW * i).roundToInt() + if (i == 0) 1 else 0
            val monday = t.dayOfWeek[i] == java.util.Calendar.MONDAY
            vLine(canvas, x, top1, bottom1, monday)
            vLine(canvas, x, top2, bottom2, monday)
        }
    }

    /** One bar a day ([values] of [scale] in [color], -1 = no bar); with [stackedOff], the Screen Off part on top. */
    private fun drawDayBars(canvas: Canvas, top: Int, bottom: Int, values: IntArray, stackedOff: IntArray?, scale: Float, color: Int) {
        val dayW = (right - left) / values.size.toFloat()
        val barW = (dayW * 0.7f).roundToInt()
        for (i in values.indices) {
            val x0 = (left + dayW * i + (dayW - barW) / 2f).roundToInt().toFloat()
            val off = if (stackedOff == null) 0 else maxOf(0, stackedOff[i])
            stacked(canvas, x0, x0 + barW, top, bottom, maxOf(0, values[i]), off, scale, color)
        }
    }

    /** The weekday initial of each day, just right of its line; under the screen chart also the date (first day, Mondays). */
    private fun drawDayLabels(canvas: Canvas, t: TenDayChart, axis: Int, dates: Boolean) {
        val dayW = (right - left) / t.used.size.toFloat()
        setLabelText()
        val names = DateFormatSymbols.getInstance(Locale.getDefault()).shortWeekdays
        val baseline = labelBaseline(axis, dates)
        for (i in t.used.indices) {
            val initial = names.getOrNull(t.dayOfWeek[i])?.take(1)?.uppercase(Locale.getDefault()).orEmpty()
            canvas.drawText(initial, left + dayW * i + pxf(LABEL_INSET), baseline, text)
        }
        if (!dates) return
        val format = SimpleDateFormat("d MMM", Locale.getDefault())
        drawDates(canvas, axis, BatteryHistoryLogic.weekMarks(t.dayOfWeek).map { i ->
            (left + dayW * i).roundToInt() to format.format(Date(t.dayStarts[i]))
        })
    }

    private fun drawEmpty(canvas: Canvas, top: Int, bottom: Int) {
        text.textSize = pxf(TITLE_TEXT)
        text.typeface = ThemeManager.getCustomFont()
        text.textAlign = Paint.Align.CENTER
        text.color = secondary
        canvas.drawText(localize("No battery history yet"), (left + right) / 2f, capCentredBaseline((top + bottom) / 2f), text)
    }

    /** The baseline that puts the centre of the capitals of [text] on [centre]. */
    private fun capCentredBaseline(centre: Float): Float {
        text.getTextBounds("H", 0, 1, capBounds)
        return centre + capBounds.height() / 2f
    }

    private companion object {
        // dp (1 dp = 1 px on the Y2)
        const val PAD = 12
        const val PAD_TOP = 6
        const val AXIS_WIDTH = 46
        const val SEGMENT_WIDTH = 330
        const val SEGMENT_HEIGHT = 28
        const val SEGMENT_GAP = 6
        const val SEGMENT_TEXT = 17f
        const val HEADER_HEIGHT = 27
        const val HEADER_BASELINE = 17
        const val HEADER_TEXT = 15f
        const val LEVEL_TEXT = 17f
        const val TITLE_HEIGHT = 22
        const val TITLE_TEXT = 16f
        const val LEGEND_BOX = 10f
        const val LEGEND_GAP = 16f
        const val AXIS_TEXT = 13f
        const val LABEL_HEIGHT = 17
        const val LABEL_INSET = 3f
        const val DATE_HEIGHT = 15
        const val BAND_THICK = 3f
        /** A bar is at least 2 px high: a 1 px bar is hidden in the axis line (for example 1 min of screen on). */
        const val MIN_BAR = 2
        /** The labels under the first chart move down this much: the charging band is over them. */
        const val LABEL_SHIFT = 2f
        const val BOTTOM = 6
        /** All heights but the two charts. */
        const val FIXED_HEIGHT = PAD_TOP + SEGMENT_HEIGHT + SEGMENT_GAP + HEADER_HEIGHT + TITLE_HEIGHT + LABEL_HEIGHT +
            TITLE_HEIGHT + LABEL_HEIGHT + DATE_HEIGHT + BOTTOM
        const val MIN_HEIGHT = 330
    }
}
