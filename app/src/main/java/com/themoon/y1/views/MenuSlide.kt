package com.themoon.y1.views

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.os.SystemClock
import android.util.Log
import android.view.Choreographer
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import com.themoon.y1.navigation.BrowserMode
import com.themoon.y1.navigation.NavigationManager
import com.themoon.y1.navigation.ScreenState
import com.themoon.y1.views.MenuSlideMotion.Kind

/**
 * The menu slides of the iPod classic: a list that opens comes in from the right and the old list goes out to the
 * left (Back: the reverse). Between two split screens only the list pane slides and the art stays; into Cover
 * Flow and into Now Playing the list pane and the art pane go apart, and the new screen shows from behind them (the
 * split apart; from a full-width list, Now Playing slides in as a list). Timing and positions: [MenuSlideMotion].
 *
 * One layer over all screens, below nothing: the status bar area (full width, only its title changes) is not
 * covered. At each push or pop of the back stack ([NavigationManager.DirectionListener]) the layer draws the old
 * screen once into a bitmap (software draw, before the old screen exits), holds it while the new screen builds and
 * selects its row, draws the new screen once into a second bitmap, and then moves the two bitmaps. The frames
 * change only offsets: no allocation, no view layout, no copy. The bitmaps are kept for the next slide.
 * The split apart also has a live art layer ([CoverArtView] with [CoverArtView.slideLayer]) that continues
 * the Menu Cover Motion with the same zoom and offset, and also draws the art that the list pane hid (left of the
 * pane edge, up to the art's own left edge): the art shows from under the list as the list goes, and as the art
 * pane goes right ([MenuSlideMotion.splitApartArtLayer]). The menu shadow moves with the list pane's right edge.
 *
 * The screens change at once (the controllers have the new state at the key press), so keys always act on the new
 * screen: a key during a slide ends the slide at once ([finishNow]), and the key then acts as normal (not lost,
 * not doubled). No slide when the screen is off or blanked.
 */
