package com.themoon.y1

import android.content.Context
import android.graphics.drawable.ColorDrawable
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import com.themoon.y1.controllers.browser.BrowserController
import com.themoon.y1.controllers.connectivity.BluetoothController
import com.themoon.y1.controllers.connectivity.WebServerController
import com.themoon.y1.controllers.connectivity.WifiController
import com.themoon.y1.controllers.connectivity.WifiKeyboardController
import com.themoon.y1.controllers.coverflow.CoverFlowController
import com.themoon.y1.controllers.home.HomeWidgetsController
import com.themoon.y1.controllers.menu.MainMenuController
import com.themoon.y1.controllers.player.PlayerController
import com.themoon.y1.controllers.playlists.PlaylistController
import com.themoon.y1.controllers.podcasts.PodcastController
import com.themoon.y1.controllers.quiz.MusicQuizController
import com.themoon.y1.controllers.radio.FmRadioController
import com.themoon.y1.controllers.settings.BrightnessController
import com.themoon.y1.controllers.settings.EqualizerController
import com.themoon.y1.views.ClassicMenuStyle
import com.themoon.y1.controllers.settings.SettingsController
import com.themoon.y1.controllers.storage.StorageController
import com.themoon.y1.controllers.sync.JellyfinUiController
import com.themoon.y1.controllers.videos.VideoBrowserController
import com.themoon.y1.controllers.videos.VideoPlayerController
import com.themoon.y1.managers.AudioPlayerManager
import com.themoon.y1.managers.MediaLibraryManager
import com.themoon.y1.managers.OtaUpdateManager
import com.themoon.y1.models.AlbumKey
import com.themoon.y1.navigation.BrowserMode
import com.themoon.y1.navigation.KeyboardTarget
import com.themoon.y1.navigation.NavigationManager
import com.themoon.y1.navigation.ScreenState
import com.themoon.y1.navigation.SettingsSubMode
import com.themoon.y1.navigation.VideoMode
import com.themoon.y1.podcasts.PodcastDownloadManager
import com.themoon.y1.podcasts.PodcastRepository
import com.themoon.y1.views.EdgeFadeListView
import com.themoon.y1.views.ListItemViewFactory
import com.themoon.y1.views.Notice

