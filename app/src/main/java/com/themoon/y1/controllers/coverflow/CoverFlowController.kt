package com.themoon.y1.controllers.coverflow

import android.app.Activity
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.themoon.y1.R
import com.themoon.y1.ThemeManager
import com.themoon.y1.controllers.ScreenController
import com.themoon.y1.managers.AlbumCoverManager
import com.themoon.y1.models.SongItem
import com.themoon.y1.navigation.ScreenState
import com.themoon.y1.views.CoverFlowLayout
import com.themoon.y1.views.SmoothEdgeImageView

/**
 * Controller for the 3D CoverFlow carousel view.
 * Uses a 9-slot sliding buffer (-4 to +4) to prevent pop-in at the edges,
 * renders reflected album cards, and binds album art asynchronously via AlbumCoverManager.
 */
class CoverFlowController(
    private val activity: Activity,
    override val rootView: ViewGroup,
    private val listVirtualSongs: View?,
    private val uniqueAlbumList: List<SongItem>,
    private val clickFeedback: () -> Unit,
    private val onAlbumSelected: (albumItem: SongItem) -> Unit,
    private val onNavigateBack: () -> Unit,
    private val onRebuildUniqueAlbumList: (() -> Unit)? = null,
    private val localize: (String) -> String
) : ScreenController {

    private val containerBrowserItems: LinearLayout = rootView.findViewById(R.id.container_browser_items)
    private val scrollViewBrowser: View? = containerBrowserItems.parent as? View
    private val tvBrowserPath: TextView = rootView.findViewById(R.id.tv_browser_path)

    private val visibleCoversCount = 9
    var currentCoverFlowIndex = 0

    private var coverFlowContainer: CoverFlowLayout? = null
    private var cfViews: Array<View?>? = null

    private var coverFlowActiveDirection = 0
    private var coverFlowDirectionChangeTime = 0L
    private var lastCoverFlowWheelTime = 0L

    override fun onEnter(state: ScreenState) {
        buildCoverFlowUI()
    }

    override fun onRefreshUI() {
        buildCoverFlowUI()
    }

    override fun onThemeChanged() {
        // A rebuild takes about 1.2 s; only the card texts take theme colours
        for (card in cfViews ?: return) {
            card?.findViewById<TextView>(TITLE_VIEW_ID)?.apply {
                setTextColor(ThemeManager.getTextColorPrimary())
                typeface = ThemeManager.getCustomFontBold()
            }
            card?.findViewById<TextView>(ARTIST_VIEW_ID)?.setTextColor(ThemeManager.getTextColorSecondary())
        }
    }

    override fun onExit() {
        cancelAllAnimations()
        coverFlowContainer?.removeAllViews()
        containerBrowserItems.removeAllViews()
    }

    fun buildCoverFlowUI() {
        val statusBar = activity.findViewById<View>(R.id.layout_status_bar)
        statusBar?.setBackgroundColor(0x00000000)

        // Ensure split-screen right cover pane from browser mode is hidden in Cover Flow
        rootView.findViewById<View>(R.id.frame_browser_cover)?.visibility = View.GONE

        scrollViewBrowser?.visibility = View.VISIBLE
        if (scrollViewBrowser is ScrollView) {
            scrollViewBrowser.scrollTo(0, 0)
        }
        listVirtualSongs?.visibility = View.GONE
        containerBrowserItems.removeAllViews()

        if (uniqueAlbumList.isEmpty()) {
            onRebuildUniqueAlbumList?.invoke()
        }

        AlbumCoverManager.getInstance().pregenerateThumbnails(uniqueAlbumList, false)

        if (uniqueAlbumList.isEmpty()) {
            val tvEmpty = TextView(activity).apply {
                text = localize("No Albums Found")
                textSize = 18f
                setTextColor(ThemeManager.getTextColorSecondary())
                gravity = Gravity.CENTER
                setPadding(0, 50, 0, 0)
            }
            containerBrowserItems.addView(tvEmpty)
            return
        }

        if (currentCoverFlowIndex >= uniqueAlbumList.size) {
            currentCoverFlowIndex = 0
        }

        val d = activity.resources.displayMetrics.density
        val cfLayout = CoverFlowLayout(activity).apply {
            clipChildren = false
            clipToPadding = false
            // The cover top is at the iPod position for the space below the status bar.
            childTopForHeight = { height -> (CoverFlowGeometry.coverTop((height / d).toInt()) * d).toInt() }
        }
        coverFlowContainer = cfLayout
        containerBrowserItems.clipChildren = false
        containerBrowserItems.clipToPadding = false
        if (scrollViewBrowser is ViewGroup) {
            scrollViewBrowser.clipChildren = false
            scrollViewBrowser.clipToPadding = false
        }

        // Fill the space below the status bar (the ScrollView fills its viewport), so that
        // the cards are placed in it for each status bar height.
        containerBrowserItems.addView(
            cfLayout,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )

        val views = arrayOfNulls<View>(visibleCoversCount)
        for (i in 0 until visibleCoversCount) {
            val card = createSingleCoverView()
            views[i] = card
            cfLayout.addView(card)
        }
        cfViews = views

        initCoverFlowPositions()
    }

    private fun initCoverFlowPositions() {
        val total = uniqueAlbumList.size
        if (total == 0) return
        val views = cfViews ?: return

        val centerIdx = visibleCoversCount / 2

        for (i in 0 until visibleCoversCount) {
            val offsetFromCenter = i - centerIdx
            val targetIdx = (currentCoverFlowIndex + offsetFromCenter + total * 10) % total
            views[i]?.let { bindCoverData(it, targetIdx) }
        }

        val d = activity.resources.displayMetrics.density

        for (i in 0 until visibleCoversCount) {
            val dist = Math.abs(i - centerIdx)
            val sign = if (i < centerIdx) -1f else 1f

            val transX = sign * getTransXForDist(dist, d)
            val rotY = -sign * getRotYForDist(dist)
            val scale = getScaleForDist(dist)
            val alpha = getAlphaForDist(dist)

            applyTransform(views[i], transX, rotY, scale, alpha)
        }

        arrangeZIndex()

        for (i in 0 until visibleCoversCount) {
            views[i]?.let { setCardTitleAlpha(it, i == centerIdx, 0) }
        }

        val statusTitle = CoverFlowTitle.format(localize("Cover Flow"), currentCoverFlowIndex + 1, total)
        tvBrowserPath.text = statusTitle
        activity.findViewById<TextView>(R.id.tv_status_clock)?.text = statusTitle
    }

    private fun getTransXForDist(dist: Int, d: Float): Float {
        return CoverFlowGeometry.translationX(dist, sideTilt) * d
    }

    private fun getRotYForDist(dist: Int): Float {
        return if (dist == 0) 0f else sideTilt.rotationY
    }

    private fun getScaleForDist(dist: Int): Float {
        return if (dist == 0) 1.0f else sideTilt.scale
    }

    private fun getAlphaForDist(dist: Int): Float {
        return if (dist <= CoverFlowGeometry.SIDE_COUNT) 1.0f else 0.0f
    }

    private fun bindCoverData(card: View, dataIndex: Int) {
        if (uniqueAlbumList.isEmpty() || dataIndex < 0 || dataIndex >= uniqueAlbumList.size) return
        val item = uniqueAlbumList[dataIndex]

        val ivCover = card.findViewById<ImageView>(COVER_VIEW_ID)
        val ivReflection = card.findViewById<ImageView>(REFLECTION_VIEW_ID)
        val tvTitle = card.findViewById<TextView>(TITLE_VIEW_ID)
        val tvArtist = card.findViewById<TextView>(ARTIST_VIEW_ID)

        tvTitle?.text = item.album
        tvArtist?.text = item.artist

        val albumKey = AlbumCoverManager.getAlbumKey(item.file, item.album)
        ivCover?.tag = albumKey

        val manager = AlbumCoverManager.getInstance()
        val cachedBmp = manager.getMemoryCover(albumKey)
        val cachedRef = manager.getMemoryReflection(albumKey)

        if (cachedBmp != null) {
            ivCover?.animate()?.cancel()
            ivCover?.alpha = 1.0f
            ivCover?.setImageBitmap(cachedBmp)
            if (ivReflection != null) {
                ivReflection.animate().cancel()
                ivReflection.alpha = 1.0f
                ivReflection.setImageBitmap(cachedRef)
                ivReflection.visibility = if (cachedRef != null) View.VISIBLE else View.INVISIBLE
            }
            return
        }

        ivCover?.animate()?.cancel()
        ivCover?.alpha = 1.0f
        ivCover?.setImageBitmap(
            ThemeManager.getCustomIcon("icon_default_album.png", activity, R.drawable.default_album)
        )
        if (ivReflection != null) {
            ivReflection.animate().cancel()
            ivReflection.alpha = 1.0f
            ivReflection.setImageBitmap(null)
            ivReflection.visibility = View.INVISIBLE
        }

        manager.loadCoverAsync(item, object : AlbumCoverManager.CoverCallback {
            override fun onCoverLoaded(loadedKey: String, cover: Bitmap?, reflection: Bitmap?) {
                if (albumKey == ivCover?.tag) {
                    if (cover != null) {
                        ivCover.setImageBitmap(cover)
                    }
                    if (ivReflection != null) {
                        if (reflection != null) {
                            ivReflection.setImageBitmap(reflection)
                            ivReflection.visibility = View.VISIBLE
                        } else {
                            ivReflection.visibility = View.INVISIBLE
                        }
                    }
                }
            }
        })
    }

    private fun createSingleCoverView(): View {
        val d = activity.resources.displayMetrics.density
        val coverSize = (CoverFlowGeometry.COVER_SIZE * d).toInt()
        // The cover is at the top of the card, the reflection directly under it, and the text on the reflection.
        val card = FrameLayout(activity)

        // CoverFlowLayout puts the top of the card (the cover top) at the iPod position.
        val lp = FrameLayout.LayoutParams((CoverFlowGeometry.TEXT_WIDTH * d).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        }
        card.layoutParams = lp
        // Scale and turn the side cards about the cover centre (x) and the iPod vanishing line (y).
        // Setting one pivot stops Android from centring the other, so set both.
        card.pivotX = lp.width / 2f
        card.pivotY = coverSize * CoverFlowGeometry.PIVOT_Y_FRACTION
        // The camera distance sets the perspective (Android: distance / densityDpi inches, 72 px per inch).
        card.cameraDistance = sideTilt.cameraDistancePx * d * activity.resources.displayMetrics.densityDpi / 72f
        card.setLayerType(View.LAYER_TYPE_NONE, null)

        // Smooth edges while the cards turn (see SmoothEdges)
        val ivCover = SmoothEdgeImageView(activity).apply {
            id = COVER_VIEW_ID
            layoutParams = FrameLayout.LayoutParams(coverSize, coverSize).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            }
            scaleType = ImageView.ScaleType.CENTER_CROP
        }

        val ivReflection = SmoothEdgeImageView(activity).apply {
            id = REFLECTION_VIEW_ID
            layoutParams = FrameLayout.LayoutParams(coverSize, (CoverFlowGeometry.REFLECTION_HEIGHT * d).toInt()).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                topMargin = coverSize
            }
            scaleType = ImageView.ScaleType.CENTER_CROP
        }

        // Album (bold, primary colour: black on the Classic themes), then artist (regular, secondary colour),
        // on the iPod baselines.
        val tvTitle = createCardText(TITLE_VIEW_ID, CoverFlowGeometry.TITLE_SIZE * d, CoverFlowGeometry.TITLE_BASELINE * d, bold = true)
        val tvArtist = createCardText(ARTIST_VIEW_ID, CoverFlowGeometry.ARTIST_SIZE * d, CoverFlowGeometry.ARTIST_BASELINE * d, bold = false)

        card.addView(ivCover)
        card.addView(ivReflection)
        card.addView(tvTitle)
        card.addView(tvArtist)

        card.setOnClickListener { v ->
            val centerIdx = visibleCoversCount / 2
            val views = cfViews
            if (views != null && v == views[centerIdx] && currentCoverFlowIndex in uniqueAlbumList.indices) {
                clickFeedback()
                val chosen = uniqueAlbumList[currentCoverFlowIndex]
                onAlbumSelected(chosen)
            }
        }

        return card
    }

    /** One line of centred text (bold or regular theme font) whose baseline is [baseline] px below the card top. */
    private fun createCardText(viewId: Int, sizePx: Float, baseline: Float, bold: Boolean): TextView {
        return TextView(activity).apply {
            id = viewId
            setTextColor(if (bold) ThemeManager.getTextColorPrimary() else ThemeManager.getTextColorSecondary())
            setTextSize(TypedValue.COMPLEX_UNIT_PX, sizePx)
            typeface = if (bold) ThemeManager.getCustomFontBold() else ThemeManager.getCustomFont()
            includeFontPadding = false
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            isSingleLine = true
            ellipsize = android.text.TextUtils.TruncateAt.END
            alpha = 0f
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                // Without font padding, the baseline is -ascent below the top of the view.
                topMargin = Math.round(baseline + paint.fontMetricsInt.ascent)
            }
        }
    }

    private fun setCardTitleAlpha(card: View, isCenter: Boolean, duration: Int) {
        val tvTitle = card.findViewById<View>(TITLE_VIEW_ID)
        val tvArtist = card.findViewById<View>(ARTIST_VIEW_ID)
        if (tvTitle != null && tvArtist != null) {
            val targetAlpha = if (isCenter) 1.0f else 0.0f
            if (duration > 0) {
                tvTitle.animate().alpha(targetAlpha).setDuration(duration.toLong()).start()
                tvArtist.animate().alpha(targetAlpha).setDuration(duration.toLong()).start()
            } else {
                tvTitle.alpha = targetAlpha
                tvArtist.alpha = targetAlpha
            }
        }
    }

    private fun applyTransform(v: View?, transX: Float, rotY: Float, scale: Float, alpha: Float) {
        if (v == null) return
        v.animate().cancel()
        v.translationX = transX
        v.rotationY = rotY
        v.scaleX = scale
        v.scaleY = scale
        v.alpha = alpha
    }

    private fun animateTransform(v: View?, transX: Float, rotY: Float, scale: Float, alpha: Float, duration: Int) {
        if (v == null) return
        if (duration <= 0) {
            applyTransform(v, transX, rotY, scale, alpha)
        } else {
            v.animate().translationX(transX).rotationY(rotY).scaleX(scale).scaleY(scale).alpha(alpha)
                .setDuration(duration.toLong()).start()
        }
    }

    private fun arrangeZIndex() {
        coverFlowContainer?.invalidate()
    }

    private fun cancelAllAnimations() {
        cfViews?.forEach { it?.animate()?.cancel() }
    }

    override fun onWheel(delta: Int, event: KeyEvent): Boolean {
        handleCoverFlowWheelInput(if (delta > 0) 22 else 21, event)
        return true
    }

    private fun handleCoverFlowWheelInput(keyCode: Int, event: KeyEvent) {
        val dir = if (keyCode == 22) 1 else -1
        val eventTime = event.eventTime
        val now = SystemClock.uptimeMillis()

        if (now - lastCoverFlowWheelTime > 250) {
            coverFlowActiveDirection = dir
            coverFlowDirectionChangeTime = eventTime
        } else if (coverFlowActiveDirection != 0 && dir != coverFlowActiveDirection) {
            coverFlowActiveDirection = dir
            coverFlowDirectionChangeTime = eventTime
            cancelAllAnimations()
        } else if (coverFlowActiveDirection == 0) {
            coverFlowActiveDirection = dir
            coverFlowDirectionChangeTime = eventTime
        }

        if (dir != coverFlowActiveDirection || eventTime < coverFlowDirectionChangeTime) {
            return
        }

        val lag = now - eventTime
        val diff = now - lastCoverFlowWheelTime
        lastCoverFlowWheelTime = now

        val duration: Int = when {
            lag > 40 || diff < 40 -> 0
            diff < 90 -> 45
            else -> 160
        }

        scrollCoverFlow(dir > 0, duration)

        if (lag <= 40) {
            clickFeedback()
        }
    }

    private fun scrollCoverFlow(isNext: Boolean, duration: Int) {
        val total = uniqueAlbumList.size
        if (total == 0) return
        val views = cfViews ?: return

        val d = activity.resources.displayMetrics.density
        val centerIdx = visibleCoversCount / 2

        val offScreenX = getTransXForDist(centerIdx, d)
        val offScreenRot = getRotYForDist(centerIdx)
        val offScreenScale = getScaleForDist(centerIdx)

        if (isNext) {
            currentCoverFlowIndex = (currentCoverFlowIndex + 1) % total
            val oldLeft = views[0]

            for (i in 0 until visibleCoversCount - 1) {
                views[i] = views[i + 1]
            }
            views[visibleCoversCount - 1] = oldLeft

            val recycled = views[visibleCoversCount - 1]
            if (recycled != null) {
                bindCoverData(recycled, (currentCoverFlowIndex + centerIdx + total * 10) % total)
                applyTransform(recycled, offScreenX, -offScreenRot, offScreenScale, 0f)
            }
        } else {
            currentCoverFlowIndex = (currentCoverFlowIndex - 1 + total) % total
            val oldRight = views[visibleCoversCount - 1]

            for (i in visibleCoversCount - 1 downTo 1) {
                views[i] = views[i - 1]
            }
            views[0] = oldRight

            val recycled = views[0]
            if (recycled != null) {
                bindCoverData(recycled, (currentCoverFlowIndex - centerIdx + total * 10) % total)
                applyTransform(recycled, -offScreenX, offScreenRot, offScreenScale, 0f)
            }
        }

        arrangeZIndex()

        for (i in 0 until visibleCoversCount) {
            views[i]?.let { setCardTitleAlpha(it, i == centerIdx, duration) }

            val dist = Math.abs(i - centerIdx)
            val sign = if (i < centerIdx) -1f else 1f

            val transX = sign * getTransXForDist(dist, d)
            val rotY = -sign * getRotYForDist(dist)
            val scale = getScaleForDist(dist)
            val alpha = getAlphaForDist(dist)

            animateTransform(views[i], transX, rotY, scale, alpha, duration)
        }

        val statusTitle = CoverFlowTitle.format(localize("Cover Flow"), currentCoverFlowIndex + 1, total)
        tvBrowserPath.text = statusTitle
        activity.findViewById<TextView>(R.id.tv_status_clock)?.text = statusTitle
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && (event.keyCode == 21 || event.keyCode == 22)) {
            handleCoverFlowWheelInput(event.keyCode, event)
            return true
        }
        return false
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == 21 || keyCode == 22) {
            handleCoverFlowWheelInput(keyCode, event)
            return true
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
            if (currentCoverFlowIndex in uniqueAlbumList.indices) {
                clickFeedback()
                val chosen = uniqueAlbumList[currentCoverFlowIndex]
                onAlbumSelected(chosen)
            }
            return true
        }
        return false
    }

    override fun onBackPressed(): Boolean {
        onNavigateBack()
        return true
    }

    companion object {
        private const val COVER_VIEW_ID = 1001
        private val TITLE_VIEW_ID = R.id.coverflow_title
        private val ARTIST_VIEW_ID = R.id.coverflow_artist
        private const val REFLECTION_VIEW_ID = 1004

        // Scale, angle and perspective of the side covers (see CoverFlowGeometry)
        private val sideTilt = CoverFlowGeometry.sideTilt()
    }
}
