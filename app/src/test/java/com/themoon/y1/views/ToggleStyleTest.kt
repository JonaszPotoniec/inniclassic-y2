package com.themoon.y1.views

import com.themoon.y1.core.extensions.onOff
import com.themoon.y1.core.extensions.onOffState
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ToggleStyleTest {

    @Test
    fun textStyleAlwaysShowsText() {
        assertEquals(ToggleStyle.Look.TEXT, ToggleStyle.look(false, true))
        assertEquals(ToggleStyle.Look.TEXT, ToggleStyle.look(false, false))
        assertEquals(ToggleStyle.Look.TEXT, ToggleStyle.look(false, null))
    }

    @Test
    fun switchStyleShowsASwitchOnlyForOnOffValues() {
        assertEquals(ToggleStyle.Look.SWITCH_ON, ToggleStyle.look(true, onOffState(onOff(true) { it })))
        assertEquals(ToggleStyle.Look.SWITCH_OFF, ToggleStyle.look(true, onOffState(onOff(false) { it })))
        // "Off" of Bass Boost or Volume Limit is not from onOff(): it stays text.
        assertEquals(ToggleStyle.Look.TEXT, ToggleStyle.look(true, onOffState("Off")))
        assertEquals(ToggleStyle.Look.TEXT, ToggleStyle.look(true, onOffState("Wait...")))
    }

    @Test
    fun stateFollowsTheMarkAndSurvivesTextViewResets() {
        assertEquals(false, ToggleStyle.nextState(null, onOff(false) { it }, ""))
        assertEquals(true, ToggleStyle.nextState(false, onOff(true) { it }, "Off"))
        // TextView sets its own (plain) text again: keep the state.
        assertEquals(true, ToggleStyle.nextState(true, "On", "On"))
        // A new text without a mark: no state.
        assertNull(ToggleStyle.nextState(true, "Wait...", "On"))
        assertNull(ToggleStyle.nextState(false, "Weak", "Off"))
        assertNull(ToggleStyle.nextState(null, "Off", "Off"))
    }

    @Test
    fun prefValueRoundTripsAndDefaultsToText() {
        assertTrue(ToggleStyle.isSwitchPref(ToggleStyle.prefValue(true)))
        assertFalse(ToggleStyle.isSwitchPref(ToggleStyle.prefValue(false)))
        assertFalse(ToggleStyle.isSwitchPref(null))
        assertFalse(ToggleStyle.isSwitchPref("something else"))
        assertEquals("switch", ToggleStyle.prefValue(true))
        assertEquals("text", ToggleStyle.prefValue(false))
    }

    @Test
    fun labelIsEnglishWhenNoLanguageFileIsLoaded() {
        // English (Default): LanguageManager.t() gives the key back.
        assertEquals("Text", ToggleStyle.label(false) { it })
        assertEquals("Switch", ToggleStyle.label(true) { it })
        val polish = mapOf("Text (Toggle Style)" to "Tekst", "Switch (Toggle Style)" to "Przełącznik")
        assertEquals("Tekst", ToggleStyle.label(false) { polish[it] ?: it })
        assertEquals("Przełącznik", ToggleStyle.label(true) { polish[it] ?: it })
    }

    @Test
    fun colorsStayVisibleOnTheSelectedRow() {
        val accent = 0xFF2588DC.toInt()
        val white = 0xFFFFFFFF.toInt()
        val secondary = 0xFF666666.toInt()

        val on = ToggleStyle.colors(on = true, rowSelected = false, accent = accent, selectedText = white, secondaryText = secondary)
        assertEquals(accent, on.track)
        assertEquals(white, on.knob)

        val off = ToggleStyle.colors(on = false, rowSelected = false, accent = accent, selectedText = white, secondaryText = secondary)
        assertEquals(0x99666666.toInt(), off.track) // grey, not the accent
        assertEquals(white, off.knob)

        // The selected row background is the accent: the On track must not be the accent there.
        val onSelected = ToggleStyle.colors(on = true, rowSelected = true, accent = accent, selectedText = white, secondaryText = secondary)
        assertNotEquals(accent, onSelected.track)
        assertEquals(white, onSelected.track)
        assertEquals(accent, onSelected.knob)

        val offSelected = ToggleStyle.colors(on = false, rowSelected = true, accent = accent, selectedText = white, secondaryText = secondary)
        assertEquals(0x66FFFFFF, offSelected.track)
        assertEquals(white, offSelected.knob)
    }

    @Test
    fun switchHasTheIosProportions() {
        val ratio = SettingValueView.SWITCH_HEIGHT_DP / SettingValueView.SWITCH_WIDTH_DP
        assertTrue("height/width = $ratio", ratio in 0.55f..0.61f)
        // Fits in the Classic rows (33 dp; 38 dp in the iPod Classic layout) and the other rows (48 dp).
        assertTrue(SettingValueView.SWITCH_HEIGHT_DP <= 24f)
        assertTrue(SettingValueView.KNOB_INSET_DP * 2 < SettingValueView.SWITCH_HEIGHT_DP / 2)
    }

    @Test
    fun greyKeepsTheLuminance() {
        assertEquals(0xFF666666.toInt(), ToggleStyle.grey(0xFF666666.toInt()))
        assertEquals(0xFF000000.toInt(), ToggleStyle.grey(0xFF000000.toInt()))
        assertEquals(0xFFFFFFFF.toInt(), ToggleStyle.grey(0xFFFFFFFF.toInt()))
        assertEquals(0xFF969696.toInt(), ToggleStyle.grey(0xFF00FF00.toInt())) // 0.587 × 255 = 150
    }

    @Test
    fun easeOutDeceleratesFromZeroToOne() {
        assertEquals(0f, ToggleStyle.easeOut(0f), 0f)
        assertEquals(1f, ToggleStyle.easeOut(1f), 0f)
        assertEquals(0.875f, ToggleStyle.easeOut(0.5f), 1e-6f) // past the middle at half time
        assertEquals(1f, ToggleStyle.easeOut(2f), 0f)
        var last = 0f
        var lastStep = Float.MAX_VALUE
        for (i in 1..20) {
            val v = ToggleStyle.easeOut(i / 20f)
            assertTrue(v > last)
            assertTrue("steps get smaller", v - last < lastStep)
            lastStep = v - last
            last = v
        }
    }

    @Test
    fun knobSlidesFromOffToOnInTheAnimationTime() {
        val d = ToggleStyle.ANIMATION_MS
        assertTrue(d in 180L..220L)
        assertEquals(0f, ToggleStyle.knobPosition(0f, true, 0L), 0f)
        assertEquals(0.875f, ToggleStyle.knobPosition(0f, true, d / 2), 1e-6f)
        assertEquals(1f, ToggleStyle.knobPosition(0f, true, d), 0f)
        assertEquals(1f, ToggleStyle.knobPosition(0f, true, d * 10), 0f)
        // On to Off: the mirror image.
        assertEquals(0.125f, ToggleStyle.knobPosition(1f, false, d / 2), 1e-6f)
        assertEquals(0f, ToggleStyle.knobPosition(1f, false, d), 0f)
    }

    @Test
    fun aSecondClickTurnsTheKnobBackFromWhereItIs() {
        // Knob at 0.6 on its way to On; now Off: a shorter animation that starts at 0.6.
        assertEquals(120L, ToggleStyle.durationMs(0.6f, false))
        assertEquals(0.6f, ToggleStyle.knobPosition(0.6f, false, 0L), 0f)
        assertTrue(ToggleStyle.knobPosition(0.6f, false, 60L) < 0.6f)
        assertEquals(0f, ToggleStyle.knobPosition(0.6f, false, 120L), 0f)
        assertEquals(0L, ToggleStyle.durationMs(1f, true))
        assertEquals(1f, ToggleStyle.knobPosition(1f, true, 0L), 0f)
    }

    @Test
    fun coloursCrossFade() {
        val off = 0x99666666.toInt()
        val on = 0xFF2588DC.toInt()
        assertEquals(off, ToggleStyle.blend(off, on, 0f))
        assertEquals(on, ToggleStyle.blend(off, on, 1f))
        assertEquals(0xCC4677A1.toInt(), ToggleStyle.blend(off, on, 0.5f))
        assertEquals(0xFF808080.toInt(), ToggleStyle.blend(0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 0.5f))
        // The track and knob functions give the same colours as colors().
        for (on2 in listOf(true, false)) for (sel in listOf(true, false)) {
            val c = ToggleStyle.colors(on2, sel, on, 0xFFFFFFFF.toInt(), 0xFF666666.toInt())
            assertEquals(c.track, ToggleStyle.trackColor(on2, sel, on, 0xFFFFFFFF.toInt(), 0xFF666666.toInt()))
            assertEquals(c.knob, ToggleStyle.knobColor(on2, sel, on, 0xFFFFFFFF.toInt()))
        }
    }

    @Test
    fun aRowMadeAgainAfterAClickContinuesTheAnimation() {
        // The controller removes the row (knob Off) and makes it again (On) 150 ms later: animate from Off.
        ToggleStyle.rememberRemoved("Row A", 0f, 1_000L)
        assertEquals(0f, ToggleStyle.takeRemoved("Row A", true, 1_150L))
        // Taken once only.
        assertNull(ToggleStyle.takeRemoved("Row A", true, 1_160L))
        // Same state (the screen closed and opened again): no animation.
        ToggleStyle.rememberRemoved("Row B", 1f, 2_000L)
        assertNull(ToggleStyle.takeRemoved("Row B", true, 2_100L))
        // Too late (a screen opened later): no animation.
        ToggleStyle.rememberRemoved("Row C", 0f, 3_000L)
        assertNull(ToggleStyle.takeRemoved("Row C", true, 3_000L + ToggleStyle.REBUILD_WINDOW_MS + 1))
        // Mid-animation: continue from the knob position.
        ToggleStyle.rememberRemoved("Row D", 0.4f, 4_000L)
        assertEquals(0.4f, ToggleStyle.takeRemoved("Row D", true, 4_050L))
        assertNull(ToggleStyle.takeRemoved("Unknown", true, 4_050L))
    }

    @Test
    fun everyLanguageFileTranslatesTheSetting() {
        val dir = listOf(File("src/main/assets/languages"), File("app/src/main/assets/languages"))
            .first { it.isDirectory }
        val files = dir.listFiles { f -> f.name.endsWith(".json") }!!.sortedBy { it.name }
        assertTrue("no language files in $dir", files.isNotEmpty())
        val keys = listOf("Toggle Style", ToggleStyle.labelKey(false), ToggleStyle.labelKey(true))
        for (file in files) {
            val json = JSONObject(file.readText(Charsets.UTF_8))
            for (key in keys) {
                assertTrue("${file.name} has no \"$key\"", json.optString(key).isNotBlank())
            }
        }
        val english = JSONObject(File(dir, "english.json").readText(Charsets.UTF_8))
        assertEquals("Text", english.getString(ToggleStyle.labelKey(false)))
        assertEquals("Switch", english.getString(ToggleStyle.labelKey(true)))
    }
}
