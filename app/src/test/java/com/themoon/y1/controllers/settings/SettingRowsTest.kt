package com.themoon.y1.controllers.settings

import com.themoon.y1.Prefs
import com.themoon.y1.core.extensions.onOff
import com.themoon.y1.lists.Focus
import com.themoon.y1.lists.RowLook
import com.themoon.y1.migrations.MoveMisplacedPrefsTest.FakePrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingRowsTest {

    private val prefs = FakePrefs()
    private var refreshes = 0
    private val rows = SettingRows(prefs, { "[$it]" }) { refreshes++ }

    @Test
    fun aToggleShowsOnOffAndAPushChangesThePrefThenTheList() {
        var told: Boolean? = null
        val row = rows.toggle("Sound Check", Prefs.SOUND_CHECK) { told = it }
        assertEquals("Sound Check", row.label)
        val off = row.make()
        assertEquals("[Sound Check]", off.label)
        assertEquals(RowLook.VALUE, off.look)
        assertEquals(onOff(false, { "[$it]" }), off.value)
        off.onClick!!()
        assertTrue(Prefs.SOUND_CHECK.get(prefs))
        assertEquals(true, told)
        assertEquals(1, refreshes)
        assertEquals(onOff(true, { "[$it]" }), row.make().value)
    }

    @Test
    fun aToggleCanHaveItsOwnValueTextAndBeGrey() {
        var fade = false
        val row = rows.toggle("List Edges", { fade }, { fade = it }, { if (it) "Fade" else "Cut" }, enabled = { false })
        assertEquals("Cut", row.make().value)
        assertFalse(row.make().enabled)
        row.make().onClick!!()
        assertEquals("Fade", row.make().value)
    }

    @Test
    fun aCycleGoesToTheNextValueAndAfterTheLastToTheFirst() {
        val values = arrayOf("Low", "Medium", "High")
        val row = rows.cycle("Click Volume", Prefs.CLICK_VOLUME, values, feedbackAfter = true)
        prefs.edit().putInt(Prefs.CLICK_VOLUME.key, 2).commit()
        val high = row.make()
        assertEquals("[High]", high.value)
        assertTrue(high.feedbackAfter)
        high.onClick!!()
        assertEquals(0, Prefs.CLICK_VOLUME.get(prefs))
        assertEquals("[Low]", row.make().value)
    }

    @Test
    fun aCycleShowsAStoredValueThatIsNotInTheListAsItsDefault() {
        prefs.edit().putInt(Prefs.VIBRATE_STRENGTH.key, 7).commit()
        val row = rows.cycle("Vibration Strength", Prefs.VIBRATE_STRENGTH, arrayOf("Weak", "Normal", "Strong"), { if (it in 0..2) it else 1 })
        assertEquals("[Normal]", row.make().value)
        row.make().onClick!!()
        assertEquals(2, Prefs.VIBRATE_STRENGTH.get(prefs))
    }

    @Test
    fun choiceAndActionRowsShowTheirValueAndOpenWithoutAChange() {
        var opened = 0
        val choice = rows.choice("Volume Limit", { "Off 〉" }) { opened++ }.make()
        assertEquals("Off 〉", choice.value)
        choice.onClick!!()
        val action = rows.action("Legal") { opened++ }.make()
        assertEquals(SettingRows.CHEVRON, action.value)
        action.onClick!!()
        assertEquals(2, opened)
        assertEquals("rows that open a screen do not make the list again", 0, refreshes)
    }

    @Test
    fun aValueListOpensAtTheChosenValue() {
        var chosen: String? = null
        val list = rows.valueList("Volume Limit", listOf("Off", "1", "2").map { v -> SettingValue(v, v == "1") { chosen = v } })
        assertEquals("Volume Limit", list.title)
        assertEquals(Focus.CHECKED, list.focus)
        assertEquals(listOf(false, true, false), list.rows.map { it.checked })
        assertTrue(list.rows.all { it.look == RowLook.CHOICE })
        list.rows[2].onClick!!()
        assertEquals("2", chosen)
        assertNull(rows.valueList("Language", emptyList(), focus = Focus.FIRST).rows.firstOrNull())
    }
}
