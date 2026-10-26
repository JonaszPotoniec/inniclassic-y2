package com.themoon.y1.controllers.coverflow

import org.junit.Assert.assertEquals
import org.junit.Test

class CoverFlowTitleTest {
    @Test
    fun format_putsSpacesAroundTheSlash() {
        assertEquals("Cover Flow (1 / 769)", CoverFlowTitle.format("Cover Flow", 1, 769))
        assertEquals("封面流 (12 / 30)", CoverFlowTitle.format("封面流", 12, 30))
    }
}
