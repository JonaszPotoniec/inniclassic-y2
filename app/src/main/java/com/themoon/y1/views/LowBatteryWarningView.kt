package com.themoon.y1.views

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.FrameLayout
import android.widget.TextView
import com.themoon.y1.MainActivity
import com.themoon.y1.ThemeManager
import com.themoon.y1.managers.LanguageManager
import com.themoon.y1.managers.LowBatteryWarnings
import com.themoon.y1.managers.LowBatteryWarnings.Warning
import kotlin.math.roundToInt

/**
 * The low-battery warnings of the app, in the iPod style (see [LowBatteryWarnings] for when they show):
 * a banner like a selected list row below the status bar ("Battery low: 10 %", 3 s), and a full screen with
 * an empty battery and "Please charge". They are views in the activity, not a dialog, so the activity keeps the
 * focus and playback continues. Any key closes them and does nothing else. They show only while the screen is on:
 * a warning that comes while the screen is off shows when the screen comes on.
 *
 * The host is a transparent view under the loading overlay (which is also the black cover of the screen-off
 * mode). While it is attached, it receives the battery broadcasts and sees each key before the activity.
 */
class LowBatteryWarningView private constructor(private val main: MainActivity) : FrameLayout(main) {

    private val banner = TextView(main)
    private val screen = LowBatteryScreenView(main)
    private var shown = Warning.NONE
    private var shownLevel = -1
    private var gate: KeyGate? = null

