package com.themoon.y1.views

/**
 * Colour rules for the Now Playing spectrum. Plain ARGB maths (no android.graphics), so it is unit-testable.
 */
object SpectrumColors {

    /** WCAG minimum contrast for graphics against the background. */
    const val MIN_CONTRAST = 3.0

    /** Below this chroma ((max - min) / 255) a colour counts as grey. */
    const val MIN_CHROMA = 0.15f

    /**
     * Colour for the spectrum bars: the cover colour, or [accent] if there is none or it is grey,
     * made darker or lighter (same hue) until it has [MIN_CONTRAST] against [background].
     */
    fun spectrumColor(coverColor: Int?, background: Int, accent: Int): Int {
        val base = if (coverColor == null || chroma(coverColor) < MIN_CHROMA) accent else coverColor
        return withContrast(base, background)
    }

    fun withContrast(color: Int, background: Int): Int {
        val opaque = color or OPAQUE
        val bg = background or OPAQUE
        if (contrast(opaque, bg) >= MIN_CONTRAST) return opaque
        // Move towards black on light backgrounds and towards white on dark ones
        val target = if (contrast(BLACK, bg) >= contrast(WHITE, bg)) BLACK else WHITE
        for (step in 1..20) {
            val candidate = blend(opaque, target, step / 20f)
            if (contrast(candidate, bg) >= MIN_CONTRAST) return candidate
        }
        return target
    }

    fun contrast(a: Int, b: Int): Double {
        val la = relativeLuminance(a)
        val lb = relativeLuminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    fun relativeLuminance(color: Int): Double =
        0.2126 * linear((color shr 16) and 0xFF) +
            0.7152 * linear((color shr 8) and 0xFF) +
            0.0722 * linear(color and 0xFF)

    fun chroma(color: Int): Float {
        val r = (color shr 16) and 0xFF
        val g = (color shr 8) and 0xFF
        val b = color and 0xFF
        return (maxOf(r, g, b) - minOf(r, g, b)) / 255f
    }

    private fun linear(channel: Int): Double {
        val c = channel / 255.0
        return if (c <= 0.03928) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)
    }

    private fun blend(from: Int, to: Int, t: Float): Int {
        fun mix(shift: Int): Int {
            val a = (from shr shift) and 0xFF
            val b = (to shr shift) and 0xFF
            return Math.round(a + (b - a) * t) and 0xFF
        }
        return OPAQUE or (mix(16) shl 16) or (mix(8) shl 8) or mix(0)
    }

    private const val OPAQUE = 0xFF000000.toInt()
    private const val BLACK = 0xFF000000.toInt()
    private const val WHITE = 0xFFFFFFFF.toInt()
}