class MenuSlide private constructor(
    private val root: ViewGroup,
    private val statusBar: View?
) : FrameLayout(root.context), NavigationManager.DirectionListener, Choreographer.FrameCallback,
    ViewTreeObserver.OnPreDrawListener {

    private enum class Phase { IDLE, HOLD, WAIT, RUN }

    private var phase = Phase.IDLE
    private var endDrawn = false
    private var kind = Kind.FULL
    private var push = true

    /** Top of the slide area (below the status bar), and the list pane width of the split screen. */
    private var top = 0
    private var pane = 0

    private var paneOld: Int? = null
    private var coverFlowOld = false
    private var coverFlowNew = false
    private var nowPlayingOld = false
    private var nowPlayingNew = false
    private var artOld: Bitmap? = null

    private var bmpOld: Bitmap? = null
    private var bmpNew: Bitmap? = null
    private val snapCanvas = Canvas()
    private var capturing = false

    private val art = CoverArtView(root.context).apply {
        slideLayer = true
        visibility = View.GONE
    }
    private val depth = FloatArray(2)
    private val off = FloatArray(3)
    private val loc = IntArray(2)
    private val artLayer = IntArray(3)

    // Measurements of each slide (logcat tag MenuSlide)
    private var holdStartMs = 0L
    private var snapOldMs = 0f
    private var snapNewMs = 0f
    private var startNanos = 0L
    private var lastNanos = 0L
    private var frames = 0
    private var maxGapNanos = 0L
    private var lateFrames = 0

    private val waitStep1 = Runnable { root.post(waitStep2) }
    private val waitStep2 = Runnable { root.post(waitStep3) }
    private val waitStep3 = Runnable {
        if (phase != Phase.WAIT) return@Runnable
        // After the layout pass and the posted row selection of ListRows.select: draw at the next frame.
        val observer = root.viewTreeObserver
        if (observer.isAlive) observer.addOnPreDrawListener(this)
        invalidate()
    }
    private val holdTimeout = Runnable {
        if (phase == Phase.HOLD || phase == Phase.WAIT) finishNow()
    }

    init {
        visibility = View.INVISIBLE
        isFocusable = false
        descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        clipChildren = false
        setWillNotDraw(false)
        addView(art, LayoutParams(0, 0))
    }

    override fun onBeforeTransition(from: ScreenState?, to: ScreenState, direction: NavigationManager.Direction) {
        finishNow()
        if (from == null || direction == NavigationManager.Direction.NONE) return
        if (!slides(from) || !slides(to)) return
        if (!CoverPane.isScreenActive() || windowVisibility != View.VISIBLE || root.width <= 0) return
        val bar = statusBar
        top = if (bar != null && bar.visibility == View.VISIBLE) bar.bottom.coerceAtLeast(0) else 0
        if (root.height - top <= 0) return
        push = direction == NavigationManager.Direction.PUSH
        coverFlowOld = isCoverFlow(from)
        coverFlowNew = isCoverFlow(to)
        nowPlayingOld = from is ScreenState.Player
        nowPlayingNew = to is ScreenState.Player
        val shown = CoverPane.shownPane()
        paneOld = paneX(shown)
        artOld = if (coverFlowNew || nowPlayingNew) ((shown?.drawable as? BitmapDrawable)?.bitmap) else null

        val t0 = SystemClock.elapsedRealtimeNanos()
        bmpOld = snapshot(bmpOld) ?: return
        snapOldMs = (SystemClock.elapsedRealtimeNanos() - t0) / 1e6f
        holdStartMs = SystemClock.uptimeMillis()
        phase = Phase.HOLD
        visibility = View.VISIBLE
        invalidate()
        postDelayed(holdTimeout, MAX_HOLD_MS)
    }

    override fun onAfterTransition(from: ScreenState?, to: ScreenState, direction: NavigationManager.Direction) {
        if (phase != Phase.HOLD) return
        phase = Phase.WAIT
        // Runs after the layout of the new screen (posted messages wait for the traversal), then twice more:
        // ListRows.select selects the row in its layout listener and in two posted steps.
        root.post(waitStep1)
    }

    override fun onPreDraw(): Boolean {
        val observer = root.viewTreeObserver
        if (observer.isAlive) observer.removeOnPreDrawListener(this)
        if (phase != Phase.WAIT) return true

        val paneNew = paneX(CoverPane.shownPane())
        val paneA = if (push) paneOld else paneNew
        val paneB = if (push) paneNew else paneOld
        val k = MenuSlideMotion.kindOf(
            paneA, paneB,
            coverFlowA = if (push) coverFlowOld else coverFlowNew,
            coverFlowB = if (push) coverFlowNew else coverFlowOld,
            nowPlayingB = if (push) nowPlayingNew else nowPlayingOld
        )
        if (k == null || (k != Kind.FULL && paneA == null)) {
            finishNow()
            return true
        }
        kind = k
        pane = paneA ?: 0

        val t0 = SystemClock.elapsedRealtimeNanos()
        bmpNew = snapshot(bmpNew)
        snapNewMs = (SystemClock.elapsedRealtimeNanos() - t0) / 1e6f
        if (bmpNew == null) {
            finishNow()
            return true
        }

        var drawNow = true
        if (kind == Kind.SPLIT_APART) {
            // The art of the split screen: the old screen's for the entry, the new screen's for Back.
            val bitmap = if (push) artOld else (CoverPane.shownPane()?.drawable as? BitmapDrawable)?.bitmap
            if (bitmap != null && !bitmap.isRecycled) {
                MenuSlideMotion.splitApartArtLayer(root.width, pane, artLayer)
                val lp = art.layoutParams as LayoutParams
                lp.width = artLayer[1]
                lp.height = root.height - top
                lp.leftMargin = artLayer[0]
                lp.topMargin = top
                art.layoutParams = lp
                art.artLeft = artLayer[2]
                // Until the first draw of the layer: the left-most edge that the art can have (the largest zoom, the
                // art at its right end), so that Back starts with all of the art right of the screen.
                val pw = (root.width - pane).toFloat()
                val drawnW = bitmap.width *
                    CoverMotion.cropScale(bitmap.width.toFloat(), bitmap.height.toFloat(), pw, lp.height.toFloat())
                art.artEdge = (artLayer[2] + pw - CoverMotion.ZOOM_MAX * drawnW).coerceAtLeast(0f)
                art.setImageDrawable(BitmapDrawable(resources, bitmap))
                art.visibility = View.VISIBLE
                // The art layer needs a layout pass: the slide starts at the next frame.
                drawNow = false
            }
        }
        removeCallbacks(holdTimeout)
        phase = Phase.RUN
        endDrawn = false
        frames = 0
        lateFrames = 0
        maxGapNanos = 0L
        applyFrame(0L)
        Choreographer.getInstance().postFrameCallback(this)
        return drawNow
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (phase != Phase.RUN) return
        if (!CoverPane.isScreenActive()) {
            finishNow()
            return
        }
        if (frames == 0) {
            startNanos = frameTimeNanos
        } else {
            val gap = frameTimeNanos - lastNanos
            if (gap > maxGapNanos) maxGapNanos = gap
            if (gap > LATE_NANOS) lateFrames++
        }
        lastNanos = frameTimeNanos
        frames++
        val elapsed = (frameTimeNanos - startNanos) / 1_000_000L
        if (!MenuSlideMotion.running(kind, push, elapsed)) {
            if (endDrawn) {
                // The end of the slide is the screen under the layer.
                finishNow()
                return
            }
            // One frame at the end positions first (depth clamps the time): the last frame before can be far from
            // them (t² of the split apart), and what moves out must leave the screen, not go away at once.
            endDrawn = true
        }
        applyFrame(elapsed)
        Choreographer.getInstance().postFrameCallback(this)
    }

    private fun applyFrame(elapsedMs: Long) {
        MenuSlideMotion.depth(kind, push, elapsedMs, depth)
        // The left edge of the art on the screen at art offset 0 (the art layer also draws the art left of the pane).
        val artLeft = if (art.visibility == View.VISIBLE) artLayer[0] + art.artEdge else pane.toFloat()
        MenuSlideMotion.offsets(kind, depth[0], depth[1], root.width.toFloat(), pane.toFloat(), off, artLeft)
        if (kind == Kind.SPLIT_APART) art.translationX = off[2]
        invalidate()
    }

    /** Ends the slide now: the screen under the layer (the new screen) shows. */
    fun finishNow() {
        if (phase == Phase.IDLE) return
        val wasRunning = phase == Phase.RUN
        Choreographer.getInstance().removeFrameCallback(this)
        removeCallbacks(holdTimeout)
        root.removeCallbacks(waitStep1)
        root.removeCallbacks(waitStep2)
        root.removeCallbacks(waitStep3)
        val observer = root.viewTreeObserver
        if (observer.isAlive) observer.removeOnPreDrawListener(this)
        phase = Phase.IDLE
        visibility = View.INVISIBLE
        if (art.visibility != View.GONE) {
            art.visibility = View.GONE
            art.setImageDrawable(null)
            art.translationX = 0f
        }
        artOld = null
        if (wasRunning) {
            val ms = (lastNanos - startNanos) / 1e6f
            Log.i(
                TAG, "kind=$kind push=$push frames=$frames ms=${f1(ms)} " +
                    "maxGapMs=${f1(maxGapNanos / 1e6f)} late=$lateFrames " +
                    "snapOldMs=${f1(snapOldMs)} snapNewMs=${f1(snapNewMs)} " +
                    "holdMs=${startNanos / 1_000_000L - holdStartMs}"
            )
        }
    }

    override fun dispatchDraw(canvas: Canvas) {
        if (capturing || phase == Phase.IDLE) return
        val old = bmpOld ?: return
        if (phase != Phase.RUN) {
            drawPart(canvas, old, 0f, 0, old.width.toFloat())
            return
        }
        val new = bmpNew ?: return
        val a = if (push) old else new
        val b = if (push) new else old
        val w = a.width
        when (kind) {
            Kind.PANE -> {
                // Both lists only in the list pane: B comes in at the pane edge, not over the art.
                val p = pane.toFloat()
                drawPart(canvas, a, off[0], 0, MenuSlideMotion.paneColumns(off[0], p))
                drawPart(canvas, b, off[1], 0, MenuSlideMotion.paneColumns(off[1], p))
            }
            Kind.FULL -> {
                drawPart(canvas, a, off[0], 0, w.toFloat())
                drawPart(canvas, b, off[1], 0, w.toFloat())
            }
            Kind.SPLIT_TO_FULL -> {
                drawPart(canvas, a, 0f, pane, w.toFloat())
                drawPart(canvas, a, off[0], 0, pane.toFloat())
                drawPart(canvas, b, off[1], 0, w.toFloat())
            }
            Kind.SPLIT_APART -> {
                drawPart(canvas, b, 0f, 0, w.toFloat())
                super.dispatchDraw(canvas)
                drawPart(canvas, a, off[0], 0, pane.toFloat())
                // The menu shadow is the list's drop shadow: it moves with the list's right edge, over the art and
                // the screen behind, and fades out in the last shadow width before the edge leaves the screen.
                val edge = pane + off[0]
                val density = resources.displayMetrics.density
                val save = canvas.save()
                canvas.translate(edge, top.toFloat())
                CoverPane.drawShadow(canvas, density, a.height, edge / (CoverPane.SHADOW_WIDTH_DP * density))
                canvas.restoreToCount(save)
            }
        }
    }

    /** Draws columns [from]..[to] of a screen bitmap, moved by [dx], in the slide area (below the status bar). */
    private fun drawPart(canvas: Canvas, bmp: Bitmap, dx: Float, from: Int, to: Float) {
        if (to <= from) return
        val save = canvas.save()
        canvas.translate(dx, top.toFloat())
        canvas.clipRect(from.toFloat(), 0f, to, bmp.height.toFloat())
        canvas.drawBitmap(bmp, 0f, 0f, null)
        canvas.restoreToCount(save)
    }

    /** Draws the screen (all views under [root], not this layer) below the status bar into [reuse] or a new bitmap. */
    private fun snapshot(reuse: Bitmap?): Bitmap? {
        val w = root.width
        val h = root.height - top
        if (w <= 0 || h <= 0) return null
        val bmp = if (reuse != null && !reuse.isRecycled && reuse.width == w && reuse.height == h) {
            reuse
        } else {
            reuse?.recycle()
            try {
                Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            } catch (_: OutOfMemoryError) {
                return null
            }
        }
        snapCanvas.setBitmap(bmp)
        val save = snapCanvas.save()
        capturing = true
        try {
            snapCanvas.translate(0f, -top.toFloat())
            snapCanvas.clipRect(0, top, w, top + h)
            root.draw(snapCanvas)
        } catch (e: Exception) {
            Log.w(TAG, "snapshot failed", e)
            return null
        } finally {
            capturing = false
            snapCanvas.restoreToCount(save)
            snapCanvas.setBitmap(null)
        }
        return bmp
    }

    /** The x of the art pane that shows (the list pane width), or null when the screen has no art pane. */
    private fun paneX(view: CoverArtView?): Int? {
        if (view == null) return null
        view.getLocationInWindow(loc)
        val x = loc[0]
        return if (x > 0 && x < root.width) x else null
    }

    companion object {
        const val TAG = "MenuSlide"

        private fun f1(v: Float): String = String.format(java.util.Locale.US, "%.1f", v)

        /** The longest wait for the new screen; then it shows without a slide. */
        const val MAX_HOLD_MS = 600L

        /** A frame later than this after the frame before missed a refresh (the Y2 refreshes at ~63 Hz). */
        private const val LATE_NANOS = 24_000_000L

        /** Adds the layer on top of [root] (the status bar area stays uncovered) and listens to the back stack. */
        fun attach(root: ViewGroup, statusBar: View?): MenuSlide {
            val layer = MenuSlide(root, statusBar)
            root.addView(layer, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            NavigationManager.getInstance().addDirectionListener(layer)
            return layer
        }

        private fun isCoverFlow(s: ScreenState): Boolean =
            s is ScreenState.CoverFlow || (s is ScreenState.Browser && s.mode is BrowserMode.CoverFlow)

        /** Screens that slide: all lists and screens, not the video player and the quiz (full-screen media). */
        private fun slides(s: ScreenState): Boolean =
            s !is ScreenState.VideoPlayer && s !is ScreenState.MusicQuiz
    }
}
