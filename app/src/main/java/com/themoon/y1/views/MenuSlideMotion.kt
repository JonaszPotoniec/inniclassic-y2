package com.themoon.y1.views

/**
 * Timing and positions of the menu slides ([MenuSlide]), as measured frame by frame on an iPod classic video
 * (30 fps, so each value is ±33 ms; `docs/ipod-classic-ui.md`, *Transitions*).
 *
 * The two screens of a slide are the upper list (the shallow screen, `A`) and the deeper list (`B`). A push
 * brings B in; Back (a pop) takes B out again. [depth] is how far B has come in (0 = A shows, 1 = B shows).
 * Pure: no Android classes, so the unit tests can check it.
 */
object MenuSlideMotion {

    enum class Kind {
        /** Both screens are split screens with the art pane at the same place: only the list pane slides. */
        PANE,

        /** Neither screen has an art pane: the full width slides (A and B side by side). */
        FULL,

        /**
         * A is a split screen and B has the full width: B comes in from the right over the still art, and pushes
         * A's list pane out to the left when it reaches it.
         */
        SPLIT_TO_FULL,

        /**
         * A is a split screen and B is a full screen that shows from behind it (Cover Flow, Now Playing): A's list
         * pane goes out to the left, the art pane goes out to the right a little later, and B shows between them. Back is the
         * same motion in reverse.
         */
        SPLIT_APART
    }

    /** Main menu → Music: decelerates, 1 − (1 − t)². iPod: ~7 frames. */
    const val PANE_MS = 240L

    /** Music → Albums: constant speed. iPod: 3.6 screen widths a second. */
    const val FULL_MS = 280L

    /** The split apart (iPod: Music → Cover Flow): accelerates, t². iPod: ~6 frames. */
    const val SPLIT_APART_IN_MS = 200L

    /** The art pane starts this much after the list pane (iPod: about one frame). */
    const val SPLIT_APART_ART_DELAY_MS = 33L

    /** Back from the split apart (iPod: Cover Flow → Music): the entry in reverse, a little slower. iPod: ~8 frames. */
    const val SPLIT_APART_OUT_MS = 270L

    fun decelerate(t: Float): Float {
        val c = t.coerceIn(0f, 1f)
        return 1f - (1f - c) * (1f - c)
    }

    fun accelerate(t: Float): Float {
        val c = t.coerceIn(0f, 1f)
        return c * c
    }

    fun durationMs(kind: Kind, push: Boolean): Long = when (kind) {
        Kind.PANE -> PANE_MS
        Kind.FULL, Kind.SPLIT_TO_FULL -> FULL_MS
        Kind.SPLIT_APART -> if (push) SPLIT_APART_IN_MS + SPLIT_APART_ART_DELAY_MS else SPLIT_APART_OUT_MS
    }

    /**
     * How far B has come in at [elapsedMs] after the start: [out] gets the depth of the list part at 0 and of the
     * art part at 1 (the same value except for [Kind.SPLIT_APART]). Push: 0 → 1; pop: 1 → 0. The pop of a list
     * slide has the same deceleration as the push (the iPod Back was not measured); the Back of the split apart is
     * its entry in reverse, as the Cover Flow exit on the iPod.
     */
    fun depth(kind: Kind, push: Boolean, elapsedMs: Long, out: FloatArray) {
        val d = durationMs(kind, push).toFloat()
        val t = elapsedMs.toFloat()
        when (kind) {
            Kind.PANE -> {
                val p = decelerate(t / d)
                out[0] = if (push) p else 1f - p
                out[1] = out[0]
            }
            Kind.FULL, Kind.SPLIT_TO_FULL -> {
                val p = (t / d).coerceIn(0f, 1f)
                out[0] = if (push) p else 1f - p
                out[1] = out[0]
            }
            Kind.SPLIT_APART -> {
                val inTotal = (SPLIT_APART_IN_MS + SPLIT_APART_ART_DELAY_MS).toFloat()
                // The time of the entry that shows now: forward for a push, backward (and slower) for Back.
                val tau = if (push) t else inTotal * (1f - (t / d).coerceIn(0f, 1f))
                out[0] = accelerate(tau / SPLIT_APART_IN_MS)
                out[1] = accelerate((tau - SPLIT_APART_ART_DELAY_MS) / SPLIT_APART_IN_MS)
            }
        }
    }

