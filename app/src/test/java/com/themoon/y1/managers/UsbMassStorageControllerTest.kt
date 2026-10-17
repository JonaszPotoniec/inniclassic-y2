package com.themoon.y1.managers

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbMassStorageControllerTest {

    @Test
    fun testParseBool_trueParcel() {
        val output = "Result: Parcel(00000000 00000001   '........')"
        assertTrue(UsbMassStorageController.parseBool(output))
    }

    @Test
    fun testParseBool_falseParcel() {
        val output = "Result: Parcel(00000000 00000000   '........')"
        assertFalse(UsbMassStorageController.parseBool(output))
    }

    @Test
    fun testParseBool_nullOrMalformed() {
        assertFalse(UsbMassStorageController.parseBool(null))
        assertFalse(UsbMassStorageController.parseBool(""))
        assertFalse(UsbMassStorageController.parseBool("Error: something went wrong"))
    }
}
