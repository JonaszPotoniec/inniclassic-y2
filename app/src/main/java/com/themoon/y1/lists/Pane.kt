package com.themoon.y1.lists

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import com.themoon.y1.R
import com.themoon.y1.ThemeManager
import com.themoon.y1.views.ClassicMenuStyle
import com.themoon.y1.views.CoverPane

/**
 * The split screen of a list ([ListSpec.pane]): the list pane at the left ([ClassicMenuStyle.PANE_WIDTH_DP] wide in
 * the Classic themes), the art pane at the right ([CoverPane]), and the status bar over the list pane only.
 * A screen makes one [Pane] for its views; [ListScreen] shows it for the lists that have it and hides it for the others.
 *
 * [onShow] runs each time the pane shows (for example: the art of the song that plays).
 */
class Pane(
    private val activity: Activity,
    private val listPane: View,
    private val frame: View,
    private val image: ImageView?,
    private val onShow: (() -> Unit)? = null
) {
    private var imageSetUp = false

    /** True while the art pane shows. */
    val isShown: Boolean get() = frame.visibility == View.VISIBLE

    fun show() {
        split(true)
        if (!imageSetUp && image != null) {
            imageSetUp = true
            CoverPane.attach(image)
        }
        onShow?.invoke()
    }

    fun hide() = split(false)

    private fun split(on: Boolean) {
        if (on) frame.visibility = View.VISIBLE
        val density = activity.resources.displayMetrics.density
        listPane.setPadding(0, (ClassicMenuStyle.STATUS_HEIGHT_DP * density).toInt(), 0, 0)
        if (ThemeManager.isClassicTheme()) {
            val lp = listPane.layoutParams as? LinearLayout.LayoutParams
            if (lp != null) {
                lp.width = if (on) (ClassicMenuStyle.PANE_WIDTH_DP * density).toInt() else 0
                lp.weight = if (on) 0f else 1f
                listPane.layoutParams = lp
            }
        }
        activity.findViewById<View>(R.id.layout_status_bar)?.let {
            fitStatusBar(it, on)
            it.background = ThemeManager.createStatusBarBackground()
        }
        if (!on) frame.visibility = View.GONE
    }

    companion object {
        /** Puts the status bar over the list pane of a split screen ([split]), else over the full width. */
        fun fitStatusBar(statusBar: View, split: Boolean) {
            val lp = statusBar.layoutParams ?: return
            val width = if (split) {
                (ClassicMenuStyle.PANE_WIDTH_DP * statusBar.resources.displayMetrics.density).toInt()
            } else {
                ViewGroup.LayoutParams.MATCH_PARENT
            }
            if (lp.width == width) return
            lp.width = width
            statusBar.layoutParams = lp
        }
    }
}
