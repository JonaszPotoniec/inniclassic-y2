package com.themoon.y1.navigation

import android.view.KeyEvent
import android.view.ViewGroup
import com.themoon.y1.controllers.ScreenController
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class InputRouterTest {

    companion object {
        fun createDummyViewGroup(): ViewGroup {
            val unsafeClass = Class.forName("sun.misc.Unsafe")
            val field = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }
            val unsafe = field.get(null)
            val allocateMethod = unsafeClass.getMethod("allocateInstance", Class::class.java)
            return allocateMethod.invoke(unsafe, android.widget.FrameLayout::class.java) as ViewGroup
        }
    }

    private open class TestController(
        override val rootView: ViewGroup = createDummyViewGroup()
    ) : ScreenController {
        var backPressed = false
        var handleBackResult = false

        override fun onBackPressed(): Boolean {
            backPressed = true
            return handleBackResult
        }
    }

    private val navManager = NavigationManager.getInstance()
    private val inputRouter = InputRouter.getInstance()

    private val mainMenuController = TestController()
    private val subController = TestController()

    @Before
    fun setUp() {
        navManager.reset()
        navManager.registerController(ScreenState.MainMenu::class.java, mainMenuController)
        navManager.registerController(ScreenState.CoverFlow::class.java, subController)
    }

    @After
    fun tearDown() {
        navManager.reset()
    }

    @Test
    fun testOnKeyDown_backKeyConsumedByActiveController() {
        navManager.navigateTo(ScreenState.CoverFlow)
        subController.handleBackResult = true

        val event = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK)
        val handled = inputRouter.onKeyDown(KeyEvent.KEYCODE_BACK, event, subController)

        assertTrue("Back key should be consumed by activeController", handled)
        assertTrue(subController.backPressed)
        assertEquals(ScreenState.CoverFlow, navManager.currentState)
    }

    @Test
    fun testOnKeyDown_backKeyPopsBackStackWhenControllerDoesNotHandle() {
        navManager.navigateTo(ScreenState.MainMenu)
        navManager.navigateTo(ScreenState.CoverFlow)
        subController.handleBackResult = false

        val event = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK)
        val handled = inputRouter.onKeyDown(KeyEvent.KEYCODE_BACK, event, subController)

        assertTrue("Back key should pop backstack and return true", handled)
        assertEquals(ScreenState.MainMenu, navManager.currentState)
    }

    @Test
    fun testOnKeyDown_backKeyOnMainMenuAlwaysConsumed() {
        navManager.navigateTo(ScreenState.MainMenu)
        mainMenuController.handleBackResult = false

        val event = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK)
        val handled = inputRouter.onKeyDown(KeyEvent.KEYCODE_BACK, event, mainMenuController)

        assertTrue("Back key on main menu must always be consumed to prevent closing the launcher", handled)
        assertEquals(ScreenState.MainMenu, navManager.currentState)
    }

    @Test
    fun testOnKeyDown_key19OnMainMenuAlwaysConsumed() {
        navManager.navigateTo(ScreenState.MainMenu)
        mainMenuController.handleBackResult = false

        val event = KeyEvent(KeyEvent.ACTION_DOWN, 19)
        val handled = inputRouter.onKeyDown(19, event, mainMenuController)

        assertTrue("Key 19 on main menu must always be consumed", handled)
        assertEquals(ScreenState.MainMenu, navManager.currentState)
    }

    @Test
    fun testOnKeyUp_backKeysAlwaysConsumed() {
        val eventBack = KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK)
        assertTrue(inputRouter.onKeyUp(KeyEvent.KEYCODE_BACK, eventBack, mainMenuController))

        val event19 = KeyEvent(KeyEvent.ACTION_UP, 19)
        assertTrue(inputRouter.onKeyUp(19, event19, mainMenuController))
    }
}
