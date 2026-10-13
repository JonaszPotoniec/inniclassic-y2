package com.themoon.y1

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import android.media.audiofx.Virtualizer
import android.media.audiofx.Visualizer
import android.os.BatteryManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Vibrator
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import com.themoon.y1.controllers.ScreenController
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
import com.themoon.y1.controllers.settings.SettingsController
import com.themoon.y1.controllers.storage.StorageController
import com.themoon.y1.controllers.sync.JellyfinUiController
import com.themoon.y1.controllers.videos.VideoBrowserController
import com.themoon.y1.controllers.videos.VideoPlayerController
import com.themoon.y1.managers.AssetInstaller
import com.themoon.y1.managers.AudioPlayerManager
import com.themoon.y1.managers.BluetoothAvrcpManager
import com.themoon.y1.managers.ExternalSdMountMonitor
import com.themoon.y1.managers.FmRadioManager
import com.themoon.y1.managers.LanguageManager
import com.themoon.y1.managers.MediaLibraryManager
import com.themoon.y1.managers.OtaUpdateManager
import com.themoon.y1.managers.PowerBacklightManager
import com.themoon.y1.managers.UsbMassStorageController
import com.themoon.y1.managers.UsbStorageUiManager
import com.themoon.y1.managers.VirtualVolumeManager
import com.themoon.y1.models.SongItem
import com.themoon.y1.navigation.BrowserMode
import com.themoon.y1.navigation.InputRouter
import com.themoon.y1.navigation.NavigationManager
import com.themoon.y1.navigation.ScreenState
import com.themoon.y1.navigation.SettingsSubMode
import com.themoon.y1.views.ImageEffects
import com.themoon.y1.views.ListItemViewFactory
import java.io.File
import java.util.ArrayList
import java.util.HashMap
import java.util.HashSet

/**
 * Main coordinator Activity for the Innioasis Y2.
 * Wires together screen controllers, navigation, audio pipeline, and hardware input.
 */
class MainActivity : Activity(), InputRouter.VolumeAdjuster {

    companion object {
        const val STATE_MENU = 1
        const val STATE_BROWSER = 2
        const val STATE_PLAYER = 3
        const val STATE_SETTINGS = 4
        const val STATE_BLUETOOTH = 5
        const val STATE_WIFI = 6
        const val STATE_WIFI_KEYBOARD = 7
        const val STATE_BRIGHTNESS = 8
        const val STATE_STORAGE = 9
        const val STATE_WEBSERVER = 10
        const val STATE_QUIZ = 11
        const val STATE_VIDEOS = 12
        const val STATE_VIDEO_PLAYER = 13
        const val STATE_COVERFLOW = 14
        const val STATE_EQUALIZER = 15
        const val STATE_FM_RADIO = 16
        const val STATE_JELLYFIN = 17

        const val BROWSER_ROOT = 0
        const val BROWSER_FOLDER = 1
        const val BROWSER_VIRTUAL_CATEGORIES = 2
        const val BROWSER_VIRTUAL_SONGS = 3
        const val BROWSER_PLAYLISTS = 4
        const val BROWSER_FAVORITES = 5
        const val BROWSER_PODCASTS = 6

        const val VIRTUAL_VOLUME_STEPS = 30

        var instance: MainActivity? = null
        val customLibrary: ArrayList<SongItem> = ArrayList()
        val audiobookLibrary: ArrayList<SongItem> = ArrayList()
        val trackNumberMap: HashMap<String, Int> = HashMap()
    }

    var currentScreenState: Int = STATE_MENU
    var currentBrowserMode: Int = BROWSER_ROOT
    val currentPlaylist: ArrayList<File> = ArrayList()
    val originalPlaylist: ArrayList<File> = ArrayList()
    var currentIndex: Int = -1
    var lastTrackChangeTime: Long = 0L
    var isPausedByHand: Boolean = false
    var consecutiveErrorCount: Int = 0
    var lastAlbumArtBytes: ByteArray? = null
    var currentAlbumColor: Int = 0xFF555555.toInt()

