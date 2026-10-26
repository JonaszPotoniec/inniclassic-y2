package com.themoon.y1.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.AdapterView
import android.widget.ListView
import android.widget.ScrollView
import java.util.Collections
import java.util.WeakHashMap

/**
 * The "List Edges" setting.
 * - Cut (default): the list edge cuts the last visible row.
 * - Fade: a short fade at each list edge that has more rows past it. The fade is 2/3 of a row,
 *   so it only covers the edge row. The focused row is always kept clear of the fades.
 *
 * Lists use [EdgeFadeScrollView] or [EdgeFadeListView]. They turn on Android's vertical fading edges
 * for the fade length and for the scroll margin that keeps the focused row clear of the fade.
 * Over an opaque colour background they draw the fade themselves ([EdgeFadePainter]); else
 * Android draws it.
 */
object ListEdges {
    const val PREF_KEY = "list_edges_fade"
    private const val PREFS_NAME = "AppPrefs"

    private var fade: Boolean? = null
    private val lists: MutableSet<View> = Collections.newSetFromMap(WeakHashMap())
    private val initialFade = WeakHashMap<View, Boolean>()

    fun isFade(context: Context): Boolean =
        fade ?: context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(PREF_KEY, false).also { fade = it }

    /** Saves the setting and applies it at once to all lists. */
    fun setFade(context: Context, on: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putBoolean(PREF_KEY, on).commit()
        reload(context)
    }

    /** Reads the setting again (for example after Reset All Settings) and applies it to all lists. */
    fun reload(context: Context) {
        fade = null
        isFade(context)
        for (v in lists.toList()) apply(v)
    }

