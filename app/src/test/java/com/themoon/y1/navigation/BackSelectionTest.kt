package com.themoon.y1.navigation

import android.view.ViewGroup
import com.themoon.y1.controllers.ScreenController
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The back stack is the one way back: each list that opens going down (a screen, a Music level, a Settings group or
 * row screen) is an entry with its route key and its selected row. Back builds the list again from the key and
 * selects the row; going down selects no kept row; the main menu is the bottom entry.
 */
class BackSelectionTest {

    private class FakeList(
        override val rootView: ViewGroup = NavigationManagerTest.createDummyViewGroup()
    ) : ScreenController {
        var row: RowKey? = null
        val selected = ArrayList<RowKey>()
        val entered = ArrayList<ScreenState>()

        override fun onEnter(state: ScreenState) {
            entered.add(state)
        }
        override fun selectedRow(): RowKey? = row
        override fun selectRow(row: RowKey) {
            selected.add(row)
        }
    }

    private val nav = NavigationManager.getInstance()
    private val menu = FakeList()
    private val browser = FakeList()
    private val playlists = FakeList()
    private val player = FakeList()
    private val settings = FakeList()
    private val bluetooth = FakeList()
    private val other = FakeList()

    @Before
    fun setUp() {
        nav.reset()
        nav.registerController(ScreenState.MainMenu::class.java, menu)
        nav.registerController(ScreenState.Browser::class.java, browser)
        nav.registerController(ScreenState.Playlists::class.java, playlists)
        nav.registerController(ScreenState.M3uPlaylistSongs::class.java, playlists)
        nav.registerController(ScreenState.Player::class.java, player)
        nav.registerController(ScreenState.Settings::class.java, settings)
        nav.registerController(ScreenState.Bluetooth::class.java, bluetooth)
        nav.registerController(ScreenState.Storage::class.java, other)
    }

    @After
    fun tearDown() {
        nav.reset()
    }

    private fun music(mode: BrowserMode) = ScreenState.Browser(mode)

    @Test
    fun backSelectsTheRowThatOpenedEachScreen() {
        nav.navigateTo(ScreenState.MainMenu)
        menu.row = RowKey("Music", 1, 37)
        nav.navigateTo(music(BrowserMode.Root))
        browser.row = RowKey("Playlists", 1, 37)
        nav.navigateTo(ScreenState.Playlists)
        playlists.row = RowKey("Rock", 4, 148)
        nav.navigateTo(ScreenState.M3uPlaylistSongs(java.io.File("/Playlists/Rock.m3u")))

        // Going down selects no kept row
        assertTrue(menu.selected.isEmpty() && browser.selected.isEmpty() && playlists.selected.isEmpty())

        assertTrue(nav.popBackStack())
        assertEquals(listOf(RowKey("Rock", 4, 148)), playlists.selected)
        assertTrue(nav.popBackStack())
        assertEquals(listOf(RowKey("Playlists", 1, 37)), browser.selected)
        assertTrue(nav.popBackStack())
        assertEquals(listOf(RowKey("Music", 1, 37)), menu.selected)
        assertFalse(nav.popBackStack())
    }

    @Test
    fun eachMusicLevelIsAnEntryThatBackBuildsAgainFromItsKey() {
        val genres = music(BrowserMode.Genres)
        val fado = music(BrowserMode.GenreArtists("Fado"))
        val allSongs = music(BrowserMode.Songs("GENRE", "Fado", genre = "Fado"))
        nav.navigateTo(ScreenState.MainMenu)
        menu.row = RowKey("Music", 1, 37)
        nav.navigateTo(music(BrowserMode.Root))
        browser.row = RowKey("Genres", 8, 296)
        nav.navigateTo(genres)
        browser.row = RowKey("Fado", 12, 148)
        nav.navigateTo(fado)
        browser.row = RowKey(null, 0, 0) // All Songs
        nav.navigateTo(allSongs)
        browser.row = RowKey("Barco Negro", 3, 111)
        nav.navigateTo(ScreenState.Player)

        // Back step by step: the same controller builds each list again from its key, then selects its row
        browser.entered.clear()
        assertTrue(nav.navigateBack())
        assertEquals(allSongs, nav.currentState)
        assertTrue(nav.navigateBack())
        assertTrue(nav.navigateBack())
        assertTrue(nav.navigateBack())
        assertEquals(listOf(allSongs, fado, genres, music(BrowserMode.Root)), browser.entered)
        assertEquals(
            listOf(RowKey("Barco Negro", 3, 111), RowKey(null, 0, 0), RowKey("Fado", 12, 148), RowKey("Genres", 8, 296)),
            browser.selected
        )
        assertTrue(nav.navigateBack())
        assertEquals(ScreenState.MainMenu, nav.currentState)
        assertEquals(listOf(RowKey("Music", 1, 37)), menu.selected)
    }

