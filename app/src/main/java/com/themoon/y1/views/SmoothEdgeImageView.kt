package com.themoon.y1.views

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.View
import android.widget.ImageView

/**
 * ImageView for album art that is drawn with a 3D view transform (Now Playing, Cover Flow).
 *
 * With ScaleType.CENTER_CROP, it draws the art (and its background) at its final pixel size
 * into a bitmap with a transparent border (see [SmoothEdges]), and draws that bitmap with
 * bilinear filtering, so the GPU smooths the tilted edges. The border is outside the view
 * bounds, so the art keeps its size and position. The bitmap is made again only when the
 * drawable or the size changes. The drawable itself does not change, so code that reads
 * the bitmap from the drawable still gets the original art.
 */
class SmoothEdgeImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : ImageView(context, attrs, defStyleAttr) {

    private val border = SmoothEdges.borderPx(resources.displayMetrics.density)
    private val filterPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val fadePaint = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) }
    private val savedBounds = Rect()

    // The background is drawn into the bitmap, so it gets the same smooth edge as the art.
    private val artBackground: Drawable? = background?.also { background = null }

    private var isReflection = false

    private var cache: Bitmap? = null
    private val cacheCanvas = Canvas()
    private var cacheDrawable: Drawable? = null
    private var cacheWidth = 0
    private var cacheHeight = 0
    private var cacheTop = 0
    private var cacheBottom = 0

    /**
     * Uses this view as the floor reflection of the Now Playing art: it shows only the rows
     * that are inside its parent, and fades out to the bottom of the parent. Its parent then
     * draws with the GPU (no software layer), so that the border is not cut off.
     */
    fun useAsReflection() {
        isReflection = true
        val parentView = parent as? View ?: return
        if (parentView.layerType != View.LAYER_TYPE_NONE) parentView.setLayerType(View.LAYER_TYPE_NONE, null)
        // The visible rows depend on the parent's height.
        parentView.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
            if (bottom - top != oldBottom - oldTop) invalidate()
        }
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        SmoothEdges.drawOutsideParents(this)
    }

    // The art can extend past the bounds, so this view never hides what is under it.
    override fun isOpaque(): Boolean = false

    @SuppressLint("DrawAllocation")
    override fun onDraw(canvas: Canvas) {
        val w = width
        val h = height
        val strip = visibleStrip(w, h)
        if (strip == null) return
        val d = drawable
        val canSmooth = scaleType == ScaleType.CENTER_CROP && paddingLeft == 0 && paddingTop == 0 &&
            paddingRight == 0 && paddingBottom == 0 && (d != null || artBackground != null)
        if (!canSmooth) {
            canvas.save()
            canvas.clipRect(0, strip.top, w, strip.bottom)
            artBackground?.let { it.setBounds(0, 0, w, h); it.draw(canvas) }
            super.onDraw(canvas)
            canvas.restore()
            return
        }

        var bmp = cache
        if (bmp == null || d !== cacheDrawable || w != cacheWidth || h != cacheHeight ||
            strip.top != cacheTop || strip.bottom != cacheBottom
        ) {
            val (bw, bh) = SmoothEdges.borderedSize(w, strip.bottom - strip.top, border)
            if (bmp == null || bmp.width != bw || bmp.height != bh) {
                // No recycle(): a display list that is not yet drawn can still use the old bitmap.
                bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
                cache = bmp
            } else {
                bmp.eraseColor(0)
            }
            renderArt(bmp, d, w, h, strip)
            cacheDrawable = d
            cacheWidth = w
            cacheHeight = h
            cacheTop = strip.top
            cacheBottom = strip.bottom
        }
        canvas.drawBitmap(bmp, -border.toFloat(), (strip.top - border).toFloat(), filterPaint)
    }

    private fun visibleStrip(w: Int, h: Int): SmoothEdges.Strip? {
        if (w <= 0 || h <= 0) return null
        if (!isReflection) return SmoothEdges.Strip(0, h, 0f, 0f)
        val parentView = parent as? View ?: return SmoothEdges.Strip(0, h, 0f, 0f)
        return SmoothEdges.visibleStrip(top, h, parentView.height, scaleY < 0f, pivotY)
    }

    /** Draws the visible rows of the art, centre-cropped to [w] x [h], inside the border of [bmp]. */
    private fun renderArt(bmp: Bitmap, d: Drawable?, w: Int, h: Int, strip: SmoothEdges.Strip) {
        val c = cacheCanvas
        c.setBitmap(bmp)
        c.save()
        c.translate(border.toFloat(), (border - strip.top).toFloat())
        c.clipRect(0, strip.top, w, strip.bottom)
        artBackground?.let { it.setBounds(0, 0, w, h); it.draw(c) }
        if (d != null) {
            // The drawable can be shared (the reflection shows the art's drawable), so keep its bounds.
            d.copyBounds(savedBounds)
            val box = SmoothEdges.centerCropBounds(d.intrinsicWidth, d.intrinsicHeight, w, h)
            d.setBounds(box.left, box.top, box.right, box.bottom)
            d.draw(c)
            d.bounds = savedBounds
        }
        if (isReflection && strip.clearY != strip.opaqueY) {
            fadePaint.shader = LinearGradient(
                0f, strip.opaqueY, 0f, strip.clearY,
                0xFF000000.toInt(), 0x00000000, Shader.TileMode.CLAMP
            )
            c.drawRect(0f, strip.top.toFloat(), w.toFloat(), strip.bottom.toFloat(), fadePaint)
        }
        c.restore()
        c.setBitmap(null)
    }
}
