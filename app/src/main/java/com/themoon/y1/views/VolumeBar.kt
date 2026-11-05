package com.themoon.y1.views

import android.graphics.PorterDuff
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RelativeLayout
import com.themoon.y1.MainActivity
import com.themoon.y1.R
import com.themoon.y1.controllers.player.NowPlayingTheme

/**
 * The volume bar, at the iPod places (no floating box):
 * - Now Playing: the volume row replaces the progress bar row (the iPod classic does this).
 * - Video player: the same bar replaces the time row at the bottom of the video.
 * - Other screens (the volume buttons): the bar replaces the status bar title, so the list stays visible.
 * The bar goes away [SHOW_MS] after the last change.
 */
object VolumeBar {

    enum class Place { NOW_PLAYING, VIDEO, STATUS_BAR }

    const val SHOW_MS = 1500L

    /** Where the bar shows on the screen [screenState] (a MainActivity.STATE_ value). */
    fun placeFor(screenState: Int): Place = when (screenState) {
        MainActivity.STATE_PLAYER -> Place.NOW_PLAYING
        MainActivity.STATE_VIDEO_PLAYER -> Place.VIDEO
        else -> Place.STATUS_BAR
    }

    /** Width of the bar in the status bar: the title space, but not more than [maxPx], and 0 when too small. */
    fun statusBarWidth(titleWidthPx: Int, iconsPx: Int, maxPx: Int, minPx: Int): Int {
        val w = minOf(titleWidthPx - iconsPx, maxPx)
        return if (w < minPx) 0 else w
    }

    private const val TAG_STRIP = "volume_bar_strip"
    private val handler by lazy { Handler(Looper.getMainLooper()) }
    private var shown: Place? = null
    /** The activity of the bar that shows; [release] puts its window back when that activity closes. */
    private var owner: MainActivity? = null
    private var hiddenRow: View? = null
    private var hiddenRowVisibility = View.VISIBLE
    private val hideTask = Runnable { MainActivity.instance?.let { hide(it) } }

    /** Shows [level] of [max] with [leftIcon] at the left (the Play On icon for a speaker). */
    fun show(main: MainActivity, level: Int, max: Int, leftIcon: Int = R.drawable.ic_ipod_volume_down) {
        val place = placeFor(main.currentScreenState)
        if (shown != null && shown != place) hide(main)
        val ok = when (place) {
            Place.NOW_PLAYING -> main.isPlayerControllerInitialized() &&
                main.playerController.showVolume(level, max, leftIcon)
            Place.VIDEO -> showInVideo(main, level, max, leftIcon)
            Place.STATUS_BAR -> showInStatusBar(main, level, max, leftIcon)
        }
        // A bar that shows and cannot show the new level goes now: without its hide task it would stay
        if (!ok && shown != null) hide(main)
        keep(if (ok) place else null, if (ok) main else null)
        handler.removeCallbacks(hideTask)
        if (ok) handler.postDelayed(hideTask, SHOW_MS)
    }

    fun hide(main: MainActivity) {
        handler.removeCallbacks(hideTask)
        when (shown) {
            Place.NOW_PLAYING -> if (main.isPlayerControllerInitialized()) main.playerController.hideVolume()
            Place.VIDEO -> {
                main.findViewById<ViewGroup>(R.id.layout_video_player_mode)
                    ?.findViewWithTag<View>(TAG_STRIP)?.visibility = View.GONE
                hiddenRow?.visibility = hiddenRowVisibility
                hiddenRow = null
            }
            Place.STATUS_BAR -> {
                main.findViewById<ViewGroup>(R.id.layout_status_bar)
                    ?.findViewWithTag<View>(TAG_STRIP)?.visibility = View.GONE
                main.tvStatusBarTitle?.visibility = View.VISIBLE
            }
            null -> {}
        }
        keep(null, null)
    }

    /**
     * [main] closes: hide its bar now (the hide task finds only the activity that is open then), so that this object
     * does not keep a row of the old window. A bar of another activity stays.
     */
    fun release(main: MainActivity) {
        if (owner === main) hide(main)
    }

    internal fun keep(place: Place?, main: MainActivity?) {
        shown = place
        owner = main
    }

    /** True when a bar of [main] shows (for tests). */
    internal fun shows(main: MainActivity): Boolean = owner === main && shown != null

    // Video player: the strip takes the place of the time row.

