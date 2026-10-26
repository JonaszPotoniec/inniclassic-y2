package com.themoon.y1.views

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The status bar icons (headphones, Wi-Fi, Bluetooth), drawn in the style of the iPod play/pause icon
 * ([IpodPlayStateView]): a blue gradient from top to bottom with a 1 px dark blue edge.
 * An inactive icon (Wi-Fi on but not connected) is the same shape, empty, with an edge in the battery
 * outline colour. The icons are drawn as paths at the final pixel size, so they stay sharp. The ink of each
 * icon fills its bitmap from the top row to the bottom row, so icons of one height share one box.
 *
 * All icons use one gradient ([gradient]) from the top to the bottom of the status item box, so a colour is at the
 * same height in each icon.
 *
 * The Wi-Fi fan has the proportions of the macOS symbol (SF Symbol "wifi"). The Bluetooth rune is the filled Material
 * icon (as on Android) with a 2 px stem and the dark edge of the fan; its tips go 1 px above and below the fan (optical
 * overshoot: pointed shapes look shorter). With an audio device connected it has the two dots of the Material "bluetooth_connected" icon.
 *
 * The geometry functions ([wifiGeometry], [bluetoothLayout]) have no Android dependencies,
 * so the unit tests can check them.
 */
object StatusIcons {
    enum class Icon { HEADPHONES, WIFI, BLUETOOTH }

    // The palette of the iPod play/pause icon.
    const val FILL_TOP = 0xFFA6D6FF.toInt()
    const val FILL_BOTTOM = 0xFF1F70D6.toInt()
    const val EDGE = 0xFF1A5DB0.toInt()

    // The battery outline colours (BatteryIconView), at 70 %: 3.8:1 on the Classic Light bar (#DEDEDE), 4.5:1 on the
    // Classic Dark bar (#1C1C1E).
    private const val EMPTY_EDGE_LIGHT = 0xB33C3C43.toInt()
    private const val EMPTY_EDGE_DARK = 0xB3AEAEB2.toInt()

    // Wi-Fi, measured on SF Symbol "wifi" (parts of the glyph height): a wedge with round corners and two arcs with
    // round ends. Arcs 0.19, the same gap between the wedge and the arcs (0.167); the arcs are centred 0.053 above the
    // bottom of the wedge. A 90° fan (half angle from the vertical): the ink of the round ends spans about 104°.
    const val WIFI_STROKE = 0.19f
    const val WIFI_GAP = 0.167f
    const val WIFI_BELOW = 0.053f
    const val WIFI_CORNER = 0.07f
    const val WIFI_HALF_ANGLE = 45f

    // Material "bluetooth" icon (24 x 24 viewBox, Apache 2.0): the outline and the two triangle holes (even-odd).
    // The ink is x 5..17.71, y 2..22; the stem is x 11..13 (the bars of the rune are 2 units wide).
    internal val MATERIAL_RUNE = arrayOf(
        floatArrayOf(
            17.71f, 7.71f, 12f, 2f, 11f, 2f, 11f, 9.59f, 6.41f, 5f, 5f, 6.41f, 10.59f, 12f, 5f, 17.59f, 6.41f, 19f,
            11f, 14.41f, 11f, 22f, 12f, 22f, 17.71f, 16.29f, 13.41f, 12f
        ),
        floatArrayOf(13f, 5.83f, 14.88f, 7.71f, 13f, 9.59f),
        floatArrayOf(14.88f, 16.29f, 13f, 18.17f, 13f, 14.41f)
    )
    private const val MATERIAL_STEM_LEFT = 11f
    private const val MATERIAL_TOP = 2f
    private const val MATERIAL_HEIGHT = 20f
    private const val MATERIAL_BAR = 2f
    private const val MATERIAL_ARM_END = 5f
    private const val MATERIAL_TIP = 17.71f
    // Centres of the dots of "bluetooth_connected" (diamonds at x 5 and 19, y 12).
    private const val MATERIAL_DOT_LEFT = 5f
    private const val MATERIAL_DOT_RIGHT = 19f
    // Stem width / height: 2 px at the status bar heights (17 and 18 px with the overshoot), and the edge.
    const val RUNE_STEM = 0.135f
    const val RUNE_EDGE = 1f

