package com.themoon.y1.views

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import android.util.AttributeSet
import android.view.Choreographer
import android.view.View
import android.view.animation.AnimationUtils
import android.widget.ImageView

/**
 * The art of a split-screen pane ([CoverPane]). It fills the pane and draws the art with CENTER_CROP, a zoom and
 * an offset from the motion that all panes share ([CoverPane.motion]), so the art does not jump when a menu opens
 * or closes. Each frame changes only the canvas transform of one bitmap draw (no new bitmap), and the next frame
 * comes only after a frame was drawn: a hidden pane or a screen that does not draw stops the motion. When the art
 * is different from the art of the last frame (other menu, other song), the old art fades out over the new art
 * ([CoverPane.FADE_MS]; one more bitmap draw during the fade only). The menu shadow ([CoverPane.drawShadow]) is
 * drawn last, over the art.
 */
class CoverArtView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : ImageView(context, attrs) {

    // Nullable: ImageView's constructor can call the overridden methods before the fields are set.
    private var pose: FloatArray? = null
    private var fadeFrom: Bitmap? = null
    private var fadeStart = 0L
    private var fadePaint: Paint? = null
    private var frameQueued = false
    private var frameCallback: Choreographer.FrameCallback? = null

    init {
        scaleType = ScaleType.CENTER_CROP
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        CoverPane.register(this)
    }

    override fun onDetachedFromWindow() {
        CoverPane.unregister(this)
        cancelFrame()
        super.onDetachedFromWindow()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility == View.VISIBLE) {
            // The pane comes into view: a draw that starts the frames, on the shared path (no new start).
            invalidate()
        } else {
            cancelFrame()
        }
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == View.VISIBLE) invalidate() else cancelFrame()
    }

    /** The setting or the screen state changed ([CoverPane]). */
    internal fun onMotionStateChanged() {
        if (!CoverPane.isMoving()) cancelFrame()
        invalidate() // start the frames, or show the still art at once
    }

    override fun onDraw(canvas: Canvas) {
        val d = drawable
        val w = width
        val h = height
        if (d == null || w <= 0 || h <= 0) {
            super.onDraw(canvas)
            if (h > 0) CoverPane.drawShadow(canvas, resources.displayMetrics.density, h)
            return
        }
        val p = pose ?: FloatArray(3).also { pose = it }
        val art: Any = (d as? BitmapDrawable)?.bitmap ?: d
        val now = AnimationUtils.currentAnimationTimeMillis()

        // Screen off or blanked: the shared clock stops at the current pose, and continues from it later.
        val moving = CoverPane.isMoving()
        val old = CoverPane.motion.frame(now, art, moving, p)
        if (old is Bitmap && !old.isRecycled && CoverPane.isScreenActive()) {
            fadeFrom = old
            fadeStart = now
        }
        var zoom = CoverPane.ZOOM
        var x = 0f
        var y = 0f
        if (CoverPane.isEnabled()) {
            zoom = CoverMotion.zoom(p[2])
            x = p[0]
            y = p[1]
        }

        val save = canvas.save()
        canvas.clipRect(0, 0, w, h)
        transform(canvas, d.intrinsicWidth.toFloat(), d.intrinsicHeight.toFloat(), x, y, zoom)
        super.onDraw(canvas)
        canvas.restoreToCount(save)

        // The old art over the new art, more transparent at each frame: a cross-fade with one more draw.
        val from = fadeFrom
        var fading = false
        if (from != null) {
            val t = (now - fadeStart).toFloat() / CoverPane.FADE_MS
            if (t >= 1f || from.isRecycled || !CoverPane.isScreenActive()) {
                fadeFrom = null
            } else {
                val bw = from.width.toFloat()
                val bh = from.height.toFloat()
                val paint = fadePaint ?: Paint(Paint.FILTER_BITMAP_FLAG).also { fadePaint = it }
                paint.alpha = ((1f - t.coerceAtLeast(0f)) * 255f).toInt()
                val s2 = canvas.save()
                canvas.clipRect(0, 0, w, h)
                transform(canvas, bw, bh, x, y, zoom)
                // CENTER_CROP of the old art, as ImageView does for the new art.
                val crop = CoverMotion.cropScale(bw, bh, w.toFloat(), h.toFloat())
                canvas.translate((w - bw * crop) / 2f, (h - bh * crop) / 2f)
                canvas.scale(crop, crop)
                canvas.drawBitmap(from, 0f, 0f, paint)
                canvas.restoreToCount(s2)
                fading = true
            }
        }
        if (moving || fading) queueFrame()
        // Over the art and outside its motion: the shadow stays at the menu edge.
        CoverPane.drawShadow(canvas, resources.displayMetrics.density, h)
    }

    /**
     * The motion transform of art [cw] × [ch] (before CENTER_CROP) at the pose part ([x], [y]) and [zoom]: the
     * same for each pane of the same size, so the same art is at the same place in each menu.
     */
    private fun transform(canvas: Canvas, cw: Float, ch: Float, x: Float, y: Float, zoom: Float) {
        val w = width.toFloat()
        val h = height.toFloat()
        val crop = CoverMotion.cropScale(cw, ch, w, h)
        val drawnW = if (cw > 0f) cw * crop else w
        val drawnH = if (ch > 0f) ch * crop else h
        val dx = CoverMotion.offset(x, drawnW, w, zoom)
        val dy = CoverMotion.offset(y, drawnH, h, zoom)
        canvas.translate(w / 2f + dx, h / 2f + dy)
        canvas.scale(zoom, zoom)
        canvas.translate(-w / 2f, -h / 2f)
    }

    private fun queueFrame() {
        if (frameQueued) return
        val cb = frameCallback ?: Choreographer.FrameCallback {
            frameQueued = false
            if (isShown) invalidate()
        }.also { frameCallback = it }
        frameQueued = true
        Choreographer.getInstance().postFrameCallbackDelayed(cb, CoverPane.FRAME_DELAY_MS)
    }

    private fun cancelFrame() {
        fadeFrom = null
        CoverPane.motion.pause()
        val cb = frameCallback ?: return
        if (frameQueued) Choreographer.getInstance().removeFrameCallback(cb)
        frameQueued = false
    }
}
