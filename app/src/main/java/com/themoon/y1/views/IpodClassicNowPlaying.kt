package com.themoon.y1.views

import com.themoon.y1.Prefs
import android.content.Context
import android.content.SharedPreferences
import android.graphics.PorterDuff
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.text.Editable
import android.text.TextPaint
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewTreeObserver
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import com.themoon.y1.MainActivity
import com.themoon.y1.R
import com.themoon.y1.ThemeManager
import java.lang.ref.WeakReference
import java.util.Collections
import java.util.WeakHashMap
import com.themoon.y1.views.IpodClassicLayout as L
import com.themoon.y1.views.IpodClassicMetrics as M

/**
 * Now Playing in the iPod Classic layout: tilted art at (15, 52), text column at x = 240 with fixed
 * baselines, rating stars under the album, shuffle/repeat icons under the status bar on the right,
 * and the progress bar and times at the iPod positions. All changes are recorded by [IpodClassicLayout].
 */
object IpodClassicNowPlaying {
    // Dark grey on a white Now Playing screen, light grey on a dark one (Classic Dark without a restart)
    private fun detailColor(): Int {
        val bg = MainActivity.instance?.findViewById<View>(R.id.layout_player_mode)?.background as? ColorDrawable
        val c = bg?.color ?: return 0xFF333333.toInt()
        val dark = 0.299 * ((c shr 16) and 0xFF) + 0.587 * ((c shr 8) and 0xFF) + 0.114 * (c and 0xFF) < 128
        return if (dark) 0xFFCCCCCC.toInt() else 0xFF333333.toInt()
    }
    private const val STAR = "★"
    private const val HOLLOW_STAR = '☆'

    private val hooked: MutableSet<View> = Collections.newSetFromMap(WeakHashMap())
    private var repeatIcon: WeakReference<ImageView>? = null
    private val listenedPrefs = ArrayList<SharedPreferences>()