    /** True while the slide is not at its end at [elapsedMs]. */
    fun running(kind: Kind, push: Boolean, elapsedMs: Long): Boolean = elapsedMs < durationMs(kind, push)

    /**
     * The x offsets (px) at depth [listDepth] (and [artDepth] for the split apart) for a slide area [width] wide whose
     * split screens have their list pane [pane] wide. [out]: 0 = A (for split screens: A's list pane),
     * 1 = B, 2 = the art pane of the split screen (split apart only; else 0). [artLeft] (split apart only): the x of
     * the left edge of the art at art offset 0. The art layer also draws the art left of the pane edge, so the art
     * goes right until this edge is at [width]: all of the art leaves the screen, not only the pane.
     */
    fun offsets(
        kind: Kind, listDepth: Float, artDepth: Float, width: Float, pane: Float, out: FloatArray,
        artLeft: Float = pane
    ) {
        when (kind) {
            Kind.PANE -> {
                out[0] = -pane * listDepth
                out[1] = pane * (1f - listDepth)
                out[2] = 0f
            }
            Kind.FULL -> {
                out[0] = -width * listDepth
                out[1] = width * (1f - listDepth)
                out[2] = 0f
            }
            Kind.SPLIT_TO_FULL -> {
                val b = width * (1f - listDepth)
                out[0] = minOf(0f, b - pane)
                out[1] = b
                out[2] = 0f
            }
            Kind.SPLIT_APART -> {
                out[0] = -pane * listDepth
                out[1] = 0f
                out[2] = (width - minOf(artLeft, pane)) * artDepth
            }
        }
    }

    /**
     * The columns of a list pane [pane] px wide, moved by [offset] px, that show: the columns left of the pane edge
     * (the art pane does not move, and a list never draws over it). From the left edge of the pane bitmap to the
     * returned column. Between split screens B's list comes in at the pane edge (offset = [pane]: no column shows),
     * not from the right edge of the screen.
     */
    fun paneColumns(offset: Float, pane: Float): Float = (pane - offset).coerceIn(0f, pane)

    /**
     * The art layer of the split apart for a slide area [width] wide whose art pane starts at [pane]: [out] gets
     * 0 = the x of the layer, 1 = its width, 2 = the x of the art pane in the layer. The art pane stays [width] − [pane]
     * wide (the same CENTER_CROP, zoom and motion as the pane), but the layer also draws the art left of the pane
     * (the part that the list pane hides). The layer covers the full slide area at each art offset from 0 to
     * [width] − [pane], so the art shows up to its own left edge as the list goes out and as the art goes right.
     */
    fun splitApartArtLayer(width: Int, pane: Int, out: IntArray) {
        out[0] = pane - width
        out[1] = 2 * width - pane
        out[2] = width
    }

    /**
     * The kind of slide between A and B. [paneA], [paneB]: the x of the art pane of each screen (its list pane
     * width), or null when the screen has no art pane. [coverFlowB], [nowPlayingB]: B is Cover Flow or Now Playing,
     * which a split screen shows with the split apart. Else Cover Flow cuts, and Now Playing slides as a full-width
     * screen. Null: no slide (a cut).
     */
    fun kindOf(
        paneA: Int?, paneB: Int?, coverFlowA: Boolean, coverFlowB: Boolean, nowPlayingB: Boolean = false
    ): Kind? = when {
        coverFlowA -> null
        (coverFlowB || nowPlayingB) && paneA != null -> Kind.SPLIT_APART
        coverFlowB -> null
        paneA != null && paneB != null && kotlin.math.abs(paneA - paneB) <= 2 -> Kind.PANE
        paneA != null && paneB == null -> Kind.SPLIT_TO_FULL
        else -> Kind.FULL
    }
}