    private val hideBanner = Runnable { if (state.onBannerTimeout()) render() }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_ON -> render()
                Intent.ACTION_BATTERY_CHANGED -> {
                    val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                    val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                    val pct = if (level >= 0 && scale > 0) level * 100 / scale else -1
                    if (state.onBattery(pct, isCharging(intent))) render()
                }
                // A charger (or USB) that comes or goes starts or ends a discharge at once.
                Intent.ACTION_POWER_CONNECTED -> if (state.onBattery(-1, true)) render()
            }
        }
    }

    init {
        // Not focusable and not clickable: the lists keep the focus, and the views under the warnings stay the same.
        isFocusable = false
        banner.visibility = GONE
        screen.visibility = GONE
        addView(screen, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(banner, LayoutParams(LayoutParams.MATCH_PARENT, 0, Gravity.TOP))
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED).apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        // ACTION_BATTERY_CHANGED is sticky: the receiver gets the current level at once.
        main.registerReceiver(receiver, filter)
        val window = main.window
        val g = KeyGate(window.callback, this)
        window.callback = g
        gate = g
    }

    override fun onDetachedFromWindow() {
        try {
            main.unregisterReceiver(receiver)
        } catch (_: Exception) {}
        gate?.let { if (main.window.callback === it) main.window.callback = it.inner }
        gate = null
        removeCallbacks(hideBanner)
        super.onDetachedFromWindow()
    }

    /** First look at each key: true when the key closed a warning (or is a part of that push) and must do nothing else. */
    internal fun beforeKey(event: KeyEvent): Boolean {
        if (!isScreenVisible()) return false
        val down = event.action == KeyEvent.ACTION_DOWN
        if (event.action != KeyEvent.ACTION_DOWN && event.action != KeyEvent.ACTION_UP) return false
        // Only a warning that the user can see is closed by a key.
        if (down && shown == Warning.NONE && state.warning != Warning.NONE) return false
        val ignore = state.onKey(event.keyCode, down, event.repeatCount, SystemClock.uptimeMillis())
        if (ignore && state.warning != shown) render()
        return ignore
    }

    /** After each key: a key can turn the screen on (screen-off mode), then a waiting warning shows. */
    internal fun afterKey() {
        if (state.warning != shown) render()
    }

    private fun isScreenVisible(): Boolean {
        if (main.isFakeScreenOff) return false
        val pm = main.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return true
        @Suppress("DEPRECATION")
        return if (Build.VERSION.SDK_INT >= 20) pm.isInteractive else pm.isScreenOn
    }

    /** Shows [LowBatteryWarnings.warning] (when the screen is on), or removes the warning that shows. */
    private fun render() {
        val want = state.warning
        if (want != Warning.NONE && !isScreenVisible()) {
            // Wait for the screen; remove an old warning now.
            if (shown != Warning.NONE && shown != want) hideAll()
            return
        }
        if (want == shown && state.warningLevel == shownLevel) return
        hideAll()
        when (want) {
            Warning.BANNER -> showBanner(state.warningLevel)
            Warning.SCREEN -> {
                screen.setText(t("Please charge"))
                screen.visibility = VISIBLE
                screen.invalidate()
            }
            Warning.NONE -> {}
        }
        shown = want
        shownLevel = state.warningLevel
    }

    private fun hideAll() {
        removeCallbacks(hideBanner)
        banner.visibility = GONE
        screen.visibility = GONE
        shown = Warning.NONE
        shownLevel = -1
    }

    private fun showBanner(level: Int) {
        val d = resources.displayMetrics.density
        val rowHeight = (IpodClassicMetrics.ROW_HEIGHT * d).roundToInt()
        (banner.layoutParams as LayoutParams).let {
            it.height = rowHeight
            it.topMargin = (ClassicMenuStyle.STATUS_HEIGHT_DP * d).roundToInt()
            banner.layoutParams = it
        }
        // The look of a selected list row: the theme's selection colours, bold list text, the row padding.
        banner.background = ListItemViewFactory.createFocusedButtonBackground(main)
        banner.setTextColor(ThemeManager.getListButtonFocusedTextColor())
        banner.typeface = ThemeManager.getCustomFontBold()
        banner.setTextSize(TypedValue.COMPLEX_UNIT_PX, IpodClassicMetrics.LIST_TEXT_SP * d)
        banner.includeFontPadding = false
        banner.isSingleLine = true
        banner.gravity = Gravity.LEFT or Gravity.TOP
        banner.text = "${t("Battery low")}: $level %"
        val padLeft = (IpodClassicMetrics.ROW_PADDING_LEFT * d).roundToInt()
        val padRight = (IpodClassicMetrics.ROW_PADDING_RIGHT * d).roundToInt()
        banner.setPadding(padLeft, IpodClassicLayout.capCentredPaddingTop(banner, rowHeight.toFloat()), padRight, 0)
        banner.visibility = VISIBLE
        postDelayed(hideBanner, LowBatteryWarnings.BANNER_MS)
    }

    private fun t(key: String): String = try {
        LanguageManager.getInstance(main).t(key)
    } catch (_: Exception) {
        key
    }

    /** Sees each key of the activity window first; all other calls go to the activity. */
    internal class KeyGate(val inner: Window.Callback, private val host: LowBatteryWarningView) : Window.Callback by inner {
        override fun dispatchKeyEvent(event: KeyEvent): Boolean {
            if (host.beforeKey(event)) return true
            val handled = inner.dispatchKeyEvent(event)
            host.afterKey()
            return handled
        }
    }

    companion object {
        /** One state for the life of the app, so a new activity does not show a warning again in the same discharge. */
        private val state = LowBatteryWarnings()

        /** Adds the warnings to the activity, under the loading / screen-off overlay. Call after the view bindings. */
        fun install(main: MainActivity) {
            val content = main.findViewById<ViewGroup>(android.R.id.content) ?: return
            val view = LowBatteryWarningView(main)
            val cover = main.layoutLoadingOverlay
            val index = if (cover != null && cover.parent === content) content.indexOfChild(cover) else content.childCount
            content.addView(view, index, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }

        /** Charging as the status bar battery shows it: charging or full, or on a charger. */
        internal fun isCharging(intent: Intent): Boolean {
            val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1)
            return status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL || plugged > 0
        }
    }
}

