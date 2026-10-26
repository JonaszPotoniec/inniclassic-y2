package com.themoon.y1.controllers.player

import android.graphics.PorterDuff
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import com.themoon.y1.R
import com.themoon.y1.ThemeManager

/**
 * Theme colours of the Now Playing parts that activity_main.xml gives fixed light-theme colours:
 * scrub times, volume icons, the bar track and the reflection fade. Classic Light keeps the iPod look;
 * Classic Dark and the other themes use the theme colours ([PlayerController.applyThemeColors]).
 */
object NowPlayingTheme {

    /** Now Playing background: white in Classic Light, else the theme background. */
    fun background(): Int =
        if (ThemeManager.isClassicLightTheme()) 0xFFFFFFFF.toInt() else ThemeManager.getOverlayBackgroundColor()

    /** Colour of the times and the volume icons. */
    fun timeColor(): Int =
        if (ThemeManager.isClassicLightTheme()) 0xFF000000.toInt() else ThemeManager.getTextColorPrimary()

    /** True for a dark background (relative luminance below 0.2; alpha is ignored). */
    fun isDark(color: Int): Boolean {
        fun linear(c: Int): Double {
            val v = c / 255.0
            return if (v <= 0.03928) v / 12.92 else Math.pow((v + 0.055) / 1.055, 2.4)
        }
        val l = 0.2126 * linear((color shr 16) and 0xFF) + 0.7152 * linear((color shr 8) and 0xFF) + 0.0722 * linear(color and 0xFF)
        return l < 0.2
    }

    fun apply(root: View) {
        val time = timeColor()
        for (id in intArrayOf(R.id.tv_player_seek_current, R.id.tv_player_seek_total)) {
            root.findViewById<TextView>(id)?.setTextColor(time)
        }
        for (id in intArrayOf(R.id.tv_player_volume_down, R.id.tv_player_volume_up)) {
            root.findViewById<ImageView>(id)?.setColorFilter(time, PorterDuff.Mode.SRC_ATOP)
        }

        val background = background()
        val track = if (isDark(background)) R.drawable.progress_bar_now_playing_dark else R.drawable.progress_bar_now_playing
        for (id in intArrayOf(R.id.player_progress, R.id.player_volume_progress, R.id.seek_player_scrub)) {
            val bar = root.findViewById<ProgressBar>(id) ?: continue
            if (bar.getTag(R.id.player_progress) == track) continue
            bar.progressDrawable = bar.resources.getDrawable(track)
            bar.setTag(R.id.player_progress, track)
        }

        // The reflection fades to the background through a gradient view on top of it (if the layout has one)
        val reflection = root.findViewById<ViewGroup>(R.id.frame_album_reflection) ?: return
        for (i in 0 until reflection.childCount) {
            val v = reflection.getChildAt(i)
            if (v is ImageView || v is ViewGroup) continue
            v.background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(background and 0x00FFFFFF, background or 0xFF000000.toInt())
            )
        }
    }
}
