package com.themoon.y1.views

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IpodClassicMetricsTest {

    @Test
    fun metricsAreTheIpodScreenTimesOnePointFive() {
        assertEquals(30, IpodClassicMetrics.STATUS_BAR_HEIGHT)
        assertEquals(240, IpodClassicMetrics.PANE_WIDTH)
        assertEquals(IpodClassicMetrics.TEXT_LEFT, IpodClassicMetrics.PANE_WIDTH)
        // Eight rows fill the list below the status bar, like the iPod (25 px rows below a 20 px bar).
        assertTrue(IpodClassicMetrics.STATUS_BAR_HEIGHT + 8 * IpodClassicMetrics.ROW_HEIGHT <= 360)
        assertEquals(27, IpodClassicMetrics.NP_ARTIST_BASELINE - IpodClassicMetrics.NP_TITLE_BASELINE)
        assertEquals(27, IpodClassicMetrics.NP_ALBUM_BASELINE - IpodClassicMetrics.NP_ARTIST_BASELINE)
        assertEquals(54, IpodClassicMetrics.NP_COUNT_BASELINE - IpodClassicMetrics.NP_ALBUM_BASELINE)
        assertEquals(
            IpodClassicMetrics.TIME_RIGHT,
            IpodClassicMetrics.PROGRESS_LEFT + IpodClassicMetrics.PROGRESS_WIDTH + 62
        )
    }

    @Test
    fun capCentredPaddingPutsTheCapsOnTheRowCentre() {
        // 38 px row, ascent -22, caps 16 px: baseline at 5 + 22 = 27, caps 11..27, centre 19.
        val top = IpodClassicMetrics.capCentredPaddingTop(38f, -22, 16f)
        assertEquals(5, top)
        val baseline = top + 22
        assertEquals(19f, baseline - 16f / 2f, 0.001f)
    }

    @Test
    fun capCentredPaddingFixesTheDefaultRowOffset() {
        // Nimbus Sans: hhea ascent 729 = cap height 729, descent 271 (per 1000). At 21 px Android
        // gives FontMetricsInt ascent -16, descent 6; the caps are 15.3 px.
        val ascent = -16
        val descent = 6
        val cap = 21 * 0.729f
        // Today the text box is centred in the 33 px row: the caps are 3 px high (about 6 above, 12 below).
        val boxTop = (33 - (descent - ascent)) / 2
        val capTopToday = boxTop - ascent - cap
        assertEquals(5.7f, capTopToday, 0.1f)
        assertEquals(12f, 33 - (capTopToday + cap), 0.1f)
        // With the cap-centred padding the space above and below the caps differs by less than 1 px.
        val top = IpodClassicMetrics.capCentredPaddingTop(33f, ascent, cap)
        val capTop = top - ascent - cap
        val below = 33 - (capTop + cap)
        assertTrue(Math.abs(capTop - below) < 1f)
    }

    @Test
    fun textTopAndGlyphCentre() {
        assertEquals(106 - 22, IpodClassicMetrics.textTopForBaseline(106, -22))
        // A star from 13 px above the baseline to 1 px below it, centred on y = 181.
        val baseline = IpodClassicMetrics.baselineForGlyphCentre(181, -13, 1)
        assertEquals(187, baseline)
    }

    @Test
    fun stackTopMarginsPlaceEachViewAtItsTop() {
        val margins = IpodClassicMetrics.stackTopMargins(52, intArrayOf(84, 115, 142), intArrayOf(27, 22, 22))
        assertArrayEquals(intArrayOf(32, 4, 5), margins)
    }

    @Test
    fun scaleTextSizeToHeight() {
        assertEquals(20f, IpodClassicMetrics.scaleTextSizeToHeight(19f, 13.3f, 14f), 0.01f)
        assertEquals(19f, IpodClassicMetrics.scaleTextSizeToHeight(19f, 0f, 14f), 0.0f)
    }

    @Test
    fun artTiltMatchesApplesNowPlayingImage() {
        val side = IpodClassicMetrics.ART_SIZE.toFloat()
        val tilt = IpodClassicMetrics.artTilt(side)
        assertEquals(IpodClassicMetrics.TILT_ANGLE_DEG, tilt.rotationY, 0f)

        // Left edge is the pivot: full height, no movement.
        val (leftX, leftScale) = IpodClassicMetrics.projectTilted(tilt, 0f)
        assertEquals(0f, leftX, 0.001f)
        assertEquals(1f, leftScale, 0.0001f)

        // Right edge: about 13% shorter (Apple: 119 / 136 px), and the art keeps its width.
        val (rightX, rightScale) = IpodClassicMetrics.projectTilted(tilt, side)
        assertEquals(119f / 136f, rightScale, 0.0001f)
        assertEquals(side, rightX, 0.01f)
        assertEquals(178.5f, side * rightScale, 0.01f)
    }

    @Test
    fun artTiltPictureDoesNotDependOnTheAngle() {
        val side = 204f
        val a = IpodClassicMetrics.artTilt(side, angleDeg = 10f)
        val b = IpodClassicMetrics.artTilt(side, angleDeg = 40f)
        for (u in floatArrayOf(0f, 51f, 102f, 153f, 204f)) {
            val pa = IpodClassicMetrics.projectTilted(a, u)
            val pb = IpodClassicMetrics.projectTilted(b, u)
            assertEquals(pa.first, pb.first, 0.01f)
            assertEquals(pa.second, pb.second, 0.0001f)
        }
    }

    @Test
    fun cameraDistanceSettingConvertsPixelsForAndroid() {
        // Android: camera z = -distance / densityDpi inches; Skia uses 72 px per inch.
        assertEquals(1280f, IpodClassicMetrics.cameraDistanceSetting(576f, 160), 0.001f)
        val tilt = IpodClassicMetrics.artTilt(204f)
        assertEquals(tilt.cameraDistancePx * 160f / 72f, IpodClassicMetrics.cameraDistanceSetting(tilt.cameraDistancePx, 160), 0.001f)
    }
}
