package com.themoon.y1.navigation

import android.view.KeyEvent
import android.view.ViewGroup
import com.themoon.y1.controllers.ScreenController
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class NavigationManagerTest {

    companion object {
        fun createDummyViewGroup(): ViewGroup {
            val unsafeClass = Class.forName("sun.misc.Unsafe")
            val field = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }
            val unsafe = field.get(null)
            val allocateMethod = unsafeClass.getMethod("allocateInstance", Class::class.java)
            return allocateMethod.invoke(unsafe, android.widget.FrameLayout::class.java) as ViewGroup
        }
    }

    private open class FakeController(
        override val rootView: ViewGroup = createDummyViewGroup()
    ) : ScreenController {
        var enterCount = 0
        var exitCount = 0
        var lastStateEntered: ScreenState? = null

        override fun onEnter(state: ScreenState) {
            enterCount++
            lastStateEntered = state
        }

        override fun onExit() {
            exitCount++
        }
    }

    private class FakeCoverFlowController : FakeController()
    private class FakeBrowserController : FakeController()
    private class FakeMainMenuController : FakeController()
    private class FakePlaylistController : FakeController()

    private val navManager = NavigationManager.getInstance()
    private val fakeMainMenu = FakeMainMenuController()
    private val fakeBrowser = FakeBrowserController()
    private val fakeCoverFlow = FakeCoverFlowController()
    private val fakePlaylist = FakePlaylistController()

    @Before
    fun setUp() {
        navManager.reset()
        navManager.registerController(ScreenState.MainMenu::class.java, fakeMainMenu)
        navManager.registerController(ScreenState.Browser::class.java, fakeBrowser)
        navManager.registerController(ScreenState.CoverFlow::class.java, fakeCoverFlow)
        navManager.registerController(ScreenState.Playlists::class.java, fakePlaylist)
        navManager.registerController(ScreenState.M3uPlaylistSongs::class.java, fakePlaylist)
    }

    @After
    fun tearDown() {
        navManager.reset()
    }

    @Test
    fun testMusicToPlaylistsAndBackStack() {
        // Start at Main Menu
        navManager.navigateTo(ScreenState.MainMenu)
        assertEquals(fakeMainMenu, navManager.activeController)
        assertEquals(ScreenState.MainMenu, navManager.currentState)

        // Navigate to Music (Browser Root)
        navManager.navigateTo(ScreenState.Browser(BrowserMode.Root))
        assertEquals(fakeBrowser, navManager.activeController)
        assertEquals(ScreenState.Browser(BrowserMode.Root), navManager.currentState)

        // Navigate to Playlists
        navManager.navigateTo(ScreenState.Playlists)
        assertEquals(fakePlaylist, navManager.activeController)
        assertEquals(ScreenState.Playlists, navManager.currentState)

        // Navigate into a specific M3U playlist
        val m3uFile = java.io.File("/storage/sdcard1/Playlists/Rock.m3u")
        navManager.navigateTo(ScreenState.M3uPlaylistSongs(m3uFile))
        assertEquals(fakePlaylist, navManager.activeController)
        assertEquals(ScreenState.M3uPlaylistSongs(m3uFile), navManager.currentState)

        // Back 1: returns to Playlists list
        assertTrue(navManager.popBackStack())
        assertEquals(fakePlaylist, navManager.activeController)
        assertEquals(ScreenState.Playlists, navManager.currentState)

        // Back 2: returns to Music menu
        assertTrue(navManager.popBackStack())
        assertEquals(fakeBrowser, navManager.activeController)
        assertEquals(ScreenState.Browser(BrowserMode.Root), navManager.currentState)

        // Back 3: returns to Main Menu
        assertTrue(navManager.popBackStack())
        assertEquals(fakeMainMenu, navManager.activeController)
        assertEquals(ScreenState.MainMenu, navManager.currentState)

        // Back 4: stays on Main Menu and returns false
        org.junit.Assert.assertFalse(navManager.popBackStack())
        assertEquals(fakeMainMenu, navManager.activeController)
        assertEquals(ScreenState.MainMenu, navManager.currentState)
    }

    @Test
    fun testCoverFlowNavigationAndBackStack() {
        // Start at Main Menu
        navManager.navigateTo(ScreenState.MainMenu)
        assertEquals(fakeMainMenu, navManager.activeController)
        assertEquals(ScreenState.MainMenu, navManager.currentState)
        assertEquals(1, fakeMainMenu.enterCount)

        // Navigate to Cover Flow
        navManager.navigateTo(ScreenState.CoverFlow)
        assertEquals(fakeCoverFlow, navManager.activeController)
        assertEquals(ScreenState.CoverFlow, navManager.currentState)
        assertEquals(1, fakeMainMenu.exitCount)
        assertEquals(1, fakeCoverFlow.enterCount)

        // Select album to go to VirtualSongs
        navManager.navigateTo(ScreenState.Browser(BrowserMode.VirtualSongs))
        assertEquals(fakeBrowser, navManager.activeController)
        assertEquals(1, fakeCoverFlow.exitCount)
        assertEquals(1, fakeBrowser.enterCount)

        // Pop backstack should return to Cover Flow
        assertTrue(navManager.popBackStack())
        assertEquals(fakeCoverFlow, navManager.activeController)
        assertEquals(ScreenState.CoverFlow, navManager.currentState)
        assertEquals(2, fakeCoverFlow.enterCount)

        // Pop backstack again should return to Main Menu
        assertTrue(navManager.popBackStack())
        assertEquals(fakeMainMenu, navManager.activeController)
        assertEquals(ScreenState.MainMenu, navManager.currentState)
    }

    @Test
    fun testTransitionListener() {
        var transitionedFrom: ScreenState? = null
        var transitionedTo: ScreenState? = null

        navManager.setTransitionListener(object : NavigationManager.StateChangeListener {
            override fun onScreenTransition(from: ScreenState, to: ScreenState) {
                transitionedFrom = from
                transitionedTo = to
            }
        })

        navManager.navigateTo(ScreenState.MainMenu)
        navManager.navigateTo(ScreenState.CoverFlow)

        assertEquals(ScreenState.MainMenu, transitionedFrom)
        assertEquals(ScreenState.CoverFlow, transitionedTo)
    }

    @Test
    fun testJellyfinNavigationAndBackStack() {
        val fakeSettings = FakeController()
        val fakeJellyfin = FakeController()
        navManager.registerController(ScreenState.Settings::class.java, fakeSettings)
        navManager.registerController(ScreenState.Jellyfin::class.java, fakeJellyfin)

        navManager.navigateTo(ScreenState.MainMenu)
        navManager.navigateTo(ScreenState.Settings())
        assertEquals(fakeSettings, navManager.activeController)
        assertEquals(ScreenState.Settings(), navManager.currentState)

        navManager.navigateTo(ScreenState.Jellyfin)
        assertEquals(fakeJellyfin, navManager.activeController)
        assertEquals(ScreenState.Jellyfin, navManager.currentState)
        assertEquals(1, fakeJellyfin.enterCount)
        assertEquals(1, fakeSettings.exitCount)

        assertTrue(navManager.popBackStack())
        assertEquals(fakeSettings, navManager.activeController)
        assertEquals(ScreenState.Settings(), navManager.currentState)
    }

    @Test
    fun testJellyfinToKeyboardCancelAndBackStack() {
        val fakeSettings = FakeController()
        val fakeJellyfin = FakeController()
        val fakeKeyboard = FakeController()
        navManager.registerController(ScreenState.Settings::class.java, fakeSettings)
        navManager.registerController(ScreenState.Jellyfin::class.java, fakeJellyfin)
        navManager.registerController(ScreenState.WifiKeyboard::class.java, fakeKeyboard)

        navManager.navigateTo(ScreenState.MainMenu)
        navManager.navigateTo(ScreenState.Settings())
        navManager.navigateTo(ScreenState.Jellyfin)
        assertEquals(fakeJellyfin, navManager.activeController)

        // Open keyboard
        navManager.navigateTo(ScreenState.WifiKeyboard(KeyboardTarget.LibrarySearch))
        assertEquals(fakeKeyboard, navManager.activeController)
        assertEquals(ScreenState.WifiKeyboard(KeyboardTarget.LibrarySearch), navManager.currentState)

        // Cancel / exit keyboard pops back to Jellyfin
        assertTrue(navManager.popBackStack())
        assertEquals(fakeJellyfin, navManager.activeController)
        assertEquals(ScreenState.Jellyfin, navManager.currentState)

        // Back from Jellyfin pops back to Settings
        assertTrue(navManager.popBackStack())
        assertEquals(fakeSettings, navManager.activeController)
        assertEquals(ScreenState.Settings(), navManager.currentState)

        // Back from Settings pops to MainMenu
        assertTrue(navManager.popBackStack())
        assertEquals(ScreenState.MainMenu, navManager.currentState)
    }

    @Test
    fun testAudiobooksNavigationAndBackStack() {
        // Start at Main Menu
        navManager.navigateTo(ScreenState.MainMenu)
        assertEquals(fakeMainMenu, navManager.activeController)
        assertEquals(ScreenState.MainMenu, navManager.currentState)

        // Navigate to Audiobooks (Browser Audiobooks mode)
        navManager.navigateTo(ScreenState.Browser(BrowserMode.Audiobooks))
        assertEquals(fakeBrowser, navManager.activeController)
        assertEquals(ScreenState.Browser(BrowserMode.Audiobooks), navManager.currentState)

        // Pop backstack should return to Main Menu
        assertTrue(navManager.popBackStack())
        assertEquals(fakeMainMenu, navManager.activeController)
        assertEquals(ScreenState.MainMenu, navManager.currentState)

        // Now navigate to Music (Browser Root mode)
        navManager.navigateTo(ScreenState.Browser(BrowserMode.Root))
        assertEquals(fakeBrowser, navManager.activeController)
        assertEquals(ScreenState.Browser(BrowserMode.Root), navManager.currentState)

        // Pop backstack returns to Main Menu
        assertTrue(navManager.popBackStack())
        assertEquals(fakeMainMenu, navManager.activeController)
        assertEquals(ScreenState.MainMenu, navManager.currentState)
    }
}