    var isAudiobookLibraryMode: Boolean = false
    var isShuffleMode: Boolean = false
    var repeatMode: Int = 0
    var isSoundEffectEnabled: Boolean = true
    var isVibrationEnabled: Boolean = true
    var vibrationStrengthLevel: Int = 1
    var isScreenOffControlEnabled: Boolean = false
    var isUsbPromptEnabled: Boolean = true
    var isAutoFetchEnabled: Boolean = true
    var is24HourFormat: Boolean = false
    var isTimeInTitleBar: Boolean = false
    var isShowBatteryPercentage: Boolean = false
    var isSoundCheckEnabled: Boolean = false
    var isAlbumTiltEnabled: Boolean = true
    var volumeLimitMax: Int = -1
    var isSoftwareEqEnabled: Boolean = false

    var currentVirtualVolume: Int = -1
    var lastTargetVolume: Int = -1
    var lastTargetVolumeTime: Long = 0L
    var lastKnownSystemVolume: Int = -1
    var isScreenSleeping: Boolean = false
    var lastScreenOnTime: Long = 0L
    var isLongPressConsumed: Boolean = false
    var isMediaLongPressConsumed: Boolean = false
    var lastJellyfinLongClickTime: Long = 0L
    var isRadioUIShowing: Boolean = false
    var activePlayer: Int = 0

    var isFakeScreenOff: Boolean = false
    var isPlaylistOpenedFromLibrary: Boolean = false
    var isPodcastOpenedFromLibrary: Boolean = false
    var backTargetForPlayer: Int = STATE_BROWSER
    var backTargetForUtility: Int = STATE_SETTINGS
    var isPendingUpdateCheckAfterWifi: Boolean = false
    var isPendingJellyfinSyncAfterWifi: Boolean = false
    var isPendingJellyfinTestAfterWifi: Boolean = false
    var isPendingJellyfinFetchLibrariesAfterWifi: Boolean = false
    var isPendingJellyfinQuickConnectAfterWifi: Boolean = false
    var currentSettingsDepth: Int = 0
    var lastSettingsFocusIndex: Int = 0
    var lastMainMenuFocusIndex: Int = 0
    var currentTimeoutIndex: Int = 1
    var currentSystemBrightness: Int = 255
    var currentEqPresetIndex: Int = 0
    var currentEqProfile: String = "preset_0"
    var currentBassBoostStep: Int = 0
    var currentVirtualizerStep: Int = 0
    var currentCrossfeedStep: Int = 0
    val eqPresetNames: ArrayList<String> = ArrayList()

    var isCustomScanning: Boolean = false
    var isServerRunning: Boolean = false
    val blacklist: MutableSet<String> = HashSet()
    val favoritePaths: MutableSet<String> = HashSet()
    val virtualSongList: ArrayList<File> = ArrayList()
    val uniqueAlbumList: ArrayList<SongItem> = ArrayList()
    val videoFiles: ArrayList<File> = ArrayList()
    var virtualListSelectedIndex: Int = 0
    var virtualQueryType: String = ""
    var virtualQueryValue: String = ""
    var displayedPlayerPath: String? = null
    var nowPlayingBottomState: Int = 0
    var pendingSeekChanged: Boolean = false
    var isVisualizerShowing: Boolean = false
    var audioVisualizer: Visualizer? = null
    var equalizer: Equalizer? = null
    var bassBoost: BassBoost? = null
    var virtualizer: Virtualizer? = null
    var loudnessEnhancer: LoudnessEnhancer? = null
    var currentAudioSessionId: Int = -1
    val customBandLevels = IntArray(32)

    private var usbStateReceiverReady: Boolean = false
    internal val rootFolder: File by lazy { StoragePaths.getPrimaryRoot() }

    lateinit var prefs: SharedPreferences
    lateinit var audioManager: AudioManager
    lateinit var vibrator: Vibrator

