package com.themoon.y1.views

import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.themoon.y1.ThemeManager

/**
 * Shared metrics matching the approved Classic main menu (480 x 360 reference).
 */
object ClassicMenuStyle {
    const val ROW_HEIGHT_DP: Int = 33
    const val STATUS_HEIGHT_DP: Int = 26
    const val PANE_WIDTH_DP: Int = 242
    fun apply(row: LinearLayout, title: TextView, arrow: TextView?, heightDp: Int) {
        if (!ThemeManager.isClassicTheme()) return
        val density = row.resources.displayMetrics.density
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding((8 * density).toInt(), 0, (8 * density).toInt(), 0)
        row.minimumHeight = (heightDp * density).toInt()
        val params = row.layoutParams
        if (params != null) {
            params.height = (heightDp * density).toInt()
            if (params is ViewGroup.MarginLayoutParams) {
                params.setMargins(0, 0, 0, 0)
            }
            row.layoutParams = params
        }
        title.includeFontPadding = false
        title.setTextSize(TypedValue.COMPLEX_UNIT_PX, 21 * density)
        title.typeface = ThemeManager.getCustomFontBold()
        if (arrow != null) {
            arrow.text = "›"
            arrow.includeFontPadding = false
            arrow.setTextSize(TypedValue.COMPLEX_UNIT_PX, 21 * density)
            arrow.setTextColor(ThemeManager.getListButtonFocusedTextColor())
            arrow.visibility = if (row.isFocused) View.VISIBLE else View.GONE
        }
    }
}
