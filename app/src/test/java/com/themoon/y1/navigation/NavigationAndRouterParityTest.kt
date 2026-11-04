package com.themoon.y1.navigation

import android.view.KeyEvent
import android.view.ViewGroup
import com.themoon.y1.controllers.ScreenController
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Parity and contract verification tests for [NavigationManager], [InputRouter],
 * and [ScreenState] hierarchies.
 */
class NavigationAndRouterParityTest {

    companion object {
        fun createDummyViewGroup(): ViewGroup {
            val unsafeClass = Class.forName("sun.misc.Unsafe")
            val field = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }
            val unsafe = field.get(null)
            val allocateMethod = unsafeClass.getMethod("allocateInstance", Class::class.java)
            return allocateMethod.invoke(unsafe, android.widget.FrameLayout::class.java) as ViewGroup
        }
    }

    private class ParityTestController(
        override val rootView: ViewGroup = createDummyViewGroup()
    ) : ScreenController {
        var enterCount = 0
        var exitCount = 0
        var refreshCount = 0
        var lastStateEntered: ScreenState? = null

        var backPressed = false
        var handleBackResult = false

        var handleKeyDownResult = false
        var handleKeyUpResult = false
        var lastKeyDownCode: Int? = null
        var lastKeyUpCode: Int? = null

        override fun onEnter(state: ScreenState) {
            enterCount++
            lastStateEntered = state
        }

        override fun onExit() {
            exitCount++
        }

        override fun onRefreshUI() {
            refreshCount++
        }

        override fun onBackPressed(): Boolean {
            backPressed = true
            return handleBackResult
        }

        override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
            lastKeyDownCode = keyCode
            return handleKeyDownResult
        }

        override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
            lastKeyUpCode = keyCode
            return handleKeyUpResult
        }
    }

    private val navManager = NavigationManager.getInstance()
    private val inputRouter = InputRouter.getInstance()

    private val mainMenuController = ParityTestController()
    private val browserController = ParityTestController()
    private val playerController = ParityTestController()
    private val coverFlowController = ParityTestController()
    private val settingsController = ParityTestController()

    @Before
    fun setUp() {
        navManager.reset()
        navManager.registerController(ScreenState.MainMenu::class.java, mainMenuController)
        navManager.registerController(ScreenState.Browser::class.java, browserController)
        navManager.registerController(ScreenState.Player::class.java, playerController)
        navManager.registerController(ScreenState.CoverFlow::class.java, coverFlowController)
        navManager.registerController(ScreenState.Settings::class.java, settingsController)
    }

    @After
    fun tearDown() {
        navManager.reset()
    }

    // =========================================================================
    // 1. NavigationManager Scenarios
    // =========================================================================

    @Test
    fun testDuplicateNavigationGuard_doesNotDuplicateOnBackStack() {
        navManager.navigateTo(ScreenState.MainMenu)
        assertEquals(ScreenState.MainMenu, navManager.currentState)

        // Navigate to Browser(Artists)
        val artistsState = ScreenState.Browser(BrowserMode.Artists)
        navManager.navigateTo(artistsState)
        assertEquals(artistsState, navManager.currentState)
        assertEquals(1, browserController.enterCount)
        assertEquals(0, browserController.refreshCount)

        // Attempt duplicate navigation to the same state
        navManager.navigateTo(artistsState)
        assertEquals(artistsState, navManager.currentState)
        // Guard triggers: onRefreshUI is called, onEnter is not invoked again
        assertEquals(1, browserController.enterCount)
        assertEquals(1, browserController.refreshCount)

        // Popping backstack should directly navigate to MainMenu, not duplicate Artists
        assertTrue("First pop should succeed returning to MainMenu", navManager.popBackStack())
        assertEquals(ScreenState.MainMenu, navManager.currentState)

        // Popping again should return false (no extra duplicated history)
        assertFalse("Second pop on MainMenu with empty backstack must return false", navManager.popBackStack())
        assertEquals(ScreenState.MainMenu, navManager.currentState)
    }

    @Test
    fun testEmptyBackStackPop_returnsFalseAndDoesNotThrow() {
        // Initially empty state after reset
        navManager.navigateTo(ScreenState.MainMenu)
        assertEquals(ScreenState.MainMenu, navManager.currentState)

        // Pop on empty backstack while on MainMenu
        val popped = navManager.popBackStack()
        assertFalse("popBackStack() on empty backstack on MainMenu must return false", popped)
        assertEquals(ScreenState.MainMenu, navManager.currentState)

        // Subsequent pop calls must remain safe and return false without throwing
        for (i in 1..5) {
            val result = navManager.popBackStack()
            assertFalse("Subsequent empty pop #$i must return false", result)
            assertEquals(ScreenState.MainMenu, navManager.currentState)
        }
    }

    @Test
    fun testEmptyBackStackPop_fromNonMainMenuFallsBackToMainMenuSafely() {
        // Navigate to a screen without adding to backstack (e.g. direct switch)
        navManager.navigateTo(ScreenState.Player, addToBackStack = false)
        assertEquals(ScreenState.Player, navManager.currentState)

        // popBackStack with empty backstack but non-MainMenu should navigate to MainMenu safely
        val poppedToMain = navManager.popBackStack()
        assertTrue("popBackStack() from non-MainMenu with empty stack should fallback to MainMenu", poppedToMain)
        assertEquals(ScreenState.MainMenu, navManager.currentState)

        // Next pop should return false
        val nextPop = navManager.popBackStack()
        assertFalse("popBackStack() once on MainMenu with empty stack must return false", nextPop)
        assertEquals(ScreenState.MainMenu, navManager.currentState)
    }

    @Test
    fun testFocusPreservationOnBackNavigation_onEnterCalledWithPoppedToState() {
        navManager.navigateTo(ScreenState.MainMenu)

        // Navigate to Browser with a specific filtered album state
        val albumsState = ScreenState.Browser(BrowserMode.Albums(artistFilter = "Pink Floyd"))
        navManager.navigateTo(albumsState)
        assertEquals(albumsState, navManager.currentState)
        assertEquals(albumsState, browserController.lastStateEntered)
        assertEquals(1, browserController.enterCount)

        // Navigate forward to Player
        navManager.navigateTo(ScreenState.Player)
        assertEquals(ScreenState.Player, navManager.currentState)
        assertEquals(1, playerController.enterCount)
        assertEquals(1, browserController.exitCount)

        // Pop backstack to return to Browser
        val popped = navManager.popBackStack()
        assertTrue("Pop should return true", popped)

        // Verify that the exact state (including album filter) was restored to onEnter
        assertEquals(albumsState, navManager.currentState)
        assertSame(browserController, navManager.activeController)
        assertEquals(2, browserController.enterCount)
        assertEquals(albumsState, browserController.lastStateEntered)

        val mode = (browserController.lastStateEntered as ScreenState.Browser).mode
        assertTrue("Popped-to mode must be Albums", mode is BrowserMode.Albums)
        assertEquals("Pink Floyd", (mode as BrowserMode.Albums).artistFilter)
    }

    @Test
    fun testBrowserSubModes_areDistinctStates() {
        val rootMode = ScreenState.Browser(BrowserMode.Root)
        val artistsMode = ScreenState.Browser(BrowserMode.Artists)
        val albumsModeAll = ScreenState.Browser(BrowserMode.Albums(artistFilter = null))
        val albumsModeArtistA = ScreenState.Browser(BrowserMode.Albums(artistFilter = "Artist A"))
        val albumsModeArtistB = ScreenState.Browser(BrowserMode.Albums(artistFilter = "Artist B"))
        val folder1 = ScreenState.Browser(BrowserMode.Folder(File("/storage/sdcard1/Music")))
        val folder2 = ScreenState.Browser(BrowserMode.Folder(File("/storage/sdcard0/Podcasts")))

        // Verify inequality of sub-modes
        assertNotEquals(rootMode, artistsMode)
        assertNotEquals(artistsMode, albumsModeAll)
        assertNotEquals(albumsModeArtistA, albumsModeArtistB)
        assertNotEquals(folder1, folder2)

        // Navigate through a series of distinct browser sub-modes
        navManager.navigateTo(ScreenState.MainMenu)
        navManager.navigateTo(rootMode)
        navManager.navigateTo(artistsMode)
        navManager.navigateTo(albumsModeArtistA)

        assertEquals(albumsModeArtistA, navManager.currentState)

        // Backstack traversal should pop through each distinct sub-mode in reverse order
        assertTrue(navManager.popBackStack())
        assertEquals(artistsMode, navManager.currentState)

        assertTrue(navManager.popBackStack())
        assertEquals(rootMode, navManager.currentState)

        assertTrue(navManager.popBackStack())
        assertEquals(ScreenState.MainMenu, navManager.currentState)
    }

    @Test
    fun testSettingsSubModes_areDistinctStates() {
        val rootSettings = ScreenState.Settings(SettingsSubMode.Root)
        val dateTimeSettings = ScreenState.Settings(SettingsSubMode.DateTime)
        val eq5Settings = ScreenState.Settings(SettingsSubMode.Equalizer5Band)
        val jellyfinDepth0 = ScreenState.Settings(SettingsSubMode.Jellyfin(depth = 0))
        val jellyfinDepth1 = ScreenState.Settings(SettingsSubMode.Jellyfin(depth = 1))

        assertNotEquals(rootSettings, dateTimeSettings)
        assertNotEquals(dateTimeSettings, eq5Settings)
        assertNotEquals(jellyfinDepth0, jellyfinDepth1)

        navManager.navigateTo(ScreenState.MainMenu)
        navManager.navigateTo(rootSettings)
        navManager.navigateTo(dateTimeSettings)

        assertEquals(dateTimeSettings, navManager.currentState)
        assertTrue(navManager.popBackStack())
        assertEquals(rootSettings, navManager.currentState)
    }

    @Test
    fun testMultipleRegistrationsForSameState_latestControllerWins() {
        val initialPlayerController = ParityTestController()
        val replacementPlayerController = ParityTestController()

        navManager.registerController(ScreenState.Player::class.java, initialPlayerController)
        // Re-register for the same state class
        navManager.registerController(ScreenState.Player::class.java, replacementPlayerController)

        navManager.navigateTo(ScreenState.Player)

        assertSame("Latest registered controller must become activeController", replacementPlayerController, navManager.activeController)
        assertEquals(1, replacementPlayerController.enterCount)
        assertEquals(0, initialPlayerController.enterCount)
        assertEquals(ScreenState.Player, replacementPlayerController.lastStateEntered)
    }

    @Test
    fun testTransitionListener_triggersOnEveryScreenNavigation() {
        val transitions = mutableListOf<Pair<ScreenState, ScreenState>>()
        navManager.setTransitionListener(object : NavigationManager.StateChangeListener {
            override fun onScreenTransition(from: ScreenState, to: ScreenState) {
                transitions.add(from to to)
            }
        })

        // Initial navigation from null does not trigger transition listener (previous is null)
        navManager.navigateTo(ScreenState.MainMenu)
        assertTrue("First navigation from null should not fire transition listener", transitions.isEmpty())

        // 1. MainMenu -> Browser(Root)
        navManager.navigateTo(ScreenState.Browser(BrowserMode.Root))
        assertEquals(1, transitions.size)
        assertEquals(ScreenState.MainMenu to ScreenState.Browser(BrowserMode.Root), transitions[0])

        // 2. Browser(Root) -> CoverFlow
        navManager.navigateTo(ScreenState.CoverFlow)
        assertEquals(2, transitions.size)
        assertEquals(ScreenState.Browser(BrowserMode.Root) to ScreenState.CoverFlow, transitions[1])

        // 3. Duplicate navigation to CoverFlow (guard stops transition)
        navManager.navigateTo(ScreenState.CoverFlow)
        assertEquals("Duplicate navigation must not trigger transition listener", 2, transitions.size)

        // 4. Pop CoverFlow -> Browser(Root)
        assertTrue(navManager.popBackStack())
        assertEquals(3, transitions.size)
        assertEquals(ScreenState.CoverFlow to ScreenState.Browser(BrowserMode.Root), transitions[2])

        // 5. Pop Browser(Root) -> MainMenu
        assertTrue(navManager.popBackStack())
        assertEquals(4, transitions.size)
        assertEquals(ScreenState.Browser(BrowserMode.Root) to ScreenState.MainMenu, transitions[3])
    }

    // =========================================================================
    // 2. InputRouter Scenarios
    // =========================================================================

    @Test
    fun testOnKeyUp_backAndDpadUpAlwaysReturnTrue_preventingAppClosure() {
        val eventBack = KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK)
        val eventUp = KeyEvent(KeyEvent.ACTION_UP, 19)

        // 1. Controller does not consume onKeyUp
        mainMenuController.handleKeyUpResult = false
        assertTrue("KEYCODE_BACK on key up must always return true", inputRouter.onKeyUp(KeyEvent.KEYCODE_BACK, eventBack, mainMenuController))
        assertTrue("KEYCODE_DPAD_UP (19) on key up must always return true", inputRouter.onKeyUp(19, eventUp, mainMenuController))

        // 2. Null controller
        assertTrue("KEYCODE_BACK on key up with null controller must return true", inputRouter.onKeyUp(KeyEvent.KEYCODE_BACK, eventBack, null))
        assertTrue("KEYCODE_DPAD_UP (19) on key up with null controller must return true", inputRouter.onKeyUp(19, eventUp, null))

        // 3. Controller consumes onKeyUp
        mainMenuController.handleKeyUpResult = true
        assertTrue("KEYCODE_BACK on key up consumed by controller returns true", inputRouter.onKeyUp(KeyEvent.KEYCODE_BACK, eventBack, mainMenuController))
        assertTrue("KEYCODE_DPAD_UP (19) on key up consumed by controller returns true", inputRouter.onKeyUp(19, eventUp, mainMenuController))
    }

    @Test
    fun testOnKeyRelease_backKeyDelegatesToActiveControllerOnBackPressed() {
        navManager.navigateTo(ScreenState.MainMenu)
        navManager.navigateTo(ScreenState.CoverFlow)
        assertEquals(ScreenState.CoverFlow, navManager.currentState)

        val backEvent = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK)

        // Controller consumes back press internally (e.g. closes modal/overlay)
        coverFlowController.handleBackResult = true
        val handled = inputRouter.onKeyDown(KeyEvent.KEYCODE_BACK, backEvent, coverFlowController)
        assertFalse("Back must not act on press", coverFlowController.backPressed)
        inputRouter.onKeyUp(KeyEvent.KEYCODE_BACK, KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK), coverFlowController)

        assertTrue("onKeyDown must return true when activeController handles back", handled)
        assertTrue("Controller's onBackPressed must be invoked", coverFlowController.backPressed)
        assertEquals("Backstack must not be popped when controller handled back", ScreenState.CoverFlow, navManager.currentState)
    }

    @Test
    fun testOnKeyRelease_backKeyPopsBackStackWhenControllerDoesNotHandle() {
        navManager.navigateTo(ScreenState.MainMenu)
        navManager.navigateTo(ScreenState.CoverFlow)
        assertEquals(ScreenState.CoverFlow, navManager.currentState)

        val backEvent = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK)

        // Controller does NOT handle back press
        coverFlowController.handleBackResult = false
        coverFlowController.backPressed = false

        val handled = inputRouter.onKeyDown(KeyEvent.KEYCODE_BACK, backEvent, coverFlowController)

        assertFalse("Back must not act on press", coverFlowController.backPressed)

        inputRouter.onKeyUp(KeyEvent.KEYCODE_BACK, KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK), coverFlowController)

        assertTrue("onKeyDown must return true when backstack is popped", handled)
        assertTrue("Controller's onBackPressed should have been queried first", coverFlowController.backPressed)
        assertEquals("Backstack must be popped to MainMenu", ScreenState.MainMenu, navManager.currentState)
    }

    @Test
    fun testOnKeyRelease_dpadUpPopsBackStackWhenControllerDoesNotHandle() {
        navManager.navigateTo(ScreenState.MainMenu)
        navManager.navigateTo(ScreenState.Player)
        assertEquals(ScreenState.Player, navManager.currentState)

        val upEvent = KeyEvent(KeyEvent.ACTION_DOWN, 19)

        playerController.handleBackResult = false
        playerController.backPressed = false

        val handled = inputRouter.onKeyDown(19, upEvent, playerController)

        assertFalse("Back must not act on press", playerController.backPressed)

        inputRouter.onKeyUp(19, KeyEvent(KeyEvent.ACTION_UP, 19), playerController)

        assertTrue("onKeyDown with key 19 must return true", handled)
        assertTrue("Controller's onBackPressed should be queried", playerController.backPressed)
        assertEquals("Should pop back to MainMenu", ScreenState.MainMenu, navManager.currentState)
    }

    @Test
    fun testOnKeyDown_backOnMainMenuAlwaysConsumedEvenWithEmptyBackStack() {
        navManager.navigateTo(ScreenState.MainMenu)
        mainMenuController.handleBackResult = false
        mainMenuController.backPressed = false

        val backEvent = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK)
        val handled = inputRouter.onKeyDown(KeyEvent.KEYCODE_BACK, backEvent, mainMenuController)

        assertTrue("Back on MainMenu must always be consumed to prevent closing launcher", handled)
        assertEquals(ScreenState.MainMenu, navManager.currentState)
    }

    @Test
    fun testUnknownAndUnhandledKeyEvents_passedThroughAppropriately() {
        val unknownCode = 999
        val unhandledCodeA = KeyEvent.KEYCODE_A
        val unhandledCodeSpace = KeyEvent.KEYCODE_SPACE

        val eventDownUnknown = KeyEvent(KeyEvent.ACTION_DOWN, unknownCode)
        val eventDownA = KeyEvent(KeyEvent.ACTION_DOWN, unhandledCodeA)
        val eventUpUnknown = KeyEvent(KeyEvent.ACTION_UP, unknownCode)
        val eventUpA = KeyEvent(KeyEvent.ACTION_UP, unhandledCodeA)

        // 1. When controller does not consume key down, unhandled keys return false
        browserController.handleKeyDownResult = false
        assertFalse("Unknown key down should return false", inputRouter.onKeyDown(unknownCode, eventDownUnknown, browserController))
        assertEquals(unknownCode, browserController.lastKeyDownCode)

        assertFalse("Key A down should return false", inputRouter.onKeyDown(unhandledCodeA, eventDownA, browserController))
        assertEquals(unhandledCodeA, browserController.lastKeyDownCode)

        // 2. When controller does consume key down, it returns true
        browserController.handleKeyDownResult = true
        assertTrue("Consumed key down should return true", inputRouter.onKeyDown(unhandledCodeA, eventDownA, browserController))

        // 3. When controller does not consume key up, unhandled keys return false
        browserController.handleKeyUpResult = false
        assertFalse("Unknown key up should return false", inputRouter.onKeyUp(unknownCode, eventUpUnknown, browserController))
        assertEquals(unknownCode, browserController.lastKeyUpCode)

        assertFalse("Key A up should return false", inputRouter.onKeyUp(unhandledCodeA, eventUpA, browserController))
        assertEquals(unhandledCodeA, browserController.lastKeyUpCode)

        // 4. When controller consumes key up, it returns true
        browserController.handleKeyUpResult = true
        assertTrue("Consumed key up should return true", inputRouter.onKeyUp(unhandledCodeA, eventUpA, browserController))

        // 5. Unhandled keys with null controller return false
        assertFalse("Key down with null controller returns false for unhandled keys", inputRouter.onKeyDown(unhandledCodeSpace, KeyEvent(KeyEvent.ACTION_DOWN, unhandledCodeSpace), null))
        assertFalse("Key up with null controller returns false for unhandled keys", inputRouter.onKeyUp(unhandledCodeSpace, KeyEvent(KeyEvent.ACTION_UP, unhandledCodeSpace), null))
    }
}