    lateinit var layoutMainMenu: View
    lateinit var layoutBrowserMode: View
    lateinit var layoutPlayerMode: View
    lateinit var layoutSettingsMode: View
    lateinit var layoutBluetoothMode: View
    lateinit var layoutWifiMode: View
    lateinit var layoutWifiKeyboard: View
    lateinit var layoutBrightnessMode: View
    lateinit var layoutStorageMode: View
    lateinit var layoutWebServerMode: View
    lateinit var layoutMusicQuizMode: View
    lateinit var layoutVideosMode: View
    lateinit var layoutVideoPlayerMode: View
    lateinit var layoutVolumeOverlay: View

    var ivMainBg: ImageView? = null
    lateinit var ivAlbumArt: ImageView
    var ivAlbumReflection: ImageView? = null
    lateinit var ivPlayerBgBlur: ImageView
    lateinit var ivPauseOverlay: ImageView
    lateinit var tvPlayerTitle: TextView
    lateinit var tvPlayerArtist: TextView
    lateinit var tvPlayerAlbum: TextView
    lateinit var tvPlayerTrackCount: TextView
    lateinit var tvPlayerTimeCurrent: TextView
    lateinit var tvPlayerTimeTotal: TextView
    lateinit var playerProgress: ProgressBar

    var tvFastScrollLetter: TextView? = null
    var tvStatusBarTitle: TextView? = null
    var ivStatusWifi: ImageView? = null
    var ivStatusBluetooth: ImageView? = null
    var ivStatusHeadphone: ImageView? = null
    var tvStatusBattery: TextView? = null
    var volumeProgress: ProgressBar? = null
    var layoutLoadingOverlay: LinearLayout? = null
    var pbLoadingProgress: ProgressBar? = null
    var tvLoadingProgress: TextView? = null

    val usbStorageOverlay: View?
        get() = usbStorageManager.usbStorageOverlay

    lateinit var mainMenuController: MainMenuController
    var homeWidgetsController: HomeWidgetsController? = null
    lateinit var browserController: BrowserController
    lateinit var playlistController: PlaylistController
    lateinit var coverFlowController: CoverFlowController
    lateinit var playerController: PlayerController
    lateinit var equalizerController: EqualizerController
    lateinit var fmRadioController: FmRadioController
    lateinit var podcastController: PodcastController
    lateinit var musicQuizController: MusicQuizController
    lateinit var videoBrowserController: VideoBrowserController
    lateinit var videoPlayerController: VideoPlayerController
    lateinit var bluetoothController: BluetoothController
    lateinit var wifiController: WifiController
    lateinit var wifiKeyboardController: WifiKeyboardController
    lateinit var settingsController: SettingsController
    lateinit var appDrawerController: AppDrawerController
    lateinit var brightnessController: BrightnessController
    lateinit var screenRecorderController: ScreenRecorderController
    lateinit var storageController: StorageController
    lateinit var jellyfinUiController: JellyfinUiController
    lateinit var webServerController: WebServerController
    var otaUpdateManager: OtaUpdateManager? = null
    var externalSdMountMonitor: ExternalSdMountMonitor? = null
    var powerBacklightManager: PowerBacklightManager? = null

    internal val usbStorageManager by lazy {
        UsbStorageUiManager(
            activity = this,
            getExternalSdMountMonitor = { externalSdMountMonitor },
            isFmRadioActive = { activePlayer == 1 },
            stopFmRadio = { FmRadioManager.getInstance(this).powerDown() },
            localize = { t(it) }
        )
    }

    private val volumeHandler = Handler(Looper.getMainLooper())
    private val hideVolumeTask = Runnable {
        layoutVolumeOverlay.visibility = View.GONE
    }

    private val fastScrollHandler = Handler(Looper.getMainLooper())
    private val hideFastScrollTask = Runnable {
        tvFastScrollLetter?.visibility = View.GONE
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        instance = this

        prefs = getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator

        window.setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN)
        setContentView(R.layout.activity_main)

        AssetInstaller.installBundledLanguages(this, prefs)
        AssetInstaller.installBundledThemes(this, prefs)
        LanguageManager.getInstance(this).loadAvailableLanguages()

