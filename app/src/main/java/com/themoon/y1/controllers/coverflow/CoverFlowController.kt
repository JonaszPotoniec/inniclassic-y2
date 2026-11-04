package com.themoon.y1.controllers.coverflow

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.os.Handler
import android.os.Looper
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
import com.themoon.y1.MainActivity
import com.themoon.y1.R
import com.themoon.y1.ThemeManager
import com.themoon.y1.controllers.ScreenController
import com.themoon.y1.managers.AlbumCoverManager
import com.themoon.y1.models.SongItem
import com.themoon.y1.navigation.NavigationManager
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
    private val onRebuildUniqueAlbumList: (() -> Unit)? = null,
    /** The library song in Now Playing (playing or paused), or null: Cover Flow opens at its album (see [CoverFlowStart]). */
    private val nowPlayingSong: () -> SongItem? = { null },
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

    // Speed scrolling: after a fast wheel turn, the wheel moves through the artist letters (see CoverFlowLetterJump).
    private val letterJump = CoverFlowLetterJump(uniqueAlbumList, object : CoverFlowLetterJump.Ui {
        override fun showLetter(letter: String) { MainActivity.instance?.showJumpLetter(letter) }
        override fun hideLetter() { MainActivity.instance?.hideFastScrollLetter() }
        override fun goTo(index: Int) = settleOnAlbum(index)
        override fun click() = clickFeedback()
        override fun postDelayed(task: Runnable, delayMs: Long) { rootView.postDelayed(task, delayMs) }
        override fun cancel(task: Runnable) { rootView.removeCallbacks(task) }
    })

    // Fly-by at the end of a letter jump (see CoverFlowFlyBy). Positions are album numbers that can be
    // fractional, and can be outside 0 until total after wheel steps (the cards show them modulo total).
    private var flying = false
    private var flyStartPos = 0f
    private var flyTarget = 0
    private var flyStartTime = 0L
    private var flyDurationMs = 0L
    /** The position on the screen now. */
    private var flyPos = 0f
    /** The album of the centre card (views[centre]) during a fly-by; the other cards have the next albums. */
    private var cardsBase = 0
    private val flyFrame = Runnable { onFlyFrame() }
    // Card position, angle, scale and alpha at each slot (-4..4), made at the start of a fly-by: no work for them in a frame.
    private val slotX = FloatArray(visibleCoversCount)
    private val slotRotY = FloatArray(visibleCoversCount)
    private val slotScale = FloatArray(visibleCoversCount)
    private val slotAlpha = FloatArray(visibleCoversCount)

    /** The song list of the album that was opened from Cover Flow, or null. */
    private var albumListState: ScreenState? = null
    /** The random start album that [prepareCovers] chose and prepared, or -1. */
    private var preparedRandomStart = -1

    override fun onEnter(state: ScreenState) {
        if (uniqueAlbumList.isEmpty()) onRebuildUniqueAlbumList?.invoke()
        // Only Back from the song list of the album that Cover Flow opened keeps that album.
        val back = albumListState
        albumListState = null
        if (back == null || NavigationManager.getInstance().previousScreen != back) currentCoverFlowIndex = startIndex()
        buildCoverFlowUI()
    }

    /** The album of the Now Playing song; without one, a random album (the one that was prepared, if any). */
    private fun startIndex(): Int {
        val random = preparedRandomStart
        preparedRandomStart = -1
        val playing = CoverFlowStart.albumIndex(uniqueAlbumList, nowPlayingSong())
        return when {
            playing >= 0 -> playing
            random in uniqueAlbumList.indices -> random
            else -> CoverFlowStart.startIndex(uniqueAlbumList, null)
        }
    }

    private fun openAlbum(album: SongItem) {
        onAlbumSelected(album)
        albumListState = NavigationManager.getInstance().currentState
    }

    override fun onRefreshUI() {
        buildCoverFlowUI()
    }

    override fun onLibraryLoaded() {
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
        letterJump.reset()
        stopFlyBy()
        cancelAllAnimations()
        // Keep the layout and its cards for the next entry (no new views and bitmaps).
        containerBrowserItems.removeAllViews()
    }

    fun buildCoverFlowUI() {
        letterJump.reset()
        stopFlyBy()
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

        // A scan pre-generates by itself; here only when the album list changed (for example after a sync).
        AlbumCoverManager.getInstance().pregenerateThumbnailsIfChanged(uniqueAlbumList)

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
        val kept = cfViews != null
        ensureCards()
        val cfLayout = coverFlowContainer ?: return
        (cfLayout.parent as? ViewGroup)?.removeView(cfLayout)
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

        if (kept) {
            // The theme can have changed since the cards were made.
            cfViews?.forEach { card -> (card?.tag as? CardState)?.let { styleCardTexts(it, d) } }
        }

        initCoverFlowPositions()
    }

    /** The Cover Flow layout and its cards: made one time and kept. */
    private fun ensureCards(): Array<View?> {
        cfViews?.let { return it }
        val d = activity.resources.displayMetrics.density
        val cfLayout = coverFlowContainer ?: CoverFlowLayout(activity).apply {
            clipChildren = false
            clipToPadding = false
            // The cover top is at the iPod position for the space below the status bar.
            childTopForHeight = { height -> (CoverFlowGeometry.coverTop((height / d).toInt()) * d).toInt() }
            // Changes in a card do not lay out the full window.
            isLayoutBoundary = true
        }
        coverFlowContainer = cfLayout
        val views = arrayOfNulls<View>(visibleCoversCount)
        for (i in 0 until visibleCoversCount) {
            val card = createSingleCoverView()
            views[i] = card
            cfLayout.addView(card)
        }
        cfViews = views
        return views
    }

    /** The album index that card [slot] shows (the centre card shows the current album). */
    private fun cardIndex(slot: Int): Int {
        val total = uniqueAlbumList.size
        return (currentCoverFlowIndex + slot - visibleCoversCount / 2 + total * 10) % total
    }

    // --- preparing the first entry ---

    private val mainHandler = Handler(Looper.getMainLooper())
    private val prepareTask = Runnable { prepareCovers() }
    private var scratch: Bitmap? = null

    /**
     * Does the slow work of the first entry before the user opens Cover Flow, so that the first entry after an app
     * start or a library change is as fast as the next ones (Y2: before, up to 2.5 s with no frame). That work: the
     * thumbnail check of all albums (when the album list changed), the covers around the current album, and the
     * cards with their art bitmaps. On Android 4.4 a bitmap allocation often starts a full garbage collection
     * (~60 ms, all threads stop). Call it after each library load, from any thread: it starts [PREPARE_DELAY_MS]
     * later, and prepares one card for each main-thread message, so keys still go through.
     */
    fun prepareSoon() {
        mainHandler.removeCallbacks(prepareTask)
        mainHandler.postDelayed(prepareTask, PREPARE_DELAY_MS)
    }

    private fun isOpen() = NavigationManager.getInstance().currentState is ScreenState.CoverFlow

    private fun prepareCovers() {
        if (uniqueAlbumList.isEmpty() || isOpen()) return
        if (albumListState == null) {
            // The album that the next entry opens at.
            val playing = CoverFlowStart.albumIndex(uniqueAlbumList, nowPlayingSong())
            preparedRandomStart = if (playing >= 0) -1 else CoverFlowStart.startIndex(uniqueAlbumList, null)
            currentCoverFlowIndex = if (playing >= 0) playing else preparedRandomStart
        }
        if (currentCoverFlowIndex >= uniqueAlbumList.size) currentCoverFlowIndex = 0
        val manager = AlbumCoverManager.getInstance()
        manager.pregenerateThumbnailsIfChanged(uniqueAlbumList)
        // The covers first (worker thread), then the cards with them (no placeholder art to draw first).
        val items = (0 until visibleCoversCount).map { uniqueAlbumList[cardIndex(it)] }.filter { it.file != null }
        var waiting = items.size
        if (waiting == 0) {
            prepareCard(0)
            return
        }
        for (item in items) {
            manager.loadCoverAsync(item, object : AlbumCoverManager.CoverCallback {
                override fun onCoverLoaded(albumKey: String, cover: Bitmap?, reflection: Bitmap?) {
                    if (--waiting == 0) prepareCard(0)
                }
            })
        }
    }

    /**
     * Binds card [slot], and measures, lays out and draws it off screen: this makes its text layouts and the art
     * bitmaps of its [SmoothEdgeImageView]s now, at their Cover Flow sizes. The entry then only binds the same bitmaps.
     */
    private fun prepareCard(slot: Int) {
        if (uniqueAlbumList.isEmpty() || isOpen()) {
            scratch = null
            return
        }
        val views = ensureCards()
        val card = views[slot] ?: return
        bindCoverData(card, cardIndex(slot))
        card.measure(
            View.MeasureSpec.makeMeasureSpec(card.layoutParams.width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        card.layout(0, 0, card.measuredWidth, card.measuredHeight)
        // A canvas of the card size (one byte a pixel), so that no child is outside the clip and skipped.
        val bmp = scratch?.takeIf { it.width >= card.measuredWidth && it.height >= card.measuredHeight }
            ?: Bitmap.createBitmap(card.measuredWidth.coerceAtLeast(1), card.measuredHeight.coerceAtLeast(1), Bitmap.Config.ALPHA_8)
        scratch = bmp
        card.draw(Canvas(bmp))
        if (slot + 1 < views.size) mainHandler.post { prepareCard(slot + 1) } else scratch = null
    }

    private fun initCoverFlowPositions() {
        val total = uniqueAlbumList.size
        if (total == 0) return
        val views = cfViews ?: return

        val centerIdx = visibleCoversCount / 2

        for (i in 0 until visibleCoversCount) {
            views[i]?.let { bindCoverData(it, cardIndex(i)) }
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
        // The album artist, as on the iPod ("Various Artists" for a compilation), not the artist of the first track.
        tvArtist?.text = CoverFlowOrder.artistOf(item)

        val albumKey = AlbumCoverManager.getAlbumKey(item.file, item.album)
        ivCover?.tag = albumKey

        val manager = AlbumCoverManager.getInstance()
        val cachedBmp = manager.getMemoryCover(albumKey)

        if (cachedBmp != null) {
            ivCover?.animate()?.cancel()
            ivCover?.alpha = 1.0f
            ivCover?.let { setBitmapIfChanged(it, cachedBmp) }
            if (ivReflection != null) showReflection(ivReflection, cachedBmp)
            return
        }

        // Albums without art, and albums not yet loaded, show the placeholder (decoded one time, thumbnail size).
        ivCover?.animate()?.cancel()
        ivCover?.alpha = 1.0f
        ivCover?.let { setBitmapIfChanged(it, manager.getPlaceholder(activity)) }
        if (ivReflection != null) showReflection(ivReflection, null)
        // Known to have no art: nothing to load.
        if (manager.hasNoArt(albumKey)) return

        manager.loadCoverAsync(item, object : AlbumCoverManager.CoverCallback {
            override fun onCoverLoaded(loadedKey: String, cover: Bitmap?, reflection: Bitmap?) {
                if (albumKey == ivCover?.tag) {
                    if (cover != null) {
                        ivCover.setImageBitmap(cover)
                    }
                    if (ivReflection != null) showReflection(ivReflection, cover)
                }
            }
        })
    }

    /**
     * Shows the reflection of [cover] (drawn from the cover, no reflection bitmap), or hides the view with
     * alpha 0 (not drawn) and keeps its old drawable. All reflections have the same intrinsic size, so the view
     * never changes size: no requestLayout, which on Android 4.4 lays out the full window. setVisibility is not
     * used for the same reason.
     */
    private fun showReflection(iv: ImageView, cover: Bitmap?) {
        iv.animate().cancel()
        if (cover != null) {
            if ((iv.drawable as? CoverReflectionDrawable)?.cover !== cover) {
                iv.setImageDrawable(CoverReflectionDrawable(cover, AlbumCoverManager.THUMB_SIZE))
            }
            iv.alpha = 1.0f
        } else {
            iv.alpha = 0f
        }
    }

    /** Sets [bmp] only when the view does not already show it (a new drawable makes the card draw its art again). */
    private fun setBitmapIfChanged(iv: ImageView, bmp: Bitmap?) {
        if ((iv.drawable as? BitmapDrawable)?.bitmap !== bmp || (bmp == null && iv.drawable != null)) {
            iv.setImageBitmap(bmp)
        }
    }

    private fun createSingleCoverView(): View {
        val d = activity.resources.displayMetrics.density
        val coverSize = (CoverFlowGeometry.COVER_SIZE * d).toInt()
        // The cover is at the top of the card, the reflection directly under it, and the text on the reflection.
        // No off-screen buffer when a card fades: its children do not overlap where it is not opaque
        // (the edge cards have no text).
        val card = object : FrameLayout(activity) {
            override fun hasOverlappingRendering(): Boolean = false
        }

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
            // Starts empty, with the reflection size, so the first reflection does not change its size.
            setImageDrawable(CoverReflectionDrawable(null, AlbumCoverManager.THUMB_SIZE))
            alpha = 0f
        }

        // Album (bold, primary colour: black on the Classic themes), then artist (regular, secondary colour),
        // on the iPod baselines.
        val tvTitle = createCardText(TITLE_VIEW_ID)
        val tvArtist = createCardText(ARTIST_VIEW_ID)
        val state = CardState(tvTitle, tvArtist)
        styleCardTexts(state, d)
        card.tag = state

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
                openAlbum(chosen)
            }
        }

        return card
    }

    /** One line of centred text, styled by [styleCardText]. */
    private fun createCardText(viewId: Int): TextView {
        return TextView(activity).apply {
            id = viewId
            includeFontPadding = false
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            isSingleLine = true
            ellipsize = android.text.TextUtils.TruncateAt.END
            alpha = 0f
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
    }

    private fun styleCardTexts(state: CardState, d: Float) {
        styleCardText(state.title, CoverFlowGeometry.TITLE_SIZE * d, CoverFlowGeometry.TITLE_BASELINE * d, bold = true)
        styleCardText(state.artist, CoverFlowGeometry.ARTIST_SIZE * d, CoverFlowGeometry.ARTIST_BASELINE * d, bold = false)
    }

    /** Theme colour and font (bold or regular), with the baseline [baseline] px below the card top. Sets only what changed. */
    private fun styleCardText(tv: TextView, sizePx: Float, baseline: Float, bold: Boolean) {
        tv.setTextColor(if (bold) ThemeManager.getTextColorPrimary() else ThemeManager.getTextColorSecondary())
        tv.setTextSize(TypedValue.COMPLEX_UNIT_PX, sizePx)
        tv.typeface = if (bold) ThemeManager.getCustomFontBold() else ThemeManager.getCustomFont()
        // Without font padding, the baseline is -ascent below the top of the view.
        val top = Math.round(baseline + tv.paint.fontMetricsInt.ascent)
        val lp = tv.layoutParams as? FrameLayout.LayoutParams ?: return
        if (lp.topMargin != top) {
            lp.topMargin = top
            tv.layoutParams = lp
        }
    }

    private fun setCardTitleAlpha(card: View, isCenter: Boolean, duration: Int) {
        val state = card.tag as? CardState ?: return
        // Only the old and the new centre card change; the others keep their text hidden without an animator.
        if (CoverFlowSteps.textAnimation(state.textShown, isCenter, duration) == CoverFlowSteps.Change.NONE) return
        state.textShown = isCenter
        val targetAlpha = if (isCenter) 1.0f else 0.0f
        for (tv in arrayOf(state.title, state.artist)) {
            if (duration > 0) {
                tv.animate().alpha(targetAlpha).setDuration(duration.toLong()).start()
            } else {
                tv.animate().cancel()
                tv.alpha = targetAlpha
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
        // A card at alpha 0 is not drawn. Not setVisibility: on Android 4.4 it lays out the full window.
        v.alpha = alpha
    }

    private fun animateTransform(v: View?, transX: Float, rotY: Float, scale: Float, alpha: Float, duration: Int) {
        if (v == null) return
        when (CoverFlowSteps.cardAnimation(v.alpha, alpha, duration)) {
            CoverFlowSteps.Change.NONE, CoverFlowSteps.Change.SET -> applyTransform(v, transX, rotY, scale, alpha)
            CoverFlowSteps.Change.ANIMATE ->
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
        if (letterJump.onStep(dir, eventTime, currentCoverFlowIndex)) return
        if (flying) {
            // The step takes over from where the covers are now: the fly-by goes one album past its target.
            flyTo(flyTarget + dir)
            if (lag <= 40) clickFeedback()
            return
        }

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

    /** End of a letter jump: the covers scroll past fast to album [index]. */
    private fun settleOnAlbum(index: Int) {
        val total = uniqueAlbumList.size
        if (index !in 0 until total) return
        if (flying) {
            // In the numbers of the running fly-by (its target can be outside 0 until total after wheel steps).
            flyTo(index + flyTarget - wrap(flyTarget, total))
        } else if (index != currentCoverFlowIndex) {
            flyTo(index)
        }
    }

    /**
     * Starts a fly-by to album [target] from the position on the screen, or changes the target of the running
     * fly-by (the covers continue from where they are). The album count and select use [target] immediately.
     */
    private fun flyTo(target: Int) {
        val total = uniqueAlbumList.size
        val views = cfViews ?: return
        val container = coverFlowContainer ?: return
        if (total == 0) return
        if (!flying) {
            cancelAllAnimations()
            makeSlots()
            cardsBase = currentCoverFlowIndex
            flyPos = currentCoverFlowIndex.toFloat()
            for (card in views) card?.let { setCardTitleAlpha(it, false, CoverFlowFlyBy.TEXT_FADE_MS) }
            flying = true
        }
        flyStartPos = flyPos
        flyTarget = target
        flyStartTime = SystemClock.uptimeMillis()
        flyDurationMs = CoverFlowFlyBy.durationMs(target - flyPos)
        currentCoverFlowIndex = wrap(target, total)
        container.removeCallbacks(flyFrame)
        container.postOnAnimation(flyFrame)
    }

    private fun onFlyFrame() {
        if (!flying) return
        val elapsed = SystemClock.uptimeMillis() - flyStartTime
        val done = elapsed >= flyDurationMs
        flyPos = if (done) flyTarget.toFloat()
        else flyStartPos + CoverFlowFlyBy.offsetAt(elapsed, flyDurationMs, flyTarget - flyStartPos)
        placeCards(flyPos)
        if (!done) {
            coverFlowContainer?.postOnAnimation(flyFrame)
            return
        }
        stopFlyBy()
        // Exact positions, the album count, then the text of the centre album fades in.
        initCoverFlowPositions()
        cfViews?.get(visibleCoversCount / 2)?.let {
            setCardTitleAlpha(it, false, 0)
            setCardTitleAlpha(it, true, CoverFlowFlyBy.TEXT_FADE_MS)
        }
    }

    private fun stopFlyBy() {
        if (!flying) return
        flying = false
        coverFlowContainer?.removeCallbacks(flyFrame)
    }

    /**
     * Puts the cards at position [pos] (album number, can be fractional). The cards move in the views array as in
     * a wheel step; only the cards that come in get a new album (from the cover cache, else the placeholder).
     */
    private fun placeCards(pos: Float) {
        val views = cfViews ?: return
        val total = uniqueAlbumList.size
        if (total == 0) return
        val centerIdx = visibleCoversCount / 2
        val base = Math.floor(pos.toDouble()).toInt()
        val shift = base - cardsBase
        if (shift >= visibleCoversCount || shift <= -visibleCoversCount) {
            for (i in 0 until visibleCoversCount) views[i]?.let { bindCoverData(it, wrap(base + i - centerIdx, total)) }
        } else if (shift > 0) {
            repeat(shift) {
                val first = views[0]
                for (i in 0 until visibleCoversCount - 1) views[i] = views[i + 1]
                views[visibleCoversCount - 1] = first
            }
            for (i in visibleCoversCount - shift until visibleCoversCount) {
                views[i]?.let { bindCoverData(it, wrap(base + i - centerIdx, total)) }
            }
        } else if (shift < 0) {
            repeat(-shift) {
                val last = views[visibleCoversCount - 1]
                for (i in visibleCoversCount - 1 downTo 1) views[i] = views[i - 1]
                views[0] = last
            }
            for (i in 0 until -shift) views[i]?.let { bindCoverData(it, wrap(base + i - centerIdx, total)) }
        }
        cardsBase = base
        val frac = pos - base
        for (i in 0 until visibleCoversCount) views[i]?.let { placeCard(it, i - centerIdx - frac) }
    }

    /** Puts [card] at [offset] albums from the centre: between two slots, the values are interpolated. */
    private fun placeCard(card: View, offset: Float) {
        val maxDist = visibleCoversCount / 2
        val o = offset.coerceIn(-maxDist.toFloat(), maxDist.toFloat())
        val k = Math.floor(o.toDouble()).toInt()
        val t = o - k
        val a = k + maxDist
        val b = if (t > 0f) a + 1 else a
        card.translationX = slotX[a] + (slotX[b] - slotX[a]) * t
        card.rotationY = slotRotY[a] + (slotRotY[b] - slotRotY[a]) * t
        val scale = slotScale[a] + (slotScale[b] - slotScale[a]) * t
        card.scaleX = scale
        card.scaleY = scale
        // A card at alpha 0 is not drawn.
        card.alpha = slotAlpha[a] + (slotAlpha[b] - slotAlpha[a]) * t
    }

    /** The slot values, the same as in [initCoverFlowPositions]. */
    private fun makeSlots() {
        val d = activity.resources.displayMetrics.density
        val centerIdx = visibleCoversCount / 2
        for (i in 0 until visibleCoversCount) {
            val dist = Math.abs(i - centerIdx)
            val sign = if (i < centerIdx) -1f else 1f
            slotX[i] = sign * getTransXForDist(dist, d)
            slotRotY[i] = -sign * getRotYForDist(dist)
            slotScale[i] = getScaleForDist(dist)
            slotAlpha[i] = getAlphaForDist(dist)
        }
    }

    private fun wrap(index: Int, total: Int): Int = ((index % total) + total) % total

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
        // Letter mode: a centre press only ends it; Back and Play/Pause end it, then work as usual.
        if (letterJump.onKey(event.keyCode, event.action == KeyEvent.ACTION_DOWN, event.repeatCount)) return true
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
            // Open the album on release (onKeyUp); on press, the release went to the album list and played a song
            return true
        }
        return false
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
            if (currentCoverFlowIndex in uniqueAlbumList.indices) {
                clickFeedback()
                val chosen = uniqueAlbumList[currentCoverFlowIndex]
                openAlbum(chosen)
            }
            return true
        }
        return false
    }

    /** Views of a card and whether its text is shown (kept in card.tag). */
    private class CardState(val title: TextView, val artist: TextView) {
        var textShown = false
    }

    companion object {
        // After a library load: the start-up work and the main menu first
        private const val PREPARE_DELAY_MS = 3000L
        private const val COVER_VIEW_ID = 1001
        private val TITLE_VIEW_ID = R.id.coverflow_title
        private val ARTIST_VIEW_ID = R.id.coverflow_artist
        private const val REFLECTION_VIEW_ID = 1004

        // Scale, angle and perspective of the side covers (see CoverFlowGeometry)
        private val sideTilt = CoverFlowGeometry.sideTilt()
    }
}