    /** Lets the plain ScrollViews under [root] fade too (lists that code builds, such as dialog items). */
    fun install(root: View) {
        if (root is ScrollView) {
            if (root !is EdgeFadeScrollView) replaceScrollView(root)
            return
        }
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) install(root.getChildAt(i))
        }
    }

    /**
     * Puts an [EdgeFadeScrollView] in place of [old], with the same content, before the first layout.
     * Code that still holds [old] gets a ScrollView with no content: its scrollTo() then does nothing,
     * and focus changes scroll the new view.
     */
    private fun replaceScrollView(old: ScrollView) {
        val parent = old.parent as? ViewGroup ?: return
        val content = old.getChildAt(0) ?: return
        val index = parent.indexOfChild(old)
        val replacement = EdgeFadeScrollView(old.context).apply {
            id = old.id
            isFocusable = old.isFocusable
            isVerticalScrollBarEnabled = old.isVerticalScrollBarEnabled
            isFillViewport = old.isFillViewport
            visibility = old.visibility
        }
        old.removeView(content)
        replacement.addView(content, content.layoutParams)
        parent.removeViewAt(index)
        parent.addView(replacement, index, old.layoutParams)
    }

    internal fun register(v: View) {
        if (lists.add(v) && !initialFade.containsKey(v)) initialFade[v] = v.isVerticalFadingEdgeEnabled
        apply(v)
    }

    internal fun unregister(v: View) {
        lists.remove(v)
    }

    private fun apply(v: View) {
        val on = isFade(v.context)
        val enabled = on || initialFade[v] == true
        if (v.isVerticalFadingEdgeEnabled != enabled) {
            v.isVerticalFadingEdgeEnabled = enabled
            v.invalidate()
        }
        if (!on) return
        updateLength(v)
        when (v) {
            is EdgeFadeScrollView -> v.keepFocusClear()
            is ListView -> v.focusedChild?.let { revealRow(v, it) }
        }
    }

    /** Sets the fade length from the height of the list rows. */
    internal fun updateLength(v: View) {
        if (!isFade(v.context)) return
        val rows: ViewGroup? = when (v) {
            is AbsListView -> v
            is ScrollView -> v.getChildAt(0) as? ViewGroup
            else -> null
        }
        val heights = ArrayList<Int>()
        if (rows != null) {
            for (i in 0 until minOf(rows.childCount, 24)) {
                val row = rows.getChildAt(i)
                if (row.visibility == View.VISIBLE && (rows is AbsListView || row.isFocusable)) {
                    heights.add(row.measuredHeight)
                }
            }
        }
        val fallback = (ClassicMenuStyle.ROW_HEIGHT_DP * v.resources.displayMetrics.density).toInt()
        val length = fadeLength(heights, fallback)
        if (v.verticalFadingEdgeLength != length) v.setFadingEdgeLength(length)
    }

    /**
     * Fade mode: focuses row [pos] of [list] clear of the fades and returns true.
     * Cut mode: returns false, and the caller scrolls as before.
     */
    fun focusRow(list: ListView, pos: Int): Boolean {
        if (!isFade(list.context) || pos !in 0 until list.count) return false
        val row = list.getChildAt(pos - list.firstVisiblePosition)
        if (row != null) {
            // On screen: EdgeFadeListView.requestChildFocus scrolls it clear of the fade.
            row.requestFocus()
            return true
        }
        list.setSelectionFromTop(pos, if (pos > 0) list.verticalFadingEdgeLength else 0)
        list.post { list.getChildAt(pos - list.firstVisiblePosition)?.requestFocus() }
        return true
    }

    /**
     * Scrolls [list] so that [row], a child of the list, is clear of the fades. It also selects the row:
     * ListView lays out again around its selected row (for example when a row changes size), so the
     * selection must follow the focus, or the list jumps back.
     */
    internal fun revealRow(list: ListView, row: View) {
        if (!isFade(list.context) || list.isInLayout || list.height == 0 || row.parent !== list) return
        val pos = list.getPositionForView(row)
        if (pos == AdapterView.INVALID_POSITION) return
        val delta = revealDelta(
            row.top, row.bottom, list.paddingTop, list.height - list.paddingBottom,
            list.verticalFadingEdgeLength, pos > 0, pos < list.count - 1
        )
        if (delta != 0 || pos != list.selectedItemPosition) {
            list.setSelectionFromTop(pos, row.top - delta - list.paddingTop)
        }
    }

    /**
     * The colour of the background behind [v], when it is one opaque colour; else 0.
     * With a colour, [EdgeFadePainter] draws the fade in that colour; with 0, Android fades the content
     * in an off-screen layer.
     */
    internal fun solidColorBehind(v: View): Int {
        val layers = ArrayList<Int?>()
        var cur: View = v
        while (true) {
            val parent = cur.parent as? View ?: break
            // The screen root: the wallpaper image draws over its background.
            if (parent.id == android.R.id.content) break
            if (cur.alpha < 1f) return 0
            val bg = cur.background
            layers.add(if (bg == null) 0 else (bg as? ColorDrawable)?.color)
            cur = parent
        }
        return solidColor(layers)
    }

    /**
     * Fade strength of one edge: a full fade when more content is past that edge, else none.
     * Android's own strength is (hidden pixels / fade length) when less than one fade length is hidden,
     * so a row that the edge cuts by only a few pixels got a fade only a few pixels tall: almost no fade.
     */
    fun edgeStrength(moreContent: Boolean): Float = if (moreContent) 1f else 0f

    /** For a ListView: true when rows are above the top edge (first child at adapter position [firstPos]). */
    fun listHasMoreAbove(firstPos: Int, firstTop: Int, viewTop: Int): Boolean = firstPos > 0 || firstTop < viewTop

    /** For a ListView: true when rows are below the bottom edge (last child at adapter position [lastPos]). */
    fun listHasMoreBelow(lastPos: Int, count: Int, lastBottom: Int, viewBottom: Int): Boolean =
        lastPos < count - 1 || lastBottom > viewBottom

    /**
     * Alpha (0..255) of the fade colour at pixel row [i] from the list edge (0 = the edge row of pixels)
     * for a fade [length] pixels long: from almost opaque at the edge to almost clear at the end.
     */
    fun fadeAlpha(i: Int, length: Int): Int = ((2 * (length - i) - 1) * 255 + length) / (2 * length)

    /** Fade length for rows of these heights: 2/3 of the smallest row, so the fade covers only the edge row. */
    fun fadeLength(rowHeights: List<Int>, fallbackRowHeight: Int): Int {
        val row = rowHeights.filter { it > 0 }.minOrNull() ?: fallbackRowHeight
        return row * 2 / 3
    }

    /**
     * Scroll distance (positive: the content moves up) that puts a row from [top] to [bottom] clear of
     * the fades of a list that shows [viewTop] to [viewBottom]. There is a fade only on a side with more rows.
     */
    fun revealDelta(
        top: Int,
        bottom: Int,
        viewTop: Int,
        viewBottom: Int,
        fade: Int,
        rowsBefore: Boolean,
        rowsAfter: Boolean
    ): Int {
        val lo = viewTop + if (rowsBefore) fade else 0
        val hi = viewBottom - if (rowsAfter) fade else 0
        return when {
            top < lo || bottom - top > hi - lo -> top - lo
            bottom > hi -> bottom - hi
            else -> 0
        }
    }

    /**
     * The background colour from the backgrounds of a view and its parents, nearest first:
     * an ARGB colour, 0 for no background, or null for a background that is not one colour.
     * Returns the first opaque colour, or 0 when something non-opaque comes first.
     */
    fun solidColor(layers: List<Int?>): Int {
        for (c in layers) {
            if (c == null) return 0
            val alpha = c ushr 24
            if (alpha == 0xFF) return c
            if (alpha != 0) return 0
        }
        return 0
    }
}