        val themeDir = File(filesDir, "themes")
        ThemeManager.loadThemesFromStorage(themeDir)
        val savedThemeName = prefs.getString("app_theme_name", null)
            ?: prefs.getString("current_theme_name", "Classic Light")
        val themeIdx = ThemeManager.findThemeIndexByName(savedThemeName)
        if (themeIdx != -1) ThemeManager.setThemeIndex(themeIdx)

        loadPreferences()
        initViewBindings()
        applyRootSystemSettings()
        initControllers()

        powerBacklightManager = PowerBacklightManager.getInstance(this).apply {
            backlightTimerIndex = currentTimeoutIndex
            setTimeoutAction { turnOffScreen() }
        }

        AudioPlayerManager.getInstance().initPlayer(applicationContext)
        BluetoothAvrcpManager.getInstance(this).init()

        if (ExternalSdMountMonitor.hasSecondarySlot() && !ExternalSdMountMonitor.isSecondaryReady()) {
            android.util.Log.i("InniClassic", "Secondary storage not ready yet at onCreate, will load library once mounted")
        } else {
            loadLibraryCache()
        }

        externalSdMountMonitor = ExternalSdMountMonitor(this).apply {
            setListener(object : ExternalSdMountMonitor.Listener {
                override fun onSecondaryStorageReady() {
                    runOnUiThread {
                        if (customLibrary.isEmpty()) {
                            loadLibraryCache()
                        }
                        if (customLibrary.isEmpty() && !isCustomScanning) {
                            startMediaLibraryScan()
                        }
                    }
                }
            })
            start()
        }

        Thread({
            if (UsbMassStorageController.isEnabled()) {
                externalSdMountMonitor?.setSuspended(true)
                runOnUiThread { showUsbStorageOverlay() }
            }
        }, "y1-ums-init-check").start()

