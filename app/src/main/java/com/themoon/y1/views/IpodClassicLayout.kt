package com.themoon.y1.views

import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.RelativeLayout
import android.widget.TextView
import com.themoon.y1.MainActivity
import com.themoon.y1.R
import com.themoon.y1.ThemeManager
import java.util.WeakHashMap
import kotlin.math.roundToInt

/**
 * The iPod Classic layout of the Classic themes (Classic Light and Classic Dark; Auto switches between them).
 *
 * The status bar, the lists and Now Playing use the iPod classic proportions from [IpodClassicMetrics]. The
 * status bar spans the full width on all screens, also over the split screens and Cover Flow. The static views
 * from activity_main.xml are changed at runtime. Each changed property is recorded the first time it changes,
 * so a theme change without a restart restores the original views first (the other themes keep their own layout).
 */
object IpodClassicLayout {
    private const val TAG_PLAY_ICON = "ipod_play_state"
    private const val TAG_STATUS_RULE = "ipod_status_rule"

    private var listenedPrefs: SharedPreferences? = null
    private var applied = false
    private var appliedTheme: String? = null
    private var reapplying = false

    // SharedPreferences keeps listeners in a weak map, so this object holds the only strong reference.
    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        // The iPod tilt (pivot, scaleX) is set in applyTheme, so set it again when the tilt changes
        if (key == "album_tilt_enabled" && isActive) MainActivity.instance?.applyTheme()
    }

    /** True when a Classic theme is in use: only the Classic themes have the iPod layout. */
    val isActive: Boolean
        get() = ThemeManager.isClassicTheme()

    /** Applies (or, after a theme change, restores) the static views. Called at the end of applyTheme(). */
    fun apply(main: MainActivity) {
        if (listenedPrefs !== main.prefs) {
            main.prefs.registerOnSharedPreferenceChangeListener(prefsListener)
            listenedPrefs = main.prefs
        }
        val theme = ThemeManager.getCurrentTheme().name
        if (applied && (!isActive || theme != appliedTheme)) {
            // A theme change without a restart: restore the recorded values, then let
            // applyTheme() set the values of the current theme on the restored views (and call apply() again).
            restoreAll()
            removeAddedViews(main)
            applied = false
            if (!reapplying) {
                reapplying = true
                try {
                    main.applyTheme()
                } finally {
                    reapplying = false
                }
            }
            return
        }
        if (!isActive) return
        val first = !applied
        applied = true
        appliedTheme = theme
        val d = main.resources.displayMetrics.density
        try {
            // applyTheme() runs at each screen change, so do only the work that can have an effect:
            // the status bar again only when its views changed, Now Playing only while it shows
            // (its onEnter sets colours and the tilt again) or at the first apply (sizes for the lyrics box).
            val statusKey = statusBarKey(main)
            if (first || statusKey != appliedStatusKey) {
                applyStatusBar(main, d)
                appliedStatusKey = statusBarKey(main)
            }
            keepStatusBarFull(main.findViewById(R.id.layout_status_bar))
            applyScreenPaddings(main, d)
            if (first || main.findViewById<View>(R.id.layout_player_mode)?.visibility == View.VISIBLE) {
                IpodClassicNowPlaying.apply(main, d)
            }
        } catch (_: Exception) {}
    }

    private var appliedStatusKey: String? = null

    // Other code changes only the width, the background and the title text of the status bar
    private fun statusBarKey(main: MainActivity): String {
        val bar = main.findViewById<ViewGroup>(R.id.layout_status_bar)
        val group = main.findViewById<ViewGroup>(R.id.layout_status_right_group)
        return "${bar?.childCount}|${group?.childCount}|${main.resources.displayMetrics.density}"
    }

    // Status bar

    private fun applyStatusBar(main: MainActivity, d: Float) {
        val bar = main.findViewById<RelativeLayout>(R.id.layout_status_bar) ?: return
        val padLeft = px(IpodClassicMetrics.STATUS_PADDING_LEFT, d)
        val padRight = px(IpodClassicMetrics.STATUS_PADDING_RIGHT, d)
        setHeight(bar, px(IpodClassicMetrics.STATUS_BAR_HEIGHT, d))
        setPadding(bar, padLeft, 0, padRight, 0)
        // The 1 px line spans the full width, past the padding.
        setClipToPadding(bar, false, true)

        val bodyHeight = (IpodClassicMetrics.STATUS_BAR_HEIGHT - IpodClassicMetrics.STATUS_BAR_RULE) * d
        val titleSize = IpodClassicMetrics.STATUS_TITLE_SP * d
        main.findViewById<TextView>(R.id.tv_status_clock)?.let {
            styleCentredText(it, titleSize, ThemeManager.getCustomFontBold(), bodyHeight)
        }

        val group = main.findViewById<LinearLayout>(R.id.layout_status_right_group) ?: return
        var batteryIndex = -1
        for (i in 0 until group.childCount) {
            val child = group.getChildAt(i)
            when (child) {
                is BatteryIconView -> {
                    batteryIndex = i
                    setWidth(child, px(IpodClassicMetrics.BATTERY_WIDTH, d))
                    setHeight(child, px(IpodClassicMetrics.BATTERY_HEIGHT, d))
                    setMargins(child, 0, 0, 0, px(IpodClassicMetrics.STATUS_BAR_RULE, d))
                }
                // The status bar title and any clock or text added to the right group.
                is TextView -> styleCentredText(child, titleSize, ThemeManager.getCustomFontBold(), bodyHeight)
            }
        }

        if (group.findViewWithTag<View>(TAG_PLAY_ICON) == null && batteryIndex >= 0) {
            val size = px(IpodClassicMetrics.PLAY_ICON_SIZE, d)
            val lp = LinearLayout.LayoutParams(size, size).apply {
                gravity = Gravity.CENTER_VERTICAL
                rightMargin = px(IpodClassicMetrics.PLAY_ICON_GAP, d)
                bottomMargin = px(IpodClassicMetrics.STATUS_BAR_RULE, d)
            }
            group.addView(IpodPlayStateView(main).apply { tag = TAG_PLAY_ICON }, batteryIndex, lp)
        }

        if (bar.findViewWithTag<View>(TAG_STATUS_RULE) == null) {
            val lp = RelativeLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                px(IpodClassicMetrics.STATUS_BAR_RULE, d)
            ).apply {
                addRule(RelativeLayout.ALIGN_PARENT_BOTTOM)
                leftMargin = -padLeft
                rightMargin = -padRight
            }
            bar.addView(IpodStatusRuleView(main).apply { tag = TAG_STATUS_RULE }, lp)
        }
    }

    private fun removeAddedViews(main: MainActivity) {
        for (tag in arrayOf(TAG_PLAY_ICON, TAG_STATUS_RULE)) {
            val bar = main.findViewById<View>(R.id.layout_status_bar) ?: continue
            val v = bar.findViewWithTag<View>(tag) ?: continue
            (v.parent as? ViewGroup)?.removeView(v)
        }
    }

    /**
     * Keeps the status bar at the full width (the controllers set half the width for the split screens)
     * and opaque in Cover Flow (applyTheme and Cover Flow make it transparent there). It stays transparent
     * over the video player. Called at each apply and before each frame ([IpodStatusRuleView]), because
     * the controllers also change it outside applyTheme(). Returns true when it changed the bar.
     */
    internal fun keepStatusBarFull(bar: View?): Boolean {
        if (bar == null || !isActive) return false
        var changed = false
        val lp = bar.layoutParams
        if (lp != null && lp.width != ViewGroup.LayoutParams.MATCH_PARENT) {
            lp.width = ViewGroup.LayoutParams.MATCH_PARENT
            bar.layoutParams = lp
            changed = true
        }
        if (MainActivity.instance?.currentScreenState == MainActivity.STATE_COVERFLOW && isTransparent(bar.background)) {
            bar.background = ThemeManager.createStatusBarBackground()
            changed = true
        }
        return changed
    }

    internal fun isTransparent(bg: Drawable?): Boolean =
        bg == null || (bg is ColorDrawable && Color.alpha(bg.color) == 0)

    // Screens whose content starts below the status bar.

    private val belowStatusBarIds = intArrayOf(
        R.id.layout_settings_mode,
        R.id.layout_storage_mode,
        R.id.layout_bluetooth_mode,
        R.id.layout_wifi_mode,
        R.id.layout_videos_left_pane
    )

    private fun applyScreenPaddings(main: MainActivity, d: Float) {
        val views = ArrayList<View>()
        for (id in belowStatusBarIds) main.findViewById<View>(id)?.let { views.add(it) }
        // The browser list pane (MainActivityControllers moves the list into it).
        (main.findViewById<View>(R.id.container_browser_items)?.parent?.parent as? View)?.let { views.add(it) }
        for (v in views) {
            // Restore to the status bar height of the default layout at that time.
            record(v, "statusPadding") {
                it.setPadding(it.paddingLeft, px(ClassicMenuStyle.STATUS_HEIGHT_DP, d), it.paddingRight, it.paddingBottom)
            }
            v.setPadding(v.paddingLeft, px(IpodClassicMetrics.STATUS_BAR_HEIGHT, d), v.paddingRight, v.paddingBottom)
        }
        // The cover panes of the split screens start below the full-width status bar.
        for (id in coverPaneIds) {
            main.findViewById<View>(id)?.let { setMargins(it, top = px(IpodClassicMetrics.STATUS_BAR_HEIGHT, d)) }
        }
    }

    private val coverPaneIds = intArrayOf(R.id.frame_browser_cover, R.id.frame_video_cover)

    // Text helpers

    /**
     * Gives [tv] the [height] (default: the full height of its row) and a top padding that puts the centre
     * of its capital letters on the centre of a box [boxHeight] tall that starts at the top of [tv].
     */
    internal fun styleCentredText(
        tv: TextView,
        sizePx: Float,
        typeface: Typeface?,
        boxHeight: Float,
        height: Int = ViewGroup.LayoutParams.MATCH_PARENT,
        hGravity: Int = horizontalGravity(tv)
    ) {
        setTextSizePx(tv, sizePx)
        if (typeface != null) setTypeface(tv, typeface)
        setIncludeFontPadding(tv, false)
        setHeight(tv, height)
        setTextGravity(tv, hGravity or Gravity.TOP)
        setPadding(tv, tv.paddingLeft, capCentredPaddingTop(tv, boxHeight), tv.paddingRight, 0)
    }

    /** Top padding for [tv] (gravity TOP, no font padding) that centres its capitals in [boxHeight]. */
    fun capCentredPaddingTop(tv: TextView, boxHeight: Float): Int {
        val paint = tv.paint
        // The layout puts the baseline at paddingTop - FontMetricsInt.ascent; the cap height is measured
        // as a path because getTextBounds() rounds to whole pixels.
        val path = android.graphics.Path()
        paint.getTextPath("H", 0, 1, 0f, 0f, path)
        val bounds = android.graphics.RectF()
        path.computeBounds(bounds, true)
        return IpodClassicMetrics.capCentredPaddingTop(boxHeight, paint.fontMetricsInt.ascent, -bounds.top)
            .coerceAtLeast(0)
    }

    internal fun horizontalGravity(tv: TextView): Int {
        val h = tv.gravity and Gravity.RELATIVE_HORIZONTAL_GRAVITY_MASK
        return if (h == 0) Gravity.LEFT else h
    }

    internal fun px(dp: Int, density: Float): Int = (dp * density).roundToInt()

    // Recorded property changes

    private val restorers = WeakHashMap<View, LinkedHashMap<String, (View) -> Unit>>()

    /** Records how to restore [key] of [v]; only the first record of each key is kept (the original value). */
    internal fun record(v: View, key: String, restore: (View) -> Unit) {
        val map = restorers.getOrPut(v) { LinkedHashMap() }
        if (!map.containsKey(key)) map[key] = restore
    }

    private fun restoreAll() {
        val entries = ArrayList(restorers.entries)
        restorers.clear()
        for ((view, map) in entries) {
            for (restore in map.values) {
                try {
                    restore(view)
                } catch (_: Exception) {}
            }
        }
    }

    internal fun setHeight(v: View, height: Int) {
        val lp = v.layoutParams ?: return
        val old = lp.height
        record(v, "height") { it.layoutParams?.let { p -> p.height = old; it.layoutParams = p } }
        if (lp.height != height) {
            lp.height = height
            v.layoutParams = lp
        }
    }

    internal fun setWidth(v: View, width: Int) {
        val lp = v.layoutParams ?: return
        val old = lp.width
        record(v, "width") { it.layoutParams?.let { p -> p.width = old; it.layoutParams = p } }
        if (lp.width != width) {
            lp.width = width
            v.layoutParams = lp
        }
    }

    internal fun setWeight(v: View, weight: Float) {
        val lp = v.layoutParams as? LinearLayout.LayoutParams ?: return
        val old = lp.weight
        record(v, "weight") { (it.layoutParams as? LinearLayout.LayoutParams)?.let { p -> p.weight = old; it.layoutParams = p } }
        if (lp.weight != weight) {
            lp.weight = weight
            v.layoutParams = lp
        }
    }

    /** Sets the margins that are not null. Start and end follow left and right (the UI is left-to-right). */
    internal fun setMargins(v: View, left: Int? = null, top: Int? = null, right: Int? = null, bottom: Int? = null) {
        val lp = v.layoutParams as? ViewGroup.MarginLayoutParams ?: return
        val old = intArrayOf(lp.leftMargin, lp.topMargin, lp.rightMargin, lp.bottomMargin, lp.marginStart, lp.marginEnd)
        record(v, "margins") {
            (it.layoutParams as? ViewGroup.MarginLayoutParams)?.let { p ->
                p.setMargins(old[0], old[1], old[2], old[3])
                p.marginStart = old[4]
                p.marginEnd = old[5]
                it.layoutParams = p
            }
        }
        var changed = false
        if (left != null && (lp.leftMargin != left || lp.marginStart != left)) {
            lp.leftMargin = left; lp.marginStart = left; changed = true
        }
        if (top != null && lp.topMargin != top) {
            lp.topMargin = top; changed = true
        }
        if (right != null && (lp.rightMargin != right || lp.marginEnd != right)) {
            lp.rightMargin = right; lp.marginEnd = right; changed = true
        }
        if (bottom != null && lp.bottomMargin != bottom) {
            lp.bottomMargin = bottom; changed = true
        }
        if (changed) v.layoutParams = lp
    }

    internal fun setPadding(v: View, left: Int, top: Int, right: Int, bottom: Int) {
        val old = intArrayOf(v.paddingLeft, v.paddingTop, v.paddingRight, v.paddingBottom)
        record(v, "padding") { it.setPadding(old[0], old[1], old[2], old[3]) }
        if (v.paddingLeft != left || v.paddingTop != top || v.paddingRight != right || v.paddingBottom != bottom) {
            v.setPadding(left, top, right, bottom)
        }
    }

    /** ViewGroup has no clipToPadding getter on API 19, so the caller gives the default value to restore. */
    internal fun setClipToPadding(v: ViewGroup, clip: Boolean, restoreTo: Boolean) {
        record(v, "clipToPadding") { (it as ViewGroup).clipToPadding = restoreTo }
        v.clipToPadding = clip
    }

    /** LinearLayout has no gravity getter on API 19, so the caller gives the value to restore. */
    internal fun setLinearGravity(v: LinearLayout, gravity: Int, restoreTo: Int) {
        record(v, "linearGravity") { (it as LinearLayout).gravity = restoreTo }
        v.gravity = gravity
    }

    internal fun setTextSizePx(tv: TextView, sizePx: Float) {
        val old = tv.textSize
        record(tv, "textSize") { (it as TextView).setTextSize(TypedValue.COMPLEX_UNIT_PX, old) }
        if (tv.textSize != sizePx) tv.setTextSize(TypedValue.COMPLEX_UNIT_PX, sizePx)
    }

    internal fun setTypeface(tv: TextView, typeface: Typeface) {
        val old = tv.typeface
        record(tv, "typeface") { (it as TextView).typeface = old }
        if (tv.typeface !== typeface) tv.typeface = typeface
    }

    internal fun setIncludeFontPadding(tv: TextView, include: Boolean) {
        val old = tv.includeFontPadding
        record(tv, "includeFontPadding") { (it as TextView).includeFontPadding = old }
        if (tv.includeFontPadding != include) tv.includeFontPadding = include
    }

    internal fun setTextGravity(tv: TextView, gravity: Int) {
        val old = tv.gravity
        record(tv, "gravity") { (it as TextView).gravity = old }
        if (tv.gravity != gravity) tv.gravity = gravity
    }

    internal fun setTextColor(tv: TextView, color: Int) {
        val old: ColorStateList = tv.textColors
        record(tv, "textColor") { (it as TextView).setTextColor(old) }
        if (tv.currentTextColor != color) tv.setTextColor(color)
    }
}