internal fun MainActivity.initControllers() {
    val rootContent = findViewById<ViewGroup>(android.R.id.content)
    val containerBrowserItems = findViewById<LinearLayout>(R.id.container_browser_items)
    val tvBrowserPath = findViewById<TextView>(R.id.tv_browser_path)

    val listContainer = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
    }

    val listVirtualSongs = object : EdgeFadeListView(this) {
        override fun setVisibility(visibility: Int) {
            super.setVisibility(visibility)
            listContainer.visibility = visibility
        }
    }.apply {
        divider = ColorDrawable(0)
        dividerHeight = if (ThemeManager.isClassicTheme()) 0 else (4 * resources.displayMetrics.density).toInt()
        selector = ColorDrawable(0)
        itemsCanFocus = true
        isSoundEffectsEnabled = false
        visibility = View.GONE
    }
    listContainer.addView(listVirtualSongs, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.0f))

    val scrollViewBrowser = containerBrowserItems.parent as View
    val browserParent = scrollViewBrowser.parent as ViewGroup
    val density = resources.displayMetrics.density
    browserParent.setPadding(0, (ClassicMenuStyle.STATUS_HEIGHT_DP * density).toInt(), 0, 0)
    browserParent.addView(listContainer, scrollViewBrowser.layoutParams)

    mainMenuController = MainMenuController(
        activity = this,
        rootView = layoutMainMenu as ViewGroup,
        clickFeedback = { clickFeedback() },
        onActionTriggered = { action -> handleMainMenuAction(action) },
        onButtonFocusChanged = { el ->
            homeWidgetsController?.updateFocusPreview(el)
        },
        onLongPressScreenOff = View.OnLongClickListener {
            turnOffScreen()
            true
        },
        localize = { t(it) },
        onDynamicUiBuilt = {
            homeWidgetsController?.refreshWidgets()
        }
    )

    homeWidgetsController = HomeWidgetsController(
        context = this,
        mainMenuProvider = { mainMenuController },
        ivBrowserCover = findViewById(R.id.iv_browser_cover),
        frameBrowserCover = findViewById(R.id.frame_browser_cover),
        fetchRandomIdleCoverAsync = { fetchRandomIdleCoverAsync() },
        isAudiobookMode = {
            isAudiobookLibraryMode || (isBrowserControllerInitialized() && (browserController.isAudiobookLibraryMode || browserController.currentBrowserMode is BrowserMode.Audiobooks))
        },
        is24HourFormat = { is24HourFormat }
    )

    browserController = BrowserController(
        activity = this,
        rootView = layoutBrowserMode as ViewGroup,
        listVirtualSongs = listVirtualSongs,
        clickFeedback = { clickFeedback() },
        onNavigateToScreen = { state ->
            changeScreen(state)
        },
        onNavigateBackToMenu = {
            NavigationManager.getInstance().popBackStack()
        },
        onCoverFlowRequested = {
            changeScreen(ScreenState.CoverFlow)
        },
        onPlaylistsRequested = {
            changeScreen(ScreenState.Playlists)
        },
        onShuffleSongsRequested = {
            shuffleAllSongs()
        },
        onSearchRequested = {
            changeScreen(ScreenState.WifiKeyboard(KeyboardTarget.LibrarySearch))
        },
        onStartMediaLibraryScan = {
            startMediaLibraryScan()
        },
        showLoadingPopup = {
            showLoadingPopup()
        },
        showAddToPlaylistDialog = { file ->
            showAddToPlaylistDialog(file)
        },
        onAudiobookSelected = { file, parent ->
            AudioPlayerManager.getInstance().setupFolderPlaylist(file, parent)
            changeScreen(MainActivity.STATE_PLAYER)
        },
        localize = { t(it) },
        onRefreshCoverPane = {
            homeWidgetsController?.refreshWidgets()
        }
    )

    playlistController = PlaylistController(
        activity = this,
        rootView = layoutBrowserMode as ViewGroup,
        listVirtualSongs = listVirtualSongs,
        musicRootFolder = rootFolder,
        isAudioFile = { MediaLibraryManager.getInstance().isAudioFile(it) },
        clickFeedback = { clickFeedback() },
        onPlaylistSelected = { file ->
            changeScreen(ScreenState.M3uPlaylistSongs(file))
        },
        localize = { t(it) }
    )

    coverFlowController = CoverFlowController(
        activity = this,
        rootView = layoutBrowserMode as ViewGroup,
        listVirtualSongs = listVirtualSongs,
        uniqueAlbumList = uniqueAlbumList,
        clickFeedback = { clickFeedback() },
        onAlbumSelected = { album ->
            changeScreen(ScreenState.Browser(BrowserMode.Songs("COVER_FLOW_ALBUM", album.album, AlbumKey.artistOf(album))))
        },
        onRebuildUniqueAlbumList = { rebuildUniqueAlbumListInternal() },
        nowPlayingSong = {
            // While the FM radio is on, the radio plays, not the song of the queue.
            if (com.themoon.y1.managers.FmRadioManager.getInstance()?.isPowerUp == true) null
            else currentPlaylist.getOrNull(currentIndex)?.let { file -> MainActivity.customLibrary.firstOrNull { it.file == file } }
        },
        localize = { t(it) }
    )

    playerController = PlayerController(
        activity = this,
        rootView = layoutPlayerMode as ViewGroup,
        clickFeedback = { clickFeedback() },
        onAdjustVolume = { adjustVolume(it) },
        onTogglePlayback = { com.themoon.y1.managers.AudioSources.app.playPauseKey() },
        onNavigateBack = { NavigationManager.getInstance().popBackStack() },
        onBrowseAlbum = { album ->
            // The menu gives only the title; take the album artist from the song that plays.
            val playingPath = currentPlaylist.getOrNull(currentIndex)?.absolutePath
            val playingSong = MainActivity.customLibrary.firstOrNull { it.file.absolutePath == playingPath }
            val albumArtist = playingSong?.let { AlbumKey.artistOf(it) } ?: ""
            changeScreen(ScreenState.Browser(BrowserMode.Songs("ALBUM", album, albumArtist)))
        },
        onBrowseArtist = { artist ->
            changeScreen(ScreenState.Browser(BrowserMode.Songs("ARTIST", artist)))
        },
        onToggleFavorite = { toggleFavorite() },
        onAddToOnTheGo = { playlistController.addSongToOnTheGo(it) },
        onAddToPlaylist = { showAddToPlaylistDialog(it) },
        onToggleVisualizer = { toggleVisualizer() },
        localize = { t(it) }
    )

    equalizerController = EqualizerController(
        activity = this,
        rootView = layoutSettingsMode as ViewGroup,
        eqPresetNames = eqPresetNames,
        createCategoryHeader = { title ->
            val container = findViewById<LinearLayout>(R.id.container_settings_items)
            if (container != null) ListItemViewFactory.createCategoryHeader(this, container, title)
        },
        createListButton = { icon, text -> createListButton(text, icon) },
        clickFeedback = { clickFeedback() },
        rebootAudioEngine = { rebootAudioEngine() },
        onNavigateBack = { NavigationManager.getInstance().popBackStack() },
        localize = { t(it) }
    )

    fmRadioController = FmRadioController(
        activity = this,
        rootView = layoutSettingsMode as ViewGroup,
        clickFeedback = { clickFeedback() },
        updateGlobalStatusPlayIcon = { updatePlayerUI() },
        onExitRadio = { NavigationManager.getInstance().popBackStack() },
        showLoadingPopup = { showLoadingPopup() },
        dismissLoadingOverlay = { dismissLoadingOverlay() },
        updateLoadingProgress = { progress, message -> updateLoadingProgress(progress, message) },
        localize = { t(it) }
    )

    podcastController = PodcastController(
        activity = this,
        rootView = layoutBrowserMode as ViewGroup,
        containerBrowserItems = containerBrowserItems,
        listVirtualSongs = listVirtualSongs,
        repository = PodcastRepository.getInstance(),
        downloadManager = PodcastDownloadManager.getInstance(),
        onRequestSearch = {
            changeScreen(ScreenState.WifiKeyboard(KeyboardTarget.PodcastSearch))
        },
        onNavigateToPlayer = { changeScreen(MainActivity.STATE_PLAYER) },
        clickFeedback = { clickFeedback() },
        localize = { t(it) },
        showLoadingPopup = { showLoadingPopup() },
        onHideLoadingPopup = { dismissLoadingOverlay() }
    )

    musicQuizController = MusicQuizController(
        activity = this,
        rootView = layoutMusicQuizMode as ViewGroup,
        libraryProvider = { MainActivity.customLibrary },
        onExitRequested = { NavigationManager.getInstance().popBackStack() },
        clickFeedback = { clickFeedback() },
        localize = { t(it) }
    )

    videoBrowserController = VideoBrowserController(
        activity = this,
        rootView = layoutVideosMode as ViewGroup,
        movieVideosProvider = { movieVideos },
        tvSeriesProvider = { tvSeriesList },
        allVideosProvider = { videoFiles },
        onVideoSelected = { file ->
            changeScreen(ScreenState.VideoPlayer(file))
        },
        onNavigateToMode = { mode ->
            changeScreen(ScreenState.Videos(mode))
        },
        clickFeedback = { clickFeedback() },
        localize = { t(it) },
        updateHeaderTitle = { title ->
            tvStatusBarTitle?.text = title
        }
    )

    videoPlayerController = VideoPlayerController(
        activity = this,
        rootView = layoutVideoPlayerMode as ViewGroup,
        onExitRequested = { NavigationManager.getInstance().popBackStack() },
        onAdjustVolume = { adjustVolume(it) },
        clickFeedback = { clickFeedback() },
        localize = { t(it) }
    )

    bluetoothController = BluetoothController(
        activity = this,
        rootView = layoutBluetoothMode as ViewGroup,
        clickFeedback = { clickFeedback() },
        localize = { t(it) }
    )

    wifiController = WifiController(
        activity = this,
        rootView = layoutWifiMode as ViewGroup,
        clickFeedback = { clickFeedback() },
        onConnectToSavedNetwork = { _, _ -> },
        onOpenWifiKeyboard = { ssid, isOpen ->
            changeScreen(ScreenState.WifiKeyboard(KeyboardTarget.WifiPassword(ssid, isOpen)))
        },
        localize = { t(it) }
    )

    wifiKeyboardController = WifiKeyboardController(
        activity = this,
        rootView = layoutWifiKeyboard as ViewGroup,
        clickFeedback = { clickFeedback() },
        onExitKeyboard = { _ ->
            NavigationManager.getInstance().popBackStack()
        },
        onSubmitSearchPodcast = { keyword ->
            NavigationManager.getInstance().popBackStack()
            podcastController.searchApplePodcasts(keyword)
        },
        onSubmitSearchLibrary = { keyword ->
            // The result list takes the place of the keyboard: Back goes to the Music menu, on Search
            NavigationManager.getInstance().navigateTo(ScreenState.Browser(BrowserMode.Songs("SEARCH", keyword)), addToBackStack = false)
        },
        onSubmitLastFmUsername = { username ->
            prefs.edit().putString(Prefs.LASTFM_USERNAME.key, username).apply()
        },
        onSubmitLastFmPassword = { password ->
            prefs.edit().putString(Prefs.LASTFM_PASSWORD.key, password).apply()
            NavigationManager.getInstance().popBackStack()
        },
        onSubmitJellyfinServerUrl = { url ->
            val provider = com.themoon.y1.sync.jellyfin.JellyfinSyncProvider.getInstance(this)
            provider.serverUrl = url
            NavigationManager.getInstance().popBackStack()
        },
        onSubmitJellyfinUsername = { username ->
            val provider = com.themoon.y1.sync.jellyfin.JellyfinSyncProvider.getInstance(this)
            provider.username = username
        },
        onSubmitJellyfinPassword = { password ->
            val provider = com.themoon.y1.sync.jellyfin.JellyfinSyncProvider.getInstance(this)
            provider.password = password
            NavigationManager.getInstance().popBackStack()
        },
        onSubmitJellyfinToken = { token ->
            val provider = com.themoon.y1.sync.jellyfin.JellyfinSyncProvider.getInstance(this)
            provider.accessToken = token
            NavigationManager.getInstance().popBackStack()
        },
        onConnectWifi = { ssid, password, isOpen ->
            val wm = getSystemService(Context.WIFI_SERVICE) as? WifiManager
            if (wm != null) {
                val conf = WifiConfiguration().apply {
                    SSID = "\"$ssid\""
                    if (isOpen) {
                        allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE)
                    } else {
                        preSharedKey = "\"$password\""
                    }
                }
                val netId = wm.addNetwork(conf)
                if (netId != -1) {
                    wm.enableNetwork(netId, true)
                    wm.saveConfiguration()
                }
            }
            NavigationManager.getInstance().popBackStack()
        },
        initialText = { target ->
            val provider = com.themoon.y1.sync.jellyfin.JellyfinSyncProvider.getInstance(this)
            when (target) {
                KeyboardTarget.JellyfinServerUrl -> provider.serverUrl
                KeyboardTarget.JellyfinUsername -> provider.username
                is KeyboardTarget.JellyfinPassword -> provider.password
                KeyboardTarget.JellyfinToken -> provider.accessToken
                else -> ""
            }
        },
        localize = { t(it) }
    )

    webServerController = WebServerController(
        activity = this,
        rootView = layoutWebServerMode as ViewGroup,
        onServerStateChanged = { running ->
            isServerRunning = running
            // The server holds the screen on: the app's own Backlight timer runs only now.
            if (running) powerBacklightManager?.resetTimer() else powerBacklightManager?.cancelTimer()
        },
        onScanMediaRequested = { startMediaLibraryScan() },
        clickFeedback = { clickFeedback() },
        localize = { t(it) }
    )

    storageController = StorageController(
        activity = this,
        rootView = layoutStorageMode as ViewGroup,
        songsProvider = { MainActivity.customLibrary },
        videosProvider = { videoFiles },
        clickFeedback = { clickFeedback() },
        localize = { t(it) }
    )

    brightnessController = BrightnessController(
        activity = this,
        rootView = layoutBrightnessMode as ViewGroup,
        clickFeedback = { clickFeedback() }
    )

    jellyfinUiController = JellyfinUiController(
        activity = this,
        rootView = layoutSettingsMode as ViewGroup,
        clickFeedback = { clickFeedback() },
        onOpenKeyboard = { target ->
            changeScreen(ScreenState.WifiKeyboard(target))
        },
        onRequestWifi = { _ -> changeScreen(MainActivity.STATE_WIFI) },
        isNetworkConnected = {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
            val net = cm?.activeNetworkInfo
            net != null && net.isConnected
        },
        onSyncFinished = { startMediaLibraryScan() },
        onScanVideos = { scanVideos() },
        isFilePlaying = { file ->
            AudioPlayerManager.getInstance().isFileCurrentlyPlaying(file)
        },
        customLibrary = MainActivity.customLibrary,
        trackNumberMap = MainActivity.trackNumberMap,
        localize = { t(it) },
        isCustomScanning = { isCustomScanning }
    )

    otaUpdateManager = OtaUpdateManager(
        activity = this,
        clickFeedback = { clickFeedback() },
        onRequireWifi = {
            isPendingUpdateCheckAfterWifi = true
            changeScreen(MainActivity.STATE_WIFI)
        },
        localize = { t(it) }
    )

    settingsController = SettingsController(
        activity = this,
        rootView = layoutSettingsMode as ViewGroup,
        eqPresetNames = eqPresetNames,
        createSettingRow = { title, value -> ListItemViewFactory.createSettingRow(this, title, value) },
        createTablerListButton = { icon, text, normalColor, focusedColor ->
            // Settings makes rows with a tick or with no icon only for single-choice lists (Language, Theme...):
            // keep a tick column on each row, so the text does not move, and the normal colours (no green).
            if (icon == null || icon === com.themoon.y1.views.TablerIcons.CHECK) {
                ListItemViewFactory.createCheckListButton(this, text, icon != null)
            } else {
                ListItemViewFactory.createTablerListButton(this, icon, text, normalColor, focusedColor)
            }
        },
        clickFeedback = { clickFeedback() },
        onNavigateBack = { NavigationManager.getInstance().popBackStack() },
        onNavigateToScreen = { screenId ->
            if (screenId == MainActivity.STATE_WIFI_KEYBOARD) {
                // Settings opens the keyboard only from the Last.fm Account row
                changeScreen(ScreenState.WifiKeyboard(KeyboardTarget.LastFmUsername))
            } else {
                changeScreen(screenId)
            }
        },
        onOpenEqualizer = { changeScreen(ScreenState.Equalizer) },
        onOpenJellyfin = { changeScreen(ScreenState.Jellyfin()) },
        onOpenUpdateCheck = { otaUpdateManager?.checkForUpdates() },
        onClearCacheConfirmed = {
            MediaLibraryManager.getInstance().musicLibrary.clear()
            MainActivity.customLibrary.clear()
            startMediaLibraryScan()
        },
        onApplyThemeBackground = {
            prefs.edit().putString(Prefs.BACKGROUND.key, "THEME_DEFAULT").apply()
            updateMainMenuBackground()
        },
        onClearCustomBackground = {
            prefs.edit().remove(Prefs.BACKGROUND.key).apply()
            updateMainMenuBackground()
        },
        onPickBackgroundFromStorage = {
            changeScreen(ScreenState.Browser(BrowserMode.Folder(StoragePaths.getPrimaryRoot(), pickImage = true)))
        },
        resetBacklightTimer = { powerBacklightManager?.applyBacklightSetting() },
        applySoundSetting = {
            updateScreenOffFeedback(true)
        },
        applyAlbumTiltSetting = {
            playerController.applyAlbumTiltSetting()
        },
        setShuffle = { on ->
            Prefs.SHUFFLE.setAsync(prefs, on)
            AudioPlayerManager.getInstance().setShuffleMode(on)
            updatePlayerUI()
        },
        setRepeatMode = { mode ->
            Prefs.REPEAT_MODE.setAsync(prefs, mode)
            AudioPlayerManager.getInstance().setRepeatMode(mode)
            updatePlayerUI()
        },
        localize = { t(it) }
    )

    NavigationManager.getInstance().apply {
        registerController(ScreenState.MainMenu::class.java, mainMenuController)
        registerController(ScreenState.Browser::class.java, browserController)
        registerController(ScreenState.CoverFlow::class.java, coverFlowController)
        registerController(ScreenState.Playlists::class.java, playlistController)
        registerController(ScreenState.M3uPlaylistSongs::class.java, playlistController)
        registerController(ScreenState.SmartPlaylistSongs::class.java, playlistController)
        registerController(ScreenState.Podcasts::class.java, podcastController)
        registerController(ScreenState.PodcastEpisodes::class.java, podcastController)
        registerController(ScreenState.PodcastManage::class.java, podcastController)
        registerController(ScreenState.Player::class.java, playerController)
        registerController(ScreenState.Settings::class.java, settingsController)
        registerController(ScreenState.Equalizer::class.java, equalizerController)
        registerController(ScreenState.FmRadio::class.java, fmRadioController)
        registerController(ScreenState.FmRadioMenu::class.java, fmRadioController)
        registerController(ScreenState.FmRadioSaved::class.java, fmRadioController)
        registerController(ScreenState.Jellyfin::class.java, jellyfinUiController)
        registerController(ScreenState.Bluetooth::class.java, bluetoothController)
        registerController(ScreenState.Wifi::class.java, wifiController)
        registerController(ScreenState.WifiKeyboard::class.java, wifiKeyboardController)
        registerController(ScreenState.Brightness::class.java, brightnessController)
        registerController(ScreenState.Storage::class.java, storageController)
        registerController(ScreenState.WebServer::class.java, webServerController)
        registerController(ScreenState.MusicQuiz::class.java, musicQuizController)
        registerController(ScreenState.Videos::class.java, videoBrowserController)
        registerController(ScreenState.VideoPlayer::class.java, videoPlayerController)
    }
}