    /** The gradient of all status icons, from the top to the bottom of the status item box (y in the bitmap). */
    fun gradient(boxTop: Float, boxBottom: Float): LinearGradient =
        LinearGradient(0f, boxTop, 0f, boxBottom, FILL_TOP, FILL_BOTTOM, Shader.TileMode.CLAMP)

    /** Rows of the bitmap above and below the [heightPx] box of the round icons (optical overshoot of the rune). */
    fun overshoot(icon: Icon, heightPx: Int): Int =
        if (icon == Icon.BLUETOOTH) max(1, (heightPx / 15f).roundToInt()) else 0

    fun bitmapHeight(icon: Icon, heightPx: Int): Int = heightPx + 2 * overshoot(icon, heightPx)

    /**
     * Top and bottom of the status item box in the bitmap of [icon]: the round icons are [heightPx] tall with their
     * bottom on the box ([boxHeightPx] tall), plus the overshoot.
     */
    fun boxInBitmap(icon: Icon, heightPx: Int, boxHeightPx: Int): Pair<Float, Float> {
        val bottom = (overshoot(icon, heightPx) + heightPx).toFloat()
        return Pair(bottom - boxHeightPx, bottom)
    }

    /** Rounds to a quarter pixel, so that the shapes are the same at each draw and in the tests. */
    internal fun quarter(x: Float): Float = floor(x * 4f + 0.5f) / 4f

    /**
     * Wi-Fi fan [height] tall, from the top of the outer arc to the bottom of the wedge. Radii are from the centre of
     * the arcs ([centreY] from the top); the arc radii are to the centre line of each arc. The wedge is a 90° sector
     * below the arcs with corners of radius [corner]: a core sector ([wedgeApexY] = its apex below the centre,
     * [wedgeCoreRadius], [wedgeSide] = length of its sides) grown by [corner] with round joins.
     */
    class WifiGeometry(
        val stroke: Float,
        val gap: Float,
        val below: Float,
        val corner: Float,
        val halfAngleDeg: Float,
        height: Float
    ) {
        /** Radius of the top of the wedge. */
        val wedge = height - below - 2f * gap - 2f * stroke
        val arcRadius1 = wedge + gap + stroke / 2f
        val arcRadius2 = arcRadius1 + stroke + gap
        val height = below + arcRadius2 + stroke / 2f
        val centreY = height - below
        /** Width of the ink: the round ends of the outer arc are the widest points. */
        val width = 2f * (arcRadius2 * sin(Math.toRadians(halfAngleDeg.toDouble())).toFloat() + stroke / 2f)
        val wedgeApexY = below - corner
        val wedgeCoreRadius = wedge - corner
        val wedgeSide: Float = run {
            // The core sides go up at 45° from the apex to the circle of wedgeCoreRadius.
            val s45 = sqrt(0.5f)
            wedgeApexY * s45 + sqrt(wedgeCoreRadius * wedgeCoreRadius - wedgeApexY * wedgeApexY / 2f)
        }
    }

    fun wifiGeometry(height: Float): WifiGeometry = WifiGeometry(
        quarter(WIFI_STROKE * height), quarter(WIFI_GAP * height), quarter(WIFI_BELOW * height),
        max(0.75f, quarter(WIFI_CORNER * height)), WIFI_HALF_ANGLE, height
    )

