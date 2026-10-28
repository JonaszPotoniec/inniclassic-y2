package com.themoon.y1.controllers.browser

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
import com.themoon.y1.managers.RecentlyPlayedManager
import com.themoon.y1.models.SongItem
import com.themoon.y1.navigation.BrowserMode
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
    var categoryArtistFilter: String = ""
    var categoryArtistFilterIsTrackArtist: Boolean = false
    var categoryGenreFilter: String = ""
    var lastBrowserFocusText: String = ""
    var virtualListSelectedIndex: Int = 0

    private val currentScrollIndexList = ArrayList<String>()
    private var browserCoverPaneSetUp: Boolean = false
    private val uiHandler = Handler(Looper.getMainLooper())

    companion object {
        private const val FOLDER_ROW_CHUNK_SIZE = 25
        const val ALL_SONGS_SENTINEL = "__ALL_SONGS__"
        const val CLEAR_RECENTLY_PLAYED_SENTINEL = "__CLEAR_RECENTLY_PLAYED__"
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
                is BrowserMode.RecentlyPlayed -> {
                    isAudiobookLibraryMode = false
                    buildRecentlyPlayedAlbums()
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
        hzIndexScroll?.visibility = View.GONE
        isAudiobookLibraryMode = false
    }

    override fun onRefreshUI() {
        when (currentBrowserMode) {
            is BrowserMode.Root, is BrowserMode.Audiobooks -> buildFileBrowserUI()
            is BrowserMode.Folder -> buildFolderBrowserUI()
            is BrowserMode.VirtualSongs -> buildVirtualSongs()
            is BrowserMode.Favorites -> buildVirtualSongsForFavorites()
            is BrowserMode.RecentlyPlayed -> buildRecentlyPlayedAlbums()
            else -> {}
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

            if (!isMusicMenuItemHidden("coverflow")) {
                val btnCoverFlow = createListButtonWithIcon("\uE3B6", localize("Cover Flow"), null)
                btnCoverFlow.setOnClickListener {
                    clickFeedback()
                    lastBrowserFocusText = localize("Cover Flow")
                    onCoverFlowRequested()
                }
                containerBrowserItems.addView(btnCoverFlow)
            }

            if (!isMusicMenuItemHidden("recently_played")) {
                val btnRecentlyPlayed = createListButtonWithIcon("\uE8B6", localize("Recently Played"), null)
                btnRecentlyPlayed.setOnClickListener {
                    clickFeedback()
                    lastBrowserFocusText = localize("Recently Played")
                    currentBrowserMode = BrowserMode.RecentlyPlayed
                    buildRecentlyPlayedAlbums()
                }
                containerBrowserItems.addView(btnRecentlyPlayed)
            }

            if (!isMusicMenuItemHidden("playlists")) {
                val btnM3u = createListButtonWithIcon("\uE05F", localize("Playlists"), null)
                btnM3u.setOnClickListener {
                    clickFeedback()
                    lastBrowserFocusText = localize("Playlists")
                    onPlaylistsRequested()
                }
                containerBrowserItems.addView(btnM3u)
            }

            if (!isMusicMenuItemHidden("track_artists")) {
                val btnTrackArtist = createListButtonWithIcon("\uE7FD", localize("Artists"), null)
                btnTrackArtist.setOnClickListener {
                    clickFeedback()
                    lastBrowserFocusText = localize("Artists")
                    currentBrowserMode = BrowserMode.TrackArtists
                    virtualQueryValue = ""
                    categoryArtistFilter = ""
                    categoryArtistFilterIsTrackArtist = false
                    buildVirtualCategories("TRACK_ARTIST")
                }
                containerBrowserItems.addView(btnTrackArtist)
            }

            if (!isMusicMenuItemHidden("artists")) {
                val btnArtist = createListButtonWithIcon("\uE7FD", localize("Album Artists"), null)
                btnArtist.setOnClickListener {
                    clickFeedback()
                    lastBrowserFocusText = localize("Album Artists")
                    currentBrowserMode = BrowserMode.Artists
                    virtualQueryValue = ""
                    categoryArtistFilter = ""
                    categoryArtistFilterIsTrackArtist = false
                    buildVirtualCategories("ARTIST")
                }
                containerBrowserItems.addView(btnArtist)
            }

            if (!isMusicMenuItemHidden("albums")) {
                val btnAlbum = createListButtonWithIcon("\uE019", localize("Albums"), null)
                btnAlbum.setOnClickListener {
                    clickFeedback()
                    lastBrowserFocusText = localize("Albums")
                    currentBrowserMode = BrowserMode.Albums()
                    virtualQueryValue = ""
                    categoryArtistFilter = ""
                    categoryArtistFilterIsTrackArtist = false
                    buildVirtualCategories("ALBUM")
                }
                containerBrowserItems.addView(btnAlbum)
            }

            if (!isMusicMenuItemHidden("songs")) {
                val btnAll = createListButtonWithIcon("\uE03D", localize("Songs"), null)
                btnAll.setOnClickListener {
                    clickFeedback()
                    lastBrowserFocusText = localize("Songs")
                    currentBrowserMode = BrowserMode.VirtualSongs
                    virtualQueryType = "ALL"
                    buildVirtualSongs()
                }
                containerBrowserItems.addView(btnAll)
            }

            if (!isMusicMenuItemHidden("genres")) {
                val btnGenre = createListButtonWithIcon("\uE030", localize("Genres"), null)
                btnGenre.setOnClickListener {
                    clickFeedback()
                    lastBrowserFocusText = localize("Genres")
                    currentBrowserMode = BrowserMode.Genres
                    virtualQueryValue = ""
                    categoryGenreFilter = ""
                    categoryArtistFilter = ""
                    categoryArtistFilterIsTrackArtist = false
                    buildVirtualCategories("GENRE")
                }
                containerBrowserItems.addView(btnGenre)
            }

            if (!isMusicMenuItemHidden("composers")) {
                val btnComposer = createListButtonWithIcon("\uE7FD", localize("Composers"), null)
                btnComposer.setOnClickListener {
                    clickFeedback()
                    lastBrowserFocusText = localize("Composers")
                    currentBrowserMode = BrowserMode.Composers
                    virtualQueryValue = ""
                    buildVirtualCategories("COMPOSER")
                }
                containerBrowserItems.addView(btnComposer)
            }

            if (!isMusicMenuItemHidden("years")) {
                val btnYears = createListButtonWithIcon("\uE8B6", localize("Years"), null)
                btnYears.setOnClickListener {
                    clickFeedback()
                    lastBrowserFocusText = localize("Years")
                    currentBrowserMode = BrowserMode.Years
                    virtualQueryValue = ""
                    buildVirtualCategories("YEAR")
                }
                containerBrowserItems.addView(btnYears)
            }

            if (!isMusicMenuItemHidden("recently_added")) {
                val btnRecent = createListButtonWithIcon("\uE8B6", localize("Recently Added"), null)
                btnRecent.setOnClickListener {
                    clickFeedback()
                    lastBrowserFocusText = localize("Recently Added")
                    currentBrowserMode = BrowserMode.RecentlyAdded
                    virtualQueryType = "RECENT"
                    buildVirtualSongs()
                }
                containerBrowserItems.addView(btnRecent)
            }

            if (!isMusicMenuItemHidden("favorites")) {
                val btnFav = createListButtonWithIcon("\uE8B6", localize("My Favorites"), null)
                btnFav.setOnClickListener {
                    clickFeedback()
                    lastBrowserFocusText = localize("My Favorites")
                    currentBrowserMode = BrowserMode.Favorites
                    buildVirtualSongsForFavorites()
                }
                containerBrowserItems.addView(btnFav)
            }

            if (!isMusicMenuItemHidden("folders")) {
                val btnFolders = createListButtonWithIcon("\uE8B6", localize("Folders"), null)
                btnFolders.setOnClickListener {
                    clickFeedback()
                    lastBrowserFocusText = localize("Folders")
                    currentBrowserMode = BrowserMode.Folder(StoragePaths.getPrimaryRoot())
                    currentFolder = StoragePaths.getPrimaryRoot()
                    buildFileBrowserUI()
                }
                containerBrowserItems.addView(btnFolders)
            }

            if (!isMusicMenuItemHidden("search")) {
                val btnSearch = createListButtonWithIcon("\uE8B6", localize("Search"), null)
                btnSearch.setOnClickListener {
                    clickFeedback()
                    lastBrowserFocusText = localize("Search")
                    onSearchRequested()
                }
                containerBrowserItems.addView(btnSearch)
            }
        } else {
            updateHeaderTitle(localize("Audiobooks"))
            showBrowserCoverPane()

            val btnContinue = createListButtonWithIcon("\uE037", localize("Continue"), null)
            btnContinue.setOnClickListener {
                clickFeedback()
                val main = MainActivity.instance ?: return@setOnClickListener
                main.isAudiobookLibraryMode = true
                val bookPrefs = activity.getSharedPreferences("Y1_AUDIOBOOK_BOOKMARKS", Context.MODE_PRIVATE)
                val savedPath = main.prefs.getString("last_played_audiobook_path", null)
                    ?: bookPrefs.getString("LAST_AUDIOBOOK_PATH", null)
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
                        .takeIf { it > 0 } ?: AudiobookManager.getInstance(main).getSavedPosition(targetFile.absolutePath)
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

        val activeLibrary = if (isAudiobookLibraryMode) MainActivity.audiobookLibrary else MainActivity.customLibrary
        val uniqueCategories = HashSet<String>()

        for (song in activeLibrary) {
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

            val v = when (type) {
                "ARTIST" -> song.albumArtist
                "TRACK_ARTIST", "GENRE_ARTIST" -> song.artist
                "ALBUM", "GENRE_ALBUM" -> song.album
                "YEAR" -> song.year
                "GENRE" -> song.genre
                "COMPOSER" -> song.composer
                else -> "Unknown"
            }
            uniqueCategories.add(v)
        }

        val categories = ArrayList(uniqueCategories)
        Collections.sort(categories, java.lang.String.CASE_INSENSITIVE_ORDER)

        if (!isAudiobookLibraryMode && ((type == "ALBUM" && categoryArtistFilter.isNotEmpty()) || type == "GENRE_ARTIST" || type == "GENRE_ALBUM")) {
            categories.add(0, ALL_SONGS_SENTINEL)
        }

        currentScrollIndexList.clear()
        currentScrollIndexList.addAll(categories)

        val adapter = CategoryListAdapter(categories, type, isAudiobookLibraryMode)
        listVirtualSongs?.adapter = adapter

        val targetIndex = categories.indexOf(virtualQueryValue)
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
                    virtualQueryType == "ARTIST" && song.artist == virtualQueryValue -> isMatch = true
                    virtualQueryType == "ARTIST_ALL_ALBUMS" && song.albumArtist == virtualQueryValue -> isMatch = true
                    (virtualQueryType == "ALBUM" || virtualQueryType == "RECENTLY_PLAYED_ALBUM") && song.album == virtualQueryValue -> {
                        if (categoryArtistFilter.isNotEmpty()) {
                            val matchField = if (categoryArtistFilterIsTrackArtist) song.artist else song.albumArtist
                            isMatch = categoryArtistFilter.equals(matchField, ignoreCase = true) || categoryArtistFilter.equals(song.artist, ignoreCase = true)
                        } else {
                            isMatch = true
                        }
                    }
                    virtualQueryType == "COVER_FLOW_ALBUM" && song.album == virtualQueryValue -> isMatch = true
                    virtualQueryType == "YEAR" && song.year == virtualQueryValue -> isMatch = true
                    virtualQueryType == "GENRE" && song.genre.equals(virtualQueryValue, ignoreCase = true) -> isMatch = true
                    virtualQueryType == "GENRE_ARTIST_SONGS" -> {
                        val matchField = if (categoryArtistFilterIsTrackArtist) song.artist else song.albumArtist
                        isMatch = song.genre.equals(categoryGenreFilter, ignoreCase = true) &&
                            (categoryArtistFilter.isEmpty() || categoryArtistFilter.equals(matchField, ignoreCase = true))
                    }
                    virtualQueryType == "GENRE_ALBUM_SONGS" -> {
                        val matchField = if (categoryArtistFilterIsTrackArtist) song.artist else song.albumArtist
                        isMatch = song.album.equals(virtualQueryValue, ignoreCase = true) &&
                            song.genre.equals(categoryGenreFilter, ignoreCase = true) &&
                            (categoryArtistFilter.isEmpty() || categoryArtistFilter.equals(matchField, ignoreCase = true))
                    }
                    virtualQueryType == "COMPOSER" && song.composer == virtualQueryValue -> isMatch = true
                    isSearchMatch -> isMatch = true
                }

                if (isRecentPassed && isMatch) {
                    targetSongs.add(song)
                }
            }
        }

        Collections.sort(targetSongs) { s1, s2 ->
            if ("ALBUM" == virtualQueryType || "COVER_FLOW_ALBUM" == virtualQueryType || "GENRE_ALBUM_SONGS" == virtualQueryType || "RECENTLY_PLAYED_ALBUM" == virtualQueryType) {
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

    fun buildRecentlyPlayedAlbums() {
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
        hzIndexScroll?.visibility = View.GONE

        updateHeaderTitle(localize("Recently Played"))

        val activeLibrary = if (isAudiobookLibraryMode) MainActivity.audiobookLibrary else MainActivity.customLibrary
        val recentEntries = RecentlyPlayedManager.getInstance(activity).getRecentAlbums(activeLibrary)

        val items = ArrayList<String>()
        if (recentEntries.isEmpty()) {
            Toast.makeText(activity, localize("No recently played albums yet."), Toast.LENGTH_SHORT).show()
        } else {
            for (entry in recentEntries) {
                items.add(entry.albumName)
            }
            items.add(CLEAR_RECENTLY_PLAYED_SENTINEL)
        }

        currentScrollIndexList.clear()
        currentScrollIndexList.addAll(items)

        val adapter = CategoryListAdapter(items, "RECENTLY_PLAYED_ALBUMS", isAudiobookLibraryMode, recentEntries)
        listVirtualSongs?.adapter = adapter

        virtualListSelectedIndex = 0
        listVirtualSongs?.post {
            navigateVirtualListTo(virtualListSelectedIndex)
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

        if (frame.findViewWithTag<View>("pane_shadow") == null) {
            val shadow = View(activity).apply {
                tag = "pane_shadow"
                background = android.graphics.drawable.GradientDrawable(
                    android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT,
                    intArrayOf(0x50000000, 0x00000000)
                )
            }
            val shadowWidth = (10 * density).toInt()
            val shadowLp = FrameLayout.LayoutParams(shadowWidth, FrameLayout.LayoutParams.MATCH_PARENT).apply {
                gravity = Gravity.LEFT
            }
            frame.addView(shadow, shadowLp)
        }

        if (!browserCoverPaneSetUp && ivBrowserCover != null) {
            browserCoverPaneSetUp = true
            frame.post {
                val contW = frame.width
                val contH = frame.height
                if (contW <= 0 || contH <= 0) return@post
                val overW = (contW * 1.35f).toInt()
                val overH = (contH * 1.35f).toInt()
                ivBrowserCover.scaleType = ImageView.ScaleType.CENTER_CROP
                val lp = FrameLayout.LayoutParams(overW, overH).apply {
                    gravity = Gravity.CENTER
                }
                ivBrowserCover.layoutParams = lp
            }
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

    private fun isMusicMenuItemHidden(id: String): Boolean {
        return activity.getSharedPreferences("y1_settings", Activity.MODE_PRIVATE)
            .getBoolean("hide_music_btn_$id", false)
    }

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
        clickFeedback()
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
                    "RECENTLY_PLAYED_ALBUM" -> {
                        currentBrowserMode = BrowserMode.RecentlyPlayed
                        buildRecentlyPlayedAlbums()
                    }
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
            is BrowserMode.RecentlyPlayed -> {
                currentBrowserMode = BrowserMode.Root
                lastBrowserFocusText = localize("Recently Played")
                buildFileBrowserUI()
                return true
            }
            else -> {
                onNavigateBackToMenu()
                return true
            }
        }
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
            val isLoopScrollOn = activity.getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
                .getBoolean("loop_scroll_on", true)
            val curFocus = containerBrowserItems.findFocus()
            val currentIndex = if (curFocus != null) containerBrowserItems.indexOfChild(curFocus) else -1

            if (delta < 0) {
                if (currentIndex > 0) {
                    for (i in currentIndex - 1 downTo 0) {
                        val child = containerBrowserItems.getChildAt(i)
                        if (child != null && child.visibility == View.VISIBLE && child.isFocusable) {
                            child.requestFocus()
                            clickFeedback()
                            return true
                        }
                    }
                }
                if (isLoopScrollOn && currentIndex >= 0) {
                    for (i in containerBrowserItems.childCount - 1 downTo currentIndex + 1) {
                        val child = containerBrowserItems.getChildAt(i)
                        if (child != null && child.visibility == View.VISIBLE && child.isFocusable) {
                            child.requestFocus()
                            clickFeedback()
                            return true
                        }
                    }
                }
                if (currentIndex == -1) {
                    for (i in 0 until containerBrowserItems.childCount) {
                        val child = containerBrowserItems.getChildAt(i)
                        if (child != null && child.visibility == View.VISIBLE && child.isFocusable) {
                            child.requestFocus()
                            clickFeedback()
                            return true
                        }
                    }
                }
            } else {
                if (currentIndex in 0 until containerBrowserItems.childCount - 1) {
                    for (i in currentIndex + 1 until containerBrowserItems.childCount) {
                        val child = containerBrowserItems.getChildAt(i)
                        if (child != null && child.visibility == View.VISIBLE && child.isFocusable) {
                            child.requestFocus()
                            clickFeedback()
                            return true
                        }
                    }
                }
                if (isLoopScrollOn && currentIndex >= 0) {
                    for (i in 0 until currentIndex) {
                        val child = containerBrowserItems.getChildAt(i)
                        if (child != null && child.visibility == View.VISIBLE && child.isFocusable) {
                            child.requestFocus()
                            clickFeedback()
                            return true
                        }
                    }
                }
                if (currentIndex == -1) {
                    for (i in 0 until containerBrowserItems.childCount) {
                        val child = containerBrowserItems.getChildAt(i)
                        if (child != null && child.visibility == View.VISIBLE && child.isFocusable) {
                            child.requestFocus()
                            clickFeedback()
                            return true
                        }
                    }
                }
            }
            return true
        }

        return false
    }
}
