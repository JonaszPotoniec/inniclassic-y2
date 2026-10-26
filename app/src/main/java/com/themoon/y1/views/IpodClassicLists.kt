package com.themoon.y1.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.themoon.y1.ThemeManager
import java.util.Collections
import java.util.WeakHashMap

/** List rows and the main menu in the iPod Classic layout of the Classic themes (see [IpodClassicLayout]). */
object IpodClassicLists {
    // Recycled list rows can come back with another height; this set tells which titles to put back.
    private val centred: MutableSet<TextView> = Collections.newSetFromMap(WeakHashMap())

    /** Called by [ClassicMenuStyle.apply] for each Classic list row. */
    fun styleClassicRow(row: LinearLayout, title: TextView, arrow: TextView?, heightDp: Int) {
        val d = row.resources.displayMetrics.density
        row.setPadding(px(IpodClassicMetrics.ROW_PADDING_LEFT, d), 0, px(IpodClassicMetrics.ROW_PADDING_RIGHT, d), 0)
        title.setTextSize(TypedValue.COMPLEX_UNIT_PX, IpodClassicMetrics.LIST_TEXT_SP * d)
        // Rows with cover art (64 dp) or thumbnails stack two lines of text; only one-line rows are centred.
        if (heightDp == ClassicMenuStyle.ROW_HEIGHT_DP) {
            centreInRow(title, heightDp * d)
        } else if (centred.remove(title)) {
            uncentre(title)
        }
        if (arrow != null) showChevron(arrow, d)
    }

    /**
     * Called at the end of the ListItemViewFactory row builders. [row] can be the title itself (a Button).
     * In a Classic theme the row gets the iPod row metrics and cap-centred text.
     */
    fun styleListRow(row: View, title: TextView, value: TextView?) {
        if (!IpodClassicLayout.isActive) return
        val d = row.resources.displayMetrics.density
        val rowHeight = px(IpodClassicMetrics.ROW_HEIGHT, d)
        val textSize = IpodClassicMetrics.LIST_TEXT_SP * d
        val padLeft = px(IpodClassicMetrics.ROW_PADDING_LEFT, d)
        val padRight = px(IpodClassicMetrics.ROW_PADDING_RIGHT, d)
        row.layoutParams?.let {
            it.height = rowHeight
            row.layoutParams = it
        }
        title.setTextSize(TypedValue.COMPLEX_UNIT_PX, textSize)
        title.typeface = ThemeManager.getCustomFontBold()
        title.includeFontPadding = false
        if (row === title) {
            title.gravity = IpodClassicLayout.horizontalGravity(title) or Gravity.TOP
            title.setPadding(padLeft, IpodClassicLayout.capCentredPaddingTop(title, rowHeight.toFloat()), padRight, 0)
            return
        }
        row.setPadding(padLeft, 0, padRight, 0)
        title.isSingleLine = true
        title.ellipsize = TextUtils.TruncateAt.END
        centreInRow(title, rowHeight.toFloat())
        if (value != null) {
            value.setTextSize(TypedValue.COMPLEX_UNIT_PX, textSize)
            centreInRow(value, rowHeight.toFloat())
        }
    }

    /**
     * Main menu elements with the iPod metrics: list pane 240 wide, 38 px rows with 23 sp text and a
     * drawn chevron, and the cover pane moved to the new pane edge and below the full-width status bar
     * (as on the iPod, where the title bar spans the screen). The theme's own elements are not changed.
     */
    fun menuElements(elements: List<ThemeManager.MenuElement>, context: Context): List<ThemeManager.MenuElement> {
        if (!IpodClassicLayout.isActive) return elements
        val list = elements.firstOrNull { it.type == "list_box" } ?: return elements
        val dm = context.resources.displayMetrics
        val screenWidth = (dm.widthPixels / dm.density).toInt()
        val screenHeight = (dm.heightPixels / dm.density).toInt()
        val oldRight = list.x + list.width
        val newRight = list.x + IpodClassicMetrics.PANE_WIDTH
        return elements.map { el ->
            when {
                el === list -> copy(el, width = IpodClassicMetrics.PANE_WIDTH,
                    height = screenHeight - IpodClassicMetrics.STATUS_BAR_HEIGHT - list.y)
                el.type == "button" && el.parentId == list.id -> copy(el, height = IpodClassicMetrics.ROW_HEIGHT,
                    textSize = IpodClassicMetrics.LIST_TEXT_SP.toInt(), textRight = "")
                el.type != "button" && el.x == oldRight -> {
                    val top = if (isBottomAligned(el)) el.y else maxOf(el.y, IpodClassicMetrics.STATUS_BAR_HEIGHT)
                    copy(el, x = newRight, y = top,
                        width = if (el.width > 0) screenWidth - newRight else el.width,
                        height = if (el.height > 0 && top != el.y) screenHeight - top else el.height)
                }
                else -> el
            }
        }
    }

