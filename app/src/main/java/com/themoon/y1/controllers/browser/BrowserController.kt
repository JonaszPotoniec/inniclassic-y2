package com.themoon.y1.controllers.browser

import com.themoon.y1.navigation.RowFocus
import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView
import com.themoon.y1.AppPrefs
import com.themoon.y1.MainActivity
import com.themoon.y1.R
import com.themoon.y1.StoragePaths
import com.themoon.y1.ThemeManager
import com.themoon.y1.adapters.CategoryListAdapter
import com.themoon.y1.adapters.SongListAdapter
import com.themoon.y1.controllers.ScreenController
import com.themoon.y1.managers.AlbumCoverManager
import com.themoon.y1.managers.AudioPlayerManager
import com.themoon.y1.managers.AudiobookManager
import com.themoon.y1.models.AlbumKey
import com.themoon.y1.models.SongItem
import com.themoon.y1.navigation.BrowserMode
import com.themoon.y1.navigation.InputRouter
import com.themoon.y1.navigation.LetterJump
import com.themoon.y1.navigation.NavigationManager
import com.themoon.y1.navigation.RowKey
import com.themoon.y1.navigation.ScreenState
import com.themoon.y1.navigation.isFolderLevel
import com.themoon.y1.views.AlphabetIndexBar
import com.themoon.y1.views.ClassicMenuStyle
import com.themoon.y1.views.Notice
import java.io.File
import java.util.ArrayList
import java.util.Collections
import java.util.Comparator
import java.util.HashSet

/**
 * Controller for library browser navigation:
 * - Root music / audiobook library menus
 * - Folder browsing with recursive audio playback and background picking
 * - Category lists (Artists, Track Artists, Albums, Genres, Composers, Years)
 * - Virtual song lists (All, Recent, Favorites, Album tracks, Search results)
 * - Breadcrumb navigation and Alphabet index bar integration
 */
