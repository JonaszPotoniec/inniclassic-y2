package com.themoon.y1.controllers.coverflow

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.ComposeShader
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.drawable.Drawable

/**
 * The iPod floor reflection of a Cover Flow cover, drawn directly from the cover bitmap (no reflection bitmap):
 * the bottom rows of the cover, mirrored, from [CoverFlowGeometry.REFLECTION_TOP_ALPHA] opacity at the top
 * to 0 at the bottom (linear).
 *
 * One rectangle with a ComposeShader (cover BitmapShader DST_IN a LinearGradient), which Android 4.4 draws
 * with the GPU. The shaders are made again only when the bounds change, never for each frame.
 *
 * The intrinsic size is the reflection part of the cover bitmap (256 x 104 for a thumbnail), so it does not
 * change between albums. With [cover] null, it draws nothing and has the size of a [emptyCoverSize] cover.
 */
class CoverReflectionDrawable(
    val cover: Bitmap?,
    emptyCoverSize: Int
) : Drawable() {
    private val coverWidth = cover?.width ?: emptyCoverSize
    private val coverHeight = cover?.height ?: emptyCoverSize
    private val rows = CoverFlowGeometry.reflectionRows(coverHeight)

    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val coverShader = cover?.let { BitmapShader(it, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) }
    private val shaderMatrix = Matrix()
    private val shaderBounds = Rect()

    override fun draw(canvas: Canvas) {
        val shader = coverShader ?: return
        val b = bounds
        if (b.isEmpty) return
        if (paint.shader == null || b != shaderBounds) {
            val t = CoverFlowGeometry.reflectionTransform(
                coverWidth, coverHeight, b.left.toFloat(), b.top.toFloat(), b.width().toFloat(), b.height().toFloat()
            )
            shaderMatrix.setScale(t.scaleX, t.scaleY)
            shaderMatrix.postTranslate(t.translateX, t.translateY)
            shader.setLocalMatrix(shaderMatrix)
            val fade = LinearGradient(
                0f, b.top.toFloat(), 0f, b.bottom.toFloat(),
                CoverFlowGeometry.REFLECTION_TOP_ALPHA shl 24, 0, Shader.TileMode.CLAMP
            )
            // DST_IN: the cover pixels, with the opacity of the gradient.
            paint.shader = ComposeShader(shader, fade, PorterDuff.Mode.DST_IN)
            shaderBounds.set(b)
        }
        canvas.drawRect(b, paint)
    }

    override fun getIntrinsicWidth(): Int = coverWidth

    override fun getIntrinsicHeight(): Int = rows

    override fun setAlpha(alpha: Int) {
        if (paint.alpha != alpha) {
            paint.alpha = alpha
            invalidateSelf()
        }
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
