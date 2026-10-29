package com.themoon.y1.core.extensions

import com.themoon.y1.LanguageFiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * On/off setting values: the same "On"/"Off" text on every row, its mark, and a translation in every language file.
 */
class OnOffTest {

    @Test
    fun keyIsOnOrOffInIpodCase() {
        assertEquals("On", onOffKey(true))
        assertEquals("Off", onOffKey(false))
    }

    @Test
    fun valueGoesThroughLocalize() {
        val polish = mapOf("On" to "Wł.", "Off" to "Wył.")
        assertEquals("Wł.", withoutOnOffMark(onOff(true) { polish[it] ?: it }))
        assertEquals("Wył.", withoutOnOffMark(onOff(false) { polish[it] ?: it }))
        assertEquals("On", withoutOnOffMark(onOff(true) { it }))
        assertEquals("Off", withoutOnOffMark(onOff(false) { it }))
    }

    @Test
    fun markGivesTheStateAndIsRemoved() {
        assertEquals(true, onOffState(onOff(true) { it }))
        assertEquals(false, onOffState(onOff(false) { it }))
        // Values that are not from onOff(), also "Off" of a multi-value row such as Bass Boost: no state.
        assertNull(onOffState("Off"))
        assertNull(onOffState("Weak"))
        assertNull(onOffState(""))
        assertNull(onOffState(null))
        assertEquals("Off", withoutOnOffMark("Off"))
        assertNull(withoutOnOffMark(null))
        assertEquals("", withoutOnOffMark(ON_MARK.toString()))
    }

    @Test
    fun everyLanguageFileTranslatesOnAndOff() {
        LanguageFiles.assertEachFileTranslates(listOf(onOffKey(true), onOffKey(false)))
    }
}