/** A ScrollView list with the "List Edges" setting. */
open class EdgeFadeScrollView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : ScrollView(context, attrs) {
    private var inFocusChange = false

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        ListEdges.register(this)
    }

    override fun onDetachedFromWindow() {
        ListEdges.unregister(this)
        super.onDetachedFromWindow()
    }

    private val painter = EdgeFadePainter()

    private fun moreAbove(): Boolean = childCount > 0 && scrollY > 0

    private fun moreBelow(): Boolean = childCount > 0 && getChildAt(0).bottom - scrollY > height - paddingBottom

    // Android's own fade only where this view does not draw it (a background that is not one opaque colour).
    override fun getSolidColor(): Int = 0

    override fun getTopFadingEdgeStrength(): Float = painter.androidStrength(this, moreAbove())

    override fun getBottomFadingEdgeStrength(): Float = painter.androidStrength(this, moreBelow())

    override fun draw(canvas: Canvas) {
        super.draw(canvas)
        painter.draw(canvas, this, moreAbove(), moreBelow())
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        // The rows are measured. ScrollView keeps the focused row this far from a fading edge.
        ListEdges.updateLength(this)
        super.onLayout(changed, l, t, r, b)
    }

    override fun requestChildFocus(child: View?, focused: View?) {
        inFocusChange = true
        try {
            super.requestChildFocus(child, focused)
        } finally {
            inFocusChange = false
        }
    }

    /** Also keeps the focused row clear of the fades when code scrolls the list (for example to restore a position). */
    override fun scrollTo(x: Int, y: Int) {
        super.scrollTo(x, y)
        if (!inFocusChange) keepFocusClear()
    }

    internal fun keepFocusClear() {
        if (!ListEdges.isFade(context) || childCount == 0 || height == 0) return
        val focused = findFocus() ?: return
        if (focused === this) return
        val rect = Rect()
        focused.getDrawingRect(rect)
        offsetDescendantRectToMyCoords(focused, rect)
        // A row off the screen was scrolled away on purpose.
        if (rect.bottom <= scrollY || rect.top >= scrollY + height) return
        val delta = computeScrollDeltaToGetChildRectOnScreen(rect)
        if (delta != 0) super.scrollTo(scrollX, scrollY + delta)
    }
}

/** A ListView list with the "List Edges" setting. */
open class EdgeFadeListView(context: Context) : ListView(context) {
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        ListEdges.register(this)
    }

    override fun onDetachedFromWindow() {
        ListEdges.unregister(this)
        super.onDetachedFromWindow()
    }

    private val painter = EdgeFadePainter()

    private fun moreAbove(): Boolean =
        childCount > 0 && ListEdges.listHasMoreAbove(firstVisiblePosition, getChildAt(0).top, paddingTop)

    private fun moreBelow(): Boolean = childCount > 0 && ListEdges.listHasMoreBelow(
        firstVisiblePosition + childCount - 1, count, getChildAt(childCount - 1).bottom, height - paddingBottom
    )

    // Android's own fade only where this view does not draw it (a background that is not one opaque colour).
    override fun getSolidColor(): Int = 0

    override fun getTopFadingEdgeStrength(): Float = painter.androidStrength(this, moreAbove())

    override fun getBottomFadingEdgeStrength(): Float = painter.androidStrength(this, moreBelow())

    override fun draw(canvas: Canvas) {
        super.draw(canvas)
        painter.draw(canvas, this, moreAbove(), moreBelow())
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        ListEdges.updateLength(this)
    }

    override fun requestChildFocus(child: View?, focused: View?) {
        super.requestChildFocus(child, focused)
        if (child != null) ListEdges.revealRow(this, child)
    }
}

/**
 * Draws the list fade over an opaque colour background as 1 px strips of that colour, from almost
 * opaque at the edge to almost clear. It uses no shader: with Android's own solid-colour fade
 * (a gradient shader that View.draw moves with setLocalMatrix for each edge), the Y2 sometimes
 * drew a solid band of the colour when it drew both the top and the bottom fade.
 */
internal class EdgeFadePainter {
    private var paints: Array<Paint> = emptyArray()
    private var color = 0
    private var length = 0

    /** The fade strength for Android's own drawing: none where this painter draws the fade. */
    fun androidStrength(v: View, moreContent: Boolean): Float =
        if (ListEdges.solidColorBehind(v) != 0) 0f else ListEdges.edgeStrength(moreContent)

    fun draw(canvas: Canvas, v: View, moreAbove: Boolean, moreBelow: Boolean) {
        if (!moreAbove && !moreBelow) return
        if (!v.isVerticalFadingEdgeEnabled || !ListEdges.isFade(v.context)) return
        val solid = ListEdges.solidColorBehind(v)
        val len = v.verticalFadingEdgeLength
        if (solid == 0 || len <= 0) return
        if (solid != color || len != length || paints.size != len) {
            color = solid
            length = len
            val rgb = solid and 0x00FFFFFF
            paints = Array(len) { i -> Paint().apply { this.color = (ListEdges.fadeAlpha(i, len) shl 24) or rgb } }
        }
        // The canvas is in scrolled coordinates: the visible area starts at (scrollX, scrollY).
        val left = (v.scrollX + v.paddingLeft).toFloat()
        val right = (v.scrollX + v.width - v.paddingRight).toFloat()
        val top = v.scrollY + v.paddingTop
        val bottom = v.scrollY + v.height - v.paddingBottom
        val n = minOf(len, (bottom - top) / 2)
        for (i in 0 until n) {
            if (moreAbove) canvas.drawRect(left, (top + i).toFloat(), right, (top + i + 1).toFloat(), paints[i])
            if (moreBelow) canvas.drawRect(left, (bottom - i - 1).toFloat(), right, (bottom - i).toFloat(), paints[i])
        }
    }
}