    /** Main menu rows in a Classic theme: the iPod padding, cap-centred text and a drawn chevron in place of the "›" text. */
    fun styleMainMenuButtons(buttons: List<LinearLayout>) {
        if (!IpodClassicLayout.isActive) return
        for (btn in buttons) {
            val main = btn.getChildAt(0) as? TextView ?: continue
            val right = btn.getChildAt(1) as? TextView
            val d = btn.resources.displayMetrics.density
            val height = btn.layoutParams?.height ?: continue
            if (height <= 0 || btn.paddingTop != 0 || btn.paddingBottom != 0) continue
            btn.setPadding(px(IpodClassicMetrics.ROW_PADDING_LEFT, d), 0, px(IpodClassicMetrics.ROW_PADDING_RIGHT, d), 0)
            if (right != null) showChevron(right, d)
            centreInRow(main, height.toFloat())
        }
    }

    private fun centreInRow(tv: TextView, rowHeight: Float) {
        centred.add(tv)
        tv.includeFontPadding = false
        val lp = tv.layoutParams
        if (lp != null && lp.height != ViewGroup.LayoutParams.MATCH_PARENT) {
            lp.height = ViewGroup.LayoutParams.MATCH_PARENT
            tv.layoutParams = lp
        }
        tv.gravity = IpodClassicLayout.horizontalGravity(tv) or Gravity.TOP
        tv.setPadding(tv.paddingLeft, IpodClassicLayout.capCentredPaddingTop(tv, rowHeight), tv.paddingRight, 0)
    }

    private fun uncentre(tv: TextView) {
        val lp = tv.layoutParams
        if (lp != null) {
            lp.height = ViewGroup.LayoutParams.WRAP_CONTENT
            tv.layoutParams = lp
        }
        tv.gravity = IpodClassicLayout.horizontalGravity(tv) or Gravity.CENTER_VERTICAL
        tv.setPadding(tv.paddingLeft, 0, tv.paddingRight, 0)
    }

    private fun showChevron(arrow: TextView, d: Float) {
        arrow.text = ""
        val existing = arrow.compoundDrawables[2]
        if (existing is IpodChevronDrawable) return
        val chevron = IpodChevronDrawable(
            ThemeManager.getListButtonFocusedTextColor(),
            IpodClassicMetrics.CHEVRON_STROKE * d
        ).apply {
            setBounds(0, 0, px(IpodClassicMetrics.CHEVRON_WIDTH, d), px(IpodClassicMetrics.CHEVRON_HEIGHT, d))
        }
        arrow.setCompoundDrawables(null, null, chevron, null)
        arrow.compoundDrawablePadding = 0
    }

    private fun px(dp: Int, d: Float): Int = IpodClassicLayout.px(dp, d)

    // MainMenuController measures y from the bottom for these elements.
    private fun isBottomAligned(el: ThemeManager.MenuElement): Boolean =
        el.gravity.lowercase(java.util.Locale.US).contains("bottom")

    private fun copy(
        el: ThemeManager.MenuElement,
        x: Int = el.x,
        y: Int = el.y,
        width: Int = el.width,
        height: Int = el.height,
        textSize: Int = el.textSize,
        textRight: String = el.textRight
    ) = ThemeManager.MenuElement(
        el.id, el.type, el.parentId, el.visibleOnFocus, x, y, width, height,
        el.textNormal, el.textFocused, textRight, el.textRightColor, el.textRightFocusedColor,
        el.iconNormal, el.iconFocused, el.previewImage, el.action, el.gravity, el.radius, el.focusIndex,
        textSize, el.textSecondarySize, el.textPosition, el.textAlign, el.bgColor, el.padding,
        el.focusOffsetX, el.focusOffsetY, el.focusScale
    )
}

/** The bold "›" chevron of the selected iPod row, drawn to the bounds (10 x 16 px). */
class IpodChevronDrawable(color: Int, strokeWidth: Float) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        this.strokeWidth = strokeWidth
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.SQUARE
        strokeJoin = Paint.Join.MITER
    }
    private val path = Path()

    override fun draw(canvas: Canvas) {
        val b = bounds
        val inset = paint.strokeWidth / 2f
        path.reset()
        path.moveTo(b.left + inset, b.top + inset)
        path.lineTo(b.right - inset * 1.5f, b.exactCenterY())
        path.lineTo(b.left + inset, b.bottom - inset)
        canvas.drawPath(path, paint)
    }

    override fun getIntrinsicWidth(): Int = bounds.width()

    override fun getIntrinsicHeight(): Int = bounds.height()

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