    /**
     * The Material rune in a bitmap [heightPx] tall, in the style of the fan: a dark edge [edge] px wide around a
     * core with the gradient. The core is the Material shape scaled by [scale]; the edge is the core grown by [edge]
     * (a stroke of the outline only: the triangle holes keep their size and stay open). The ink is [heightPx] tall
     * and its stem [stem] whole pixels wide from [stemLeft] (a pixel edge): the stem is sharp. With [connected], a dot
     * of [dotRadius] left and right at mid-height, at the places of the Material icon (not nearer to the rune), on
     * pixel centres. The ink touches the left and right edges of the bitmap (within one pixel).
     */
    class BluetoothLayout(
        val width: Int,
        val stemLeft: Float,
        val stem: Float,
        val scale: Float,
        val edge: Float,
        /** Ink left and right of [stemLeft]. */
        val inkLeft: Float,
        val inkRight: Float,
        val dotRadius: Float,
        /** Centres of the dots, or empty when not connected. */
        val dotX: FloatArray,
        val dotY: Float
    ) {
        /** Point (x, y) of the Material viewBox in the bitmap (on the core). */
        fun x(u: Float): Float = stemLeft + edge + (u - MATERIAL_STEM_LEFT) * scale
        fun y(v: Float): Float = edge + (v - MATERIAL_TOP) * scale
    }

    fun bluetoothLayout(heightPx: Int, connected: Boolean): BluetoothLayout {
        val h = heightPx.toFloat()
        // The stem of the shape (at least h / 10, the bars of the shape at this scale) and 1 px of edge.
        val stem = max(max(1f, (RUNE_STEM * h).roundToInt().toFloat()), ceil(h / 10f - 1e-4f)) + RUNE_EDGE
        // Ink height: (20 - 2) * scale + stem = h; ink stem: 2 * scale + 2 * edge = stem.
        val scale = (h - stem) / (MATERIAL_HEIGHT - MATERIAL_BAR)
        val edge = (stem - MATERIAL_BAR * scale) / 2f
        // The arm ends and the right tips are 90° corners: their miter goes edge * sqrt(2) past the corner.
        val miter = edge * sqrt(2f)
        val inkLeft = (MATERIAL_STEM_LEFT - MATERIAL_ARM_END) * scale - edge + miter
        val inkRight = edge + (MATERIAL_TIP - MATERIAL_STEM_LEFT) * scale + miter
        val dotRadius = stem / 2f
        // Pixel centres, rounded away from the rune.
        val dl = floor(edge + (MATERIAL_DOT_LEFT - MATERIAL_STEM_LEFT) * scale - 0.5f) + 0.5f
        val dr = ceil(edge + (MATERIAL_DOT_RIGHT - MATERIAL_STEM_LEFT) * scale - 0.5f) + 0.5f
        var left = inkLeft
        var right = inkRight
        if (connected) {
            left = max(left, -dl + dotRadius)
            right = max(right, dr + dotRadius)
        }
        val stemLeft = ceil(left - 1e-4f)
        val width = ceil(stemLeft + right - 1e-4f).toInt()
        val dots = if (connected) floatArrayOf(stemLeft + dl, stemLeft + dr) else FloatArray(0)
        return BluetoothLayout(width, stemLeft, stem, scale, edge, inkLeft, inkRight, dotRadius, dots, h / 2f)
    }

    /** Width in pixels of [icon] drawn [heightPx] tall. */
    fun widthFor(icon: Icon, heightPx: Int, connected: Boolean = false): Int = when (icon) {
        // The edge is 1 px: the shape is 1 px smaller than the ink.
        Icon.WIFI -> ceil(wifiGeometry(heightPx - 1f).width + 1f).toInt()
        // The shape is designed in an 18 x 18 box.
        Icon.HEADPHONES -> heightPx
        Icon.BLUETOOTH -> bluetoothLayout(bitmapHeight(icon, heightPx), connected).width
    }

