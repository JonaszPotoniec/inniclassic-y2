package com.themoon.y1.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.themoon.y1.MainActivity
import com.themoon.y1.R
import com.themoon.y1.ThemeManager
import com.themoon.y1.controllers.player.NowPlayingTheme

/**
 * Work in progress, in the iPod way: a screen under the status bar (the status bar and its title stay) with the
 * message in the centre and the Now Playing progress bar under it, or the iPod activity spinner when the end is
 * not known. In place of a dark full-screen layer with an Android bar. Keys still go to the screen below.
 */
object ProgressLayer {

    /** What the layer shows under the message. */
    enum class Look { SPINNER, BAR }

    /**
     * The message for [show]: [message], or the old one while the layer shows (a list opened during a scan shows
     * the scan), else [fallback].
     */
    fun messageFor(message: String?, current: String?, showing: Boolean, fallback: String): String = when {
        message != null -> message
        showing && !current.isNullOrEmpty() -> current
        else -> fallback
    }

    private const val TAG_LAYER = "progress_layer"

    // The iPod bar: 204 × 14 at 320 × 240, so 306 × 21 px on the Y2 (docs: IpodClassicMetrics PROGRESS_*)
    private const val BAR_WIDTH = 306
    private const val BAR_HEIGHT = 21
    private const val SPINNER_SIZE = 36

    private var layer: FrameLayout? = null
    private var text: TextView? = null
    private var bar: ProgressBar? = null
    private var spinner: IpodSpinnerView? = null

    val isShowing: Boolean get() = layer?.visibility == View.VISIBLE

    /** Shows the layer with the spinner; [message] null keeps the message of a layer that shows. */
    fun show(main: MainActivity, message: String?) {
        val wasShowing = isShowing
        ensure(main)
        val m = messageFor(message, text?.text?.toString(), wasShowing, main.t("Please wait..."))
        text?.text = m
        if (!wasShowing) setLook(Look.SPINNER)
        applyTheme(main)
        layer?.visibility = View.VISIBLE
    }

    /** Shows [percent] (0–100) on the bar and the new [message]. */
    fun update(main: MainActivity, percent: Int, message: String) {
        if (!isShowing) show(main, message)
        text?.text = message
        bar?.progress = percent.coerceIn(0, 100)
        setLook(Look.BAR)
    }

    /** [main] closes: drop the views of its window, so that this object does not keep the old activity in memory. */
    fun release(main: MainActivity) {
        if (layer?.context !== main) return
        layer = null
        text = null
        bar = null
        spinner = null
    }

    fun hide() {
        layer?.visibility = View.GONE
        spinner?.visibility = View.GONE
    }

    private fun setLook(look: Look) {
        bar?.visibility = if (look == Look.BAR) View.VISIBLE else View.GONE
        spinner?.visibility = if (look == Look.SPINNER) View.VISIBLE else View.GONE
    }

    private fun ensure(main: MainActivity) {
        if (layer?.parent != null && layer?.context === main) return
        val statusBar = main.findViewById<View>(R.id.layout_status_bar) ?: return
        val root = statusBar.parent as? ViewGroup ?: return
        val d = main.resources.displayMetrics.density
        val column = LinearLayout(main).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        val s = IpodSpinnerView(main)
        column.addView(s, LinearLayout.LayoutParams((SPINNER_SIZE * d).toInt(), (SPINNER_SIZE * d).toInt()).apply {
            bottomMargin = (14 * d).toInt()
        })
        val t = TextView(main).apply {
            gravity = Gravity.CENTER
            maxWidth = (440 * d).toInt()
            setTextSize(TypedValue.COMPLEX_UNIT_PX, 19 * d)
        }
        column.addView(t, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val b = ProgressBar(main, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
        column.addView(b, LinearLayout.LayoutParams((BAR_WIDTH * d).toInt(), (BAR_HEIGHT * d).toInt()).apply {
            topMargin = (16 * d).toInt()
        })
        val l = FrameLayout(main).apply {
            tag = TAG_LAYER
            isClickable = true
            visibility = View.GONE
            addView(column, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        }
        // Under the status bar: the screens are under it too, so the layer covers the screen but not the bar
        root.addView(l, root.indexOfChild(statusBar), FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        layer = l
        text = t
        bar = b
        spinner = s
    }

    private fun applyTheme(main: MainActivity) {
        val l = layer ?: return
        val statusBar = main.findViewById<View>(R.id.layout_status_bar)
        // The column is centred in the space under the status bar
        val top = statusBar?.height ?: 0
        if (l.paddingTop != top) l.setPadding(0, top, 0, 0)
        val background = NowPlayingTheme.background() or 0xFF000000.toInt()
        l.setBackgroundColor(background)
        val color = NowPlayingTheme.timeColor()
        text?.setTextColor(color)
        text?.typeface = ThemeManager.getCustomFontBold()
        spinner?.color = color
        val track = if (NowPlayingTheme.isDark(background)) R.drawable.progress_bar_now_playing_dark else R.drawable.progress_bar_now_playing
        bar?.let {
            if (it.getTag(R.id.player_progress) != track) {
                val p = it.progress
                it.progressDrawable = it.resources.getDrawable(track)
                it.setTag(R.id.player_progress, track)
                it.progress = p
            }
        }
    }
}

/** The iPod activity spinner: 12 spokes; the bright spoke turns once a second. */
class IpodSpinnerView(context: Context) : View(context) {

    var color: Int = 0xFF000000.toInt()
        set(value) {
            field = value
            invalidate()
        }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    override fun onDraw(canvas: Canvas) {
        val r = Math.min(width, height) / 2f
        if (r <= 0f) return
        paint.strokeWidth = r * 0.22f
        val inner = r * 0.45f
        val outer = r - paint.strokeWidth / 2f
        val head = ((SystemClock.uptimeMillis() / STEP_MS) % SPOKES).toInt()
        canvas.save()
        canvas.translate(width / 2f, height / 2f)
        for (i in 0 until SPOKES) {
            // The head spoke is the brightest; the spokes behind it fade
            val age = (head - i + SPOKES) % SPOKES
            paint.color = color
            paint.alpha = (255 * (1f - age * 0.075f)).toInt().coerceIn(40, 255)
            canvas.drawLine(0f, -inner, 0f, -outer, paint)
            canvas.rotate(360f / SPOKES)
        }
        canvas.restore()
        if (isShown) postInvalidateDelayed(STEP_MS)
    }

    companion object {
        const val SPOKES = 12
        const val STEP_MS = 83L
    }
}
