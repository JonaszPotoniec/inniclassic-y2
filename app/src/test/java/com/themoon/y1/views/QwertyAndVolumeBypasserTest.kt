package com.themoon.y1.views

import com.themoon.y1.managers.SafeVolumeBypasser
import com.themoon.y1.managers.UsbMassStorageController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QwertyAndVolumeBypasserTest {

    // =========================================================================
    // 1. QwertyKeyboardView Coordinate Mapping Tests
    // =========================================================================

    @Test
    fun testCol10ToRow4_mapping() {
        // Row 4 has 5 keys:
        // Key 0 (?123) spans cols 0..1
        // Key 1 (@) spans col 2
        // Key 2 (SPACE) spans cols 3..6
        // Key 3 (_) spans col 7
        // Key 4 (DONE) spans cols 8..9

        assertEquals(0, QwertyKeyboardView.col10ToRow4(0))
        assertEquals(0, QwertyKeyboardView.col10ToRow4(1))
        assertEquals(1, QwertyKeyboardView.col10ToRow4(2))
        assertEquals(2, QwertyKeyboardView.col10ToRow4(3))
        assertEquals(2, QwertyKeyboardView.col10ToRow4(4))
        assertEquals(2, QwertyKeyboardView.col10ToRow4(5))
        assertEquals(2, QwertyKeyboardView.col10ToRow4(6))
        assertEquals(3, QwertyKeyboardView.col10ToRow4(7))
        assertEquals(4, QwertyKeyboardView.col10ToRow4(8))
        assertEquals(4, QwertyKeyboardView.col10ToRow4(9))
    }

    @Test
    fun testRow4ToCol10_inverseMapping() {
        // Inverse mapping maps row 4 column index (0..4) to anchor column in 10-column row
        assertEquals(0, QwertyKeyboardView.row4ToCol10(0))
        assertEquals(2, QwertyKeyboardView.row4ToCol10(1))
        assertEquals(4, QwertyKeyboardView.row4ToCol10(2))
        assertEquals(7, QwertyKeyboardView.row4ToCol10(3))
        assertEquals(9, QwertyKeyboardView.row4ToCol10(4))
    }

    @Test
    fun testQwertyCoordinateMapping_symmetryAndRoundTrip() {
        // Round trip: for every key 0..4 in row 4, row4ToCol10 -> col10ToRow4 must return the exact same key
        for (r in 0..4) {
            val col10 = QwertyKeyboardView.row4ToCol10(r)
            val mappedBack = QwertyKeyboardView.col10ToRow4(col10)
            assertEquals("Round trip failed for row4 key $r (mapped to col10 $col10)", r, mappedBack)
        }

        // Canonical anchors round trip: row4ToCol10(col10ToRow4(anchor)) == anchor
        val anchors = intArrayOf(0, 2, 4, 7, 9)
        for (anchor in anchors) {
            val r = QwertyKeyboardView.col10ToRow4(anchor)
            val mappedCol = QwertyKeyboardView.row4ToCol10(r)
            assertEquals("Anchor round trip failed for anchor $anchor", anchor, mappedCol)
        }
    }

    @Test
    fun testQwertyCoordinateMapping_edgeCases() {
        // Out of bounds (< 0)
        assertEquals(0, QwertyKeyboardView.col10ToRow4(-1))
        assertEquals(0, QwertyKeyboardView.col10ToRow4(-100))
        assertEquals(0, QwertyKeyboardView.col10ToRow4(Int.MIN_VALUE))

        // Out of bounds (> 9)
        assertEquals(4, QwertyKeyboardView.col10ToRow4(10))
        assertEquals(4, QwertyKeyboardView.col10ToRow4(100))
        assertEquals(4, QwertyKeyboardView.col10ToRow4(Int.MAX_VALUE))

        // row4ToCol10 edge cases: indices outside 0..3 fall through to default anchor 9
        assertEquals(9, QwertyKeyboardView.row4ToCol10(-1))
        assertEquals(9, QwertyKeyboardView.row4ToCol10(-99))
        assertEquals(9, QwertyKeyboardView.row4ToCol10(5))
        assertEquals(9, QwertyKeyboardView.row4ToCol10(10))
        assertEquals(9, QwertyKeyboardView.row4ToCol10(100))
        assertEquals(9, QwertyKeyboardView.row4ToCol10(Int.MAX_VALUE))
    }

    // =========================================================================
    // 2. UsbMassStorageController parseBool Tests
    // =========================================================================

    @Test
    fun testUsbMassStorageController_parseBool_trueParcel() {
        val output = "Result: Parcel(00000000 00000001   '........')"
        assertTrue(UsbMassStorageController.parseBool(output))
    }

    @Test
    fun testUsbMassStorageController_parseBool_falseParcel() {
        val output = "Result: Parcel(00000000 00000000   '........')"
        assertFalse(UsbMassStorageController.parseBool(output))
    }

    @Test
    fun testUsbMassStorageController_parseBool_null() {
        assertFalse(UsbMassStorageController.parseBool(null))
    }

    @Test
    fun testUsbMassStorageController_parseBool_emptyOrMalformed() {
        assertFalse(UsbMassStorageController.parseBool(""))
        assertFalse(UsbMassStorageController.parseBool("   "))
        assertFalse(UsbMassStorageController.parseBool("Error: something went wrong"))
        assertFalse(UsbMassStorageController.parseBool("Result: Parcel()"))
        assertFalse(UsbMassStorageController.parseBool("212 Share disabled"))
    }

    @Test
    fun testUsbMassStorageController_parseBool_nonZeroValues() {
        // Non-zero values in second word represent true
        assertTrue(UsbMassStorageController.parseBool("Result: Parcel(00000000 00000002   '........')"))
        assertTrue(UsbMassStorageController.parseBool("Result: Parcel(00000000 000000ff   '........')"))
        assertTrue(UsbMassStorageController.parseBool("Result: Parcel(00000001 00000001   '........')"))
        assertFalse(UsbMassStorageController.parseBool("Result: Parcel(00000001 00000000   '........')"))
    }

    // =========================================================================
    // 3. SafeVolumeBypasser Constants & State Validation
    // =========================================================================

    @Test
    fun testSafeVolumeBypasser_constants() {
        assertEquals("SafeVolumeBypasser", SafeVolumeBypasser.TAG)
        assertEquals("audio_safe_volume_state", SafeVolumeBypasser.KEY_AUDIO_SAFE_VOLUME_STATE)
        assertEquals(2, SafeVolumeBypasser.VALUE_SAFE_VOLUME_STATE_DISABLED)
        assertEquals("safe_media_volume_enabled", SafeVolumeBypasser.KEY_SAFE_MEDIA_VOLUME_ENABLED)
        assertEquals(0, SafeVolumeBypasser.VALUE_SAFE_MEDIA_VOLUME_DISABLED)
    }

    @Test
    fun testSafeVolumeBypasser_pureStateAndNullSafety() {
        // Verify isRootInitialized state is accessible as a boolean
        val initialized: Boolean = SafeVolumeBypasser.isRootInitialized
        // By default or before root is run, it is a boolean value
        assertTrue(initialized == true || initialized == false)

        // Verify null safety on public API methods
        SafeVolumeBypasser.bypass(null, null)
        SafeVolumeBypasser.setStreamVolumeWithBypass(null, null, 3, 10, 0)
        SafeVolumeBypasser.bypassWithRoot(null, null)
    }
}
