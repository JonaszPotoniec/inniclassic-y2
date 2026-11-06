package com.themoon.y1.controllers.settings

import com.themoon.y1.lists.Focus
import com.themoon.y1.lists.RowLook
import com.themoon.y1.navigation.ScreenState
import com.themoon.y1.navigation.SettingsSubMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ActionScreenTest {

    @Test
    fun eachScreenHasItsOwnRouteKey() {
        val a = ActionScreens.add(ActionScreens.Spec("A"))
        val b = ActionScreens.add(ActionScreens.Spec("B"))
        assertNotEquals(a, b)
        assertEquals("A", ActionScreens.spec(a)?.title)
        assertEquals("B", ActionScreens.spec(b)?.title)
        // The route key is a value: Back finds the same entry again
        assertEquals(ScreenState.Settings(SettingsSubMode.Action(a)), ActionScreens.route(a))
    }

    @Test
    fun onlyTheNewestScreensAreKept() {
        val first = ActionScreens.add(ActionScreens.Spec("first"))
        repeat(ActionScreens.MAX_SCREENS) { ActionScreens.add(ActionScreens.Spec("n$it")) }
        assertNull(ActionScreens.spec(first))
    }

    @Test
    fun aRowClosesTheScreenFirstThenDoesItsAction() {
        val steps = mutableListOf<String>()
        val spec = confirmScreen("Reset All Settings", "info", "Reset") { steps.add("reset") }
        ActionScreens.choose(spec.actions.single()) { steps.add("close") }
        assertEquals(listOf("close", "reset"), steps)
    }

    @Test
    fun aRowThatOpensAListKeepsTheScreenUnderIt() {
        val steps = mutableListOf<String>()
        val playOn = ActionScreens.Action("Play On", opensList = true) { steps.add("open list") }
        assertFalse(playOn.closes)
        ActionScreens.choose(playOn) { steps.add("close") }
        assertEquals(listOf("open list"), steps)
        // A row that goes to another screen itself: no close first, and no chevron
        val speaker = ActionScreens.Action("Sonos", checked = false, closes = false) { steps.add("play") }
        assertFalse(speaker.opensList)
        ActionScreens.choose(speaker) { steps.add("close") }
        assertEquals(listOf("open list", "play"), steps)
    }

    @Test
    fun backClosesTheScreenAndCancelsWithoutTheAction() {
        val steps = mutableListOf<String>()
        val spec = ActionScreens.Spec(
            "Downloading Update",
            actions = listOf(ActionScreens.Action("Install") { steps.add("install") }),
            onCancel = { steps.add("cancel") }
        )
        ActionScreens.cancel(spec) { steps.add("close") }
        assertEquals(listOf("close", "cancel"), steps)
        // A screen whose content is gone still closes
        ActionScreens.cancel(null) { steps.add("close") }
        assertEquals(listOf("close", "cancel", "close"), steps)
    }

    @Test
    fun confirmScreenHasOneInfoLineAndOneAction() {
        val spec = confirmScreen("Import Settings", "Replace the settings?", "Import", selectAction = false) {}
        assertEquals(listOf("Replace the settings?"), spec.info)
        assertEquals(listOf("Import"), spec.actions.map { it.label })
        assertFalse(spec.selectAction)
        assertTrue(confirmScreen("T", "", "Go") {}.info.isEmpty())
        assertTrue(confirmScreen("T", "i", "Go") {}.selectAction)
    }

    @Test
    fun progressScreenGetsNewInfoAndKeepsItsActions() {
        val action = ActionScreens.Action("Stop") {}
        val key = ActionScreens.add(ActionScreens.Spec("Sync", listOf("0 %"), listOf(action)))
        assertTrue(ActionScreens.setInfo(key, listOf("50 %")))
        assertEquals(listOf("50 %"), ActionScreens.spec(key)?.info)
        assertSame(action, ActionScreens.spec(key)?.actions?.single())
        ActionScreens.forget(key)
        assertFalse(ActionScreens.setInfo(key, listOf("100 %")))
    }

    @Test
    fun aSpecIsAListOfInfoRowsThenActionRows() {
        val steps = mutableListOf<String>()
        val spec = ActionScreens.Spec(
            "Play On",
            info = listOf("Searching…"),
            actions = listOf(
                ActionScreens.Action("This Y2", checked = true) { steps.add("y2") },
                ActionScreens.Action("Kitchen", checked = false) { steps.add("kitchen") },
                ActionScreens.Action("More", opensList = true) { steps.add("more") },
                ActionScreens.Action("Search Again") { steps.add("search") }
            )
        )
        val list = ActionScreens.list(spec) { steps.add("close") }
        assertEquals("Play On", list.title)
        assertEquals(listOf(RowLook.INFO, RowLook.CHOICE, RowLook.CHOICE, RowLook.VALUE, RowLook.VALUE), list.rows.map { it.look })
        assertEquals(listOf(true, false), list.rows.subList(1, 3).map { it.checked })
        // A chevron only on the row that opens a list
        assertEquals(listOf("〉", ""), list.rows.subList(3, 5).map { it.value })
        assertEquals(Focus.FIRST, list.focus)
        list.rows[4].onClick!!()
        list.rows[3].onClick!!()
        assertEquals(listOf("close", "search", "more"), steps)
    }

    @Test
    fun aScreenThatSelectsNoActionIsAListWithNoFirstRow() {
        val spec = confirmScreen("Reset All Settings", "All settings go back to their defaults.", "Reset", selectAction = false) {}
        assertEquals(Focus.NONE, ActionScreens.list(spec) {}.focus)
        // A message: info rows only
        assertTrue(ActionScreens.list(ActionScreens.Spec("Bluetooth Pairing", listOf("Code", "123456"))) {}.rows.all { it.look == RowLook.INFO })
    }
}
