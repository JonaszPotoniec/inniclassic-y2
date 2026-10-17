package com.themoon.y1.views

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import androidx.core.graphics.PathParser

object TablerIcons {
    val USB: Array<String> = arrayOf(
        "M10 19a2 2 0 1 0 4 0a2 2 0 1 0 -4 0",
        "M12 17v-11.5",
        "M7 10v3l5 3",
        "M12 14.5l5 -2v-2.5",
        "M16 10h2v-2h-2l0 2",
        "M6 9a1 1 0 1 0 2 0a1 1 0 1 0 -2 0",
        "M10 5.5h4l-2 -2.5l-2 2.5"
    )
    val REFRESH: Array<String> = arrayOf(
        "M20 11a8.1 8.1 0 0 0 -15.5 -2m-.5 -4v4h4",
        "M4 13a8.1 8.1 0 0 0 15.5 2m.5 4v-4h-4"
    )
    val DOWNLOAD: Array<String> = arrayOf(
        "M4 17v2a2 2 0 0 0 2 2h12a2 2 0 0 0 2 -2v-2",
        "M7 11l5 5l5 -5",
        "M12 4l0 12"
    )
    val CHECK: Array<String> = arrayOf(
        "M5 12l5 5l10 -10"
    )
    val WIFI: Array<String> = arrayOf(
        "M12 18l0.01 0",
        "M9.172 15.172a4 4 0 0 1 5.656 0",
        "M6.343 12.343a8 8 0 0 1 11.314 0",
        "M3.515 9.515c4.686 -4.687 12.284 -4.687 17 0"
    )
    val SHUFFLE: Array<String> = arrayOf(
        "M18 4l3 3l-3 3",
        "M18 20l3 -3l-3 -3",
        "M3 7h3a5 5 0 0 1 5 5a5 5 0 0 0 5 5h5",
        "M21 7h-5a4.978 4.978 0 0 0 -3 1m-4 8a4.984 4.984 0 0 1 -3 1h-3"
    )
    val LOGIN: Array<String> = arrayOf(
        "M15 8v-2a2 2 0 0 0 -2 -2h-7a2 2 0 0 0 -2 2v12a2 2 0 0 0 2 2h7a2 2 0 0 0 2 -2v-2",
        "M21 12h-13l3 -3",
        "M11 15l-3 -3"
    )
    val BLUETOOTH: Array<String> = arrayOf(
        "M7 8l10 8l-5 4l0 -16l5 4l-10 8"
    )
    val HEADPHONES: Array<String> = arrayOf(
        "M4 16v-3a8 8 0 1 1 16 0v3",
        "M4 16a2 2 0 0 1 2 -2h1a2 2 0 0 1 2 2v3a2 2 0 0 1 -2 2h-1a2 2 0 0 1 -2 -2z",
        "M15 16a2 2 0 0 1 2 -2h1a2 2 0 0 1 2 2v3a2 2 0 0 1 -2 2h-1a2 2 0 0 1 -2 -2z"
    )
    val SEARCH: Array<String> = arrayOf(
        "M3 10a7 7 0 1 0 14 0a7 7 0 1 0 -14 0",
        "M21 21l-6 -6"
    )
    val TRASH: Array<String> = arrayOf(
        "M4 7h16",
        "M10 11v6",
        "M14 11v6",
        "M5 7l1 12a2 2 0 0 0 2 2h8a2 2 0 0 0 2 -2l1 -12",
        "M9 7v-3a1 1 0 0 1 1 -1h4a1 1 0 0 1 1 1v3"
    )
    val UNLINK: Array<String> = arrayOf(
        "M9 15l3 -3",
        "M14 10l1 -1",
        "M11 6l.463 -.536a5 5 0 0 1 7.071 7.072l-.534 .464",
        "M3 3l18 18",
        "M13 18l-.397 .534a5.068 5.068 0 0 1 -7.127 0a4.972 4.972 0 0 1 0 -7.071l.524 -.463"
    )
    val LOCK: Array<String> = arrayOf(
        "M5 13a2 2 0 0 1 2 -2h10a2 2 0 0 1 2 2v6a2 2 0 0 1 -2 2h-10a2 2 0 0 1 -2 -2z",
        "M11 16a1 1 0 1 0 2 0a1 1 0 0 0 -2 0",
        "M8 11v-4a4 4 0 1 1 8 0v4"
    )
    val CHECKBOX: Array<String> = arrayOf(
        "M3 5a2 2 0 0 1 2 -2h14a2 2 0 0 1 2 2v14a2 2 0 0 1 -2 2h-14a2 2 0 0 1 -2 -2z",
        "M9 12l2 2l4 -4"
    )
    val SQUARE_MINUS: Array<String> = arrayOf(
        "M3 5a2 2 0 0 1 2 -2h14a2 2 0 0 1 2 2v14a2 2 0 0 1 -2 2h-14a2 2 0 0 1 -2 -2z",
        "M9 12h6"
    )
    val SQUARE: Array<String> = arrayOf(
        "M3 5a2 2 0 0 1 2 -2h14a2 2 0 0 1 2 2v14a2 2 0 0 1 -2 2h-14a2 2 0 0 1 -2 -2z"
    )
    val CHEVRON_RIGHT: Array<String> = arrayOf(
        "M9 6l6 6l-6 6"
    )
    val DEVICE_TV: Array<String> = arrayOf(
        "M3 7a2 2 0 0 1 2 -2h14a2 2 0 0 1 2 2v9a2 2 0 0 1 -2 2h-14a2 2 0 0 1 -2 -2z",
        "M16 3l-4 4l-4 -4"
    )
    val MOVIE: Array<String> = arrayOf(
        "M4 4m0 2a2 2 0 0 1 2 -2h12a2 2 0 0 1 2 2v12a2 2 0 0 1 -2 2h-12a2 2 0 0 1 -2 -2z",
        "M8 4l0 16",
        "M16 4l0 16",
        "M4 8l4 0",
        "M4 16l4 0",
        "M4 12l16 0",
        "M16 8l4 0",
        "M16 16l4 0"
    )
    val DISC: Array<String> = arrayOf(
        "M12 12m-9 0a9 9 0 1 0 18 0a9 9 0 1 0 -18 0",
        "M12 12m-1 0a1 1 0 1 0 2 0a1 1 0 1 0 -2 0",
        "M7 12a5 5 0 0 1 5 -5",
        "M12 17a5 5 0 0 0 5 -5"
    )
    val USER: Array<String> = arrayOf(
        "M8 7a4 4 0 1 0 8 0a4 4 0 0 0 -8 0",
        "M6 21v-2a4 4 0 0 1 4 -4h4a4 4 0 0 1 4 4v2"
    )
    val MUSIC: Array<String> = arrayOf(
        "M3 17a3 3 0 1 0 6 0a3 3 0 0 0 -6 0",
        "M13 17a3 3 0 1 0 6 0a3 3 0 0 0 -6 0",
        "M9 17v-13h10v13",
        "M9 8h10"
    )
    val BOOK: Array<String> = arrayOf(
        "M3 19a9 9 0 0 1 9 0a9 9 0 0 1 9 0",
        "M3 6a9 9 0 0 1 9 0a9 9 0 0 1 9 0",
        "M3 6l0 13",
        "M12 6l0 13",
        "M21 6l0 13"
    )
    val FOLDER: Array<String> = arrayOf(
        "M5 4h4l3 3h7a2 2 0 0 1 2 2v8a2 2 0 0 1 -2 2h-14a2 2 0 0 1 -2 -2v-11a2 2 0 0 1 2 -2"
    )