    private fun showInVideo(main: MainActivity, level: Int, max: Int, leftIcon: Int): Boolean {
        val frame = main.findViewById<FrameLayout>(R.id.layout_video_player_mode) ?: return false
        val d = main.resources.displayMetrics.density
        var strip = frame.findViewWithTag<LinearLayout>(TAG_STRIP)
        if (strip == null) {
            strip = buildStrip(main, d, iconHeight = 18, barHeight = 20, iconGap = 10)
            val lp = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM)
            strip.setPadding((18 * d).toInt(), 0, (18 * d).toInt(), (14 * d).toInt())
            frame.addView(strip, lp)
        }
        val row = main.findViewById<View>(R.id.video_progress)?.parent as? View
        if (row != null && row !== hiddenRow) {
            hiddenRowVisibility = row.visibility
            row.visibility = View.INVISIBLE
            hiddenRow = row
        }
        setStrip(strip, level, max, leftIcon, 0xFFFFFFFF.toInt(), dark = true)
        strip.visibility = View.VISIBLE
        return true
    }

    // Other screens: the strip takes the place of the status bar title.

    private fun showInStatusBar(main: MainActivity, level: Int, max: Int, leftIcon: Int): Boolean {
        val bar = main.findViewById<RelativeLayout>(R.id.layout_status_bar) ?: return false
        val title = main.tvStatusBarTitle ?: return false
        if (!bar.isShown) return false
        val d = main.resources.displayMetrics.density
        var strip = bar.findViewWithTag<LinearLayout>(TAG_STRIP)
        if (strip == null) {
            strip = buildStrip(main, d, iconHeight = 15, barHeight = 12, iconGap = 6)
            val lp = RelativeLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT).apply {
                addRule(RelativeLayout.ALIGN_PARENT_LEFT)
                addRule(RelativeLayout.CENTER_VERTICAL)
            }
            bar.addView(strip, lp)
        }
        val progress = strip.getChildAt(1)
        val icons = strip.getChildAt(0).layoutParams.width + strip.getChildAt(2).layoutParams.width + (12 * d).toInt()
        val width = statusBarWidth(title.width, icons, (220 * d).toInt(), (60 * d).toInt())
        if (width == 0) return false
        if (progress.layoutParams.width != width) {
            progress.layoutParams = progress.layoutParams.apply { this.width = width }
        }
        (strip.layoutParams as? RelativeLayout.LayoutParams)?.let { lp ->
            if (lp.leftMargin != title.left - bar.paddingLeft) {
                lp.leftMargin = title.left - bar.paddingLeft
                strip.layoutParams = lp
            }
        }
        val color = title.currentTextColor
        setStrip(strip, level, max, leftIcon, color, dark = !NowPlayingTheme.isDark(color))
        title.visibility = View.INVISIBLE
        strip.visibility = View.VISIBLE
        return true
    }

    /** Small speaker, bar, loud speaker (the icon sizes of the Now Playing row, scaled to [iconHeight]). */
    private fun buildStrip(main: MainActivity, d: Float, iconHeight: Int, barHeight: Int, iconGap: Int): LinearLayout {
        fun icon(res: Int, widthDp: Float, heightDp: Float, left: Int, right: Int) = ImageView(main).apply {
            setImageResource(res)
            scaleType = ImageView.ScaleType.FIT_CENTER
            layoutParams = LinearLayout.LayoutParams((widthDp * d).toInt(), (heightDp * d).toInt()).apply {
                leftMargin = left
                rightMargin = right
            }
        }
        val scale = iconHeight / 18f
        val gap = (iconGap * d).toInt()
        return LinearLayout(main).apply {
            tag = TAG_STRIP
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            addView(icon(R.drawable.ic_ipod_volume_down, 16 * scale, 18 * scale, 0, gap))
            addView(ProgressBar(main, null, android.R.attr.progressBarStyleHorizontal).apply {
                layoutParams = LinearLayout.LayoutParams(0, (barHeight * d).toInt(), 1f)
            })
            addView(icon(R.drawable.ic_ipod_volume_up, 24 * scale, 20 * scale, gap, 0))
        }
    }

    private fun setStrip(strip: LinearLayout, level: Int, max: Int, leftIcon: Int, color: Int, dark: Boolean) {
        val down = strip.getChildAt(0) as ImageView
        val bar = strip.getChildAt(1) as ProgressBar
        val up = strip.getChildAt(2) as ImageView
        if (down.getTag(R.id.player_volume_progress) != leftIcon) {
            down.setImageResource(leftIcon)
            down.setTag(R.id.player_volume_progress, leftIcon)
        }
        down.setColorFilter(color, PorterDuff.Mode.SRC_ATOP)
        up.setColorFilter(color, PorterDuff.Mode.SRC_ATOP)
        val track = if (dark) R.drawable.progress_bar_now_playing_dark else R.drawable.progress_bar_now_playing
        if (bar.getTag(R.id.player_progress) != track) {
            bar.progressDrawable = bar.resources.getDrawable(track)
            bar.setTag(R.id.player_progress, track)
        }
        bar.max = max
        bar.progress = level
    }
}
