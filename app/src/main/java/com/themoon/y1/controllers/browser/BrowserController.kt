package com.themoon.y1.controllers.browser

import android.app.Activity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import com.themoon.y1.AppPrefs
import com.themoon.y1.Prefs
import com.themoon.y1.MainActivity
import com.themoon.y1.R
import com.themoon.y1.StoragePaths
import com.themoon.y1.ThemeManager
import com.themoon.y1.adapters.CategoryListAdapter
import com.themoon.y1.adapters.SongListAdapter
import com.themoon.y1.controllers.ScreenController
import com.themoon.y1.lists.LetterBox
import com.themoon.y1.lists.ListScreen
import com.themoon.y1.lists.ListSpec
import com.themoon.y1.lists.Pane
import com.themoon.y1.lists.RowLook
import com.themoon.y1.lists.RowSpec
import com.themoon.y1.managers.AudioPlayerManager
import com.themoon.y1.managers.AudiobookManager
import com.themoon.y1.models.AlbumKey
import com.themoon.y1.models.SongItem
import com.themoon.y1.navigation.BrowserMode
import com.themoon.y1.navigation.InputRouter
import com.themoon.y1.navigation.NavigationManager
import com.themoon.y1.navigation.RowKey
import com.themoon.y1.navigation.ScreenState
import com.themoon.y1.navigation.isFolderLevel
import com.themoon.y1.views.ListItemViewFactory
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
 *
 * The lists are [ListSpec]s on one [ListScreen]: the Music and Audiobooks menus with the split screen ([Pane]), the
 * long lists with their adapters, and the iPod letter jump in the A–Z lists.
 */
