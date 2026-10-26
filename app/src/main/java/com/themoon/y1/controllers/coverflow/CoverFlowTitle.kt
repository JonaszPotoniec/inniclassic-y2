package com.themoon.y1.controllers.coverflow

/** Status bar title of Cover Flow (no Android dependencies, so it can be unit-tested). */
object CoverFlowTitle {
    /**
     * "Cover Flow (1 / 769)". The spaces around the slash keep the spacing even: in the Classic theme font
     * (Nimbus Sans) the digits all have the same advance, so "1" has a large gap on its right, and the
     * slash has almost no side bearing. Android 4.4 does not apply the font's kerning.
     */
    fun format(name: String, position: Int, total: Int): String = "$name ($position / $total)"
}