        registerReceivers()
        updateMainMenuBackground()
        changeScreen(STATE_MENU)
        Handler(Looper.getMainLooper()).postDelayed({ usbStateReceiverReady = true }, 2000)
    }

    fun t(key: String): String = LanguageManager.getInstance(this).t(key)

    fun clickFeedback() {
        if (isVibrationEnabled) {
            vibrator.vibrate((vibrationStrengthLevel * 15).toLong())
        }
        if (isSoundEffectEnabled) {
            audioManager.playSoundEffect(AudioManager.FX_KEY_CLICK)
        }
    }

    fun changeScreen(state: Int) {
        val screenState = when (state) {
            STATE_MENU -> ScreenState.MainMenu
            STATE_BROWSER -> ScreenState.Browser(BrowserMode.Root)
            STATE_PLAYER -> ScreenState.Player
            STATE_SETTINGS -> ScreenState.Settings()
            STATE_BLUETOOTH -> ScreenState.Bluetooth
            STATE_WIFI -> ScreenState.Wifi
            STATE_WIFI_KEYBOARD -> ScreenState.WifiKeyboard("")
            STATE_BRIGHTNESS -> ScreenState.Brightness
            STATE_STORAGE -> ScreenState.Storage
            STATE_WEBSERVER -> ScreenState.WebServer
            STATE_QUIZ -> ScreenState.MusicQuiz
            STATE_VIDEOS -> ScreenState.Videos
            STATE_COVERFLOW -> ScreenState.Browser(BrowserMode.CoverFlow)
            STATE_EQUALIZER -> ScreenState.Settings(SettingsSubMode.Equalizer5Band)
            STATE_FM_RADIO -> ScreenState.Settings(SettingsSubMode.Radio)
            STATE_JELLYFIN -> ScreenState.Settings(SettingsSubMode.Jellyfin())
            else -> ScreenState.MainMenu
        }
        changeScreen(screenState)
    }

    fun changeScreen(state: ScreenState) {
        currentScreenState = when (state) {
            is ScreenState.MainMenu -> STATE_MENU
            is ScreenState.Browser -> {
                when (state.mode) {
                    is BrowserMode.CoverFlow -> STATE_COVERFLOW
                    else -> STATE_BROWSER
                }
            }
            is ScreenState.Player -> STATE_PLAYER
            is ScreenState.Settings -> {
                when (state.subMode) {
                    is SettingsSubMode.Equalizer5Band, is SettingsSubMode.Equalizer10Band -> STATE_EQUALIZER
                    is SettingsSubMode.Radio, is SettingsSubMode.RadioSavedChannels -> STATE_FM_RADIO
                    is SettingsSubMode.Jellyfin -> STATE_JELLYFIN
                    else -> STATE_SETTINGS
                }
            }
            is ScreenState.Bluetooth -> STATE_BLUETOOTH
            is ScreenState.Wifi -> STATE_WIFI
            is ScreenState.WifiKeyboard -> STATE_WIFI_KEYBOARD
            is ScreenState.Brightness -> STATE_BRIGHTNESS
            is ScreenState.Storage -> STATE_STORAGE
            is ScreenState.WebServer -> STATE_WEBSERVER
            is ScreenState.MusicQuiz -> STATE_QUIZ
            is ScreenState.Videos -> STATE_VIDEOS
            is ScreenState.VideoPlayer -> STATE_VIDEO_PLAYER
        }
        NavigationManager.getInstance().navigateTo(state)
        updateStatusBarTitle()
    }

    fun getActiveScreenController(): ScreenController? = NavigationManager.getInstance().activeController

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        return InputRouter.getInstance().dispatchKeyEvent(
            event = event,
            context = this,
            isFakeScreenOff = isFakeScreenOff,
            onWakeFromFakeScreenOff = Runnable { wakeFromFakeScreenOff() },
            onResetBacklightTimer = Runnable { powerBacklightManager?.resetTimer() },
            isUsbStorageActive = usbStorageManager.isStorageOverlayActive,
            onStopUsbStorage = Runnable { stopUsbStorage() },
            onAdjustVolume = this,
            activeController = getActiveScreenController()
        ) || super.dispatchKeyEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        return InputRouter.getInstance().onKeyDown(
            keyCode = keyCode,
            event = event,
            activeController = getActiveScreenController()
        ) || super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        return InputRouter.getInstance().onKeyUp(
            keyCode = keyCode,
            event = event,
            activeController = getActiveScreenController()
        ) || super.onKeyUp(keyCode, event)
    }

    override fun onKeyLongPress(keyCode: Int, event: KeyEvent): Boolean {
        return InputRouter.getInstance().onKeyLongPress(
            keyCode = keyCode,
            event = event,
            activeController = getActiveScreenController()
        ) || super.onKeyLongPress(keyCode, event)
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        powerBacklightManager?.resetTimer()
        return super.dispatchTouchEvent(event)
    }

    override fun adjustVolume(isUp: Boolean) {
        val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        if (currentVirtualVolume < 0) {
            val curSys = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            currentVirtualVolume = VirtualVolumeManager.systemToVirtualVolume(curSys, maxVol, currentVirtualVolume)
        }
        val target = if (isUp) {
            (currentVirtualVolume + 1).coerceAtMost(VIRTUAL_VOLUME_STEPS)
        } else {
            (currentVirtualVolume - 1).coerceAtLeast(0)
        }
        setVolumeLevel(AudioManager.STREAM_MUSIC, target, maxVol)
        clickFeedback()
    }

    fun setVolumeLevel(targetVirtualVol: Int) {
        val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        setVolumeLevel(AudioManager.STREAM_MUSIC, targetVirtualVol, maxVol)
    }

    fun setVolumeLevel(stream: Int, targetVirtualVol: Int, sysMaxVol: Int) {
        currentVirtualVolume = targetVirtualVol
        val gain = VirtualVolumeManager.virtualToSoftwareGain(targetVirtualVol)
        VirtualVolumeManager.getInstance().setMasterGain(gain)
        val sysTarget = VirtualVolumeManager.virtualToSystemVolume(targetVirtualVol, sysMaxVol)
        lastTargetVolume = sysTarget
        lastTargetVolumeTime = System.currentTimeMillis()
        try {
            audioManager.setStreamVolume(stream, sysTarget, 0)
        } catch (_: Exception) {}
        showDynamicVolumeOverlay(currentVirtualVolume, VIRTUAL_VOLUME_STEPS)
    }

    fun showDynamicVolumeOverlay(currentVol: Int, maxVol: Int) {
        if (isScreenSleeping) return
        volumeProgress?.let {
            it.max = maxVol
            it.progress = currentVol
        }
        layoutVolumeOverlay.visibility = View.VISIBLE
        volumeHandler.removeCallbacks(hideVolumeTask)
        volumeHandler.postDelayed(hideVolumeTask, 1500)
    }

    fun updatePlayerUI() {
        if (::playerController.isInitialized) {
            playerController.updatePlayerUI()
        }
        val am = AudioPlayerManager.getInstance()
        sendBluetoothMetaToCar()
        BluetoothAvrcpManager.getInstance(this).updatePlaybackState(am.isPlaying())
    }

    fun sendBluetoothMetaToCar() {
        if (currentPlaylist.isNotEmpty() && currentIndex in currentPlaylist.indices) {
            val file = currentPlaylist[currentIndex]
            val song = customLibrary.firstOrNull { it.file.absolutePath == file.absolutePath }
            val title = song?.title ?: file.nameWithoutExtension
            val artist = song?.artist ?: t("Unknown Artist")
            val album = song?.album ?: t("Unknown Album")
            val bmp = (ivAlbumArt.drawable as? BitmapDrawable)?.bitmap
            BluetoothAvrcpManager.getInstance(this).updateMetadata(title, artist, album, bmp)
        }
    }

    fun refreshNowPlayingPreview() {
        homeWidgetsController?.refreshWidgets()
    }

    fun findFolderCover(folder: File?): File? = MediaLibraryManager.getInstance().findFolderCover(folder)

    fun applyGaussianBlur(original: Bitmap?): Bitmap? = ImageEffects.applyGaussianBlur(this, original)

    fun updateMainMenuBackground() = updateMainMenuBackgroundInternal()
    fun applyCachedCoverArt(imagePath: String) = applyCachedCoverArtInternal(imagePath)
    fun fetchRandomIdleCoverAsync() = fetchRandomIdleCoverAsyncInternal()
    fun setupVisualizer() = setupVisualizerInternal()
    fun toggleVisualizer() = toggleVisualizerInternal()
    fun rebootAudioEngine() = rebootAudioEngineInternal()
    fun loadLibraryCache() = loadLibraryCacheInternal()
    fun startMediaLibraryScan() = startMediaLibraryScanInternal()
    fun rebuildUniqueAlbumList() = rebuildUniqueAlbumListInternal()
    fun scanVideos() = scanVideosInternal()

    fun showFastScrollLetter(letter: String) {
        val tv = tvFastScrollLetter ?: return
        tv.text = letter
        tv.setTextColor(ThemeManager.getTextColorPrimary())
        tv.visibility = View.VISIBLE
        fastScrollHandler.removeCallbacks(hideFastScrollTask)
        fastScrollHandler.postDelayed(hideFastScrollTask, 700)
    }

    fun updateAudioQualityInfo(audioFile: File) {}

    fun getSongRating(path: String): Int = prefs.getInt("rating_$path", 0)

    fun setSongRating(path: String, rating: Int) {
        prefs.edit().putInt("rating_$path", rating).commit()
    }

    fun toggleFavorite() {
        if (currentPlaylist.isEmpty() || currentIndex !in currentPlaylist.indices) return
        val currentFile = currentPlaylist[currentIndex]
        val path = currentFile.absolutePath
        if (favoritePaths.contains(path)) {
            favoritePaths.remove(path)
            Toast.makeText(this, t("Removed from Favorites."), Toast.LENGTH_SHORT).show()
        } else {
            favoritePaths.add(path)
            Toast.makeText(this, t("Added to Favorites."), Toast.LENGTH_SHORT).show()
        }
        prefs.edit().putStringSet("favorite_songs_set", favoritePaths).apply()
    }

    fun showLoadingPopup(title: String? = null) {
        pbLoadingProgress?.visibility = View.VISIBLE
        tvLoadingProgress?.text = title ?: ""
        layoutLoadingOverlay?.apply {
            setBackgroundColor(0xBB000000.toInt())
            alpha = 1.0f
            visibility = View.VISIBLE
        }
    }

    fun dismissLoadingOverlay() {
        layoutLoadingOverlay?.visibility = View.GONE
    }

    fun showAddToPlaylistDialog(songFile: File) {
        playlistController.showAddToPlaylistDialog(songFile)
    }

    fun showRemoveFromPlaylistDialog(songFile: File) {
        playlistController.showRemoveFromPlaylistDialog(songFile)
    }

    fun showRemoveFromFavoritesDialog(songFile: File) {
        favoritePaths.remove(songFile.absolutePath)
        prefs.edit().putStringSet("favorite_songs_set", favoritePaths).apply()
        Toast.makeText(this, t("Removed from Favorites."), Toast.LENGTH_SHORT).show()
        browserController.buildVirtualSongsForFavorites()
    }

    fun showPodcastActionDialog(title: String, audioUrl: String, channelTitle: String) {
        podcastController.showActionDialog(title, audioUrl, "", channelTitle)
    }

    fun showPodcastActionDialog(title: String, audioUrl: String, imageUrl: String, channelTitle: String) {
        podcastController.showActionDialog(title, audioUrl, imageUrl, channelTitle)
    }

    fun fetchTrackInfoFromInternet(track: File, originalQuery: String, hasValidTags: Boolean, title: String?, artist: String?) {
        // Tag lookups handled via MediaLibraryManager
    }

    fun turnOffScreen() {
        if (isScreenSleeping) return
        isScreenSleeping = true
        powerBacklightManager?.cancelTimer()
        val am = AudioPlayerManager.getInstance()
        val fm = FmRadioManager.getInstance(this)
        val isAudioActive = am.isPlaying() || fm.isPowerUp || isServerRunning
        if (isAudioActive) {
            isFakeScreenOff = true
            pbLoadingProgress?.visibility = View.GONE
            tvLoadingProgress?.text = ""
            powerBacklightManager?.enterFakeScreenOff(window, layoutLoadingOverlay) { sleep ->
                autoManageWifiPower(sleep)
            }
        }
    }

    fun wakeFromFakeScreenOff() {
        if (!isFakeScreenOff) return
        isFakeScreenOff = false
        isScreenSleeping = false
        lastScreenOnTime = System.currentTimeMillis()
        powerBacklightManager?.wakeFromFakeScreenOff(window, layoutLoadingOverlay, currentSystemBrightness) { sleep ->
            autoManageWifiPower(sleep)
        }
        powerBacklightManager?.resetTimer()
    }

    private fun autoManageWifiPower(isGoingToSleep: Boolean) {
        val isRadioActive = FmRadioManager.getInstance(this).isPowerUp
        val pbm = powerBacklightManager ?: PowerBacklightManager.getInstance(this)
        pbm.autoManageWifiPower(isGoingToSleep, isServerRunning, isRadioActive)
    }

    private fun updateStatusBarTitle() {
        tvStatusBarTitle?.text = when (currentScreenState) {
            STATE_MENU -> t("iPod")
            STATE_BROWSER -> t("Music")
            STATE_PLAYER -> t("Now Playing")
            STATE_SETTINGS -> t("Settings")
            STATE_BLUETOOTH -> t("Bluetooth")
            STATE_WIFI -> t("Wi-Fi")
            STATE_WIFI_KEYBOARD -> t("Keyboard")
            STATE_BRIGHTNESS -> t("Brightness")
            STATE_STORAGE -> t("About")
            STATE_WEBSERVER -> t("Upload")
            STATE_QUIZ -> t("Music Quiz")
            STATE_VIDEOS -> t("Videos")
            STATE_COVERFLOW -> t("Cover Flow")
            STATE_EQUALIZER -> t("Equalizer")
            STATE_FM_RADIO -> t("Radio")
            STATE_JELLYFIN -> t("Jellyfin")
            else -> t("iPod")
        }
    }

    fun showUsbStorageDialog() = usbStorageManager.showUsbStorageDialog()

    fun startUsbStorage() = usbStorageManager.startUsbStorage()

    fun stopUsbStorage() = usbStorageManager.stopUsbStorage()

    fun showUsbStorageOverlay() = usbStorageManager.showUsbStorageOverlay()

    fun hideUsbStorageOverlay() = usbStorageManager.hideUsbStorageOverlay()

    fun createButtonBackground(color: Int): GradientDrawable =
        ListItemViewFactory.createButtonBackground(this, color)

    fun createFocusedButtonBackground(): GradientDrawable =
        ListItemViewFactory.createFocusedButtonBackground(this)

    fun createListButtonWithIcon(iconUnicode: String, textLabel: String, customColor: Int = 0): View =
        ListItemViewFactory.createListButtonWithIcon(this, iconUnicode, textLabel, customColor) { showFastScrollLetter(it) }

    fun setupAudiobookProgress(btn: View, pos: Int, dur: Int) {
        ListItemViewFactory.setupAudiobookProgress(this, btn, pos, dur) { showFastScrollLetter(it) }
    }

    fun applyProgressBackground(btn: View, currentMs: Int, totalMs: Int, isFocused: Boolean) {
        ListItemViewFactory.applyProgressBackground(this, btn, currentMs, totalMs, isFocused)
    }

    internal fun createListButton(text: String, icon: String, color: Int? = null): View =
        createListButtonWithIcon(icon, text, color ?: 0)

    private val systemStatusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            when (action) {
                Intent.ACTION_BATTERY_CHANGED -> {
                    val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                    val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                    if (level != -1 && scale != -1) {
                        val pct = ((level / scale.toFloat()) * 100).toInt()
                        tvStatusBattery?.text = "$pct%"
                    }
                }
                Intent.ACTION_HEADSET_PLUG -> {
                    val state = intent.getIntExtra("state", 0)
                    ivStatusHeadphone?.visibility = if (state == 1) View.VISIBLE else View.GONE
                }
                Intent.ACTION_TIME_TICK, Intent.ACTION_TIME_CHANGED -> {
                    homeWidgetsController?.refreshWidgets()
                }
                "android.hardware.usb.action.USB_STATE" -> {
                    if (usbStateReceiverReady && isUsbPromptEnabled) {
                        val connected = intent.getBooleanExtra("connected", false)
                        if (connected && !UsbMassStorageController.isEnabled()) {
                            showUsbStorageDialog()
                        }
                    }
                }
            }
        }
    }

    private fun registerReceivers() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(Intent.ACTION_HEADSET_PLUG)
            addAction(Intent.ACTION_TIME_TICK)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction("android.hardware.usb.action.USB_STATE")
        }
        registerReceiver(systemStatusReceiver, filter)
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(systemStatusReceiver)
        } catch (_: Exception) {}
        externalSdMountMonitor?.stop()
        audioVisualizer?.release()
        audioVisualizer = null
        AudioPlayerManager.getInstance().releasePlayer()
        NavigationManager.getInstance().reset()
        instance = null
    }

    class MediaBtnReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val event = intent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT) ?: return
            if (event.action != KeyEvent.ACTION_DOWN || event.repeatCount != 0) return
            val am = AudioPlayerManager.getInstance()
            when (event.keyCode) {
                KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, 85 -> {
                    am.playOrPauseMusic()
                }
                KeyEvent.KEYCODE_MEDIA_NEXT, 87 -> {
                    am.nextTrack()
                }
                KeyEvent.KEYCODE_MEDIA_PREVIOUS, 88 -> {
                    am.prevTrack()
                }
            }
        }
    }
}
