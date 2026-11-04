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
import android.widget.Toast
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
import com.themoon.y1.navigation.LetterJump
import com.themoon.y1.navigation.ScreenState
import com.themoon.y1.views.AlphabetIndexBar
import com.themoon.y1.views.ClassicMenuStyle
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
    var lastBrowserFocusText: String = ""
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

    override fun onEnter(state: ScreenState) {
        if (state is ScreenState.Browser) {
            currentBrowserMode = state.mode
            when (val mode = state.mode) {
                is BrowserMode.Root -> {
                    isAudiobookLibraryMode = false
                    buildFileBrowserUI()
                }
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
                is BrowserMode.VirtualSongs -> buildVirtualSongs()
                is BrowserMode.Favorites -> buildVirtualSongsForFavorites()
                is BrowserMode.RecentlyAdded -> {
                    virtualQueryType = "RECENT"
                    buildVirtualSongs()
                }
                is BrowserMode.Audiobooks -> {
                    isAudiobookLibraryMode = true
                    buildFileBrowserUI()
                }
                is BrowserMode.CoverFlow -> onCoverFlowRequested()
                else -> buildFileBrowserUI()
            }
        } else {
            buildFileBrowserUI()
        }
    }

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
        // Not onEnter: it clears the filters of the category lists. The list position is kept (ListPosition).
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
                    lastBrowserFocusText = label
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
                    Toast.makeText(activity, localize("No audiobooks found"), Toast.LENGTH_SHORT).show()
                }
            }
            containerBrowserItems.addView(btnContinue)

            val btnBook = createListButtonWithIcon("\uE86D", localize("Books"), null)
            btnBook.setOnClickListener {
                clickFeedback()
                lastBrowserFocusText = localize("Books")
                currentBrowserMode = BrowserMode.Albums()
                virtualQueryValue = ""
                categoryArtistFilter = ""
                buildVirtualCategories("ALBUM")
            }
            containerBrowserItems.addView(btnBook)

            val btnAuthor = createListButtonWithIcon("\uE7FD", localize("Authors"), null)
            btnAuthor.setOnClickListener {
                clickFeedback()
                lastBrowserFocusText = localize("Authors")
                currentBrowserMode = BrowserMode.Artists
                virtualQueryValue = ""
                categoryArtistFilter = ""
                buildVirtualCategories("ARTIST")
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
                if (song.genre.isEmpty() || !song.genre.equals(categoryGenreFilter, ignoreCase = true)) continue
            } else if (type == "GENRE_ALBUM") {
                if (song.genre.isEmpty() || !song.genre.equals(categoryGenreFilter, ignoreCase = true)) continue
                if (categoryArtistFilter.isNotEmpty()) {
                    val matchField = if (categoryArtistFilterIsTrackArtist) song.artist else song.albumArtist
                    if (!categoryArtistFilter.equals(matchField, ignoreCase = true)) continue
                }
            }

            if (type == "ALBUM" || type == "GENRE_ALBUM") {
                albumSongs.add(song)
                continue
            }
            val v = when (type) {
                "ARTIST" -> song.albumArtist
                "TRACK_ARTIST", "GENRE_ARTIST" -> song.artist
                "YEAR" -> song.year
                "GENRE" -> song.genre
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

        val albumIndex = categories.indices.firstOrNull {
            categories[it] == virtualQueryValue && albumArtists?.get(it).equals(virtualQueryAlbumArtist, ignoreCase = true)
        }
        val targetIndex = albumIndex ?: categories.indexOf(virtualQueryValue)
        virtualListSelectedIndex = if (targetIndex >= 0) targetIndex else 0
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
                    virtualQueryType == "GENRE" && song.genre.equals(virtualQueryValue, ignoreCase = true) -> isMatch = true
                    virtualQueryType == "GENRE_ARTIST_SONGS" -> {
                        val matchField = if (categoryArtistFilterIsTrackArtist) song.artist else song.albumArtist
                        isMatch = song.genre.equals(categoryGenreFilter, ignoreCase = true) &&
                            (categoryArtistFilter.isEmpty() || categoryArtistFilter.equals(matchField, ignoreCase = true))
                    }
                    virtualQueryType == "GENRE_ALBUM_SONGS" -> {
                        val matchField = if (categoryArtistFilterIsTrackArtist) song.artist else song.albumArtist
                        isMatch = AlbumKey.matches(song, virtualQueryValue, virtualQueryAlbumArtist) &&
                            song.genre.equals(categoryGenreFilter, ignoreCase = true) &&
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
            Toast.makeText(activity, localize("No favorites added yet."), Toast.LENGTH_SHORT).show()
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
        val folderTitle = "${localize("Path")}: ${StoragePaths.toDisplayPath(currentFolder.absolutePath)}"
        updateHeaderTitle(folderTitle)
        val files = currentFolder.listFiles()

        if (files == null || files.isEmpty()) {
            val emptyMsg = if (files == null) {
                "⚠️ ${localize("USB Disconnect Required (Tap to go back)")}"
            } else {
                "📂 ${localize("Empty Folder (Tap to go back)")}"
            }
            val btnEmpty = Button(activity).apply {
                text = emptyMsg
                setTextColor(0xFFFF5555.toInt())
                background = createButtonBackground(ThemeManager.getListButtonNormalBg())
                setOnClickListener {
                    clickFeedback()
                    if (currentFolder.absolutePath == StoragePaths.getPrimaryRoot().absolutePath ||
                        StoragePaths.isAnyStorageRoot(currentFolder.absolutePath) ||
                        StoragePaths.isVideosRoot(currentFolder.absolutePath) ||
                        StoragePaths.isAudiobooksRoot(currentFolder.absolutePath)
                    ) {
                        if (isPickingBackground) {
                            isPickingBackground = false
                            onNavigateToScreen(ScreenState.Settings())
                        } else {
                            currentBrowserMode = BrowserMode.Root
                            buildFileBrowserUI()
                        }
                    } else {
                        currentFolder = currentFolder.parentFile ?: StoragePaths.getPrimaryRoot()
                        buildFileBrowserUI()
                    }
                }
            }
            containerBrowserItems.addView(btnEmpty)
            return
        }

        val folders = ArrayList<File>()
        val audioFiles = ArrayList<File>()
        val apkFiles = ArrayList<File>()
        val imageFiles = ArrayList<File>()

        for (f in files) {
            when {
                f.isDirectory -> folders.add(f)
                isPickingBackground && isImageFile(f) -> imageFiles.add(f)
                !isPickingBackground && isAudioFile(f) -> audioFiles.add(f)
                !isPickingBackground && isApkFile(f) -> apkFiles.add(f)
            }
        }

        val fileSorter = Comparator<File> { f1, f2 -> f1.name.compareTo(f2.name, ignoreCase = true) }
        Collections.sort(folders, fileSorter)
        Collections.sort(audioFiles, fileSorter)
        Collections.sort(apkFiles, fileSorter)
        Collections.sort(imageFiles, fileSorter)

        if (!isPickingBackground && (audioFiles.isNotEmpty() || folders.isNotEmpty())) {
            val btnPlayAll = Button(activity).apply {
                text = "▶ ${localize("Play All")}"
                setTextColor(0xFFFFFFFF.toInt())
                typeface = ThemeManager.getCustomFontBold()
                background = createButtonBackground(ThemeManager.getListButtonNormalBg())
                setOnClickListener {
                    clickFeedback()
                    val allAudio = ArrayList<File>()
                    showLoadingPopup()
                    Thread {
                        collectAudioFilesAsFile(currentFolder, allAudio)
                        Collections.sort(allAudio, fileSorter)
                        activity.runOnUiThread {
                            MainActivity.instance?.dismissLoadingOverlay()
                            if (allAudio.isEmpty()) {
                                Toast.makeText(activity, localize("No audio files found in subfolders."), Toast.LENGTH_SHORT).show()
                            } else {
                                AudioPlayerManager.getInstance().playTrackList(allAudio, 0)
                                onNavigateToScreen(ScreenState.Player)
                            }
                        }
                    }.start()
                }
            }
            containerBrowserItems.addView(btnPlayAll)
        }

        for (folder in folders) {
            val b = createListButtonWithIcon("\uE2C7", folder.name, null)
            b.setOnClickListener {
                clickFeedback()
                currentFolder = folder
                buildFileBrowserUI()
            }
            containerBrowserItems.addView(b)
        }

        if (isPickingBackground) {
            for (img in imageFiles) {
                val b = createListButtonWithIcon("\uE410", img.name, null)
                b.setOnClickListener {
                    clickFeedback()
                    try {
                        AppPrefs.get(activity).edit().putString("bg_path", img.absolutePath).commit()
                        MainActivity.instance?.updateMainMenuBackground()
                    } catch (e: Exception) {
                    }
                    Toast.makeText(activity, localize("Background Applied!"), Toast.LENGTH_SHORT).show()
                    isPickingBackground = false
                    onNavigateToScreen(ScreenState.Settings())
                }
                containerBrowserItems.addView(b)
            }
            finishFolderBrowserUIRender()
        } else {
            for (apk in apkFiles) {
                val b = Button(activity).apply {
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
        val b = createListButtonWithIcon(iconCode, audio.name, null)

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

    private fun finishFolderBrowserUIRender() {
        if (containerBrowserItems.childCount > 0) {
            containerBrowserItems.getChildAt(0).requestFocus()
        }

        val sv = scrollViewBrowser ?: return
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

                if (lastBrowserFocusText.isNotEmpty() || playingFileName.isNotEmpty()) {
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

                        if (lastBrowserFocusText.isNotEmpty() && cleanItemText == lastBrowserFocusText) {
                            v.requestFocus()
                            sv.scrollTo(0, v.top)
                            break
                        } else if (lastBrowserFocusText.isEmpty() && playingFileName.isNotEmpty() && cleanItemText == playingFileName) {
                            v.requestFocus()
                            sv.scrollTo(0, v.top)
                            break
                        }
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
            Toast.makeText(activity, "Install failed: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    /** The action of a Music menu item (MusicMenuItems.ITEMS). */
    private fun openMusicMenuItem(id: String) {
        when (id) {
            "coverflow" -> onCoverFlowRequested()
            "playlists" -> onPlaylistsRequested()
            "track_artists" -> openMusicCategory(BrowserMode.TrackArtists, "TRACK_ARTIST")
            "artists" -> openMusicCategory(BrowserMode.Artists, "ARTIST")
            "albums" -> openMusicCategory(BrowserMode.Albums(), "ALBUM")
            "compilations" -> openMusicCategory(BrowserMode.Compilations, "ALBUM")
            "songs" -> {
                currentBrowserMode = BrowserMode.VirtualSongs
                virtualQueryType = "ALL"
                buildVirtualSongs()
            }
            "shuffle_songs" -> onShuffleSongsRequested()
            "genres" -> {
                categoryGenreFilter = ""
                openMusicCategory(BrowserMode.Genres, "GENRE")
            }
            "composers" -> {
                currentBrowserMode = BrowserMode.Composers
                virtualQueryValue = ""
                buildVirtualCategories("COMPOSER")
            }
            "years" -> {
                currentBrowserMode = BrowserMode.Years
                virtualQueryValue = ""
                buildVirtualCategories("YEAR")
            }
            "recently_added" -> {
                currentBrowserMode = BrowserMode.RecentlyAdded
                virtualQueryType = "RECENT"
                buildVirtualSongs()
            }
            "favorites" -> {
                currentBrowserMode = BrowserMode.Favorites
                buildVirtualSongsForFavorites()
            }
            "folders" -> {
                currentBrowserMode = BrowserMode.Folder(StoragePaths.getPrimaryRoot())
                currentFolder = StoragePaths.getPrimaryRoot()
                buildFileBrowserUI()
            }
            "search" -> onSearchRequested()
        }
    }

    /** A category list of the Music menu, with no artist filter from an earlier list. */
    private fun openMusicCategory(mode: BrowserMode, type: String) {
        currentBrowserMode = mode
        virtualQueryValue = ""
        categoryArtistFilter = ""
        categoryArtistFilterIsTrackArtist = false
        buildVirtualCategories(type)
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

    override fun onBackPressed(): Boolean {
        val mode = currentBrowserMode
        when (mode) {
            is BrowserMode.Root, is BrowserMode.Audiobooks -> {
                isAudiobookLibraryMode = false
                onNavigateBackToMenu()
                return true
            }
            is BrowserMode.Folder -> {
                val isAtFolderRoot = if (isAudiobookLibraryMode) {
                    currentFolder.absolutePath == StoragePaths.getAudiobooksDir().absolutePath
                } else {
                    currentFolder.absolutePath == StoragePaths.getPrimaryRoot().absolutePath
                }
                if (isAtFolderRoot || StoragePaths.isAnyStorageRoot(currentFolder.absolutePath)) {
                    currentBrowserMode = if (isAudiobookLibraryMode) BrowserMode.Audiobooks else BrowserMode.Root
                    lastBrowserFocusText = localize("Folders")
                    buildFileBrowserUI()
                } else {
                    val exitedName = currentFolder.name
                    val parent = currentFolder.parentFile
                    if (parent == null) {
                        isAudiobookLibraryMode = false
                        onNavigateBackToMenu()
                    } else {
                        currentFolder = parent
                        lastBrowserFocusText = exitedName
                        buildFileBrowserUI()
                    }
                }
                return true
            }
            is BrowserMode.VirtualSongs -> {
                when (virtualQueryType) {
                    "COVER_FLOW_ALBUM" -> onNavigateBackToMenu()
                    "GENRE_ALBUM_SONGS" -> {
                        currentBrowserMode = BrowserMode.GenreAlbums(categoryGenreFilter, categoryArtistFilter)
                        buildVirtualCategories("GENRE_ALBUM")
                    }
                    "GENRE_ARTIST_SONGS" -> {
                        currentBrowserMode = BrowserMode.GenreAlbums(categoryGenreFilter, categoryArtistFilter)
                        virtualQueryValue = ALL_SONGS_SENTINEL
                        buildVirtualCategories("GENRE_ALBUM")
                    }
                    "GENRE" -> {
                        currentBrowserMode = BrowserMode.GenreArtists(categoryGenreFilter)
                        virtualQueryValue = ALL_SONGS_SENTINEL
                        buildVirtualCategories("GENRE_ARTIST")
                    }
                    "ALL" -> {
                        currentBrowserMode = if (isAudiobookLibraryMode) BrowserMode.Audiobooks else BrowserMode.Root
                        lastBrowserFocusText = if (isAudiobookLibraryMode) localize("All Audiobooks") else localize("All Songs")
                        buildFileBrowserUI()
                    }
                    "RECENT" -> {
                        currentBrowserMode = BrowserMode.Root
                        lastBrowserFocusText = localize("Recently Added")
                        buildFileBrowserUI()
                    }
                    "ARTIST_ALL_ALBUMS" -> {
                        val exitedArtist = categoryArtistFilter
                        currentBrowserMode = BrowserMode.Albums(exitedArtist, categoryArtistFilterIsTrackArtist)
                        virtualQueryValue = ALL_SONGS_SENTINEL
                        buildVirtualCategories("ALBUM")
                    }
                    "ALBUM" -> {
                        val exitedAlbum = virtualQueryValue
                        currentBrowserMode = BrowserMode.Albums(categoryArtistFilter, categoryArtistFilterIsTrackArtist)
                        virtualQueryValue = exitedAlbum
                        buildVirtualCategories("ALBUM")
                    }
                    "COMPILATION_ALBUM" -> {
                        val exitedAlbum = virtualQueryValue
                        currentBrowserMode = BrowserMode.Compilations
                        virtualQueryValue = exitedAlbum
                        buildVirtualCategories("ALBUM")
                    }
                    "ARTIST" -> {
                        val exitedArtist = virtualQueryValue
                        currentBrowserMode = if (categoryArtistFilterIsTrackArtist) BrowserMode.TrackArtists else BrowserMode.Artists
                        virtualQueryValue = exitedArtist
                        buildVirtualCategories(if (categoryArtistFilterIsTrackArtist) "TRACK_ARTIST" else "ARTIST")
                    }
                    "YEAR" -> {
                        val exitedYear = virtualQueryValue
                        currentBrowserMode = BrowserMode.Years
                        virtualQueryValue = exitedYear
                        buildVirtualCategories("YEAR")
                    }
                    "COMPOSER" -> {
                        val exitedComposer = virtualQueryValue
                        currentBrowserMode = BrowserMode.Composers
                        virtualQueryValue = exitedComposer
                        buildVirtualCategories("COMPOSER")
                    }
                    else -> {
                        currentBrowserMode = if (isAudiobookLibraryMode) BrowserMode.Audiobooks else BrowserMode.Root
                        buildFileBrowserUI()
                    }
                }
                return true
            }
            is BrowserMode.Artists -> {
                currentBrowserMode = if (isAudiobookLibraryMode) BrowserMode.Audiobooks else BrowserMode.Root
                lastBrowserFocusText = if (isAudiobookLibraryMode) localize("Authors") else localize("Album Artists")
                buildFileBrowserUI()
                return true
            }
            is BrowserMode.TrackArtists -> {
                currentBrowserMode = BrowserMode.Root
                lastBrowserFocusText = localize("Artists")
                buildFileBrowserUI()
                return true
            }
            is BrowserMode.Favorites -> {
                currentBrowserMode = BrowserMode.Root
                lastBrowserFocusText = localize("My Favorites")
                buildFileBrowserUI()
                return true
            }
            is BrowserMode.Albums -> {
                if (categoryArtistFilter.isNotEmpty()) {
                    val exitedArtist = categoryArtistFilter
                    val wasTrack = categoryArtistFilterIsTrackArtist
                    categoryArtistFilter = ""
                    currentBrowserMode = if (wasTrack) BrowserMode.TrackArtists else BrowserMode.Artists
                    virtualQueryValue = exitedArtist
                    buildVirtualCategories(if (wasTrack) "TRACK_ARTIST" else "ARTIST")
                } else {
                    currentBrowserMode = if (isAudiobookLibraryMode) BrowserMode.Audiobooks else BrowserMode.Root
                    lastBrowserFocusText = if (isAudiobookLibraryMode) localize("Books") else localize("Albums")
                    buildFileBrowserUI()
                }
                return true
            }
            is BrowserMode.Compilations -> {
                currentBrowserMode = BrowserMode.Root
                lastBrowserFocusText = localize("Compilations")
                buildFileBrowserUI()
                return true
            }
            is BrowserMode.GenreAlbums -> {
                currentBrowserMode = BrowserMode.GenreArtists(categoryGenreFilter)
                val exitedArtist = categoryArtistFilter
                categoryArtistFilter = ""
                virtualQueryValue = exitedArtist
                buildVirtualCategories("GENRE_ARTIST")
                return true
            }
            is BrowserMode.GenreArtists -> {
                currentBrowserMode = BrowserMode.Genres
                val exitedGenre = categoryGenreFilter
                categoryGenreFilter = ""
                virtualQueryValue = exitedGenre
                buildVirtualCategories("GENRE")
                return true
            }
            is BrowserMode.Years -> {
                currentBrowserMode = BrowserMode.Root
                lastBrowserFocusText = localize("Years")
                buildFileBrowserUI()
                return true
            }
            is BrowserMode.Genres -> {
                currentBrowserMode = BrowserMode.Root
                categoryGenreFilter = ""
                categoryArtistFilter = ""
                lastBrowserFocusText = localize("Genres")
                buildFileBrowserUI()
                return true
            }
            is BrowserMode.Composers -> {
                currentBrowserMode = BrowserMode.Root
                lastBrowserFocusText = localize("Composers")
                buildFileBrowserUI()
                return true
            }
            is BrowserMode.RecentlyAdded -> {
                currentBrowserMode = BrowserMode.Root
                lastBrowserFocusText = localize("Recently Added")
                buildFileBrowserUI()
                return true
            }
            else -> {
                onNavigateBackToMenu()
                return true
            }
        }
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