    @Test
    fun theMainMenuIsTheBottomEntryAlsoWhenAScreenGoesThereDirectly() {
        nav.navigateTo(ScreenState.MainMenu)
        menu.row = RowKey("Settings", 7, 259)
        nav.navigateTo(ScreenState.Settings())
        settings.row = RowKey("Connectivity", 7, 259)
        nav.navigateTo(ScreenState.Settings(SettingsSubMode.Group("Connectivity")))
        settings.row = RowKey("Bluetooth", 0, 0)
        nav.navigateTo(ScreenState.Bluetooth)

        // Not with Back: all entries go, and the main menu selects the row that opened Settings
        nav.navigateTo(ScreenState.MainMenu)
        assertEquals(listOf(RowKey("Settings", 7, 259)), menu.selected)
        assertTrue(settings.selected.isEmpty())
        assertNull(nav.previousState())
        assertFalse(nav.popBackStack())
    }

    @Test
    fun settingsGroupsAndRowScreensAreEntries() {
        val root = ScreenState.Settings()
        val page = ScreenState.Settings(SettingsSubMode.Page("DateTime"))
        val year = ScreenState.Settings(SettingsSubMode.Page("DateTime", "Year"))
        nav.navigateTo(ScreenState.MainMenu)
        nav.navigateTo(root)
        settings.row = RowKey("Date & Time", 9, 333)
        nav.navigateTo(page)
        settings.row = RowKey("Year", 1, 37)
        nav.navigateTo(year)
        assertEquals(page, nav.previousState())

        // A value was chosen: Back to the Date & Time list, on its Year row
        assertTrue(nav.popBackStack())
        assertEquals(page, settings.entered.last())
        assertEquals(RowKey("Year", 1, 37), settings.selected.last())
        assertTrue(nav.popBackStack())
        assertEquals(root, settings.entered.last())
        assertEquals(RowKey("Date & Time", 9, 333), settings.selected.last())
    }

    @Test
    fun popBackStackToSkipsTheEntriesAboveIt() {
        val page = ScreenState.Settings(SettingsSubMode.Page("Background"))
        nav.navigateTo(ScreenState.MainMenu)
        nav.navigateTo(page)
        settings.row = RowKey("Select New Background", 0, 0)
        nav.navigateTo(music(BrowserMode.Folder(java.io.File("/sdcard"), pickImage = true)))
        nav.navigateTo(music(BrowserMode.Folder(java.io.File("/sdcard/Pictures"), pickImage = true)))

        assertTrue(nav.popBackStackTo { it !is ScreenState.Browser })
        assertEquals(page, nav.currentState)
        assertEquals(listOf(RowKey("Select New Background", 0, 0)), settings.selected)
        assertEquals(ScreenState.MainMenu, nav.previousState())
    }

    private fun folder(path: String) = music(BrowserMode.Folder(java.io.File(path)))

    private fun browserThrough(start: String): RowKey {
        val row = RowKey("Folders", 12, 40)
        nav.navigateTo(ScreenState.MainMenu)
        nav.navigateTo(music(BrowserMode.Root))
        browser.row = row
        nav.navigateTo(folder(start))
        return row
    }

    @Test
    fun eachFolderLevelIsAnEntryAndBackSelectsTheRowThatOpenedIt() {
        val foldersRow = browserThrough("/storage/sdcard1/Music")
        browser.row = RowKey("Queen", 3, 60)
        nav.navigateTo(folder("/storage/sdcard1/Music/Queen"))
        browser.row = RowKey("Greatest Hits", 1, 20)
        nav.navigateTo(folder("/storage/sdcard1/Music/Queen/Greatest Hits"))
        // ".." opens the parent with the row of the folder selected, and Back comes back to the ".." row
        browser.row = RowKey("..", 0, 0)
        nav.navigateTo(folder("/storage/sdcard1/Music/Queen"), row = RowKey("Greatest Hits", 0))
        assertEquals(RowKey("Greatest Hits", 0), browser.selected.last())

        nav.popBackStack()
        assertEquals(folder("/storage/sdcard1/Music/Queen/Greatest Hits"), nav.currentState)
        assertEquals(RowKey("..", 0, 0), browser.selected.last())
        nav.popBackStack()
        assertEquals(folder("/storage/sdcard1/Music/Queen"), nav.currentState)
        assertEquals(RowKey("Greatest Hits", 1, 20), browser.selected.last())
        nav.popBackStack()
        assertEquals(folder("/storage/sdcard1/Music"), nav.currentState)
        assertEquals(RowKey("Queen", 3, 60), browser.selected.last())
        nav.popBackStack()
        assertEquals(music(BrowserMode.Root), nav.currentState)
        assertEquals(foldersRow, browser.selected.last())
    }

