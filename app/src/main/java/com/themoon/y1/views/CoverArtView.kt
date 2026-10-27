package com.themoon.y1.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.util.AttributeSet
import android.view.Choreographer
import android.view.View
import android.view.animation.AnimationUtils
import android.widget.ImageView

/**
 * The art of a split-screen pane ([CoverPane]). It fills the pane and draws the art with CENTER_CROP, a zoom and
 * an offset from [CoverMotion]. Each frame changes only the canvas transform of one bitmap draw (no new bitmap), and
 * the next frame comes only after a frame was drawn: a hidden pane or a screen that does not draw stops the motion.
 */
class CoverArtView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : ImageView(context, attrs) {

    // Nullable: ImageView's constructor can call the overridden methods before the fields are set.
    private var motion: CoverMotion? = null
    private var pose: FloatArray? = null
    private var lastArt: Any? = null
    private var restartNext = true
    private var frameQueued = false
    private var frameCallback: Choreographer.FrameCallback? = null

    init {
        scaleType = ScaleType.CENTER_CROP
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        CoverPane.register(this)
        restartNext = true
    }

    override fun onDetachedFromWindow() {
        CoverPane.unregister(this)
        cancelFrame()
        super.onDetachedFromWindow()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility == View.VISIBLE) {
            // The pane comes into view: a new random start, and a draw that starts the frames.
            restartNext = true
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
        if (!CoverPane.isEnabled()) restartNext = true
        invalidate() // start the frames, or show the still art at once
    }

    override fun onDraw(canvas: Canvas) {
        val d = drawable
        val w = width
        val h = height
        if (d == null || w <= 0 || h <= 0) {
            super.onDraw(canvas)
            return
        }
        val m = motion ?: CoverMotion().also { motion = it }
        val p = pose ?: FloatArray(3).also { pose = it }
        val art: Any = (d as? BitmapDrawable)?.bitmap ?: d

        var zoom = CoverPane.ZOOM
        var x = 0f
        var y = 0f
        if (CoverPane.isEnabled()) {
            if (CoverPane.isMoving()) {
                if (restartNext) {
                    m.restart()
                    m.pause()
                    restartNext = false
                } else if (lastArt != null && art !== lastArt) {
                    m.newPath()
                }
                m.frame(AnimationUtils.currentAnimationTimeMillis(), p)
                queueFrame()
            } else {
                // Screen off or blanked: keep the pose, continue from it later.
                m.pause()
                m.current(p)
            }
            zoom = CoverMotion.zoom(p[2])
            x = p[0]
            y = p[1]
        }
        lastArt = art

        val cw = d.intrinsicWidth.toFloat()
        val ch = d.intrinsicHeight.toFloat()
        val crop = CoverMotion.cropScale(cw, ch, w.toFloat(), h.toFloat())
        val drawnW = if (cw > 0f) cw * crop else w.toFloat()
        val drawnH = if (ch > 0f) ch * crop else h.toFloat()
        val dx = CoverMotion.offset(x, drawnW, w.toFloat(), zoom)
        val dy = CoverMotion.offset(y, drawnH, h.toFloat(), zoom)

        val save = canvas.save()
        canvas.clipRect(0, 0, w, h)
        canvas.translate(w / 2f + dx, h / 2f + dy)
        canvas.scale(zoom, zoom)
        canvas.translate(-w / 2f, -h / 2f)
        super.onDraw(canvas)
        canvas.restoreToCount(save)
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
        val cb = frameCallback ?: return
        if (frameQueued) Choreographer.getInstance().removeFrameCallback(cb)
        frameQueued = false
        motion?.pause()
    }
}
