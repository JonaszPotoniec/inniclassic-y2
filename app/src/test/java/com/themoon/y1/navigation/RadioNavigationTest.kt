package com.themoon.y1.navigation

import android.view.ViewGroup
import com.themoon.y1.controllers.ScreenController
import com.themoon.y1.controllers.radio.FmRadioController
import com.themoon.y1.controllers.radio.FmRadioController.Page
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The FM radio screens as on the iPod nano: the tuner opens directly, and its Radio menu is always under it. */
class RadioNavigationTest {

    private open class Fake(
        override val rootView: ViewGroup = NavigationManagerTest.createDummyViewGroup()
    ) : ScreenController

    /** The back-stack part of FmRadioController.onEnter. */
    private class FakeRadio(private val nav: NavigationManager) : Fake() {
        var opened = 0

        override fun onEnter(state: ScreenState) {
            if (FmRadioController.pageOf(state) != Page.TUNER) return
            if (FmRadioController.opensRadio(nav.previousState())) opened++
            nav.setBackTarget(ScreenState.FmRadioMenu)
        }
    }

    private val nav = NavigationManager.getInstance()
    private val radio = FakeRadio(nav)

    @Before
    fun setUp() {
        nav.reset()
        nav.registerController(ScreenState.MainMenu::class.java, Fake())
        nav.registerController(ScreenState.Settings::class.java, Fake())
        nav.registerController(ScreenState.FmRadio::class.java, radio)
        nav.registerController(ScreenState.FmRadioMenu::class.java, radio)
        nav.registerController(ScreenState.FmRadioSaved::class.java, radio)
        nav.navigateTo(ScreenState.MainMenu)
    }

    @After
    fun tearDown() {
        nav.reset()
    }

    @Test
    fun pages() {
        assertEquals(Page.TUNER, FmRadioController.pageOf(ScreenState.FmRadio))
        assertEquals(Page.MENU, FmRadioController.pageOf(ScreenState.FmRadioMenu))
        assertEquals(Page.SAVED, FmRadioController.pageOf(ScreenState.FmRadioSaved))
    }

    @Test
    fun mainMenuOpensTheTunerAndMenuGoesToTheRadioMenu() {
        nav.navigateTo(ScreenState.FmRadio)
        assertEquals(1, radio.opened)
        assertEquals(ScreenState.FmRadioMenu, nav.previousState())

        assertTrue(nav.popBackStack())
        assertEquals(ScreenState.FmRadioMenu, nav.currentState)
        assertTrue(nav.popBackStack())
        assertEquals(ScreenState.MainMenu, nav.currentState)
        assertFalse(nav.popBackStack())
    }

    @Test
    fun tunerRowOfTheRadioMenuDoesNotAddAnEntry() {
        nav.navigateTo(ScreenState.FmRadio)
        nav.popBackStack()
        nav.navigateTo(ScreenState.FmRadio) // Radio menu → Tuner
        assertEquals(1, radio.opened)

        nav.popBackStack()
        assertEquals(ScreenState.FmRadioMenu, nav.currentState)
        nav.popBackStack()
        assertEquals(ScreenState.MainMenu, nav.currentState)
    }

    @Test
    fun savedChannelGoesToTheTunerAndBackGoesToTheRadioMenu() {
        nav.navigateTo(ScreenState.FmRadio)
        nav.popBackStack()
        nav.navigateTo(ScreenState.FmRadioSaved)
        nav.navigateTo(ScreenState.FmRadio) // choose a channel
        assertEquals(1, radio.opened)

        nav.popBackStack()
        assertEquals(ScreenState.FmRadioMenu, nav.currentState)
        nav.popBackStack()
        assertEquals(ScreenState.MainMenu, nav.currentState)
    }

    @Test
    fun backFromAScreenOverTheTunerDoesNotOpenTheRadioAgain() {
        nav.navigateTo(ScreenState.FmRadio)
        nav.navigateTo(ScreenState.Settings(SettingsSubMode.Action(1))) // the scan prompt
        nav.popBackStack()
        assertEquals(ScreenState.FmRadio, nav.currentState)
        assertEquals(1, radio.opened)
        assertEquals(ScreenState.FmRadioMenu, nav.previousState())
    }

    @Test
    fun setBackTargetRemovesTheEntriesAboveAnEntryThatIsThere() {
        nav.navigateTo(ScreenState.Settings())
        nav.navigateTo(ScreenState.Settings(SettingsSubMode.Page("Theme")))
        nav.navigateTo(ScreenState.Settings(SettingsSubMode.Page("Language")))
        nav.setBackTarget(ScreenState.Settings())
        assertEquals(ScreenState.Settings(), nav.previousState())
        nav.popBackStack()
        assertEquals(ScreenState.Settings(), nav.currentState)
        nav.popBackStack()
        assertEquals(ScreenState.MainMenu, nav.currentState)
    }
}