/** iPod "Shuffle Songs": plays the whole music library with Shuffle on, from a random song. */
internal fun MainActivity.shuffleAllSongs() {
    val tracks = MainActivity.customLibrary.map { it.file }
    if (tracks.isEmpty()) {
        Notice.show(this, t("No playable audio files found."))
        return
    }
    Prefs.SHUFFLE.setAsync(prefs, true)
    AudioPlayerManager.getInstance().playTrackList(tracks, tracks.indices.random())
    changeScreen(MainActivity.STATE_PLAYER)
}

internal fun MainActivity.handleMainMenuAction(action: String) {
    when (action) {
        "OPEN_BROWSER", "MUSIC" -> {
            this.isAudiobookLibraryMode = false
            changeScreen(ScreenState.Browser(BrowserMode.Root))
        }
        "OPEN_VIDEOS", "VIDEOS" -> {
            changeScreen(ScreenState.Videos(VideoMode.Root))
        }
        "OPEN_COVER_FLOW", "PHOTOS" -> {
            changeScreen(MainActivity.STATE_COVERFLOW)
        }
        "OPEN_PODCASTS", "PODCASTS" -> {
            changeScreen(ScreenState.Podcasts)
        }
        "OPEN_MUSIC_QUIZ", "EXTRAS" -> {
            changeScreen(MainActivity.STATE_QUIZ)
            musicQuizController.showStartHub()
        }
        "OPEN_SETTINGS", "SETTINGS" -> {
            changeScreen(MainActivity.STATE_SETTINGS)
        }
        "OPEN_RADIO", "RADIO" -> {
            changeScreen(ScreenState.FmRadio)
        }
        "OPEN_WEBSERVER", "WEBSERVER" -> {
            changeScreen(MainActivity.STATE_WEBSERVER)
        }
        "OPEN_BLUETOOTH", "BLUETOOTH" -> {
            changeScreen(MainActivity.STATE_BLUETOOTH)
        }
        "OPEN_WIFI", "WIFI" -> {
            changeScreen(MainActivity.STATE_WIFI)
        }
        "OPEN_PLAY_ON" -> {
            com.themoon.y1.cast.PlayOnMenu.open(this)
        }
        "OPEN_AUDIOBOOKS" -> {
            this.isAudiobookLibraryMode = true
            changeScreen(ScreenState.Browser(BrowserMode.Audiobooks, audiobooks = true))
        }
        "OPEN_THEME_SETTINGS" -> {
            changeScreen(ScreenState.Settings(SettingsSubMode.Page("Theme")))
        }
        "OPEN_PLAYER" -> {
            if (currentPlaylist.isEmpty()) {
                Notice.show(this@handleMainMenuAction, t("No music is currently playing."))
            } else {
                changeScreen(MainActivity.STATE_PLAYER)
            }
        }
    }
}