    /**
     * Draws [icon] into a new bitmap: [heightPx] tall (the round icons), plus the overshoot of the rune
     * ([bitmapHeight]). [lightBar] = dark content on a light status bar. [connected] = an audio device is connected
     * (Bluetooth only). [boxHeightPx] = the status item box, for the gradient (its bottom = the bottom of the round
     * icons).
     */
    fun render(
        icon: Icon,
        heightPx: Int,
        active: Boolean,
        lightBar: Boolean,
        connected: Boolean = false,
        boxHeightPx: Int = heightPx
    ): Bitmap {
        val h = heightPx.coerceAtLeast(1)
        val w = widthFor(icon, h, connected).coerceAtLeast(1)
        val bh = bitmapHeight(icon, h)
        val bmp = Bitmap.createBitmap(w, bh, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val inactive = if (lightBar) EMPTY_EDGE_LIGHT else EMPTY_EDGE_DARK
        val box = boxInBitmap(icon, h, boxHeightPx.coerceIn(1, h))
        val shader = gradient(box.first, box.second)

        if (icon == Icon.BLUETOOTH) {
            drawBluetooth(canvas, bluetoothLayout(bh, connected), shader, if (active) null else inactive)
            return bmp
        }

        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            this.shader = shader
        }
        val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1f
            color = if (active) EDGE else inactive
        }
        if (icon == Icon.HEADPHONES) {
            // Band and cups overlap. Draw a 2 px edge, then the fill over it: the fill covers the inner half of the
            // edge and the edges inside the overlap. (Path.op can loop for ever in the Skia of Android 4.4.)
            val shape = headphones(w.toFloat(), h.toFloat())
            edge.strokeWidth = 2f
            canvas.drawPath(shape, edge)
            if (!active) fill.apply { this.shader = null; xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR) }
            canvas.drawPath(shape, fill)
        } else {
            drawWifi(canvas, w.toFloat(), h.toFloat(), active, fill, edge)
        }
        return bmp
    }

    /**
     * The wedge and the two arcs (round ends) of [wifiGeometry], 0.5 px inside the bitmap for the edge.
     * Arcs: filled, then a 1 px edge. Wedge (round corners from a stroke with round joins, so the edge cannot be a
     * stroke of its outline): the edge colour grown by corner + 0.5, then the fill (or CLEAR when empty) grown by
     * corner - 0.5. The wedge and the arcs do not overlap.
     */
    private fun drawWifi(canvas: Canvas, w: Float, h: Float, active: Boolean, fill: Paint, edge: Paint) {
        val g = wifiGeometry(h - 1f)
        val cx = w / 2f
        val cy = 0.5f + g.centreY
        val line = Paint().apply {
            style = Paint.Style.STROKE
            strokeWidth = g.stroke
            strokeCap = Paint.Cap.ROUND
        }
        val arcs = Path()
        for (r in floatArrayOf(g.arcRadius1, g.arcRadius2)) {
            val arc = Path().apply {
                arcTo(RectF(cx - r, cy - r, cx + r, cy + r), -90f - g.halfAngleDeg, 2f * g.halfAngleDeg, true)
            }
            val band = Path()
            line.getFillPath(arc, band)
            arcs.addPath(band)
        }

        val s45 = sqrt(0.5f)
        val t = g.wedgeSide
        val p = g.wedgeApexY
        val rho = g.wedgeCoreRadius
        val right = Math.toDegrees(atan2((p - t * s45).toDouble(), (t * s45).toDouble())).toFloat()
        val left = Math.toDegrees(atan2((p - t * s45).toDouble(), (-t * s45).toDouble())).toFloat()
        val core = Path().apply {
            moveTo(cx, cy + p)
            lineTo(cx + t * s45, cy + p - t * s45)
            arcTo(RectF(cx - rho, cy - rho, cx + rho, cy + rho), right, left - right, false)
            close()
        }
        val dark = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = edge.color
            style = Paint.Style.FILL
        }
        canvas.drawPath(core, dark)
        dark.style = Paint.Style.STROKE
        dark.strokeWidth = 2f * g.corner + 1f
        dark.strokeJoin = Paint.Join.ROUND
        canvas.drawPath(core, dark)
        val inner = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            if (active) shader = fill.shader else xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
            style = Paint.Style.FILL
        }
        canvas.drawPath(core, inner)
        inner.style = Paint.Style.STROKE
        inner.strokeWidth = 2f * g.corner - 1f
        inner.strokeJoin = Paint.Join.ROUND
        canvas.drawPath(core, inner)

        if (active) canvas.drawPath(arcs, fill)
        canvas.drawPath(arcs, edge)
    }

    /**
     * The filled rune (even-odd: the two triangles are holes; not Path.op): the edge colour on the core grown by the
     * edge (a stroke of the outline only, so the holes stay open), then the core with the status icon [shader].
     * The dots: the edge colour, then the gradient 0.5 px inside (as the 1 px edge of the fan). [inactiveColor] =
     * grey, without the gradient.
     */
    private fun drawBluetooth(canvas: Canvas, layout: BluetoothLayout, shader: Shader, inactiveColor: Int?) {
        fun path(polys: List<FloatArray>) = Path().apply {
            fillType = Path.FillType.EVEN_ODD
            for (poly in polys) {
                moveTo(layout.x(poly[0]), layout.y(poly[1]))
                var i = 2
                while (i < poly.size) {
                    lineTo(layout.x(poly[i]), layout.y(poly[i + 1]))
                    i += 2
                }
                close()
            }
        }
        val core = path(MATERIAL_RUNE.toList())
        val dark = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = inactiveColor ?: EDGE
        }
        canvas.drawPath(core, dark)
        if (layout.edge > 0f) {
            // A separate stroke, not FILL_AND_STROKE: the result does not depend on how Skia combines the two.
            dark.style = Paint.Style.STROKE
            dark.strokeWidth = 2f * layout.edge
            dark.strokeJoin = Paint.Join.MITER
            canvas.drawPath(path(listOf(MATERIAL_RUNE[0])), dark)
            dark.style = Paint.Style.FILL
        }
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            this.shader = shader
        }
        if (inactiveColor == null) canvas.drawPath(core, fill)
        for (x in layout.dotX) {
            canvas.drawCircle(x, layout.dotY, layout.dotRadius, dark)
            if (inactiveColor == null) canvas.drawCircle(x, layout.dotY, layout.dotRadius - 0.5f, fill)
        }
    }

    /** A head band and two ear cups (they overlap; render() hides the edges inside). */
    private fun headphones(w: Float, h: Float): Path {
        val u = h / 18f
        val cupW = 4.4f * u
        val cupH = 7.6f * u
        val bandW = 2.4f * u
        val left = 0.5f + 0.2f * u
        val right = w - 0.5f - 0.2f * u
        val cupTop = h - 0.5f - cupH
        val r = (right - left - bandW) / 2f
        val cx = w / 2f
        // The band touches the top of the box and the cups its bottom, like the ink of the other icons.
        val cy = 0.5f + bandW / 2f + r

        val arc = Path().apply {
            moveTo(cx - r, cupTop + 2f * u)
            lineTo(cx - r, cy)
            arcTo(RectF(cx - r, cy - r, cx + r, cy + r), 180f, 180f, false)
            lineTo(cx + r, cupTop + 2f * u)
        }
        val band = Path()
        Paint().apply {
            style = Paint.Style.STROKE
            strokeWidth = bandW
            strokeCap = Paint.Cap.BUTT
            strokeJoin = Paint.Join.MITER
        }.getFillPath(arc, band)

        val cups = Path().apply {
            val cr = 1.4f * u
            addRoundRect(RectF(left, cupTop, left + cupW, h - 0.5f), cr, cr, Path.Direction.CW)
            addRoundRect(RectF(right - cupW, cupTop, right, h - 0.5f), cr, cr, Path.Direction.CW)
        }
        // Not Path.op: see render()
        band.addPath(cups)
        return band
    }
}