class BrowserController(
    private val activity: Activity,
    override val rootView: ViewGroup,
    private val listVirtualSongs: ListView?,
    private val hzIndexScroll: HorizontalScrollView?,
    private val layoutIndexContainer: LinearLayout?,
    private val clickFeedback: () -> Unit,
    private val createListButtonWithIcon: (icon: String, text: String, color: Int?) -> View,
    private val onNavigateToScreen: (ScreenState) -> Unit,
    private val onNavigateBackToMenu: () -> Unit,
    private val onCoverFlowRequested: () -> Unit,
    private val onPlaylistsRequested: () -> Unit,
    private val onShuffleSongsRequested: () -> Unit,
    private val onSearchRequested: () -> Unit,
    private val onStartMediaLibraryScan: () -> Unit,
    private val showLoadingPopup: () -> Unit,
    private val showAddToPlaylistDialog: (File) -> Unit,
    private val onAudiobookSelected: (audioFile: File, parentFolder: File) -> Unit,
    private val localize: (String) -> String,
    private val onRefreshCoverPane: (() -> Unit)? = null
) : ScreenController {

    private val containerBrowserItems: LinearLayout = rootView.findViewById(R.id.container_browser_items)
    private val scrollViewBrowser: ScrollView? = containerBrowserItems.parent as? ScrollView
    private val tvBrowserPath: TextView = rootView.findViewById(R.id.tv_browser_path)
    private val frameBrowserCover: FrameLayout? = rootView.findViewById(R.id.frame_browser_cover)
    private val ivBrowserCover: ImageView? = rootView.findViewById(R.id.iv_browser_cover)

    private val alphabetIndexBarHelper = if (hzIndexScroll != null && layoutIndexContainer != null) {
        AlphabetIndexBar(
            activity,
            hzIndexScroll,
            layoutIndexContainer,
            listVirtualSongs,
            clickFeedback
        )
    } else null

    var currentBrowserMode: BrowserMode = BrowserMode.Root
    var isAudiobookLibraryMode: Boolean = false
    var isPickingBackground: Boolean = false

    var currentFolder: File = StoragePaths.getPrimaryRoot()
    var virtualQueryType: String = "ALL"
    var virtualQueryValue: String = ""
    /** Album artist of the album that virtualQueryValue names; empty matches any artist. */
    var virtualQueryAlbumArtist: String = ""
    var categoryArtistFilter: String = ""
    var categoryArtistFilterIsTrackArtist: Boolean = false
    var categoryGenreFilter: String = ""
    var virtualListSelectedIndex: Int = 0

    private val currentScrollIndexList = ArrayList<String>()
    private var browserCoverPaneSetUp: Boolean = false
    private val uiHandler = Handler(Looper.getMainLooper())

    private val letterMode = LetterJump.Mode()
    private val letterIdleTask = Runnable { endLetterJump() }
    private var rowLettersKey: Any? = null
    private var rowLetters: List<String?>? = null
    /** The letter in the letter box during letter mode; null when the wheel steps row by row. */
    var jumpLetter: String? = null
        private set

    companion object {
        private const val FOLDER_ROW_CHUNK_SIZE = 25
        const val ALL_SONGS_SENTINEL = "__ALL_SONGS__"
    }

    /**
     * Builds the list that [state] names (its route key): a list that opens going down, or the list that Back
     * comes back to (NavigationManager then selects the row that opened the deeper list).
     */
    override fun onEnter(state: ScreenState) {
        if (state is ScreenState.Browser) {
            currentBrowserMode = state.mode
            isAudiobookLibraryMode = state.audiobooks || state.mode is BrowserMode.Audiobooks
            isPickingBackground = (state.mode as? BrowserMode.Folder)?.pickImage == true
            when (val mode = state.mode) {
                is BrowserMode.Root -> buildFileBrowserUI()
                is BrowserMode.Folder -> {
                    currentFolder = mode.dir
                    buildFolderBrowserUI()
                }
                is BrowserMode.Artists -> {
                    virtualQueryValue = ""
                    categoryArtistFilter = ""
                    categoryArtistFilterIsTrackArtist = false
                    buildVirtualCategories("ARTIST")
                }
                is BrowserMode.TrackArtists -> {
                    virtualQueryValue = ""
                    categoryArtistFilter = ""
                    categoryArtistFilterIsTrackArtist = false
                    buildVirtualCategories("TRACK_ARTIST")
                }
                is BrowserMode.Albums -> {
                    virtualQueryValue = ""
                    categoryArtistFilter = mode.artistFilter ?: ""
                    categoryArtistFilterIsTrackArtist = mode.byTrackArtist
                    buildVirtualCategories("ALBUM")
                }
                is BrowserMode.Compilations -> {
                    virtualQueryValue = ""
                    categoryArtistFilter = ""
                    categoryArtistFilterIsTrackArtist = false
                    buildVirtualCategories("ALBUM")
                }
                is BrowserMode.Genres -> {
                    virtualQueryValue = ""
                    categoryGenreFilter = ""
                    categoryArtistFilter = ""
                    categoryArtistFilterIsTrackArtist = false
                    buildVirtualCategories("GENRE")
                }
                is BrowserMode.GenreArtists -> {
                    categoryGenreFilter = mode.genre
                    virtualQueryValue = ""
                    buildVirtualCategories("GENRE_ARTIST")
                }
                is BrowserMode.GenreAlbums -> {
                    categoryGenreFilter = mode.genre
                    categoryArtistFilter = mode.artist ?: ""
                    categoryArtistFilterIsTrackArtist = true
                    virtualQueryValue = ""
                    buildVirtualCategories("GENRE_ALBUM")
                }
                is BrowserMode.Composers -> {
                    virtualQueryValue = ""
                    buildVirtualCategories("COMPOSER")
                }
                is BrowserMode.Years -> {
                    virtualQueryValue = ""
                    buildVirtualCategories("YEAR")
                }
                is BrowserMode.Songs -> {
                    virtualQueryType = mode.type
                    virtualQueryValue = mode.value
                    virtualQueryAlbumArtist = mode.albumArtist
                    categoryArtistFilter = mode.artist
                    categoryArtistFilterIsTrackArtist = mode.byTrackArtist
                    categoryGenreFilter = mode.genre
                    currentBrowserMode = BrowserMode.VirtualSongs
                    buildVirtualSongs()
                }
                is BrowserMode.VirtualSongs -> buildVirtualSongs()
                is BrowserMode.Favorites -> buildVirtualSongsForFavorites()
                is BrowserMode.RecentlyAdded -> {
                    virtualQueryType = "RECENT"
                    buildVirtualSongs()
                }
                is BrowserMode.Audiobooks -> buildFileBrowserUI()
                is BrowserMode.CoverFlow -> onCoverFlowRequested()
                else -> buildFileBrowserUI()
            }
        } else {
            buildFileBrowserUI()
        }
    }

    /** Opens the list [mode] one level down. The back stack keeps this list and its row (NavigationManager). */
    fun openList(mode: BrowserMode) = onNavigateToScreen(ScreenState.Browser(mode, isAudiobookLibraryMode))

    /** Opens a song list one level down, with the filters of this list (see [BrowserMode.Songs]). */
    fun openSongs(type: String, value: String, albumArtist: String = "") = openList(
        BrowserMode.Songs(type, value, albumArtist, categoryArtistFilter, categoryArtistFilterIsTrackArtist, categoryGenreFilter)
    )

    override fun onExit() {
        hideBrowserCoverPane()
        endLetterJump()
        hzIndexScroll?.visibility = View.GONE
        isAudiobookLibraryMode = false
    }

    override fun onLibraryLoaded() {
        // The Music menu itself does not change; rebuilding it would move the selection to the top
        if (currentBrowserMode !is BrowserMode.Root) onRefreshUI()
    }

    override fun onRefreshUI() {
        when (currentBrowserMode) {
            is BrowserMode.Root, is BrowserMode.Audiobooks -> buildFileBrowserUI()
            is BrowserMode.Folder -> buildFolderBrowserUI()
            is BrowserMode.VirtualSongs -> buildVirtualSongs()
            is BrowserMode.Favorites -> buildVirtualSongsForFavorites()
            else -> {}
        }
    }

    override fun onThemeChanged() {
        // Not onEnter: it clears the filters of the category lists. The row is kept (MainActivityTheme, ListRows).
        when (currentBrowserMode) {
            is BrowserMode.Artists -> buildVirtualCategories("ARTIST")
            is BrowserMode.TrackArtists -> buildVirtualCategories("TRACK_ARTIST")
            is BrowserMode.Albums -> buildVirtualCategories("ALBUM")
            is BrowserMode.Genres -> buildVirtualCategories("GENRE")
            is BrowserMode.GenreArtists -> buildVirtualCategories("GENRE_ARTIST")
            is BrowserMode.GenreAlbums -> buildVirtualCategories("GENRE_ALBUM")
            is BrowserMode.Composers -> buildVirtualCategories("COMPOSER")
            is BrowserMode.Years -> buildVirtualCategories("YEAR")
            is BrowserMode.RecentlyAdded -> buildVirtualSongs()
            else -> onRefreshUI()
        }
    }

    fun buildFileBrowserUI() {
        val statusBar = activity.findViewById<View>(R.id.layout_status_bar)
        statusBar?.background = ThemeManager.createStatusBarBackground()

        scrollViewBrowser?.visibility = View.VISIBLE
        listVirtualSongs?.visibility = View.GONE
        containerBrowserItems.removeAllViews()

        if (isPickingBackground || currentBrowserMode is BrowserMode.Folder) {
            buildFolderBrowserUI()
            return
        }

        if (!isAudiobookLibraryMode && currentBrowserMode !is BrowserMode.Audiobooks) {
            updateHeaderTitle(localize("Music"))
            showBrowserCoverPane()

            for (item in MusicMenuItems.ITEMS) {
                if (isMusicMenuItemHidden(item.id)) continue
                val label = localize(item.label)
                val btn = createListButtonWithIcon(item.icon, label, null)
                btn.setOnClickListener {
                    clickFeedback()
                    openMusicMenuItem(item.id)
                }
                containerBrowserItems.addView(btn)
            }
        } else {
            updateHeaderTitle(localize("Audiobooks"))
            showBrowserCoverPane()

            val btnContinue = createListButtonWithIcon("\uE037", localize("Continue"), null)
            btnContinue.setOnClickListener {
                clickFeedback()
                val main = MainActivity.instance ?: return@setOnClickListener
                main.isAudiobookLibraryMode = true
                val savedPath = main.prefs.getString("last_played_audiobook_path", null)
                    ?: if (main.prefs.getBoolean("last_played_is_audiobook", false)) {
                        main.prefs.getString("last_played_song_path", null)
                    } else null
                val file = if (!savedPath.isNullOrEmpty()) File(savedPath) else null
                val targetFile = if (file != null && file.exists()) file else MainActivity.audiobookLibrary.firstOrNull()?.file
                if (targetFile != null && targetFile.exists()) {
                    val bookChapters = ArrayList<File>()
                    val bookItem = MainActivity.audiobookLibrary.firstOrNull { it.file.absolutePath == targetFile.absolutePath }
                    if (bookItem != null && bookItem.album.isNotEmpty()) {
                        val matching = MainActivity.audiobookLibrary.filter {
                            it.album.equals(bookItem.album, ignoreCase = true) &&
                            (bookItem.artist.isEmpty() || it.artist.equals(bookItem.artist, ignoreCase = true) || it.albumArtist.equals(bookItem.albumArtist, ignoreCase = true))
                        }.sortedWith { s1, s2 ->
                            val t1 = MainActivity.trackNumberMap[s1.file.absolutePath] ?: 0
                            val t2 = MainActivity.trackNumberMap[s2.file.absolutePath] ?: 0
                            if (t1 != t2 && t1 > 0 && t2 > 0) t1.compareTo(t2) else s1.title.compareTo(s2.title, ignoreCase = true)
                        }
                        for (s in matching) {
                            bookChapters.add(s.file)
                        }
                    }
                    if (bookChapters.isEmpty()) {
                        val parent = targetFile.parentFile ?: StoragePaths.getAudiobooksDir()
                        val files = parent.listFiles()
                        if (files != null) {
                            for (f in files) {
                                if (AudiobookManager.isAudioFile(f)) bookChapters.add(f)
                            }
                            bookChapters.sortWith { f1, f2 ->
                                val t1 = MainActivity.trackNumberMap[f1.absolutePath] ?: 0
                                val t2 = MainActivity.trackNumberMap[f2.absolutePath] ?: 0
                                if (t1 != t2 && t1 > 0 && t2 > 0) t1.compareTo(t2) else f1.name.compareTo(f2.name, ignoreCase = true)
                            }
                        }
                    }
                    if (bookChapters.isEmpty()) bookChapters.add(targetFile)

                    val curPlaying = AudioPlayerManager.getInstance().currentlyPlayingFilePath
                    if (curPlaying == targetFile.absolutePath && main.currentPlaylist.size == bookChapters.size && main.currentPlaylist.isNotEmpty()) {
                        if (!AudioPlayerManager.getInstance().isPlaying()) {
                            AudioPlayerManager.getInstance().playOrPauseMusic()
                        }
                        onNavigateToScreen(ScreenState.Player)
                        return@setOnClickListener
                    }

                    var targetIdx = bookChapters.indexOfFirst { it.absolutePath == targetFile.absolutePath }
                    if (targetIdx == -1) targetIdx = 0
                    val savedPos = main.prefs.getInt("book_pos_" + targetFile.absolutePath, 0)
                    AudioPlayerManager.getInstance().playTrackListWithOffset(bookChapters, targetIdx, savedPos)
                    onNavigateToScreen(ScreenState.Player)
                } else {
                    Notice.show(activity, localize("No audiobooks found"))
                }
            }
            containerBrowserItems.addView(btnContinue)

            val btnBook = createListButtonWithIcon("\uE86D", localize("Books"), null)
            btnBook.setOnClickListener {
                clickFeedback()
                openList(BrowserMode.Albums())
            }
            containerBrowserItems.addView(btnBook)

            val btnAuthor = createListButtonWithIcon("\uE7FD", localize("Authors"), null)
            btnAuthor.setOnClickListener {
                clickFeedback()
                openList(BrowserMode.Artists)
            }
            containerBrowserItems.addView(btnAuthor)
        }

        val isScanning = MainActivity.instance?.isCustomScanning == true
        val scanIcon = if (isScanning) "\uE88B" else "\uE863"
        val scanText = if (isScanning) localize("Scanning Media...") else localize("Scan Media Library")
        val btnScan = createListButtonWithIcon(scanIcon, scanText, null)
        btnScan.setOnClickListener {
            clickFeedback()
            onStartMediaLibraryScan()
        }
        containerBrowserItems.addView(btnScan)

        finishFolderBrowserUIRender()
    }

    fun buildVirtualCategories(type: String) {
        hideBrowserCoverPane()
        if (MainActivity.instance?.isCustomScanning == true) {
            showLoadingPopup()
            currentBrowserMode = BrowserMode.Root
            buildFileBrowserUI()
            return
        }

        scrollViewBrowser?.visibility = View.GONE
        listVirtualSongs?.visibility = View.VISIBLE

        val catTitle = when {
            type == "ALBUM" && currentBrowserMode is BrowserMode.Compilations -> localize("Compilations")
            type == "ALBUM" && categoryArtistFilter.isNotEmpty() -> categoryArtistFilter
            type == "GENRE_ALBUM" && categoryArtistFilter.isNotEmpty() -> categoryArtistFilter
            type == "GENRE_ARTIST" && categoryGenreFilter.isNotEmpty() -> categoryGenreFilter
            isAudiobookLibraryMode -> if (type == "ARTIST") localize("Authors") else localize("Books")
            type == "GENRE" -> localize("Genres")
            type == "YEAR" -> localize("Years")
            type == "COMPOSER" -> localize("Composers")
            type == "TRACK_ARTIST" -> localize("Artists")
            else -> if (type == "ARTIST") localize("Album Artists") else localize("Albums")
        }
        updateHeaderTitle(catTitle)

        // Compilations lists only compilation albums. Artists (and the albums of an artist in it) then
        // leaves out their tracks. SongItem.isCompilation is set when the library loads.
        val compilationsOnly = type == "ALBUM" && currentBrowserMode is BrowserMode.Compilations
        val hideCompilationTracks = (type == "TRACK_ARTIST" ||
            (type == "ALBUM" && categoryArtistFilterIsTrackArtist && categoryArtistFilter.isNotEmpty())) &&
            artistsHideCompilations()
        val activeLibrary = if (isAudiobookLibraryMode) MainActivity.audiobookLibrary else MainActivity.customLibrary
        val uniqueCategories = HashSet<String>()
        val albumSongs = ArrayList<SongItem>()

        for (song in activeLibrary) {
            if (compilationsOnly && !song.isCompilation) continue
            if (com.themoon.y1.models.Compilations.hiddenFromArtists(song, hideCompilationTracks)) continue
            if (type == "ALBUM" && categoryArtistFilter.isNotEmpty()) {
                val matchField = if (categoryArtistFilterIsTrackArtist) song.artist else song.albumArtist
                if (categoryArtistFilter != matchField) continue
            } else if (type == "GENRE_ARTIST") {
                if (!song.matchesGenre(categoryGenreFilter)) continue
            } else if (type == "GENRE_ALBUM") {
                if (!song.matchesGenre(categoryGenreFilter)) continue
                if (categoryArtistFilter.isNotEmpty()) {
                    val matchField = if (categoryArtistFilterIsTrackArtist) song.artist else song.albumArtist
                    if (!categoryArtistFilter.equals(matchField, ignoreCase = true)) continue
                }
            }

            if (type == "ALBUM" || type == "GENRE_ALBUM") {
                albumSongs.add(song)
                continue
            }
            if (type == "GENRE") {
                uniqueCategories.addAll(song.getGenres())
                continue
            }
            val v = when (type) {
                "ARTIST" -> song.albumArtist
                "TRACK_ARTIST", "GENRE_ARTIST" -> song.artist
                "YEAR" -> song.year
                "COMPOSER" -> song.composer
                else -> "Unknown"
            }
            uniqueCategories.add(v)
        }

        val categories = ArrayList(uniqueCategories)
        Collections.sort(categories, java.lang.String.CASE_INSENSITIVE_ORDER)
        // One row per album as Group Albums tells (AlbumKey): title, or title + album artist.
        var albumArtists: ArrayList<String>? = null
        if (type == "ALBUM" || type == "GENRE_ALBUM") {
            val albums = AlbumKey.firstSongPerAlbum(albumSongs).sortedWith(
                compareBy<SongItem, String>(java.lang.String.CASE_INSENSITIVE_ORDER) { it.album }
                    .thenBy(java.lang.String.CASE_INSENSITIVE_ORDER) { AlbumKey.artistOf(it) }
            )
            albums.mapTo(categories) { it.album }
            albumArtists = albums.mapTo(ArrayList()) { AlbumKey.artistOf(it) }
        }

        if (!isAudiobookLibraryMode && ((type == "ALBUM" && categoryArtistFilter.isNotEmpty()) || type == "GENRE_ARTIST" || type == "GENRE_ALBUM")) {
            categories.add(0, ALL_SONGS_SENTINEL)
            albumArtists?.add(0, "")
        }

        currentScrollIndexList.clear()
        currentScrollIndexList.addAll(categories)

        val adapter = CategoryListAdapter(categories, type, isAudiobookLibraryMode, albumArtists = albumArtists)
        listVirtualSongs?.adapter = adapter

        // A list opens at its first row; Back selects its row again (NavigationManager)
        virtualListSelectedIndex = 0
        listVirtualSongs?.post {
            navigateVirtualListTo(virtualListSelectedIndex)
        }
        buildAlphabetIndexBar()
    }

    fun buildVirtualSongs() {
        hideBrowserCoverPane()
        val statusBar = activity.findViewById<View>(R.id.layout_status_bar)
        statusBar?.background = ThemeManager.createStatusBarBackground()

        if (MainActivity.instance?.isCustomScanning == true) {
            showLoadingPopup()
            currentBrowserMode = BrowserMode.Root
            buildFileBrowserUI()
            return
        }

        scrollViewBrowser?.visibility = View.GONE
        listVirtualSongs?.visibility = View.VISIBLE

        val songsTitle = when (virtualQueryType) {
            "RECENT" -> localize("Recently Added")
            "ALL" -> if (isAudiobookLibraryMode) localize("All Audiobooks") else localize("All Songs")
            "SEARCH" -> "${localize("Search")}: $virtualQueryValue"
            "ARTIST_ALL_ALBUMS" -> virtualQueryValue
            else -> virtualQueryValue
        }
        updateHeaderTitle(songsTitle)

        val main = MainActivity.instance
        main?.virtualSongList?.clear()
        currentScrollIndexList.clear()
        val targetSongs = ArrayList<SongItem>()
        val activeLibrary = if (isAudiobookLibraryMode) MainActivity.audiobookLibrary else MainActivity.customLibrary
        val hideCompilationTracks = categoryArtistFilterIsTrackArtist && artistsHideCompilations()

        for (song in activeLibrary) {
            var isPathMatched = false
            if (isAudiobookLibraryMode) {
                if (StoragePaths.isUnderAudiobooks(song.file.absolutePath) || song.file.absolutePath.contains("audiobooks", ignoreCase = true)) isPathMatched = true
            } else {
                if (StoragePaths.isUnderMusic(song.file.absolutePath) || song.file.absolutePath.contains("music", ignoreCase = true)) isPathMatched = true
            }

            if (isPathMatched) {
                var isRecentPassed = true
                if (virtualQueryType == "RECENT") {
                    val now = System.currentTimeMillis()
                    val fiveDaysMs = 5L * 24 * 60 * 60 * 1000
                    if (now - song.file.lastModified() > fiveDaysMs) {
                        isRecentPassed = false
                    }
                }

                var isSearchMatch = false
                if (virtualQueryType == "SEARCH") {
                    val q = virtualQueryValue.lowercase()
                    isSearchMatch = q.isNotEmpty() && (
                        (song.title.isNotEmpty() && song.title.lowercase().contains(q)) ||
                        (song.artist.isNotEmpty() && song.artist.lowercase().contains(q)) ||
                        (song.album.isNotEmpty() && song.album.lowercase().contains(q))
                    )
                }

                var isMatch = false
                when {
                    virtualQueryType == "ALL" || virtualQueryType == "RECENT" -> isMatch = true
                    virtualQueryType == "ARTIST" && song.artist == virtualQueryValue ->
                        isMatch = !com.themoon.y1.models.Compilations.hiddenFromArtists(song, hideCompilationTracks)
                    virtualQueryType == "ARTIST_ALL_ALBUMS" && song.albumArtist == virtualQueryValue -> isMatch = true
                    virtualQueryType == "ALBUM" && AlbumKey.matches(song, virtualQueryValue, virtualQueryAlbumArtist) -> {
                        if (categoryArtistFilter.isNotEmpty()) {
                            val matchField = if (categoryArtistFilterIsTrackArtist) song.artist else song.albumArtist
                            isMatch = (categoryArtistFilter.equals(matchField, ignoreCase = true) || categoryArtistFilter.equals(song.artist, ignoreCase = true)) &&
                                !com.themoon.y1.models.Compilations.hiddenFromArtists(song, hideCompilationTracks)
                        } else {
                            isMatch = true
                        }
                    }
                    virtualQueryType == "COVER_FLOW_ALBUM" && AlbumKey.matches(song, virtualQueryValue, virtualQueryAlbumArtist) -> isMatch = true
                    virtualQueryType == "YEAR" && song.year == virtualQueryValue -> isMatch = true
                    virtualQueryType == "GENRE" && song.matchesGenre(virtualQueryValue) -> isMatch = true
                    virtualQueryType == "GENRE_ARTIST_SONGS" -> {
                        val matchField = if (categoryArtistFilterIsTrackArtist) song.artist else song.albumArtist
                        isMatch = song.matchesGenre(categoryGenreFilter) &&
                            (categoryArtistFilter.isEmpty() || categoryArtistFilter.equals(matchField, ignoreCase = true))
                    }
                    virtualQueryType == "GENRE_ALBUM_SONGS" -> {
                        val matchField = if (categoryArtistFilterIsTrackArtist) song.artist else song.albumArtist
                        isMatch = AlbumKey.matches(song, virtualQueryValue, virtualQueryAlbumArtist) &&
                            song.matchesGenre(categoryGenreFilter) &&
                            (categoryArtistFilter.isEmpty() || categoryArtistFilter.equals(matchField, ignoreCase = true))
                    }
                    virtualQueryType == "COMPOSER" && song.composer == virtualQueryValue -> isMatch = true
                    virtualQueryType == "COMPILATION_ALBUM" && song.isCompilation &&
                        song.album.trim().equals(virtualQueryValue.trim(), ignoreCase = true) -> isMatch = true
                    isSearchMatch -> isMatch = true
                }

                if (isRecentPassed && isMatch) {
                    targetSongs.add(song)
                }
            }
        }

        Collections.sort(targetSongs) { s1, s2 ->
            if ("ALBUM" == virtualQueryType || "COVER_FLOW_ALBUM" == virtualQueryType || "GENRE_ALBUM_SONGS" == virtualQueryType ||
                "COMPILATION_ALBUM" == virtualQueryType) {
                val t1 = MainActivity.trackNumberMap[s1.file.absolutePath] ?: 0
                val t2 = MainActivity.trackNumberMap[s2.file.absolutePath] ?: 0
                if (t1 != t2) {
                    return@sort t1.compareTo(t2)
                }
            }
            s1.title.compareTo(s2.title, ignoreCase = true)
        }

        main?.virtualSongList?.clear()
        currentScrollIndexList.clear()
        val displaySongs = ArrayList<SongItem>()

        for (song in targetSongs) {
            val displayTitle = song.title
            displaySongs.add(SongItem(song.file, displayTitle, song.artist, song.album, song.year, song.genre, song.albumArtist, song.composer))
            main?.virtualSongList?.add(song.file)
            currentScrollIndexList.add(displayTitle)
        }

        val adapter = SongListAdapter(displaySongs)
        listVirtualSongs?.adapter = adapter

        virtualListSelectedIndex = 0
        listVirtualSongs?.post {
            navigateVirtualListTo(0)
        }
        buildAlphabetIndexBar()
    }

    fun buildVirtualSongsForFavorites() {
        hideBrowserCoverPane()
        if (MainActivity.instance?.isCustomScanning == true) {
            showLoadingPopup()
            currentBrowserMode = BrowserMode.Root
            buildFileBrowserUI()
            return
        }

        scrollViewBrowser?.visibility = View.GONE
        listVirtualSongs?.visibility = View.VISIBLE
        updateHeaderTitle(localize("My Favorites"))

        val main = MainActivity.instance
        main?.virtualSongList?.clear()
        currentScrollIndexList.clear()
        val targetSongs = ArrayList<SongItem>()

        for (song in MainActivity.customLibrary) {
            if (main?.favoritePaths?.contains(song.file.absolutePath) == true) {
                targetSongs.add(song)
            }
        }

        Collections.sort(targetSongs) { s1, s2 -> s1.title.compareTo(s2.title, ignoreCase = true) }

        for (song in targetSongs) {
            main?.virtualSongList?.add(song.file)
            currentScrollIndexList.add(song.title)
        }

        if (targetSongs.isEmpty()) {
            Notice.show(activity, localize("No favorites added yet."))
        }

        val adapter = SongListAdapter(targetSongs)
        listVirtualSongs?.adapter = adapter

        var targetIndex = -1
        val curPlaylist = main?.currentPlaylist
        val curIndex = main?.currentIndex ?: -1
        if (curPlaylist != null && curPlaylist.isNotEmpty() && curIndex >= 0 && curIndex < curPlaylist.size) {
            val playingFile = curPlaylist[curIndex]
            for (i in targetSongs.indices) {
                if (targetSongs[i].file.absolutePath == playingFile.absolutePath) {
                    targetIndex = i
                    break
                }
            }
        }

        val finalTargetIdx = targetIndex
        listVirtualSongs?.post {
            if (finalTargetIdx in 0 until (listVirtualSongs.count)) {
                listVirtualSongs.setSelectionFromTop(finalTargetIdx, 0)
                listVirtualSongs.postDelayed({
                    val visiblePos = finalTargetIdx - listVirtualSongs.firstVisiblePosition
                    if (visiblePos in 0 until listVirtualSongs.childCount) {
                        listVirtualSongs.getChildAt(visiblePos)?.requestFocus()
                    }
                }, 50)
            } else if (listVirtualSongs.childCount > 0) {
                listVirtualSongs.getChildAt(0)?.requestFocus()
            }
        }
    }

    fun buildFolderBrowserUI() {
        hideBrowserCoverPane()
        containerBrowserItems.removeAllViews()
        val rawDisplayPath = StoragePaths.toDisplayPath(currentFolder.absolutePath)
        val folderTitle = if (currentFolder.absolutePath == StoragePaths.STORAGE_DIR) {
            localize("Storage")
        } else if (currentFolder.absolutePath == StoragePaths.SECONDARY_PATH || currentFolder.absolutePath.startsWith("${StoragePaths.SECONDARY_PATH}/")) {
            "${localize("Path")}: [SD] $rawDisplayPath"
        } else {
            "${localize("Path")}: $rawDisplayPath"
        }
        updateHeaderTitle(folderTitle)
        val files = currentFolder.listFiles()

        val isTop = StoragePaths.isTopStorageRoot(currentFolder)
        val canGoUp = !isTop && currentFolder.parentFile != null

        if (canGoUp) {
            val bUp = createListButtonWithIcon("\uE2C7", "..", null).apply { tag = "UP" }
            bUp.setOnClickListener {
                clickFeedback()
                currentFolder.parentFile?.let { openParentFolder(it, addToBackStack = true) }
            }
            containerBrowserItems.addView(bUp)
        }

        if (files == null || files.isEmpty()) {
            val emptyMsg = if (files == null) {
                "⚠️ ${localize("USB Disconnect Required (Tap to go back)")}"
            } else {
                "📂 ${localize("Empty Folder (Tap to go back)")}"
            }
            val btnEmpty = Button(activity).apply {
                tag = "EMPTY"
                text = emptyMsg
                setTextColor(0xFFFF5555.toInt())
                background = createButtonBackground(ThemeManager.getListButtonNormalBg())
                setOnClickListener {
                    clickFeedback()
                    // Back when a folder list is under this one or no folder is above it; else one level up in place of this list
                    if (isTop || NavigationManager.getInstance().previousState()?.isFolderLevel == true) onBackPressed()
                    else openParentFolder(currentFolder.parentFile ?: StoragePaths.getTopStorageRoot(), addToBackStack = false)
                }
            }
            containerBrowserItems.addView(btnEmpty)
            finishFolderBrowserUIRender()
            return
        }

        val folders = ArrayList<File>()
        val audioFiles = ArrayList<File>()
        val apkFiles = ArrayList<File>()
        val imageFiles = ArrayList<File>()

        for (f in files) {
            if (f.name.startsWith(".")) continue
            if (isTop) {
                if (f.isDirectory && !f.name.equals("emulated", ignoreCase = true) && !f.name.equals("media_rw", ignoreCase = true)) {
                    val contents = try { f.listFiles() } catch (_: Exception) { null }
                    if (contents != null && contents.isNotEmpty()) {
                        folders.add(f)
                    }
                }
                continue
            }
            when {
                f.isDirectory -> folders.add(f)
                isPickingBackground && isImageFile(f) -> imageFiles.add(f)
                !isPickingBackground && isAudioFile(f) -> audioFiles.add(f)
                !isPickingBackground && isApkFile(f) -> apkFiles.add(f)
            }
        }

        val fileSorter = Comparator<File> { f1, f2 ->
            if (isTop) {
                val p1 = f1.absolutePath
                val p2 = f2.absolutePath
                val isPri1 = p1 == StoragePaths.PRIMARY_PATH || p1 == StoragePaths.getPrimaryRoot().absolutePath
                val isPri2 = p2 == StoragePaths.PRIMARY_PATH || p2 == StoragePaths.getPrimaryRoot().absolutePath
                val isSec1 = p1 == StoragePaths.SECONDARY_PATH
                val isSec2 = p2 == StoragePaths.SECONDARY_PATH
                when {
                    isPri1 && !isPri2 -> -1
                    !isPri1 && isPri2 -> 1
                    isSec1 && !isSec2 -> -1
                    !isSec1 && isSec2 -> 1
                    else -> f1.name.compareTo(f2.name, ignoreCase = true)
                }
            } else {
                f1.name.compareTo(f2.name, ignoreCase = true)
            }
        }
        Collections.sort(folders, fileSorter)
        Collections.sort(audioFiles, fileSorter)
        Collections.sort(apkFiles, fileSorter)
        Collections.sort(imageFiles, fileSorter)

        if (!isPickingBackground && !isTop && !StoragePaths.isAnyStorageRoot(currentFolder) && (audioFiles.isNotEmpty() || folders.isNotEmpty())) {
            val btnPlayAll = createListButtonWithIcon("\uE037", "▶ ${localize("Play All")}", null).apply { tag = "PLAY_ALL" }
            btnPlayAll.setOnClickListener {
                clickFeedback()
                val allAudio = ArrayList<File>()
                showLoadingPopup()
                Thread {
                    collectAudioFilesAsFile(currentFolder, allAudio)
                    Collections.sort(allAudio, fileSorter)
                    activity.runOnUiThread {
                        MainActivity.instance?.dismissLoadingOverlay()
                        if (allAudio.isEmpty()) {
                            Notice.show(activity, localize("No audio files found in subfolders."))
                        } else {
                            AudioPlayerManager.getInstance().playTrackList(allAudio, 0)
                            onNavigateToScreen(ScreenState.Player)
                        }
                    }
                }.start()
            }
            containerBrowserItems.addView(btnPlayAll)
        }

        for (folder in folders) {
            val label = folderRowText(folder, isTop)
            val b = createListButtonWithIcon("\uE2C7", label, null).apply { tag = "FOLDER" }
            b.setOnClickListener {
                clickFeedback()
                openList(BrowserMode.Folder(folder, isPickingBackground))
            }
            containerBrowserItems.addView(b)
        }

        if (isPickingBackground) {
            for (img in imageFiles) {
                val b = createListButtonWithIcon("\uE410", img.name, null).apply { tag = "IMAGE" }
                b.setOnClickListener {
                    clickFeedback()
                    try {
                        AppPrefs.get(activity).edit().putString("bg_path", img.absolutePath).commit()
                        MainActivity.instance?.updateMainMenuBackground()
                    } catch (e: Exception) {
                    }
                    Notice.show(activity, localize("Background Applied!"))
                    isPickingBackground = false
                    // Back to the Settings screen that opened the picker, over all its folder levels
                    NavigationManager.getInstance().popBackStackTo { it !is ScreenState.Browser }
                }
                containerBrowserItems.addView(b)
            }
            finishFolderBrowserUIRender()
        } else {
            for (apk in apkFiles) {
                val b = Button(activity).apply {
                    tag = "APK"
                    text = "📦 [${localize("INSTALL")}] ${apk.name}"
                    setTextColor(0xFF00FFFF.toInt())
                    background = createButtonBackground(ThemeManager.getListButtonNormalBg())
                    setOnClickListener {
                        clickFeedback()
                        installApk(apk)
                    }
                }
                containerBrowserItems.addView(b)
            }
            renderAudioFileRowsIncrementally(audioFiles, 0)
        }
    }

    private fun renderAudioFileRowsIncrementally(audioFiles: List<File>, startIndex: Int) {
        val end = Math.min(startIndex + FOLDER_ROW_CHUNK_SIZE, audioFiles.size)
        for (i in startIndex until end) {
            containerBrowserItems.addView(buildFolderAudioRow(audioFiles[i]))
        }
        if (end < audioFiles.size) {
            containerBrowserItems.post {
                renderAudioFileRowsIncrementally(audioFiles, end)
            }
        } else {
            finishFolderBrowserUIRender()
        }
    }

    private fun buildFolderAudioRow(audio: File): View {
        val iconCode = if (isAudiobookLibraryMode || currentBrowserMode is BrowserMode.Audiobooks) "\uE310" else "\uE405"
        val b = createListButtonWithIcon(iconCode, audio.name, null).apply { tag = "AUDIO" }

        if (isAudiobookLibraryMode || currentBrowserMode is BrowserMode.Audiobooks) {
            val prefs = AppPrefs.get(activity)
            val pos = prefs.getInt("book_pos_" + audio.absolutePath, 0)
            val dur = prefs.getInt("book_dur_" + audio.absolutePath, 0)
            if (pos > 0 && dur > 0) {
                setupAudiobookProgress(b, pos, dur)
            }
        }

        b.setOnClickListener {
            clickFeedback()
            if (currentBrowserMode is BrowserMode.Audiobooks || isAudiobookLibraryMode) {
                onAudiobookSelected(audio, currentFolder)
            } else {
                AudioPlayerManager.getInstance().setupFolderPlaylist(audio, currentFolder)
            }
            onNavigateToScreen(ScreenState.Player)
        }

        b.setOnLongClickListener {
            clickFeedback()
            showAddToPlaylistDialog(audio)
            true
        }
        return b
    }

    /** The text of the row of [folder] in a folder list; [isTop]: the list of the storage volumes. */
    private fun folderRowText(folder: File, isTop: Boolean): String = when {
        !isTop -> folder.name
        folder.name == "sdcard0" || folder.absolutePath == StoragePaths.PRIMARY_PATH || folder.absolutePath == StoragePaths.getPrimaryRoot().absolutePath ->
            localize("Internal Storage")
        folder.name == "sdcard1" || folder.absolutePath == StoragePaths.SECONDARY_PATH -> localize("SD Card")
        folder.name.equals("usbotg", ignoreCase = true) -> localize("USB Drive")
        else -> folder.name
    }

    /**
     * Opens [parent] with the row of this folder selected. With [addToBackStack], Back comes back to this list,
     * on its ".." row; else [parent] takes the place of this list.
     */
    private fun openParentFolder(parent: File, addToBackStack: Boolean) {
        val row = RowKey(folderRowText(currentFolder, StoragePaths.isTopStorageRoot(parent)), 0)
        val state = ScreenState.Browser(BrowserMode.Folder(parent, isPickingBackground), isAudiobookLibraryMode)
        NavigationManager.getInstance().navigateTo(state, addToBackStack, row)
    }

    private fun findDefaultFolderFocusView(): View? {
        val count = containerBrowserItems.childCount
        if (count == 0) return null
        for (i in 0 until count) {
            val child = containerBrowserItems.getChildAt(i)
            if (child.tag == "FOLDER") return child
        }
        for (i in 0 until count) {
            val child = containerBrowserItems.getChildAt(i)
            val tag = child.tag
            if (tag == "AUDIO" || tag == "APK" || tag == "IMAGE") return child
        }
        for (i in 0 until count) {
            val child = containerBrowserItems.getChildAt(i)
            if (child.isFocusable && child.visibility == View.VISIBLE) return child
        }
        return containerBrowserItems.getChildAt(0)
    }

    private fun finishFolderBrowserUIRender() {
        val sv = scrollViewBrowser ?: return
        findDefaultFolderFocusView()?.requestFocus()
        sv.scrollTo(0, 0)

        sv.viewTreeObserver.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                if (Build.VERSION.SDK_INT >= 16) {
                    sv.viewTreeObserver.removeOnGlobalLayoutListener(this)
                } else {
                    @Suppress("DEPRECATION")
                    sv.viewTreeObserver.removeGlobalOnLayoutListener(this)
                }

                val curPlaylist = MainActivity.instance?.currentPlaylist
                val curIndex = MainActivity.instance?.currentIndex ?: -1
                val playingFileName = if (curPlaylist != null && curPlaylist.isNotEmpty() && curIndex in curPlaylist.indices) {
                    curPlaylist[curIndex].name
                } else ""

                var matched = false
                if (playingFileName.isNotEmpty()) {
                    for (i in 0 until containerBrowserItems.childCount) {
                        val v = containerBrowserItems.getChildAt(i)
                        var itemText = ""
                        if (v is Button) {
                            itemText = v.text.toString()
                        } else if (v is LinearLayout && v.childCount > 1 && v.getChildAt(1) is TextView) {
                            itemText = (v.getChildAt(1) as TextView).text.toString()
                        }

                        var cleanItemText = itemText
                        if (cleanItemText.contains("  ⏱")) {
                            cleanItemText = cleanItemText.substring(0, cleanItemText.indexOf("  ⏱")).trim()
                        }

                        if (cleanItemText == playingFileName) {
                            v.requestFocus()
                            sv.scrollTo(0, v.top)
                            matched = true
                            break
                        }
                    }
                }
                if (!matched) {
                    val v = findDefaultFolderFocusView()
                    if (v != null) {
                        v.requestFocus()
                        sv.scrollTo(0, 0)
                        // Only while this row is selected: Back selects its own row after this layout (ListRows)
                        sv.post { if (v.hasFocus()) sv.scrollTo(0, 0) }
                    }
                }
            }
        })
    }

    fun navigateVirtualListTo(targetPos: Int) {
        val list = listVirtualSongs ?: return
        if (list.count == 0) return
        val pos = Math.max(0, Math.min(targetPos, list.count - 1))
        virtualListSelectedIndex = pos
        if (com.themoon.y1.views.ListEdges.focusRow(list, pos)) return

        val first = list.firstVisiblePosition
        val last = list.lastVisiblePosition

        if (pos in first..last) {
            val child = list.getChildAt(pos - first)
            if (child != null) {
                child.requestFocus()
                return
            }
        }

        if (pos <= first) {
            list.setSelectionFromTop(pos, 0)
        } else {
            list.setSelection(pos)
        }

        list.post {
            val curFirst = list.firstVisiblePosition
            val visibleIdx = pos - curFirst
            if (visibleIdx in 0 until list.childCount) {
                list.getChildAt(visibleIdx)?.requestFocus()
            }
        }
    }

    private fun buildAlphabetIndexBar() {
        alphabetIndexBarHelper?.build(currentScrollIndexList)
    }

    fun isCoverPaneActive(): Boolean = frameBrowserCover?.visibility == View.VISIBLE

    fun getCurrentBrowserTitle(): String {
        val pathText = tvBrowserPath.text?.toString()
        if (!pathText.isNullOrBlank()) {
            return pathText
        }
        return if (isAudiobookLibraryMode) localize("Audiobooks") else localize("Music")
    }

    private fun updateHeaderTitle(title: String) {
        tvBrowserPath.text = title
        activity.findViewById<TextView>(R.id.tv_status_clock)?.text = title
    }

    private fun showBrowserCoverPane() {
        val frame = frameBrowserCover ?: return
        frame.visibility = View.VISIBLE
        val density = activity.resources.displayMetrics.density
        val leftPane = scrollViewBrowser?.parent as? View
        leftPane?.setPadding(0, (ClassicMenuStyle.STATUS_HEIGHT_DP * density).toInt(), 0, 0)
        if (ThemeManager.isClassicTheme() && scrollViewBrowser != null) {
            val left = leftPane?.layoutParams as? LinearLayout.LayoutParams
            if (left != null) {
                left.width = (ClassicMenuStyle.PANE_WIDTH_DP * density).toInt()
                left.weight = 0f
                leftPane.layoutParams = left
            }
        }

        val statusBar = activity.findViewById<View>(R.id.layout_status_bar)
        if (statusBar != null) {
            val slp = statusBar.layoutParams
            slp.width = (ClassicMenuStyle.PANE_WIDTH_DP * density).toInt()
            statusBar.layoutParams = slp
            statusBar.background = ThemeManager.createStatusBarBackground()
        }


        if (!browserCoverPaneSetUp && ivBrowserCover != null) {
            browserCoverPaneSetUp = true
            com.themoon.y1.views.CoverPane.attach(ivBrowserCover)
        }
        onRefreshCoverPane?.invoke()
    }

    private fun hideBrowserCoverPane() {
        val density = activity.resources.displayMetrics.density
        val leftPane = scrollViewBrowser?.parent as? View
        leftPane?.setPadding(0, (ClassicMenuStyle.STATUS_HEIGHT_DP * density).toInt(), 0, 0)
        if (ThemeManager.isClassicTheme() && scrollViewBrowser != null) {
            val left = leftPane?.layoutParams as? LinearLayout.LayoutParams
            if (left != null) {
                left.width = 0
                left.weight = 1f
                leftPane.layoutParams = left
            }
        }
        frameBrowserCover?.visibility = View.GONE
        val statusBar = activity.findViewById<View>(R.id.layout_status_bar)
        if (statusBar != null) {
            val slp = statusBar.layoutParams
            slp.width = ViewGroup.LayoutParams.MATCH_PARENT
            statusBar.layoutParams = slp
            statusBar.background = ThemeManager.createStatusBarBackground()
        }
    }

    private fun setupAudiobookProgress(btn: View, pos: Int, dur: Int) {
        applyProgressBackground(btn, pos, dur, btn.hasFocus())
        btn.onFocusChangeListener = View.OnFocusChangeListener { v, hasFocus ->
            if (hasFocus) {
                if (v is Button) {
                    v.setTextColor(ThemeManager.getListButtonFocusedTextColor())
                } else if (v is LinearLayout && v.childCount > 1) {
                    (v.getChildAt(0) as? TextView)?.setTextColor(ThemeManager.getListButtonFocusedTextColor())
                    (v.getChildAt(1) as? TextView)?.setTextColor(ThemeManager.getListButtonFocusedTextColor())
                }
                applyProgressBackground(btn, pos, dur, true)
            } else {
                if (v is Button) {
                    v.setTextColor(ThemeManager.getTextColorPrimary())
                } else if (v is LinearLayout && v.childCount > 1) {
                    (v.getChildAt(0) as? TextView)?.setTextColor(ThemeManager.getTextColorPrimary())
                    (v.getChildAt(1) as? TextView)?.setTextColor(ThemeManager.getTextColorPrimary())
                }
                applyProgressBackground(btn, pos, dur, false)
            }
        }
    }

    private fun applyProgressBackground(btn: View, currentMs: Int, totalMs: Int, isFocused: Boolean) {
        if (currentMs <= 0 || totalMs <= 0) return
        val progressPercent = Math.min(10000, ((currentMs.toFloat() / totalMs) * 10000).toInt())
        val baseColor = if (isFocused) ThemeManager.getListButtonFocusedBg() else ThemeManager.getListButtonNormalBg()
        val baseBg = createButtonBackground(baseColor)

        val progressColor = if (isFocused) 0x66FFFFFF else (ThemeManager.getListButtonFocusedBg() and 0x00FFFFFF) or 0x44000000
        val progressBg = createButtonBackground(progressColor)
        val clipProgress = ClipDrawable(progressBg, Gravity.LEFT, ClipDrawable.HORIZONTAL).apply {
            level = progressPercent
        }
        val layerBg = LayerDrawable(arrayOf(baseBg, clipProgress))

        val pLeft = btn.paddingLeft
        val pTop = btn.paddingTop
        val pRight = btn.paddingRight
        val pBottom = btn.paddingBottom
        btn.background = layerBg
        btn.setPadding(pLeft, pTop, pRight, pBottom)
    }

    private fun collectAudioFilesAsFile(dir: File, list: MutableList<File>) {
        val files = dir.listFiles() ?: return
        for (f in files) {
            if (f.isDirectory) {
                collectAudioFilesAsFile(f, list)
            } else if (isAudioFile(f)) {
                list.add(f)
            }
        }
    }

    private fun installApk(apkFile: File) {
        try {
            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                setDataAndType(android.net.Uri.fromFile(apkFile), "application/vnd.android.package-archive")
                flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
            }
            activity.startActivity(intent)
        } catch (e: Exception) {
            Notice.error(activity, "Install failed: ${e.message}")
        }
    }

    /** The action of a Music menu item (MusicMenuItems.ITEMS). */
    private fun openMusicMenuItem(id: String) {
        when (id) {
            "coverflow" -> onCoverFlowRequested()
            "playlists" -> onPlaylistsRequested()
            "track_artists" -> openList(BrowserMode.TrackArtists)
            "artists" -> openList(BrowserMode.Artists)
            "albums" -> openList(BrowserMode.Albums())
            "compilations" -> openList(BrowserMode.Compilations)
            "songs" -> openList(BrowserMode.Songs("ALL"))
            "shuffle_songs" -> onShuffleSongsRequested()
            "genres" -> openList(BrowserMode.Genres)
            "composers" -> openList(BrowserMode.Composers)
            "years" -> openList(BrowserMode.Years)
            "recently_added" -> openList(BrowserMode.RecentlyAdded)
            "favorites" -> openList(BrowserMode.Favorites)
            "folders" -> openList(BrowserMode.Folder(StoragePaths.getDefaultMusicDir()))
            "search" -> onSearchRequested()
        }
    }

    private fun isMusicMenuItemHidden(id: String): Boolean {
        // Settings > Music Menu Items writes these keys to AppPrefs.
        return MusicMenuItems.isHidden(activity.getSharedPreferences("AppPrefs", Activity.MODE_PRIVATE), id)
    }

    /** True when Compilations is in the Music menu: then Artists does not show the compilation tracks (as on the iPod). */
    fun artistsHideCompilations(): Boolean = !isAudiobookLibraryMode && !isMusicMenuItemHidden("compilations")

    private fun isAudioFile(f: File?): Boolean {
        if (f == null || !f.isFile) return false
        val name = f.name.lowercase()
        return name.endsWith(".mp3") || name.endsWith(".flac") || name.endsWith(".wav") || name.endsWith(".ogg") ||
            name.endsWith(".m4a") || name.endsWith(".aac") || name.endsWith(".ape") || name.endsWith(".wma") ||
            name.endsWith(".opus") || name.endsWith(".m4b")
    }

    private fun isApkFile(f: File?): Boolean = f != null && f.isFile && f.name.lowercase().endsWith(".apk")
    private fun isImageFile(f: File?): Boolean {
        if (f == null || !f.isFile) return false
        val name = f.name.lowercase()
        return name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".png")
    }

    private fun createButtonBackground(color: Int): GradientDrawable {
        return GradientDrawable().apply {
            setColor(color)
            val radius = ThemeManager.getButtonRadius() * activity.resources.displayMetrics.density
            cornerRadius = radius
        }
    }

    /** Back: the list of the top back-stack entry (NavigationManager builds it again and selects its row). */
    override fun onBackPressed(): Boolean {
        onNavigateBackToMenu()
        return true
    }

    /**
     * Speed scrolling ([LetterJump.Mode]). In letter mode, focuses with [focusRow] the first row of the next or
     * previous letter, shows the letter box and returns true. Else returns false, and the wheel steps one row.
     * [from] is the focused row, [listKey] changes when the rows change, [rowTexts] gives the text of each row.
     */
    private fun letterJump(delta: Int, event: KeyEvent, from: Int, listKey: Any, rowTexts: () -> List<String?>, focusRow: (Int) -> Unit): Boolean {
        if (letterMode.isIdle(event.eventTime)) endLetterJump() // the idle timer did not run yet
        val step = letterMode.onStep(delta, event.eventTime, from) {
            if (listKey != rowLettersKey) {
                rowLetters = LetterJump.lettersFor(rowTexts())
                rowLettersKey = listKey
            }
            rowLetters
        }
        if (step == LetterJump.Step.ROW) return false
        jumpLetter = rowLetters?.getOrNull(letterMode.row) // before the focus moves: the focused row shows the letter box
        if (step == LetterJump.Step.LETTER) {
            focusRow(letterMode.row)
            clickFeedback()
        }
        jumpLetter?.let { MainActivity.instance?.showJumpLetter(it) }
        uiHandler.removeCallbacks(letterIdleTask)
        uiHandler.postDelayed(letterIdleTask, LetterJump.IDLE_END_MS)
        return true
    }

    /**
     * Ends letter mode (idle wheel, a key, another screen). The list is already at the first row of the letter;
     * the letter box fades out.
     */
    fun endLetterJump() {
        uiHandler.removeCallbacks(letterIdleTask)
        letterMode.end()
        if (jumpLetter == null) return
        jumpLetter = null
        MainActivity.instance?.hideFastScrollLetter()
    }

    /** In letter mode, a centre press only ends it; Back and Play/Pause end it and then work as usual. */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean =
        when (letterMode.onKey(event.keyCode, event.action == KeyEvent.ACTION_DOWN, event.repeatCount)) {
            LetterJump.Press.PASS -> false
            LetterJump.Press.DROP -> true
            LetterJump.Press.END -> { endLetterJump(); true }
            LetterJump.Press.END_AND_PASS -> { endLetterJump(); false }
        }

    /** Back held in Folders (the first key repeat): out of all folder levels at once. The release does nothing. */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val router = InputRouter.getInstance()
        if (event.repeatCount == 0 || !router.isBackKey(keyCode) || currentBrowserMode !is BrowserMode.Folder) return false
        router.suppressBackUntilRelease()
        clickFeedback()
        NavigationManager.getInstance().popBackStackTo { !it.isFolderLevel }
        return true
    }

    /** The text of a ListView row: a category name or a song title; null for "All Songs" and other special rows. */
    private fun listRowText(item: Any?): String? = when (item) {
        is SongItem -> item.title
        is String -> item.takeUnless { it.startsWith("__") }
        else -> null
    }

    /** The text of a row of the browser LinearLayout: its first text with a letter or a digit (not the icon). */
    private fun containerRowText(row: View?): String? {
        if (row == null || row.visibility != View.VISIBLE || !row.isFocusable) return null
        if (row is TextView) return row.text?.toString()
        val group = row as? ViewGroup ?: return null
        for (i in 0 until group.childCount) {
            val text = (group.getChildAt(i) as? TextView)?.text?.toString() ?: continue
            if (text.any { it.isLetterOrDigit() }) return text
        }
        return null
    }

    override fun onWheel(delta: Int, event: KeyEvent): Boolean {
        val list = listVirtualSongs
        if (list != null && list.visibility == View.VISIBLE) {
            val totalCount = list.count
            if (totalCount <= 0) return true
            val isLoopScrollOn = activity.getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
                .getBoolean("loop_scroll_on", true)

            val curFocused = list.findFocus()
            if (curFocused != null) {
                val p = list.getPositionForView(curFocused)
                if (p in 0 until totalCount) {
                    virtualListSelectedIndex = p
                }
            }
            if (virtualListSelectedIndex < 0) virtualListSelectedIndex = 0
            if (virtualListSelectedIndex >= totalCount) virtualListSelectedIndex = totalCount - 1
            val adapter = list.adapter
            if (adapter != null && letterJump(delta, event, virtualListSelectedIndex, adapter,
                    { (0 until totalCount).map { listRowText(adapter.getItem(it)) } }, { navigateVirtualListTo(it) })) {
                return true
            }

            var targetPos = virtualListSelectedIndex
            if (delta < 0) {
                if (virtualListSelectedIndex > 0) {
                    targetPos = virtualListSelectedIndex - 1
                } else if (isLoopScrollOn) {
                    targetPos = totalCount - 1
                }
            } else if (delta > 0) {
                if (virtualListSelectedIndex < totalCount - 1) {
                    targetPos = virtualListSelectedIndex + 1
                } else if (isLoopScrollOn) {
                    targetPos = 0
                }
            }
            if (targetPos != virtualListSelectedIndex) {
                navigateVirtualListTo(targetPos)
            }
            clickFeedback()
            return true
        }

        if (containerBrowserItems.childCount > 0) {
            val curFocus = containerBrowserItems.findFocus()
            val currentIndex = if (curFocus != null) containerBrowserItems.indexOfChild(curFocus) else -1
            val rowCount = containerBrowserItems.childCount
            if (currentIndex >= 0 && letterJump(delta, event, currentIndex, listOf(containerBrowserItems.getChildAt(0), rowCount),
                    { (0 until rowCount).map { containerRowText(containerBrowserItems.getChildAt(it)) } },
                    { containerBrowserItems.getChildAt(it)?.requestFocus() })) {
                return true
            }
            if (RowFocus.step(containerBrowserItems, delta) != null) clickFeedback()
            return true
        }

        return false
    }
}