class BrowserController(
    private val activity: Activity,
    override val rootView: ViewGroup,
    private val listVirtualSongs: ListView?,
    private val clickFeedback: () -> Unit,
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

    /** The art pane of the Music and Audiobooks menus. */
    private val pane: Pane? = rootView.findViewById<View?>(R.id.frame_browser_cover)?.let { frame ->
        (containerBrowserItems.parent?.parent as? View)?.let { listPane ->
            Pane(activity, listPane, frame, rootView.findViewById<ImageView?>(R.id.iv_browser_cover), onRefreshCoverPane)
        }
    }

    private val screen = ListScreen(containerBrowserItems, listVirtualSongs, clickFeedback, letterBox = object : LetterBox {
        override fun show(letter: String) { MainActivity.instance?.showJumpLetter(letter) }
        override fun hide() { MainActivity.instance?.hideFastScrollLetter() }
    })

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

    /** The letter in the letter box during letter mode; null when the wheel steps row by row. */
    val jumpLetter: String? get() = screen.jumpLetter

    companion object {
        const val ALL_SONGS_SENTINEL = "__ALL_SONGS__"

        /** Keys of the ".." and Play All rows of a folder list (the folder and file rows have their paths). */
        private const val UP_KEY = "\u0000up"
        private const val PLAY_ALL_KEY = "\u0000play all"
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
        screen.clear()
        isAudiobookLibraryMode = false
    }

    override fun title(): String? = screen.title

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

    /** Shows [spec], and its title also when no screen change sets it (library scan, test commands). */
    private fun show(spec: ListSpec) {
        screen.show(spec)
        activity.findViewById<TextView>(R.id.tv_status_clock)?.text = spec.title
    }

    /** The Music menu (the items of Settings → Music Menu Items) or the Audiobooks menu, with the art pane. */
    fun buildFileBrowserUI() {
        if (currentBrowserMode is BrowserMode.Folder) {
            buildFolderBrowserUI()
            return
        }
        val audiobooks = isAudiobookLibraryMode || currentBrowserMode is BrowserMode.Audiobooks
        val rows = ArrayList<RowSpec>()
        if (!audiobooks) {
            for (item in MusicMenuItems.ITEMS) {
                if (isMusicMenuItemHidden(item.id)) continue
                rows += RowSpec(localize(item.label), key = item.id, onClick = { openMusicMenuItem(item.id) })
            }
        } else {
            rows += RowSpec(localize("Continue"), onClick = { continueAudiobook() })
            rows += RowSpec(localize("Books"), onClick = { openList(BrowserMode.Albums()) })
            rows += RowSpec(localize("Authors"), onClick = { openList(BrowserMode.Artists) })
        }
        val isScanning = MainActivity.instance?.isCustomScanning == true
        rows += RowSpec(if (isScanning) localize("Scanning Media...") else localize("Scan Media Library"), key = "scan",
            onClick = { onStartMediaLibraryScan() })
        show(ListSpec(if (audiobooks) localize("Audiobooks") else localize("Music"), rows, pane = pane))
    }

    /** Audiobooks → Continue: plays the last book from its saved place. */
    private fun continueAudiobook() {
        val main = MainActivity.instance ?: return
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
                return
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

    /** True (and the Music menu shows) while the library scan runs: the lists need the library. */
    private fun waitForScan(): Boolean {
        if (MainActivity.instance?.isCustomScanning != true) return false
        showLoadingPopup()
        currentBrowserMode = BrowserMode.Root
        buildFileBrowserUI()
        return true
    }

    fun buildVirtualCategories(type: String) {
        if (waitForScan()) return

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

        val albumList = type == "ALBUM" || type == "GENRE_ALBUM"
        show(ListSpec(catTitle, adapter = CategoryListAdapter(categories, type, isAudiobookLibraryMode, albumArtists = albumArtists),
            empty = localize(if (albumList) "No albums found." else "No results found."), letters = true))
    }

    fun buildVirtualSongs() {
        if (waitForScan()) return

        val songsTitle = when (virtualQueryType) {
            "RECENT" -> localize("Recently Added")
            "ALL" -> if (isAudiobookLibraryMode) localize("All Audiobooks") else localize("All Songs")
            "SEARCH" -> "${localize("Search")}: $virtualQueryValue"
            "ARTIST_ALL_ALBUMS" -> virtualQueryValue
            else -> virtualQueryValue
        }

        val main = MainActivity.instance
        main?.virtualSongList?.clear()
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
        val displaySongs = ArrayList<SongItem>()

        for (song in targetSongs) {
            displaySongs.add(SongItem(song.file, song.title, song.artist, song.album, song.year, song.genre, song.albumArtist, song.composer))
            main?.virtualSongList?.add(song.file)
        }

        show(ListSpec(songsTitle, adapter = SongListAdapter(displaySongs),
            empty = localize(if (virtualQueryType == "SEARCH") "No results found." else "No Songs"), letters = true))
    }

    fun buildVirtualSongsForFavorites() {
        if (waitForScan()) return

        val main = MainActivity.instance
        main?.virtualSongList?.clear()
        val targetSongs = ArrayList<SongItem>()

        for (song in MainActivity.customLibrary) {
            if (main?.favoritePaths?.contains(song.file.absolutePath) == true) {
                targetSongs.add(song)
            }
        }

        Collections.sort(targetSongs) { s1, s2 -> s1.title.compareTo(s2.title, ignoreCase = true) }

        for (song in targetSongs) {
            main?.virtualSongList?.add(song.file)
        }

        show(ListSpec(localize("My Favorites"), adapter = SongListAdapter(targetSongs),
            empty = localize("No favorites added yet."), letters = true))

        // The song that plays, if it is a favorite
        val curPlaylist = main?.currentPlaylist
        val curIndex = main?.currentIndex ?: -1
        if (curPlaylist != null && curIndex in curPlaylist.indices) {
            val playing = curPlaylist[curIndex].absolutePath
            val index = targetSongs.indexOfFirst { it.file.absolutePath == playing }
            if (index >= 0) listVirtualSongs?.post { screen.select(index) }
        }
    }

    /**
     * A folder of the memory: "..", Play All, the folders, the APK files (install) and the audio files (A to Z).
     * The top of the file tree lists the storage volumes; the background picker of Settings lists the images.
     */
    fun buildFolderBrowserUI() {
        val rawDisplayPath = StoragePaths.toDisplayPath(currentFolder.absolutePath)
        val folderTitle = if (currentFolder.absolutePath == StoragePaths.STORAGE_DIR) {
            localize("Storage")
        } else if (currentFolder.absolutePath == StoragePaths.SECONDARY_PATH || currentFolder.absolutePath.startsWith("${StoragePaths.SECONDARY_PATH}/")) {
            "${localize("Path")}: [SD] $rawDisplayPath"
        } else {
            "${localize("Path")}: $rawDisplayPath"
        }
        val files = currentFolder.listFiles()

        val isTop = StoragePaths.isTopStorageRoot(currentFolder)
        val canGoUp = !isTop && currentFolder.parentFile != null

        val rows = ArrayList<RowSpec>()
        if (canGoUp) {
            rows += RowSpec("..", key = UP_KEY, onClick = { currentFolder.parentFile?.let { openParentFolder(it, addToBackStack = true) } })
        }

        if (files == null || files.isEmpty()) {
            val emptyMsg = if (files == null) {
                "⚠️ ${localize("USB Disconnect Required (Tap to go back)")}"
            } else {
                "📂 ${localize("Empty Folder (Tap to go back)")}"
            }
            val btnEmpty = Button(activity).apply {
                text = emptyMsg
                setTextColor(0xFFFF5555.toInt())
                background = ListItemViewFactory.createButtonBackground(activity, ThemeManager.getListButtonNormalBg())
            }
            rows += RowSpec(emptyMsg, RowLook.CUSTOM, view = btnEmpty, onClick = {
                // Back when a folder list is under this one or no folder is above it; else one level up in place of this list
                if (isTop || NavigationManager.getInstance().previousState()?.isFolderLevel == true) onBackPressed()
                else openParentFolder(currentFolder.parentFile ?: StoragePaths.getTopStorageRoot(), addToBackStack = false)
            })
            show(ListSpec(folderTitle, rows))
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
            rows += RowSpec("▶ ${localize("Play All")}", key = PLAY_ALL_KEY, onClick = {
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
            })
        }

        for (folder in folders) {
            rows += RowSpec(folderRowText(folder, isTop), key = folder.absolutePath,
                onClick = { openList(BrowserMode.Folder(folder, isPickingBackground)) })
        }

        if (isPickingBackground) {
            for (img in imageFiles) {
                rows += RowSpec(img.name, key = img.absolutePath, onClick = {
                    try {
                        AppPrefs.get(activity).edit().putString(Prefs.BACKGROUND.key, img.absolutePath).commit()
                        MainActivity.instance?.updateMainMenuBackground()
                    } catch (e: Exception) {
                    }
                    Notice.show(activity, localize("Background Applied!"))
                    isPickingBackground = false
                    // Back to the Settings screen that opened the picker, over all its folder levels
                    NavigationManager.getInstance().popBackStackTo { it !is ScreenState.Browser }
                })
            }
        } else {
            for (apk in apkFiles) {
                val label = "📦 [${localize("INSTALL")}] ${apk.name}"
                val b = Button(activity).apply {
                    text = label
                    setTextColor(0xFF00FFFF.toInt())
                    background = ListItemViewFactory.createButtonBackground(activity, ThemeManager.getListButtonNormalBg())
                }
                rows += RowSpec(label, RowLook.CUSTOM, view = b, key = apk.absolutePath, onClick = { installApk(apk) })
            }
            for (audio in audioFiles) rows += folderAudioRow(audio)
        }

        show(ListSpec(folderTitle, rows, letters = true))

        // The file that plays, if it is in this folder; else the first folder, else the first file (not ".." or Play All)
        val curPlaylist = MainActivity.instance?.currentPlaylist
        val curIndex = MainActivity.instance?.currentIndex ?: -1
        val playing = if (curPlaylist != null && curIndex in curPlaylist.indices) {
            rows.indexOfFirst { it.key == curPlaylist[curIndex].absolutePath }
        } else -1
        if (playing >= 0) {
            screen.select(playing)
        } else {
            rows.firstOrNull { it.key != UP_KEY && it.key != PLAY_ALL_KEY }?.let { screen.select(it.key) }
        }
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

    /** An audio file row of a folder; an audiobook chapter shows its progress. A hold adds the file to a playlist. */
    private fun folderAudioRow(audio: File): RowSpec {
        val audiobooks = isAudiobookLibraryMode || currentBrowserMode is BrowserMode.Audiobooks
        val click = {
            if (audiobooks) {
                onAudiobookSelected(audio, currentFolder)
            } else {
                AudioPlayerManager.getInstance().setupFolderPlaylist(audio, currentFolder)
            }
            onNavigateToScreen(ScreenState.Player)
        }
        val hold = { showAddToPlaylistDialog(audio) }
        if (audiobooks) {
            val prefs = AppPrefs.get(activity)
            val pos = prefs.getInt("book_pos_" + audio.absolutePath, 0)
            val dur = prefs.getInt("book_dur_" + audio.absolutePath, 0)
            if (pos > 0 && dur > 0) {
                val b = ListItemViewFactory.createListButtonWithIcon(activity, "", audio.name)
                ListItemViewFactory.setupAudiobookProgress(activity, b, pos, dur)
                return RowSpec(audio.name, RowLook.CUSTOM, view = b, key = audio.absolutePath, onClick = click, onHold = hold)
            }
        }
        return RowSpec(audio.name, key = audio.absolutePath, onClick = click, onHold = hold)
    }

    fun isCoverPaneActive(): Boolean = pane?.isShown == true

    fun getCurrentBrowserTitle(): String =
        screen.title?.takeIf { it.isNotBlank() } ?: if (isAudiobookLibraryMode) localize("Audiobooks") else localize("Music")

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

    /** Back: the list of the top back-stack entry (NavigationManager builds it again and selects its row). */
    override fun onBackPressed(): Boolean {
        onNavigateBackToMenu()
        return true
    }

    /** Ends letter mode (another screen). The list is already at the first row of the letter. */
    fun endLetterJump() = screen.endLetters()

    /** In letter mode, a centre press only ends it; Back and Play/Pause end it and then work as usual. */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean = screen.key(event)

    override fun onWheel(delta: Int, event: KeyEvent): Boolean = screen.wheel(delta, event.eventTime)

    /** Back held in Folders (the first key repeat): out of all folder levels at once. The release does nothing. */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val router = InputRouter.getInstance()
        if (event.repeatCount == 0 || !router.isBackKey(keyCode) || currentBrowserMode !is BrowserMode.Folder) return false
        router.suppressBackUntilRelease()
        clickFeedback()
        NavigationManager.getInstance().popBackStackTo { !it.isFolderLevel }
        return true
    }
}