    @Test
    fun aHeldBackLeavesAllFolderLevelsAtOnce() {
        val foldersRow = browserThrough("/storage/sdcard1/Music")
        browser.row = RowKey("Queen", 3, 60)
        nav.navigateTo(folder("/storage/sdcard1/Music/Queen"))
        browser.row = RowKey("..", 0, 0)
        nav.navigateTo(folder("/storage/sdcard1/Music"), row = RowKey("Queen", 0))
        assertTrue(folder("/storage").isFolderLevel)
        assertFalse(music(BrowserMode.Root).isFolderLevel)

        assertTrue(nav.popBackStackTo { !it.isFolderLevel })
        assertEquals(music(BrowserMode.Root), nav.currentState)
        assertEquals(foldersRow, browser.selected.last())
    }

    @Test
    fun anEmptyStartFolderGivesItsPlaceToItsParent() {
        val foldersRow = browserThrough("/storage/sdcard1/Music")
        // The empty row of a start folder: the parent takes its place, so Back goes out of Folders
        nav.navigateTo(folder("/storage/sdcard1"), addToBackStack = false, row = RowKey("Music", 0))
        assertEquals(music(BrowserMode.Root), nav.previousState())
        nav.popBackStack()
        assertEquals(music(BrowserMode.Root), nav.currentState)
        assertEquals(foldersRow, browser.selected.last())
    }

    @Test
    fun aScreenThatTakesThePlaceOfAnotherAddsNoEntry() {
        // Music -> Search -> keyboard (here Storage); the result list takes the place of the keyboard
        nav.navigateTo(ScreenState.MainMenu)
        nav.navigateTo(music(BrowserMode.Root))
        browser.row = RowKey("Search", 14, 296)
        nav.navigateTo(ScreenState.Storage)
        nav.navigateTo(music(BrowserMode.Songs("SEARCH", "fado")), addToBackStack = false)

        assertTrue(nav.popBackStack())
        assertEquals(music(BrowserMode.Root), nav.currentState)
        assertEquals(listOf(RowKey("Search", 14, 296)), browser.selected)
    }

    @Test
    fun aRowOnlyComesBackOnce() {
        nav.navigateTo(ScreenState.MainMenu)
        menu.row = RowKey("Settings", 7, 259)
        nav.navigateTo(ScreenState.Settings())
        settings.row = RowKey("Bluetooth", 0, 0)
        nav.navigateTo(ScreenState.Bluetooth)
        assertTrue(nav.popBackStack())
        assertEquals(1, settings.selected.size)

        // Down again from the same screen: a new row is kept, the old one is gone
        settings.row = RowKey("Wi-Fi", 1, 37)
        nav.navigateTo(ScreenState.Bluetooth)
        assertTrue(nav.popBackStack())
        assertEquals(listOf(RowKey("Bluetooth", 0, 0), RowKey("Wi-Fi", 1, 37)), settings.selected)
    }

    @Test
    fun aScreenWithNoSelectedRowGetsNoRow() {
        nav.navigateTo(ScreenState.MainMenu)
        nav.navigateTo(music(BrowserMode.Root))
        nav.navigateTo(ScreenState.Player)
        assertTrue(nav.popBackStack())
        assertTrue(browser.selected.isEmpty())
        assertTrue(nav.popBackStack())
        assertTrue(menu.selected.isEmpty())
    }

    @Test
    fun rowKeyFindsTheRowByTextThenByIndex() {
        val rows = listOf("Now Playing", "Music", "Podcasts", "Settings")
        assertEquals(3, RowKey("Settings", 7).indexIn(rows))          // a row above it was hidden
        assertEquals(1, RowKey("Music", 1).indexIn(rows))
        assertEquals(2, RowKey("Videos", 2).indexIn(rows))            // the row is gone: same position
        assertEquals(3, RowKey("Videos", 9).indexIn(rows))            // ... in the list
        assertEquals(0, RowKey(null, -4).indexIn(rows))
        assertEquals(-1, RowKey("Music", 1).indexIn(emptyList()))
        // Two rows with the same text: the one nearest to the old index
        val songs = listOf("Intro", "Song", "Intro", "Song", "Intro")
        assertEquals(4, RowKey("Intro", 4).indexIn(songs))
        assertEquals(2, RowKey("Intro", 2).indexIn(songs))
        assertEquals(1, RowKey("Song", 0).indexIn(songs))
        // Rows added above it while the list was away: still found by its text
        assertEquals(5, RowKey("Settings", 3).indexIn(listOf("Now Playing", "Music", "Videos", "Photos", "Podcasts", "Settings")))
    }

    @Test
    fun rowKeyKeepsTheFullRowInView() {
        assertEquals(148, RowKey("x", 4, 148).topIn(listHeight = 333, rowHeight = 37))
        assertEquals(296, RowKey("x", 9, 320).topIn(listHeight = 333, rowHeight = 37))
        assertEquals(0, RowKey("x", 0, -20).topIn(listHeight = 333, rowHeight = 37))
        assertNull(RowKey(null, 0).text)
    }
}