    // Repeat One and Repeat All have different icons. The mode is stored in AppPrefs; older
    // Now Playing code also writes it to y1_settings, so both are watched.
    private val repeatListener = SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
        if (key == Prefs.REPEAT_MODE.key && L.isActive) {
            repeatIcon?.get()?.let { setModeIcon(it, repeatIconRes(Prefs.REPEAT_MODE.get(prefs))) }
        }
    }

    fun apply(main: MainActivity, d: Float) {
        val art = main.findViewById<View>(R.id.frame_album_art) ?: return
        val artColumn = art.parent as? LinearLayout ?: return
        val row = artColumn.parent as? LinearLayout ?: return
        val content = row.parent as? LinearLayout ?: return
        val title = main.findViewById<TextView>(R.id.tv_player_title) ?: return
        val textColumn = title.parent as? LinearLayout ?: return

        val side = px(M.ART_SIZE, d)
        L.setLinearGravity(content, Gravity.TOP or Gravity.LEFT, Gravity.CENTER_VERTICAL)
        L.setPadding(content, px(M.ART_LEFT, d), px(M.ART_TOP, d), px(M.TEXT_RIGHT_PADDING, d), 0)
        L.setMargins(artColumn, top = 0, right = px(M.TEXT_LEFT - M.ART_LEFT - M.ART_SIZE, d))
        L.setWidth(art, side)
        L.setHeight(art, side)
        val reflection = main.findViewById<View>(R.id.frame_album_reflection)
        if (reflection != null) {
            L.setWidth(reflection, side)
            L.setHeight(reflection, px(M.REFLECTION_HEIGHT, d))
            L.setMargins(reflection, top = 0)
            main.findViewById<View>(R.id.iv_album_reflection)?.let {
                L.setWidth(it, side)
                L.setHeight(it, side)
            }
        }
        applyTilt(main, art, reflection, side)

        L.setMargins(textColumn, top = 0)
        styleText(main, d)
        layoutText(textColumn, d)
        if (hooked.add(textColumn)) {
            // The rating line comes and goes with the song; keep the other lines at their baselines.
            val ref = WeakReference(textColumn)
            textColumn.viewTreeObserver.addOnGlobalLayoutListener(ViewTreeObserver.OnGlobalLayoutListener {
                val column = ref.get() ?: return@OnGlobalLayoutListener
                // The listener runs at each layout of the window; Now Playing needs it only while it shows
                if (column.isShown && L.isActive) layoutText(column, column.resources.displayMetrics.density)
            })
        }

        applyModeIcons(main, d)
        applyBottomRows(main, d)
    }

    // Album art

    private fun applyTilt(main: MainActivity, art: View, reflection: View?, side: Int) {
        val tiltOn = Prefs.ALBUM_TILT.get(main.prefs)
        val tilt = M.artTilt(side.toFloat())
        val cameraDistance = M.cameraDistanceSetting(tilt.cameraDistancePx, main.resources.displayMetrics.densityDpi)
        // The reflection turns about the same axis as the art: the art's centre line, above the reflection.
        val pivots = listOf(art to side / 2f, reflection to -(side / 2f))
        for ((v, pivotY) in pivots) {
            if (v == null) continue
            recordTransform(v)
            if (tiltOn) {
                v.pivotX = 0f
                v.pivotY = pivotY
                v.scaleX = tilt.scaleX
                v.cameraDistance = cameraDistance
                v.rotationY = tilt.rotationY
            } else {
                v.rotationY = 0f
                v.scaleX = 1f
                v.pivotX = (v.layoutParams?.width ?: 0) / 2f
                v.pivotY = (v.layoutParams?.height ?: 0) / 2f
            }
        }
    }

    private fun recordTransform(v: View) {
        val rotationY = v.rotationY
        val scaleX = v.scaleX
        val cameraDistance = v.cameraDistance
        L.record(v, "transform") {
            it.rotationY = rotationY
            it.scaleX = scaleX
            it.cameraDistance = cameraDistance
            // Back to the default centre pivot of the restored size.
            it.pivotX = (it.layoutParams?.width ?: it.width) / 2f
            it.pivotY = (it.layoutParams?.height ?: it.height) / 2f
        }
    }

    // Text column

    private fun styleText(main: MainActivity, d: Float) {
        val bold = ThemeManager.getCustomFontBold()
        main.findViewById<TextView>(R.id.tv_player_title)?.let {
            L.setTextSizePx(it, M.NP_TITLE_SP * d)
            L.setTypeface(it, bold)
            L.setIncludeFontPadding(it, false)
        }
        for (id in intArrayOf(R.id.tv_player_artist, R.id.tv_player_album, R.id.tv_player_track_count, R.id.tv_player_play_on)) {
            main.findViewById<TextView>(id)?.let {
                L.setTextSizePx(it, M.NP_DETAIL_SP * d)
                L.setTypeface(it, bold)
                L.setIncludeFontPadding(it, false)
                L.setTextColor(it, detailColor())
            }
        }
        main.findViewById<TextView>(R.id.tv_player_rating)?.let { rating ->
            // Stars come from a fallback font; size them by their measured height.
            val base = M.NP_DETAIL_SP * d
            val bounds = Rect()
            val probe = TextPaint(rating.paint).apply { textSize = base }
            probe.getTextBounds(STAR, 0, STAR.length, bounds)
            L.setTextSizePx(rating, M.scaleTextSizeToHeight(base, bounds.height().toFloat(), M.NP_RATING_HEIGHT * d))
            L.setIncludeFontPadding(rating, false)
            L.setTextColor(rating, detailColor())
            if (hooked.add(rating)) rating.addTextChangedListener(HollowStarRemover)
            val text = rating.text.toString()
            if (text.indexOf(HOLLOW_STAR) >= 0) rating.text = text.substringBefore(HOLLOW_STAR)
        }
    }

    /** The iPod shows only the filled stars. */
    private object HollowStarRemover : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        override fun afterTextChanged(s: Editable?) {
            if (L.isActive) stripHollowStars(s)
        }
    }

    private fun stripHollowStars(s: Editable?) {
        if (s == null) return
        val i = s.indexOf(HOLLOW_STAR)
        if (i >= 0) s.delete(i, s.length)
    }

    /** Puts each visible line of the text column at its iPod baseline (or centre, for the stars). */
    private fun layoutText(column: LinearLayout, d: Float) {
        val lines = ArrayList<TextView>()
        val tops = ArrayList<Int>()
        val heights = ArrayList<Int>()
        for (i in 0 until column.childCount) {
            val tv = column.getChildAt(i) as? TextView ?: continue
            if (tv.visibility == View.GONE) continue
            val fm = tv.paint.fontMetricsInt
            val baseline = when (tv.id) {
                R.id.tv_player_title -> px(M.NP_TITLE_BASELINE, d)
                R.id.tv_player_artist -> px(M.NP_ARTIST_BASELINE, d)
                R.id.tv_player_album -> px(M.NP_ALBUM_BASELINE, d)
                R.id.tv_player_track_count -> px(M.NP_COUNT_BASELINE, d)
                R.id.tv_player_play_on -> px(M.NP_PLAY_ON_BASELINE, d)
                R.id.tv_player_rating -> {
                    val b = Rect()
                    tv.paint.getTextBounds(STAR, 0, STAR.length, b)
                    M.baselineForGlyphCentre(px(M.NP_RATING_CENTRE, d), b.top, b.bottom)
                }
                else -> continue
            }
            lines.add(tv)
            tops.add(M.textTopForBaseline(baseline, fm.ascent))
            heights.add(fm.descent - fm.ascent)
        }
        val margins = M.stackTopMargins(px(M.ART_TOP, d), tops.toIntArray(), heights.toIntArray())
        for (i in lines.indices) L.setMargins(lines[i], top = margins[i])
    }

    // Shuffle and repeat state icons, under the status bar on the right.

    private fun applyModeIcons(main: MainActivity, d: Float) {
        val shuffle = main.findViewById<ImageView>(R.id.iv_player_shuffle_status) ?: return
        val repeat = main.findViewById<ImageView>(R.id.iv_player_repeat_status) ?: return
        val box = px(M.MODE_ICON_BOX, d)
        (shuffle.parent?.parent as? View)?.let { bar ->
            L.setPadding(bar, bar.paddingLeft, px(M.MODE_ICON_CENTRE_Y, d) - box / 2, px(M.MODE_ICON_RIGHT, d), bar.paddingBottom)
        }
        for (iv in arrayOf(shuffle, repeat)) {
            L.setWidth(iv, box)
            L.setHeight(iv, box)
        }
        // The glyphs have their own side bearings, which give about the iPod's gap.
        L.setMargins(shuffle, right = 0)
        setModeIcon(shuffle, R.drawable.ic_shuffle)
        setModeIcon(repeat, repeatIconRes(Prefs.REPEAT_MODE.get(main.prefs)))
        repeatIcon = WeakReference(repeat)
        if (listenedPrefs.isEmpty()) {
            for (name in arrayOf("AppPrefs", "y1_settings")) {
                val prefs = main.getSharedPreferences(name, Context.MODE_PRIVATE)
                prefs.registerOnSharedPreferenceChangeListener(repeatListener)
                listenedPrefs.add(prefs)
            }
        }
    }

    private fun repeatIconRes(mode: Int): Int = if (mode == 1) R.drawable.ic_repeat_one else R.drawable.ic_repeat

    private fun setModeIcon(iv: ImageView, res: Int) {
        val oldDrawable = iv.drawable
        val oldFilter = iv.colorFilter
        L.record(iv, "image") {
            (it as ImageView).setImageDrawable(oldDrawable)
            it.colorFilter = oldFilter
            it.setTag(R.id.iv_player_repeat_status, null)
        }
        // A new drawable decodes the resource again and asks for a layout; only change it when it differs
        if (iv.getTag(R.id.iv_player_repeat_status) != res) {
            iv.setImageResource(res)
            iv.setTag(R.id.iv_player_repeat_status, res)
        }
        iv.setColorFilter(detailColor(), PorterDuff.Mode.SRC_IN)
        iv.scaleType = ImageView.ScaleType.FIT_CENTER
    }

    // Progress bar, scrub bar and volume bar

    private fun applyBottomRows(main: MainActivity, d: Float) {
        val dm = main.resources.displayMetrics
        val screenWidth = dm.widthPixels
        val screenHeightDp = (dm.heightPixels / d).toInt()
        // The time labels are TIME_BOX_HEIGHT tall and centred on the bar, so the row is that tall.
        val timeRowBottom = screenHeightDp - M.PROGRESS_TOP - (M.PROGRESS_HEIGHT + M.TIME_BOX_HEIGHT) / 2
        styleTimeRow(main, d, screenWidth, timeRowBottom,
            R.id.row_player_progress, R.id.tv_player_time_current, R.id.player_progress, R.id.tv_player_time_total)
        styleTimeRow(main, d, screenWidth, timeRowBottom,
            R.id.row_player_seek, R.id.tv_player_seek_current, R.id.seek_player_scrub, R.id.tv_player_seek_total)

        // Volume: the same bar between the speaker icons (16 + 10 px left, 10 + 24 px right).
        main.findViewById<View>(R.id.row_player_volume)?.let { row ->
            val left = px(M.PROGRESS_LEFT - 10 - 16, d)
            val right = screenWidth - px(M.PROGRESS_LEFT + M.PROGRESS_WIDTH + 10 + 24, d)
            L.setPadding(row, left, row.paddingTop, right, px(screenHeightDp - M.PROGRESS_TOP - M.PROGRESS_HEIGHT, d))
            main.findViewById<View>(R.id.player_volume_progress)?.let { L.setHeight(it, px(M.PROGRESS_HEIGHT, d)) }
        }
    }

    private fun styleTimeRow(
        main: MainActivity, d: Float, screenWidth: Int, bottomDp: Int,
        rowId: Int, leftId: Int, barId: Int, rightId: Int
    ) {
        val row = main.findViewById<View>(rowId) ?: return
        val left = main.findViewById<TextView>(leftId) ?: return
        val bar = main.findViewById<View>(barId) ?: return
        val right = main.findViewById<TextView>(rightId) ?: return
        val box = px(M.TIME_BOX_HEIGHT, d)
        L.setPadding(row, px(M.TIME_LEFT, d), row.paddingTop, screenWidth - px(M.TIME_RIGHT, d), px(bottomDp, d))
        val bold = ThemeManager.getCustomFontBold()
        val size = M.TIME_SP * d
        L.setWidth(left, px(M.PROGRESS_LEFT - M.TIME_LEFT, d))
        L.setMargins(left, left = 0, right = 0)
        L.styleCentredText(left, size, bold, box.toFloat(), height = box, hGravity = Gravity.LEFT)
        L.setWeight(bar, 0f)
        if (bar is SeekBar) {
            styleScrubBar(bar, d)
        } else {
            L.setWidth(bar, px(M.PROGRESS_WIDTH, d))
            L.setHeight(bar, px(M.PROGRESS_HEIGHT, d))
        }
        L.setWidth(right, px(M.TIME_RIGHT - M.PROGRESS_LEFT - M.PROGRESS_WIDTH, d))
        L.setMargins(right, left = 0, right = 0)
        L.styleCentredText(right, size, bold, box.toFloat(), height = box, hGravity = Gravity.RIGHT)
    }

    /**
     * The scrub bar shows the bar at the progress bar position and adds the diamond ([ScrubBarGeometry]).
     * The view is larger than the bar by the outsets: the bar is inside the padding, and negative margins
     * keep it at the progress bar position. The default SeekBar padding (16 px each side) and thumb
     * position made the bar shorter and put the diamond off the end of the fill.
     */
    private fun styleScrubBar(bar: SeekBar, d: Float) {
        val barWidth = px(M.PROGRESS_WIDTH, d)
        val barHeight = px(M.PROGRESS_HEIGHT, d)
        val diamondWidth = px(M.SCRUB_DIAMOND_WIDTH, d)
        val diamondHeight = px(M.SCRUB_DIAMOND_HEIGHT, d)
        val ox = ScrubBarGeometry.outsetX(diamondWidth)
        val oy = ScrubBarGeometry.outsetY(diamondHeight, barHeight)
        L.setWidth(bar, barWidth + 2 * ox)
        L.setHeight(bar, barHeight + 2 * oy)
        L.setMargins(bar, left = -ox, right = -ox)
        L.setPadding(bar, ox, oy, ox, oy)

        val oldThumb = bar.thumb
        val oldOffset = bar.thumbOffset
        L.record(bar, "thumb") {
            (it as SeekBar).thumb = oldThumb
            it.thumbOffset = oldOffset
        }
        val thumb = bar.thumb as? ScrubDiamondDrawable
        if (thumb == null || thumb.diamondWidth != diamondWidth || thumb.diamondHeight != diamondHeight) {
            bar.thumb = ScrubDiamondDrawable(bar, diamondWidth, diamondHeight)
        }
        // The thumb is drawn with the origin at the bar's top-left corner (paddingLeft - thumbOffset, paddingTop).
        bar.thumbOffset = 0
    }

    private fun px(dp: Int, d: Float): Int = L.px(dp, d)
}
