package com.themoon.y1.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import android.view.ViewTreeObserver
import com.themoon.y1.MainActivity
import com.themoon.y1.ThemeManager
import com.themoon.y1.managers.AudioPlayerManager
import kotlin.math.roundToInt

/** The iPod's blue play (▶) or pause (‖) state icon, shown left of the battery. Hidden when nothing is loaded. */
class IpodPlayStateView(context: Context) : View(context) {
    private var state = STATE_NONE
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f
        color = StatusIcons.EDGE
    }
    private val path = Path()

    // Playback changes come from many places, so the view polls the player while it is attached.
    private val poll = object : Runnable {
        override fun run() {
            refresh()
            postDelayed(this, POLL_MS)
        }
    }

    init {
        visibility = GONE
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        post(poll)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(poll)
        super.onDetachedFromWindow()
    }

    private fun refresh() {
        val s = currentState()
        val vis = if (s == STATE_NONE || !IpodClassicLayout.isActive) GONE else VISIBLE
        if (visibility != vis) visibility = vis
        if (s != state) {
            state = s
            invalidate()
        }
    }

    private fun currentState(): Int {
        if (AudioPlayerManager.getInstance().isPlaying()) return STATE_PLAYING
        val main = MainActivity.instance ?: return STATE_NONE
        return if (main.currentIndex in main.currentPlaylist.indices) STATE_PAUSED else STATE_NONE
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // The view is the status item box: the gradient of all status icons.
        fill.shader = StatusIcons.gradient(0f, h.toFloat())
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        path.reset()
        // The ink fills the view from edge to edge, like the other status icons (StatusIcons): the 1 px
        // edge is centred 0.5 px inside the view.
        when (state) {
            STATE_PLAYING -> {
                path.moveTo(0.5f, 0.5f)
                path.lineTo(w - 0.5f, h / 2f)
                path.lineTo(0.5f, h - 0.5f)
                path.close()
            }
            STATE_PAUSED -> {
                val bar = (w * PAUSE_BAR_RATIO).roundToInt().toFloat()
                path.addRect(0.5f, 0.5f, bar - 0.5f, h - 0.5f, Path.Direction.CW)
                path.addRect(w - bar + 0.5f, 0.5f, w - 0.5f, h - 0.5f, Path.Direction.CW)
            }
            else -> return
        }
        canvas.drawPath(path, fill)
        canvas.drawPath(path, edge)
    }

    private companion object {
        const val STATE_NONE = 0
        const val STATE_PLAYING = 1
        const val STATE_PAUSED = 2
        const val POLL_MS = 500L
        // 4 px bars with a 4 px space in the 12 px wide icon (6 and 5 at 17 px).
        const val PAUSE_BAR_RATIO = 0.35f
    }
}

/**
 * The 1 px dark line at the bottom of the iPod status bar. It hides itself when the status bar has a
 * transparent background (video player). Before each frame it also keeps the status bar at the full
 * width ([IpodClassicLayout.keepStatusBarFull]); it is in the status bar only in the Classic themes.
 */
class IpodStatusRuleView(context: Context) : View(context) {
    private val preDraw = ViewTreeObserver.OnPreDrawListener {
        val bar = parent as? View
        // A changed bar is drawn in the next frame, so this frame is skipped.
        if (IpodClassicLayout.keepStatusBarFull(bar)) return@OnPreDrawListener false
        val transparent = IpodClassicLayout.isTransparent(bar?.background)
        val vis = if (transparent || !IpodClassicLayout.isActive) INVISIBLE else VISIBLE
        if (visibility != vis) visibility = vis
        true
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        viewTreeObserver.addOnPreDrawListener(preDraw)
    }

    override fun onDetachedFromWindow() {
        viewTreeObserver.removeOnPreDrawListener(preDraw)
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(if (ThemeManager.isClassicLightTheme()) 0xFF686868.toInt() else 0xFF000000.toInt())
    }
}