/**
 * The full "Please charge" screen: an empty battery with a red end, as on the iPod, and the text below it, on the
 * theme's background. Drawn with plain shapes (no Path.op, see docs), on whole pixels.
 */
class LowBatteryScreenView(context: Context) : View(context) {
    private var text = ""
    private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val rect = RectF()

    fun setText(value: String) {
        text = value
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width
        val h = height
        if (w <= 0 || h <= 0) return
        val d = resources.displayMetrics.density
        val textColor = ThemeManager.getTextColorPrimary() or OPAQUE
        val lightText = BatteryLayout.isLight(textColor)
        canvas.drawColor(ThemeManager.getOverlayBackgroundColor() or OPAQUE)

        // The battery: body BODY_W x BODY_H with an OUTLINE px outline, and the nub on the right.
        val outline = (OUTLINE * d).roundToInt().coerceAtLeast(1)
        val bodyW = (BODY_W * d).roundToInt()
        val bodyH = (BODY_H * d).roundToInt()
        val nubW = (NUB_W * d).roundToInt()
        var nubH = (NUB_H * d).roundToInt()
        if ((bodyH - nubH) % 2 != 0) nubH -= 1
        textPaint.typeface = ThemeManager.getCustomFontBold()
        textPaint.textSize = TEXT_SP * d
        textPaint.color = textColor
        val capHeight = capHeight()
        val gap = (GAP * d).roundToInt()
        val blockH = bodyH + gap + capHeight
        val left = (w - bodyW - nubW) / 2
        val top = (h - blockH) / 2
        val radius = bodyH * 0.12f

        val outlineColor = if (lightText) OUTLINE_DARK_THEME else OUTLINE_LIGHT_THEME
        outlinePaint.color = outlineColor
        outlinePaint.strokeWidth = outline.toFloat()
        val half = outline / 2f
        rect.set(left + half, top + half, left + bodyW - half, top + bodyH - half)
        canvas.drawRoundRect(rect, radius, radius, outlinePaint)

        // The nub starts under the outline, so only its right corners show as round.
        fillPaint.color = outlineColor
        val nubTop = top + (bodyH - nubH) / 2
        rect.set((left + bodyW - outline).toFloat(), nubTop.toFloat(), (left + bodyW + nubW).toFloat(), (nubTop + nubH).toFloat())
        canvas.drawRoundRect(rect, 2f * d, 2f * d, fillPaint)

        // The red end: a narrow fill at the left, inside the outline (the status bar battery's low colour).
        fillPaint.color = BatteryLayout.fillColors(lightText, false, 0)[0]
        val fillW = (RED_W * d).roundToInt().coerceAtLeast(1)
        val inner = maxOf(0f, radius - half)
        rect.set((left + outline).toFloat(), (top + outline).toFloat(), (left + outline + fillW).toFloat(), (top + bodyH - outline).toFloat())
        canvas.drawRoundRect(rect, inner, inner, fillPaint)

        // The text: its capitals start GAP below the battery.
        val baseline = top + bodyH + gap + capHeight
        canvas.drawText(text, w / 2f, baseline.toFloat(), textPaint)
    }

    private fun capHeight(): Int {
        val bounds = android.graphics.Rect()
        textPaint.getTextBounds("H", 0, 1, bounds)
        return -bounds.top
    }

    private companion object {
        const val OPAQUE = 0xFF000000.toInt()
        // Sizes in dp (1 dp = 1 px on the Y2), for the 480 x 360 screen.
        const val BODY_W = 150
        const val BODY_H = 72
        const val OUTLINE = 6
        const val NUB_W = 12
        const val NUB_H = 30
        const val RED_W = 14
        const val GAP = 30
        const val TEXT_SP = 27f
        // The outline colours of the status bar battery (BatteryIconView).
        const val OUTLINE_LIGHT_THEME = 0xFF3C3C43.toInt()
        const val OUTLINE_DARK_THEME = 0xFFAEAEB2.toInt()
    }
}
