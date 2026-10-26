package com.themoon.y1.controllers.coverflow

/**
 * Decides what a Cover Flow step does to each card (no Android dependencies, so it can be unit-tested).
 * Each animator invalidates its view on each frame, so a step starts animators only for what changes.
 */
object CoverFlowSteps {
    enum class Change {
        /** Nothing to do. */
        NONE,
        /** Set the end values now, without an animator. */
        SET,
        /** Animate to the end values. */
        ANIMATE
    }

    /** Text of a card: only the old and the new centre card change. */
    fun textAnimation(shown: Boolean, show: Boolean, duration: Int): Change = when {
        duration <= 0 -> Change.SET
        shown == show -> Change.NONE
        else -> Change.ANIMATE
    }

    /** Position of a card: a card that stays hidden (alpha 0) moves without an animator. */
    fun cardAnimation(currentAlpha: Float, targetAlpha: Float, duration: Int): Change = when {
        duration <= 0 -> Change.SET
        currentAlpha <= 0f && targetAlpha <= 0f -> Change.SET
        else -> Change.ANIMATE
    }
}
