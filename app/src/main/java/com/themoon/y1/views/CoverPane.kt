package com.themoon.y1.views

import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView

/**
 * The album art in the right pane of the split screens (main menu, Music, Audiobooks, Videos). All of them use one rule:
 * the image is [ZOOM] times the size of the pane, centred, with CENTER_CROP, so the pane shows the middle of the art
 * with a small zoom.
 */
object CoverPane {

    /** Size of the image divided by the size of the pane. */
    const val ZOOM = 1.35f

    /** Image size (px) for a pane side of [paneSize] px. */
    fun zoomed(paneSize: Int): Int = (paneSize * ZOOM).toInt()

    /**
     * Keeps [image] at [ZOOM] times the size of [pane], centred, also when the pane gets its size later or a new size.
     * Call it once for each pane. (A size read once in a post was 0 when the pane was hidden, for example when a theme
     * change built the hidden Music menu again; the art then stayed at the size of the pane, with no zoom.)
     */
    fun attach(pane: FrameLayout, image: ImageView) {
        image.scaleType = ImageView.ScaleType.CENTER_CROP
        fit(pane, image)
        pane.addOnLayoutChangeListener { _, l, t, r, b, oldL, oldT, oldR, oldB ->
            // A new size: set the image size after this layout pass
            if (r - l != oldR - oldL || b - t != oldB - oldT) pane.post { fit(pane, image) }
        }
    }

    private fun fit(pane: View, image: ImageView) {
        val w = pane.width
        val h = pane.height
        if (w <= 0 || h <= 0) return
        val lp = image.layoutParams as? FrameLayout.LayoutParams
        if (lp != null && lp.width == zoomed(w) && lp.height == zoomed(h) && lp.gravity == Gravity.CENTER) return
        image.layoutParams = FrameLayout.LayoutParams(zoomed(w), zoomed(h), Gravity.CENTER)
    }
}