    /**
     * Renders a Tabler icon centered on a rounded card tile.
     */

    fun renderTile(paths: Array<String>, tileSizePx: Int, iconSizePx: Int, iconColor: Int, bgColor: Int, radiusPx: Float): Bitmap {
        val bmp = Bitmap.createBitmap(tileSizePx, tileSizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = bgColor
            style = Paint.Style.FILL
        }
        val rect = RectF(0f, 0f, tileSizePx.toFloat(), tileSizePx.toFloat())
        canvas.drawRoundRect(rect, radiusPx, radiusPx, bgPaint)

        val iconBmp = render(paths, iconSizePx, iconColor)
        val offset = (tileSizePx - iconSizePx) / 2f
        canvas.drawBitmap(iconBmp, offset, offset, null)
        return bmp
    }

    /**
     * Renders a 24x24 stroke-2 Tabler icon to an ARGB_8888 bitmap scaled to sizePx.
     */

    fun render(paths: Array<String>, sizePx: Int, color: Int): Bitmap {
        val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            style = Paint.Style.STROKE
            strokeWidth = 2.0f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        val scale = sizePx / 24f
        canvas.scale(scale, scale)
        for (svgPath in paths) {
            try {
                val p: Path? = PathParser.createPathFromPathData(svgPath)
                if (p != null) {
                    canvas.drawPath(p, paint)
                }
            } catch (_: Throwable) {
            }
        }
        return bmp
    }
}
