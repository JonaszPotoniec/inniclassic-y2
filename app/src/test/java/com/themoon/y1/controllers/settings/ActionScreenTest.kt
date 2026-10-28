package com.themoon.y1.controllers.settings

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
}
