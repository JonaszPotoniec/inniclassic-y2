package com.themoon.y1

import android.content.Context
import android.graphics.drawable.ColorDrawable
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
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
import com.themoon.y1.controllers.settings.AppDrawerController
import com.themoon.y1.controllers.settings.BrightnessController
import com.themoon.y1.controllers.settings.EqualizerController
import com.themoon.y1.controllers.settings.ScreenRecorderController
import com.themoon.y1.views.ClassicMenuStyle
import com.themoon.y1.controllers.settings.SettingsController
import com.themoon.y1.controllers.storage.StorageController
import com.themoon.y1.controllers.sync.JellyfinUiController
import com.themoon.y1.controllers.videos.VideoBrowserController
import com.themoon.y1.controllers.videos.VideoPlayerController
import com.themoon.y1.managers.AudioPlayerManager
import com.themoon.y1.managers.MediaLibraryManager
import com.themoon.y1.managers.OtaUpdateManager
import com.themoon.y1.navigation.BrowserMode
import com.themoon.y1.navigation.NavigationManager
import com.themoon.y1.navigation.ScreenState
import com.themoon.y1.podcasts.PodcastDownloadManager
import com.themoon.y1.podcasts.PodcastRepository
import com.themoon.y1.views.ListItemViewFactory

internal fun MainActivity.initControllers() {
    val rootContent = findViewById<ViewGroup>(android.R.id.content)
    val containerBrowserItems = findViewById<LinearLayout>(R.id.container_browser_items)
    val tvBrowserPath = findViewById<TextView>(R.id.tv_browser_path)

    val hzIndexScroll = HorizontalScrollView(this).apply {
        isHorizontalScrollBarEnabled = false
        visibility = View.GONE
        setBackgroundColor(0x44000000.toInt())
    }
    val layoutIndexContainer = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        hzIndexScroll.addView(this)
    }

    val listContainer = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(hzIndexScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (40 * resources.displayMetrics.density).toInt()))
    }

    val listVirtualSongs = object : ListView(this) {
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
        startCoverPanAnimation = { _, _, _ -> },
        onLongPressScreenOff = View.OnLongClickListener {
            turnOffScreen()
            true
        },
        localize = { t(it) }
    )

    homeWidgetsController = HomeWidgetsController(
        context = this,
        widgetViewRegistry = mainMenuController.widgetViewRegistry,
        tvWidgetClock = mainMenuController.tvWidgetClock,
        widgetBatteryView = mainMenuController.widgetBatteryView,
        ivWidgetAlbum = mainMenuController.ivWidgetAlbum,
        tvWidgetAlbumTitle = mainMenuController.tvWidgetAlbumTitle,
        tvWidgetAlbumArtist = mainMenuController.tvWidgetAlbumArtist,
        ivAnimatedCover = mainMenuController.ivAnimatedCover,
        ivBrowserCover = findViewById(R.id.iv_browser_cover),
        frameBrowserCover = findViewById(R.id.frame_browser_cover),
        customAnalogClockView = mainMenuController.customAnalogClockView,
        customCircularBatteryView = mainMenuController.customCircularBatteryView,
        ivWidgetFocusImage = mainMenuController.ivWidgetFocusImage,
        fetchRandomIdleCoverAsync = { fetchRandomIdleCoverAsync() }
    )

    browserController = BrowserController(
        activity = this,
        rootView = layoutBrowserMode as ViewGroup,
        listVirtualSongs = listVirtualSongs,
        hzIndexScroll = hzIndexScroll,
        layoutIndexContainer = layoutIndexContainer,
        clickFeedback = { clickFeedback() },
        createListButtonWithIcon = { icon, text, color ->
            createListButton(text, icon, color)
        },
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
        onSearchRequested = {
            wifiKeyboardController.currentKeyboardMode = 3
            changeScreen(MainActivity.STATE_WIFI_KEYBOARD)
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
        localize = { t(it) }
    )

    playlistController = PlaylistController(
        activity = this,
        rootView = layoutBrowserMode as ViewGroup,
        listVirtualSongs = listVirtualSongs,
        hzIndexScroll = hzIndexScroll,
        layoutIndexContainer = layoutIndexContainer,
        musicRootFolder = rootFolder,
        isAudioFile = { MediaLibraryManager.getInstance().isAudioFile(it) },
        createListButtonWithIcon = { icon, text, color ->
            createListButton(text, icon, color)
        },
        clickFeedback = { clickFeedback() },
        onPlaylistSelected = { file ->
            changeScreen(ScreenState.M3uPlaylistSongs(file))
        },
        onNavigateBack = { NavigationManager.getInstance().popBackStack() },
        localize = { t(it) }
    )

    coverFlowController = CoverFlowController(
        activity = this,
        rootView = layoutBrowserMode as ViewGroup,
        listVirtualSongs = listVirtualSongs,
        uniqueAlbumList = uniqueAlbumList,
        clickFeedback = { clickFeedback() },
        onAlbumSelected = { album ->
            browserController.virtualQueryType = "COVER_FLOW_ALBUM"
            browserController.virtualQueryValue = album.album
            changeScreen(ScreenState.Browser(BrowserMode.VirtualSongs))
        },
        onNavigateBack = { NavigationManager.getInstance().popBackStack() },
        onRebuildUniqueAlbumList = { rebuildUniqueAlbumListInternal() },
        localize = { t(it) }
    )

    playerController = PlayerController(
        activity = this,
        rootView = layoutPlayerMode as ViewGroup,
        clickFeedback = { clickFeedback() },
        onAdjustVolume = { adjustVolume(it) },
        onTogglePlayback = { AudioPlayerManager.getInstance().playOrPauseMusic() },
        onNextTrack = { AudioPlayerManager.getInstance().nextTrack() },
        onPreviousTrack = { AudioPlayerManager.getInstance().prevTrack() },
        onNavigateBack = { NavigationManager.getInstance().popBackStack() },
        onBrowseAlbum = { album ->
            changeScreen(MainActivity.STATE_BROWSER)
            browserController.virtualQueryType = "ALBUM"
            browserController.virtualQueryValue = album
            browserController.buildVirtualSongs()
        },
        onBrowseArtist = { artist ->
            changeScreen(MainActivity.STATE_BROWSER)
            browserController.virtualQueryType = "ARTIST"
            browserController.virtualQueryValue = artist
            browserController.buildVirtualSongs()
        },
        onToggleFavorite = { toggleFavorite() },
        onAddToOnTheGo = { showAddToPlaylistDialog(it) },
        onToggleVisualizer = { toggleVisualizer() },
        localize = { t(it) }
    )

    val customBandLevels = IntArray(10)
    equalizerController = EqualizerController(
        activity = this,
        rootView = layoutSettingsMode as ViewGroup,
        customBandLevels = customBandLevels,
        eqPresetNames = eqPresetNames,
        createSettingRow = { title, value -> ListItemViewFactory.createSettingRow(this, title, value) },
        createCategoryHeader = { title ->
            val container = findViewById<LinearLayout>(R.id.container_settings_items)
            if (container != null) ListItemViewFactory.createCategoryHeader(this, container, title)
        },
        createListButton = { icon, text -> createListButton(text, icon) },
        createListButtonWithColor = { icon, text, color -> createListButton(text, icon, color) },
        clickFeedback = { clickFeedback() },
        rebootAudioEngine = { rebootAudioEngine() },
        onNavigateBack = { NavigationManager.getInstance().popBackStack() },
        localize = { t(it) }
    )

    fmRadioController = FmRadioController(
        activity = this,
        rootView = layoutSettingsMode as ViewGroup,
        createSettingRow = { title, value -> ListItemViewFactory.createSettingRow(this, title, value) },
        createCategoryHeader = { title ->
            val container = findViewById<LinearLayout>(R.id.container_settings_items)
            if (container != null) ListItemViewFactory.createCategoryHeader(this, container, title)
        },
        createListButton = { text -> ListItemViewFactory.createListButtonSimple(this, text) },
        createListButtonWithIcon = { icon, text -> createListButton(text, icon) },
        clickFeedback = { clickFeedback() },
        updateGlobalStatusPlayIcon = { updatePlayerUI() },
        onExitRadio = { NavigationManager.getInstance().popBackStack() },
        showLoadingPopup = { showLoadingPopup() },
        dismissLoadingOverlay = { dismissLoadingOverlay() },
        updateLoadingProgress = { _, _ -> },
        localize = { t(it) }
    )

    podcastController = PodcastController(
        activity = this,
        rootView = layoutBrowserMode as ViewGroup,
        containerBrowserItems = containerBrowserItems,
        listVirtualSongs = listVirtualSongs,
        tvBrowserPath = tvBrowserPath,
        repository = PodcastRepository.getInstance(),
        downloadManager = PodcastDownloadManager.getInstance(),
        onRequestSearch = {
            wifiKeyboardController.currentKeyboardMode = 1
            changeScreen(MainActivity.STATE_WIFI_KEYBOARD)
        },
        onNavigateToPlayer = { changeScreen(MainActivity.STATE_PLAYER) },
        createListButton = { icon, text, color -> createListButton(text, icon, color) },
        createTablerButton = { icon, text, textColor, iconColor ->
            ListItemViewFactory.createTablerListButtonWithRightIcon(
                context = this,
                iconPaths = icon,
                text = text,
                rightIconPaths = null,
                iconColor = iconColor,
                textColor = textColor
            )
        },
        clickFeedback = { clickFeedback() },
        localize = { t(it) },
        showLoadingPopup = { showLoadingPopup() },
        onHideLoadingPopup = { dismissLoadingOverlay() },
        onExitPodcasts = { NavigationManager.getInstance().popBackStack() }
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
        videosProvider = { videoFiles },
        onVideoSelected = { file ->
            changeScreen(ScreenState.VideoPlayer(file))
        },
        clickFeedback = { clickFeedback() },
        onNavigateBack = { NavigationManager.getInstance().popBackStack() },
        localize = { t(it) }
    )

    videoPlayerController = VideoPlayerController(
        activity = this,
        rootView = layoutVideoPlayerMode as ViewGroup,
        onExitRequested = { changeScreen(MainActivity.STATE_VIDEOS) },
        onAdjustVolume = { adjustVolume(it) },
        clickFeedback = { clickFeedback() },
        localize = { t(it) }
    )

    bluetoothController = BluetoothController(
        activity = this,
        rootView = layoutBluetoothMode as ViewGroup,
        createSettingRow = { title, value -> ListItemViewFactory.createSettingRow(this, title, value) },
        createTablerListButton = { iconPaths, text, rightIconPaths, iconColor, textColor ->
            ListItemViewFactory.createTablerListButtonWithRightIcon(
                context = this,
                iconPaths = iconPaths,
                text = text,
                rightIconPaths = rightIconPaths,
                iconColor = iconColor,
                textColor = textColor
            )
        },
        clickFeedback = { clickFeedback() },
        onNavigateBack = { NavigationManager.getInstance().popBackStack() },
        localize = { t(it) }
    )

    wifiController = WifiController(
        activity = this,
        rootView = layoutWifiMode as ViewGroup,
        createSettingRow = { title, value -> ListItemViewFactory.createSettingRow(this, title, value) },
        createTablerListButton = { iconPaths, text, rightIconPaths, iconColor, textColor ->
            ListItemViewFactory.createTablerListButtonWithRightIcon(
                context = this,
                iconPaths = iconPaths,
                text = text,
                rightIconPaths = rightIconPaths,
                iconColor = iconColor,
                textColor = textColor
            )
        },
        clickFeedback = { clickFeedback() },
        onConnectToSavedNetwork = { _, _ -> },
        onOpenWifiKeyboard = { ssid, isOpen ->
            wifiKeyboardController.targetWifiSsid = ssid
            wifiKeyboardController.isTargetWifiOpen = isOpen
            wifiKeyboardController.currentKeyboardMode = 0
            changeScreen(ScreenState.WifiKeyboard(ssid))
        },
        onNavigateBack = { NavigationManager.getInstance().popBackStack() },
        localize = { t(it) }
    )

    wifiKeyboardController = WifiKeyboardController(
        activity = this,
        rootView = layoutWifiKeyboard as ViewGroup,
        clickFeedback = { clickFeedback() },
        onExitKeyboard = { mode ->
            when (mode) {
                0 -> changeScreen(MainActivity.STATE_WIFI)
                1 -> changeScreen(MainActivity.STATE_BROWSER)
                2 -> changeScreen(MainActivity.STATE_SETTINGS)
                3 -> changeScreen(MainActivity.STATE_BROWSER)
                else -> changeScreen(MainActivity.STATE_SETTINGS)
            }
        },
        onSubmitSearchPodcast = { keyword ->
            changeScreen(MainActivity.STATE_BROWSER)
            podcastController.searchApplePodcasts(keyword)
        },
        onSubmitSearchLibrary = { keyword ->
            changeScreen(MainActivity.STATE_BROWSER)
            browserController.virtualQueryType = "SEARCH"
            browserController.virtualQueryValue = keyword
            browserController.buildVirtualSongs()
        },
        onSubmitLastFmUsername = { username ->
            prefs.edit().putString("lastfm_username", username).apply()
            changeScreen(MainActivity.STATE_SETTINGS)
        },
        onSubmitLastFmPassword = { password ->
            prefs.edit().putString("lastfm_password", password).apply()
            changeScreen(MainActivity.STATE_SETTINGS)
        },
        onSubmitJellyfinServerUrl = { url ->
            prefs.edit().putString("jellyfin_server_url", url).apply()
            changeScreen(MainActivity.STATE_SETTINGS)
            jellyfinUiController.buildSettingsUI()
        },
        onSubmitJellyfinUsername = { username ->
            prefs.edit().putString("jellyfin_username", username).apply()
            changeScreen(MainActivity.STATE_SETTINGS)
            jellyfinUiController.buildSettingsUI()
        },
        onSubmitJellyfinPassword = { password ->
            prefs.edit().putString("jellyfin_password", password).apply()
            changeScreen(MainActivity.STATE_SETTINGS)
            jellyfinUiController.buildSettingsUI()
        },
        onSubmitJellyfinToken = { token ->
            prefs.edit().putString("jellyfin_token", token).apply()
            changeScreen(MainActivity.STATE_SETTINGS)
            jellyfinUiController.buildSettingsUI()
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
            changeScreen(MainActivity.STATE_WIFI)
        },
        localize = { t(it) }
    )

    webServerController = WebServerController(
        activity = this,
        rootView = layoutWebServerMode as ViewGroup,
        onServerStateChanged = { running ->
            isServerRunning = running
        },
        onScanMediaRequested = { startMediaLibraryScan() },
        clickFeedback = { clickFeedback() },
        onNavigateBack = { NavigationManager.getInstance().popBackStack() },
        localize = { t(it) }
    )

    storageController = StorageController(
        activity = this,
        rootView = layoutStorageMode as ViewGroup,
        songsProvider = { MainActivity.customLibrary },
        videosProvider = { videoFiles },
        createSettingRow = { title, value -> ListItemViewFactory.createSettingRow(this, title, value) },
        clickFeedback = { clickFeedback() },
        onNavigateBack = { NavigationManager.getInstance().popBackStack() },
        localize = { t(it) }
    )

    brightnessController = BrightnessController(
        activity = this,
        rootView = layoutBrightnessMode as ViewGroup,
        clickFeedback = { clickFeedback() },
        onNavigateBack = { NavigationManager.getInstance().popBackStack() }
    )

    jellyfinUiController = JellyfinUiController(
        activity = this,
        rootView = layoutSettingsMode as ViewGroup,
        createSettingRow = { title, value -> ListItemViewFactory.createSettingRow(this, title, value) },
        createCategoryHeader = { title ->
            val container = findViewById<LinearLayout>(R.id.container_settings_items)
            if (container != null) ListItemViewFactory.createCategoryHeader(this, container, title)
        },
        createTablerButton = { icon, text ->
            ListItemViewFactory.createTablerListButton(this, icon, text, ThemeManager.getTextColorPrimary(), ThemeManager.getListButtonFocusedTextColor())
        },
        createDialogButton = { text -> ListItemViewFactory.createListButtonSimple(this, text) },
        clickFeedback = { clickFeedback() },
        onOpenKeyboard = { mode, _ ->
            wifiKeyboardController.currentKeyboardMode = mode
            changeScreen(MainActivity.STATE_WIFI_KEYBOARD)
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
            val am = AudioPlayerManager.getInstance()
            am.isPlaying() && currentPlaylist.isNotEmpty() && currentIndex in currentPlaylist.indices && currentPlaylist[currentIndex].absolutePath == file?.absolutePath
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
        createCategoryHeader = { title ->
            val container = findViewById<LinearLayout>(R.id.container_settings_items)
            if (container != null) ListItemViewFactory.createCategoryHeader(this, container, title)
        },
        createListButton = { text -> ListItemViewFactory.createListButtonSimple(this, text) },
        createTablerListButton = { icon, text, normalColor, focusedColor ->
            ListItemViewFactory.createTablerListButton(this, icon, text, normalColor, focusedColor)
        },
        clickFeedback = { clickFeedback() },
        onNavigateBack = { NavigationManager.getInstance().popBackStack() },
        onNavigateToScreen = { screenId ->
            backTargetForUtility = MainActivity.STATE_SETTINGS
            changeScreen(screenId)
        },
        onOpenEqualizer = { changeScreen(ScreenState.Equalizer) },
        onOpenJellyfin = { changeScreen(MainActivity.STATE_JELLYFIN) },
        onOpenUpdateCheck = { otaUpdateManager?.checkForUpdates() },
        onClearCacheConfirmed = {
            MediaLibraryManager.getInstance().musicLibrary.clear()
            MainActivity.customLibrary.clear()
            startMediaLibraryScan()
        },
        onApplyThemeBackground = {
            prefs.edit().putString("bg_path", "THEME_DEFAULT").apply()
            updateMainMenuBackground()
        },
        onClearCustomBackground = {
            prefs.edit().remove("bg_path").apply()
            updateMainMenuBackground()
        },
        onPickBackgroundFromStorage = {
            changeScreen(MainActivity.STATE_BROWSER)
            browserController.isPickingBackground = true
            browserController.buildFolderBrowserUI()
        },
        resetBacklightTimer = { powerBacklightManager?.resetTimer() },
        applySoundSetting = {
            isSoundEffectEnabled = prefs.getBoolean("sound_effect", true)
        },
        applyAlbumTiltSetting = {
            isAlbumTiltEnabled = prefs.getBoolean("album_tilt_effect", true)
        },
        toggleShuffleMode = {
            isShuffleMode = !isShuffleMode
            prefs.edit().putBoolean("shuffle", isShuffleMode).apply()
            AudioPlayerManager.getInstance().setShuffleMode(isShuffleMode)
            updatePlayerUI()
        },
        cycleRepeatMode = {
            repeatMode = (repeatMode + 1) % 3
            prefs.edit().putInt("repeat_mode", repeatMode).apply()
            updatePlayerUI()
        },
        getRepeatModeText = { mode ->
            when (mode) {
                1 -> t("All")
                2 -> t("One")
                else -> t("Off")
            }
        },
        localize = { t(it) }
    )

    appDrawerController = AppDrawerController(
        activity = this,
        rootView = layoutSettingsMode as ViewGroup,
        createListButton = { text -> ListItemViewFactory.createListButtonSimple(this, text) },
        clickFeedback = { clickFeedback() },
        onNavigateBack = { NavigationManager.getInstance().popBackStack() },
        localize = { t(it) }
    )

    screenRecorderController = ScreenRecorderController(
        activity = this,
        rootView = layoutSettingsMode as ViewGroup,
        createSettingRow = { title, value -> ListItemViewFactory.createSettingRow(this, title, value) },
        clickFeedback = { clickFeedback() },
        onNavigateBack = { NavigationManager.getInstance().popBackStack() },
        localize = { t(it) }
    )

    NavigationManager.getInstance().apply {
        registerController(ScreenState.MainMenu::class.java, mainMenuController)
        registerController(ScreenState.Browser::class.java, browserController)
        registerController(ScreenState.CoverFlow::class.java, coverFlowController)
        registerController(ScreenState.Playlists::class.java, playlistController)
        registerController(ScreenState.M3uPlaylistSongs::class.java, playlistController)
        registerController(ScreenState.Podcasts::class.java, podcastController)
        registerController(ScreenState.PodcastEpisodes::class.java, podcastController)
        registerController(ScreenState.PodcastManage::class.java, podcastController)
        registerController(ScreenState.Player::class.java, playerController)
        registerController(ScreenState.Settings::class.java, settingsController)
        registerController(ScreenState.Equalizer::class.java, equalizerController)
        registerController(ScreenState.FmRadio::class.java, fmRadioController)
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

internal fun MainActivity.handleMainMenuAction(action: String) {
    backTargetForUtility = MainActivity.STATE_MENU
    when (action) {
        "OPEN_BROWSER", "MUSIC" -> {
            changeScreen(MainActivity.STATE_BROWSER)
            browserController.buildFileBrowserUI()
        }
        "OPEN_VIDEOS", "VIDEOS" -> {
            changeScreen(MainActivity.STATE_VIDEOS)
            videoBrowserController.buildVideoListUI()
        }
        "OPEN_COVER_FLOW", "PHOTOS" -> {
            changeScreen(MainActivity.STATE_COVERFLOW)
        }
        "OPEN_PODCASTS", "PODCASTS" -> {
            changeScreen(ScreenState.Podcasts)
        }
        "OPEN_MUSIC_QUIZ", "EXTRAS" -> {
            changeScreen(MainActivity.STATE_QUIZ)
            musicQuizController.startSession()
        }
        "OPEN_SETTINGS", "SETTINGS" -> {
            changeScreen(MainActivity.STATE_SETTINGS)
            settingsController.buildSettingsUI()
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
        "OPEN_AUDIOBOOKS" -> {
            browserController.isAudiobookLibraryMode = true
            changeScreen(MainActivity.STATE_BROWSER)
            browserController.buildFileBrowserUI()
        }
        "OPEN_THEME_SETTINGS" -> {
            changeScreen(MainActivity.STATE_SETTINGS)
            settingsController.buildThemeSelectorUI()
        }
        "OPEN_PLAYER" -> {
            if (currentPlaylist.isEmpty()) {
                android.widget.Toast.makeText(this@handleMainMenuAction, t("No music is currently playing."), android.widget.Toast.LENGTH_SHORT).show()
            } else {
                changeScreen(MainActivity.STATE_PLAYER)
            }
        }
    }
}
