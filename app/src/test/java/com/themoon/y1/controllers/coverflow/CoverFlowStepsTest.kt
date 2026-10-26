package com.themoon.y1.controllers.coverflow

import com.themoon.y1.controllers.coverflow.CoverFlowSteps.Change
import org.junit.Assert.assertEquals
import org.junit.Test

class CoverFlowStepsTest {

    @Test
    fun text_animatesOnlyTheOldAndNewCentre() {
        assertEquals(Change.NONE, CoverFlowSteps.textAnimation(shown = false, show = false, duration = 160))
        assertEquals(Change.NONE, CoverFlowSteps.textAnimation(shown = true, show = true, duration = 45))
        assertEquals(Change.ANIMATE, CoverFlowSteps.textAnimation(shown = true, show = false, duration = 160))
        assertEquals(Change.ANIMATE, CoverFlowSteps.textAnimation(shown = false, show = true, duration = 160))
    }

    @Test
    fun text_withoutAnimationIsSet() {
        assertEquals(Change.SET, CoverFlowSteps.textAnimation(shown = false, show = false, duration = 0))
        assertEquals(Change.SET, CoverFlowSteps.textAnimation(shown = false, show = true, duration = 0))
    }

    @Test
    fun card_hiddenCardsMoveWithoutAnimator() {
        assertEquals(Change.SET, CoverFlowSteps.cardAnimation(0f, 0f, 160))
        assertEquals(Change.ANIMATE, CoverFlowSteps.cardAnimation(0f, 1f, 160))
        assertEquals(Change.ANIMATE, CoverFlowSteps.cardAnimation(1f, 0f, 160))
        assertEquals(Change.ANIMATE, CoverFlowSteps.cardAnimation(1f, 1f, 45))
        assertEquals(Change.SET, CoverFlowSteps.cardAnimation(1f, 1f, 0))
    }

    @Test
    fun ninePositionsStepToAtMostEightCardAnimators() {
        // A step: the cards at distance 0..3 are visible (alpha 1), distance 4 is hidden.
        fun alpha(dist: Int) = if (dist <= CoverFlowGeometry.SIDE_COUNT) 1f else 0f
        var cardAnimators = 0
        for (i in 0 until 9) {
            val before = alpha(Math.abs(i + 1 - 4).coerceAtMost(4)) // the cards before the step move one place left
            val after = alpha(Math.abs(i - 4))
            if (CoverFlowSteps.cardAnimation(before, after, 160) == Change.ANIMATE) cardAnimators++
        }
        assertEquals(8, cardAnimators)
    }
}
