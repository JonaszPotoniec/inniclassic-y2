package com.themoon.y1.controllers.player

import android.content.Context
import android.widget.ScrollView

/**
 * The scroll view of the full-width lyrics. The lines fade out at the top edge (under the status bar) and at the
 * bottom edge (above the progress bar), so they do not stop at a hard edge.
 *
 * The fade is Android's own vertical fading edge in an off-screen layer (no solid colour): it makes the lyrics
 * transparent, so it works on each Now Playing background (black, white, the blurred art). Both fades always show;
 * the text has [EDGE_PADDING] above the first line and below the last line, so the first and the last line can
 * scroll clear of the fades.
 */
class LyricsScrollView(context: Context) : ScrollView(context) {
    init {
        isVerticalFadingEdgeEnabled = true
        setFadingEdgeLength(px(FADE_LENGTH_DP))
    }

    override fun getTopFadingEdgeStrength(): Float = 1f

    override fun getBottomFadingEdgeStrength(): Float = 1f

    // 0 = fade the content in a layer, not with a colour over it
    override fun getSolidColor(): Int = 0

    private fun px(dp: Int): Int = (dp * resources.displayMetrics.density + 0.5f).toInt()

    companion object {
        /** Height of each fade: about one lyrics line. */
        const val FADE_LENGTH_DP = 36
        /** Text padding above the first line and below the last line: the fade and a small gap. */
        const val EDGE_PADDING_DP = FADE_LENGTH_DP + 4
        /** Space between the bottom of the lyrics (the end of the fade) and the progress bar. */
        const val BOTTOM_GAP_DP = 10

        fun edgePadding(context: Context): Int = (EDGE_PADDING_DP * context.resources.displayMetrics.density + 0.5f).toInt()
    }
}
