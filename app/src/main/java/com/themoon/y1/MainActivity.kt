package com.themoon.y1

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.GradientDrawable
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.media.AudioManager
import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import android.media.audiofx.Virtualizer
import android.media.audiofx.Visualizer
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.Vibrator
import android.provider.Settings
import android.view.KeyEvent
import android.view.MotionEvent
import com.themoon.y1.views.BatteryIconView
import com.themoon.y1.views.ClassicMenuStyle
import com.themoon.y1.views.IpodClassicLayout
import com.themoon.y1.views.TablerIcons
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
import com.themoon.y1.managers.BluetoothPairingAgent
import com.themoon.y1.managers.ExternalSdMountMonitor
import com.themoon.y1.managers.FmRadioManager
import com.themoon.y1.managers.LanguageManager
import com.themoon.y1.managers.MediaLibraryManager
import com.themoon.y1.managers.OtaUpdateManager
import com.themoon.y1.managers.BacklightTimer
import com.themoon.y1.managers.PowerBacklightManager
import com.themoon.y1.managers.PowerMenuInterceptor
import com.themoon.y1.managers.UsbMassStorageController
import com.themoon.y1.managers.UsbStorageUiManager
import com.themoon.y1.managers.VirtualVolumeManager
import com.themoon.y1.managers.AudioOutput
import com.themoon.y1.managers.ClickVolume
import com.themoon.y1.managers.SafeVolumeBypasser
import com.themoon.y1.managers.VolumeCurves
import com.themoon.y1.managers.VolumeMemory
import com.themoon.y1.managers.VolumeSplit
import com.themoon.y1.managers.VolumeSteps
import com.themoon.y1.managers.VolumeStore
import com.themoon.y1.models.SongItem
import com.themoon.y1.models.TvSeries
import com.themoon.y1.models.VideoItem
import com.themoon.y1.navigation.BrowserMode
import com.themoon.y1.navigation.InputRouter
import com.themoon.y1.navigation.LetterJump
import com.themoon.y1.navigation.NavigationManager
import com.themoon.y1.navigation.ScreenState
import com.themoon.y1.navigation.SettingsSubMode
import com.themoon.y1.views.ImageEffects
import com.themoon.y1.views.ListItemViewFactory
import java.io.File
import java.util.ArrayList
import java.util.Date
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

        const val VIRTUAL_VOLUME_STEPS = VolumeSteps.STEPS

        var instance: MainActivity? = null

        // Whether this app process holds a mute on STREAM_SYSTEM (see setSystemSoundsMuted)
        @Volatile
        private var isSystemStreamMuted = false

        val customLibrary: ArrayList<SongItem> = ArrayList()
        val audiobookLibrary: ArrayList<SongItem> = ArrayList()
        val trackNumberMap: HashMap<String, Int> = HashMap()
    }

    var currentScreenState: Int = STATE_MENU
    var currentBrowserMode: Int = BROWSER_ROOT
    val currentPlaylist: ArrayList<File> = ArrayList()
    val originalPlaylist: ArrayList<File> = ArrayList()
    var currentIndex: Int = -1
    var isPausedByHand: Boolean = false
    var consecutiveErrorCount: Int = 0
    var lastAlbumArtBytes: ByteArray? = null

    var isAudiobookLibraryMode: Boolean = false
    var isShuffleMode: Boolean = false
    var repeatMode: Int = 0
    var isSoundEffectEnabled: Boolean = true
    var isVibrationEnabled: Boolean = true
    var vibrationStrengthLevel: Int = 1
    var isScreenOffControlEnabled: Boolean = false
    var isUsbPromptEnabled: Boolean
        get() = if (::prefs.isInitialized) prefs.getBoolean("usb_storage_prompt", true) else true
        set(value) {
            if (::prefs.isInitialized) {
                prefs.edit().putBoolean("usb_storage_prompt", value).apply()
            }
        }
    var is24HourFormat: Boolean = ClockSettings.DEFAULT_24_HOUR_FORMAT
    var isTimeInTitleBar: Boolean = ClockSettings.DEFAULT_TIME_IN_TITLE
    var isShowBatteryPercentage: Boolean = false
    var isSoundCheckEnabled: Boolean = false
    var isAlbumTiltEnabled: Boolean = true
    var volumeLimitMax: Int = -1
    var isSoftwareEqEnabled: Boolean = false

    var currentVirtualVolume: Int = -1
    var lastTargetVolume: Int = -1
    var isScreenSleeping: Boolean = false
        set(value) {
            field = value
            refreshVisualizerCapture()
        }
    var isLongPressConsumed: Boolean = false
    var isMediaLongPressConsumed: Boolean = false

    var isFakeScreenOff: Boolean = false

    /** True from onResume to onStop: no other app or activity covers the screen. */
    private var isActivityVisible: Boolean = false

    /** The user can see this activity: it is not behind another one, and the screen is not off or blanked. */
    val isOnScreen: Boolean
        get() = isActivityVisible && !isScreenSleeping

    var backTargetForUtility: Int = STATE_SETTINGS
    var isPendingUpdateCheckAfterWifi: Boolean = false
    var lastSettingsFocusIndex: Int = 0
    val eqPresetNames: ArrayList<String> = ArrayList()

    var isCustomScanning: Boolean = false
    var mediaLibraryScanCount: Int = 0
    var isLibraryCacheLoading: Boolean = false
    val libraryCacheLoadCallbacks: ArrayList<(Boolean) -> Unit> = ArrayList()
    var isPendingRestorePlayback: Boolean = false
    var isServerRunning: Boolean = false
    val blacklist: MutableSet<String> = HashSet()
    val favoritePaths: MutableSet<String> = HashSet()
    val virtualSongList: ArrayList<File> = ArrayList()
    val uniqueAlbumList: ArrayList<SongItem> = ArrayList()
    val videoFiles: ArrayList<File> = ArrayList()
    val movieVideos: ArrayList<VideoItem> = ArrayList()
    val tvSeriesList: ArrayList<TvSeries> = ArrayList()
    var virtualListSelectedIndex: Int = 0
    var virtualQueryType: String = ""
    var virtualQueryValue: String = ""
    var displayedPlayerPath: String? = null
    var nowPlayingBottomState: Int = 0
    var pendingSeekChanged: Boolean = false
    /** Spectrum in place of the album art on Now Playing (Settings → Spectrum), kept in AppPrefs. */
    var isVisualizerShowing: Boolean
        get() = ::prefs.isInitialized && prefs.getBoolean(com.themoon.y1.controllers.player.NowPlayingSpectrum.PREF, false)
        set(value) {
            prefs.edit().putBoolean(com.themoon.y1.controllers.player.NowPlayingSpectrum.PREF, value).apply()
        }
    var audioVisualizer: Visualizer? = null
    var equalizer: Equalizer? = null
    var bassBoost: BassBoost? = null
    var virtualizer: Virtualizer? = null
    var loudnessEnhancer: LoudnessEnhancer? = null
    var currentAudioSessionId: Int = -1

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
    var tvStatusTime: TextView? = null
    var tvStatusBattery: TextView? = null
    var batteryIconView: BatteryIconView? = null
    var volumeProgress: ProgressBar? = null
    var layoutLoadingOverlay: LinearLayout? = null
    var pbLoadingProgress: ProgressBar? = null
    var tvLoadingProgress: TextView? = null

    val usbStorageOverlay: View?
        get() = usbStorageManager.usbStorageOverlay

    lateinit var mainMenuController: MainMenuController
    var homeWidgetsController: HomeWidgetsController? = null
    lateinit var browserController: BrowserController
    fun isBrowserControllerInitialized(): Boolean = ::browserController.isInitialized
    lateinit var playlistController: PlaylistController
    lateinit var coverFlowController: CoverFlowController
    fun isCoverFlowControllerInitialized(): Boolean = ::coverFlowController.isInitialized
    lateinit var playerController: PlayerController
    lateinit var equalizerController: EqualizerController
    lateinit var fmRadioController: FmRadioController
    lateinit var podcastController: PodcastController
    lateinit var musicQuizController: MusicQuizController
    lateinit var videoBrowserController: VideoBrowserController
    fun isVideosControllerInitialized(): Boolean = ::videoBrowserController.isInitialized
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
    private var powerMenuInterceptor: PowerMenuInterceptor? = null
    private var pairingAgent: BluetoothPairingAgent? = null
    var powerBacklightManager: PowerBacklightManager? = null

    internal val usbStorageManager by lazy {
        UsbStorageUiManager(
            activity = this,
            getExternalSdMountMonitor = { externalSdMountMonitor },
            isFmRadioActive = { FmRadioManager.getInstance(this).isPowerUp },
            stopFmRadio = { FmRadioManager.getInstance(this).powerDown() },
            localize = { t(it) },
            clickFeedback = { clickFeedback() },
            createListButtonWithIcon = { icon, text ->
                ListItemViewFactory.createListButtonWithIcon(this, icon, text)
            }
        )
    }

    private val volumeHandler = Handler(Looper.getMainLooper())
    private val hideVolumeTask = Runnable {
        layoutVolumeOverlay.visibility = View.GONE
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        InniClassicApplication.installConscrypt()
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
        com.themoon.y1.managers.AutoThemeManager.applyAtStart(prefs)

        loadPreferences()
        initViewBindings()
        applyRootSystemSettings()
        initControllers()
        NavigationManager.getInstance().setTransitionListener(object : NavigationManager.StateChangeListener {
            override fun onScreenTransition(from: ScreenState, to: ScreenState) {
                syncCurrentScreenState(to)
                updateStatusBarTitle()
                applyTheme()
            }
        })

        val savedBacklightIdx = prefs.getInt(BacklightTimer.PREF_INDEX, BacklightTimer.DEFAULT_INDEX)
        powerBacklightManager = PowerBacklightManager.getInstance(this).apply {
            backlightTimerIndex = savedBacklightIdx
            appHoldsScreenOn = { isServerRunning }
            setTimeoutAction { turnOffScreen() }
            applyBacklightSetting()
        }

        AudioPlayerManager.getInstance().initPlayer(applicationContext)
        BluetoothAvrcpManager.getInstance(this).init()
        restoreVolumeForOutput(force = true)
        AndroidVolumeChangeReceiver.register(applicationContext)
        com.themoon.y1.managers.ClickSound.load()
        FmRadioManager.getInstance(this).apply {
            volumeGate = { ready -> runOnUiThread { prepareVolumeForFm(ready) } }
            onPoweredDown = { runOnUiThread { leaveFmVolume() } }
        }

        var playbackRestored = false
        if (ExternalSdMountMonitor.hasSecondarySlot() && !ExternalSdMountMonitor.isSecondaryReady()) {
            android.util.Log.i("InniClassic", "Secondary storage not ready yet at onCreate, will load library once mounted")
            playbackRestored = AudioPlayerManager.getInstance().restoreLastPlaybackState(this)
        } else {
            runMigrationsAsync {
                loadLibraryCache {
                    if (!playbackRestored) {
                        playbackRestored = AudioPlayerManager.getInstance().restoreLastPlaybackState(this)
                    }
                    val mgr = com.themoon.y1.migrations.MigrationManager.getInstance(applicationContext)
                    if (mgr.isPendingMediaRescan() && !isCustomScanning) {
                        scanVideos()
                        startMediaLibraryScan()
                    }
                }
            }
        }

        externalSdMountMonitor = ExternalSdMountMonitor(this).apply {
            setListener(object : ExternalSdMountMonitor.Listener {
                override fun onSecondaryStorageReady() {
                    StoragePaths.invalidate()
                    runMigrationsAsync {
                        scanVideos()
                        val onLibraryReady = {
                            val mgr = com.themoon.y1.migrations.MigrationManager.getInstance(applicationContext)
                            if ((customLibrary.isEmpty() || mgr.isPendingMediaRescan()) && !isCustomScanning) {
                                startMediaLibraryScan()
                            }
                            if (!playbackRestored || isPendingRestorePlayback || currentPlaylist.isEmpty()) {
                                isPendingRestorePlayback = false
                                playbackRestored = AudioPlayerManager.getInstance().restoreLastPlaybackState(this@MainActivity)
                            }
                        }
                        if (customLibrary.isEmpty()) {
                            loadLibraryCache { onLibraryReady() }
                        } else {
                            onLibraryReady()
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

        com.themoon.y1.views.LowBatteryWarningView.install(this)
        registerReceivers()
        // A long press of the Power button shows our Power screen, not Android's power menu
        powerMenuInterceptor = PowerMenuInterceptor(this) { showPowerMenu() }.also { it.register() }
        com.themoon.y1.managers.CellRadioManager.start(this)

        try {
            val initBattery = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            if (initBattery != null) {
                val initL = initBattery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val initS = initBattery.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                val initStatus = initBattery.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                val initPlugged = initBattery.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1)
                val initCharging = (initStatus == BatteryManager.BATTERY_STATUS_CHARGING
                        || initStatus == BatteryManager.BATTERY_STATUS_FULL
                        || initPlugged > 0)
                if (initL >= 0 && initS > 0) {
                    val pct = ((initL / initS.toFloat()) * 100).toInt()
                    tvStatusBattery?.text = "$pct%"
                    batteryIconView?.setBatteryLevel(pct, initCharging)
                }
            }
        } catch (_: Exception) {}

        try {
            if (audioManager.isWiredHeadsetOn) {
                ivStatusHeadphone?.visibility = View.VISIBLE
            }
            val ba = BluetoothAdapter.getDefaultAdapter()
            if (ba != null && ba.isEnabled) {
                ivStatusBluetooth?.visibility = View.VISIBLE
                queryStatusBarBluetooth()
            }
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            if (wm != null && wm.isWifiEnabled) {
                ivStatusWifi?.visibility = View.VISIBLE
                val info = wm.connectionInfo
                val isConnected = (info != null && info.networkId != -1)
                updateStatusBarWifi(isConnected)
            }
        } catch (_: Exception) {}

        updateStatusBarClock()
        updateMainMenuBackground()
        changeScreen(STATE_MENU)
        applyTheme()
        com.themoon.y1.managers.AutoThemeManager.start(this)
        Handler(Looper.getMainLooper()).postDelayed({ usbStateReceiverReady = true }, 2000)
    }

    override fun onResume() {
        super.onResume()
        isActivityVisible = true
        isScreenSleeping = false
        powerBacklightManager?.resetTimer()
        updateScreenOffFeedback(true)
        updateStatusBarClock()
        applyTheme()
    }

    override fun onPause() {
        super.onPause()
        AudioPlayerManager.getInstance().savePlaybackState(sync = false)
    }

    override fun onStop() {
        super.onStop()
        isActivityVisible = false
        refreshVisualizerCapture()
        AudioPlayerManager.getInstance().savePlaybackState(sync = true)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // After a boot the window is in touch mode until the first key, so requestFocus() does nothing
        // and the first screen shows no selected row. Leave touch mode before the first layout.
        val decor = window.decorView
        if (decor.isInTouchMode) decor.requestFocusFromTouch()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // At a device boot the window can get focus in touch mode again (after onAttachedToWindow), which
        // clears the selected row. Then leave touch mode and let the screen select its row again.
        // Only in touch mode with no focus, so a row that the user selected keeps the focus.
        val decor = window.decorView
        if (hasFocus && decor.isInTouchMode && currentFocus == null) {
            decor.requestFocusFromTouch()
            getActiveScreenController()?.onRefreshUI()
        }
    }

    fun t(key: String): String = LanguageManager.getInstance(this).t(key)

    fun clickFeedback() {
        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
        val isScreenOn = if (Build.VERSION.SDK_INT >= 20) {
            pm?.isInteractive ?: true
        } else {
            pm?.isScreenOn ?: true
        }
        if (!isScreenOffControlEnabled && (!isScreenOn || isScreenSleeping)) {
            return
        }
        if (isVibrationEnabled) {
            com.themoon.y1.managers.ButtonVibration.vibrate(vibrator, vibrationStrengthLevel)
        }
        if (isSoundEffectEnabled) {
            // Settings → Click Volume: a level of its own, not the music volume (see ClickVolume).
            val level = prefs.getInt(ClickVolume.PREF, ClickVolume.DEFAULT)
            val output = currentAudioOutput ?: AudioOutput.SPEAKER
            // Our own click on STREAM_MUSIC: STREAM_SYSTEM stays muted, because the ROM clicks there on each press (see ClickSound)
            com.themoon.y1.managers.ClickSound.play(
                ClickVolume.clickGain(level, output, currentVirtualVolume.coerceAtLeast(0))
            )
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
            STATE_BRIGHTNESS -> ScreenState.Brightness
            STATE_STORAGE -> ScreenState.Storage
            STATE_WEBSERVER -> ScreenState.WebServer
            STATE_QUIZ -> ScreenState.MusicQuiz
            STATE_VIDEOS -> ScreenState.Videos()
            STATE_COVERFLOW -> ScreenState.CoverFlow
            STATE_EQUALIZER -> ScreenState.Equalizer
            STATE_FM_RADIO -> ScreenState.FmRadio
            STATE_JELLYFIN -> ScreenState.Jellyfin
            else -> ScreenState.MainMenu
        }
        changeScreen(screenState)
    }

    fun syncCurrentScreenState(state: ScreenState) {
        currentScreenState = when (state) {
            is ScreenState.MainMenu -> STATE_MENU
            is ScreenState.CoverFlow -> STATE_COVERFLOW
            is ScreenState.Browser -> {
                when (state.mode) {
                    is BrowserMode.CoverFlow -> STATE_COVERFLOW
                    else -> STATE_BROWSER
                }
            }
            is ScreenState.Playlists, is ScreenState.M3uPlaylistSongs, is ScreenState.SmartPlaylistSongs -> STATE_BROWSER
            is ScreenState.Podcasts, is ScreenState.PodcastEpisodes, is ScreenState.PodcastManage -> STATE_BROWSER
            is ScreenState.Equalizer -> STATE_EQUALIZER
            is ScreenState.FmRadio -> STATE_FM_RADIO
            is ScreenState.Jellyfin -> STATE_JELLYFIN
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
    }

    fun changeScreen(state: ScreenState) {
        val targetState = when {
            state is ScreenState.Browser && state.mode is BrowserMode.CoverFlow -> ScreenState.CoverFlow
            state is ScreenState.Settings && state.subMode is SettingsSubMode.Jellyfin -> ScreenState.Jellyfin
            else -> state
        }
        if (::browserController.isInitialized) browserController.endLetterJump()
        tvFastScrollLetter?.animate()?.cancel()
        tvFastScrollLetter?.visibility = View.GONE
        syncCurrentScreenState(targetState)
        NavigationManager.getInstance().navigateTo(targetState)
        updateStatusBarTitle()
        applyTheme()
    }

    fun getActiveScreenController(): ScreenController? = NavigationManager.getInstance().activeController

    /** The Power screen (Restart, Power Off). Back goes to the screen that was open before. */
    fun showPowerMenu() {
        wakeFromFakeScreenOff()
        // From Settings, Back comes back into the open group (SettingsController.onEnter)
        if (NavigationManager.getInstance().currentState is ScreenState.Settings) backTargetForUtility = STATE_SETTINGS
        changeScreen(ScreenState.Settings(SettingsSubMode.Power))
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        return InputRouter.getInstance().dispatchKeyEvent(
            event = event,
            context = this,
            isFakeScreenOff = isFakeScreenOff,
            isScreenOffControlEnabled = isScreenOffControlEnabled,
            onWakeFromFakeScreenOff = Runnable { wakeFromFakeScreenOff() },
            onResetBacklightTimer = Runnable { powerBacklightManager?.resetTimer() },
            isUsbStorageActive = usbStorageManager.isStorageOverlayActive,
            onStopUsbStorage = Runnable {
                clickFeedback()
                stopUsbStorage()
            },
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

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        // The router acts on Back; never call super.onBackPressed() because MainActivity is the HOME launcher.
        InputRouter.getInstance().goBack(getActiveScreenController())
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        powerBacklightManager?.resetTimer()
        return super.dispatchTouchEvent(event)
    }

    /** The volume step of each output, in AppPrefs (see [VolumeSteps]). */
    private val volumeMemory: VolumeMemory by lazy {
        VolumeMemory(object : VolumeStore {
            override fun contains(key: String) = prefs.contains(key)
            override fun getInt(key: String, default: Int) = prefs.getInt(key, default)
            override fun putInt(key: String, value: Int) {
                prefs.edit().putInt(key, value).apply()
            }
        }).also { it.migrate() }
    }

    /** The output of the current volume step; null before the first [restoreVolumeForOutput]. */
    var currentAudioOutput: AudioOutput? = null
    private val audioOutputHandler = Handler(Looper.getMainLooper())
    private val audioOutputCheckTask = Runnable { restoreVolumeForOutput() }

    fun detectAudioOutput(): AudioOutput = try {
        @Suppress("DEPRECATION")
        AudioOutput.current(audioManager.isWiredHeadsetOn, audioManager.isBluetoothA2dpOn)
    } catch (_: Exception) {
        AudioOutput.SPEAKER
    }

    /**
     * Sets the saved step of the output that plays now, when the output changed (or always with [force]).
     * Each output keeps its own step, as Android does with its index.
     */
    fun restoreVolumeForOutput(force: Boolean = false) {
        val output = detectAudioOutput()
        if (!force && output == currentAudioOutput) return
        currentAudioOutput = output
        volumeLimitMax = volumeMemory.limitStep()
        val step = volumeMemory.stepFor(output) { audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) }
        applyVolumeStep(step, output, showOverlay = false)
    }

    /** After a plug or A2DP event, checks the output again a few times: Android changes the route with a delay. */
    fun scheduleAudioOutputCheck() {
        audioOutputHandler.removeCallbacks(audioOutputCheckTask)
        for (delayMs in longArrayOf(300L, 1200L, 3000L)) {
            audioOutputHandler.postDelayed(audioOutputCheckTask, delayMs)
        }
    }

    override fun adjustVolume(isUp: Boolean) {
        restoreVolumeForOutput()
        val current = currentVirtualVolume.coerceAtLeast(0)
        val target = if (isUp) current + 1 else current - 1
        setVolumeLevel(VolumeSteps.clamp(target, volumeLimitMax))
        clickFeedback()
    }

    /**
     * With the screen off, Android gets the volume keys (only while music plays) and moves the music index. Each push
     * then changes our step by one, as with the screen on, and the index goes back to the top
     * ([VolumeSteps.stepFromIndexChange]). Screen-Off Control does not apply: Android acts on these keys in any case.
     */
    fun onAndroidVolumeChange(prevIndex: Int, newIndex: Int) {
        if (volumeSplit() != VolumeSplit.SOFTWARE || lastTargetVolume < 0) return
        val maxIndex = try { audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC) } catch (_: Exception) { return }
        val delta = VolumeSteps.stepFromIndexChange(prevIndex, newIndex, lastTargetVolume, maxIndex)
        if (delta == 0) return
        android.util.Log.d("VolumeKeys", "Android volume key (screen off): music index $prevIndex -> $newIndex, one step " +
            if (delta > 0) "up" else "down")
        adjustVolume(delta > 0)
    }

    /** Android's music index changes (hidden AudioManager.VOLUME_CHANGED_ACTION). Registered once for the process. */
    private object AndroidVolumeChangeReceiver : BroadcastReceiver() {
        private var registered = false

        fun register(context: Context) {
            if (registered) return
            registered = true
            context.registerReceiver(this, IntentFilter("android.media.VOLUME_CHANGED_ACTION"))
        }

        override fun onReceive(context: Context, intent: Intent) {
            if (intent.getIntExtra("android.media.EXTRA_VOLUME_STREAM_TYPE", -1) != AudioManager.STREAM_MUSIC) return
            val prev = intent.getIntExtra("android.media.EXTRA_PREV_VOLUME_STREAM_VALUE", -1)
            val now = intent.getIntExtra("android.media.EXTRA_VOLUME_STREAM_VALUE", -1)
            if (prev < 0 || now < 0) return
            instance?.onAndroidVolumeChange(prev, now)
        }
    }

    fun setVolumeLevel(targetVirtualVol: Int, showOverlay: Boolean = true) {
        val output = currentAudioOutput ?: detectAudioOutput().also { currentAudioOutput = it }
        applyVolumeStep(targetVirtualVol, output, showOverlay)
    }

    /** Sets the Volume Limit (a step; -1 = off) and lowers the volume to it when necessary. */
    fun setVolumeLimit(limitStep: Int) {
        volumeLimitMax = limitStep
        prefs.edit().putInt(VolumeSteps.PREF_LIMIT, limitStep).apply()
        if (limitStep > 0 && currentVirtualVolume > limitStep) {
            setVolumeLevel(limitStep, showOverlay = false)
        }
    }

    private val volumeIndexHandler = Handler(Looper.getMainLooper())
    private var lastVolumeIndexWriteMs = 0L

    /**
     * Music uses [VolumeSplit.SOFTWARE]: the Android index stays at the top, so the button click and other system
     * sounds do not change with the music volume. FM radio uses [VolumeSplit.INDEX] while it is on.
     */
    private var fmVolumeMode = false
    private val fmVolumeHandler = Handler(Looper.getMainLooper())

    private fun volumeSplit(): VolumeSplit = if (fmVolumeMode) VolumeSplit.INDEX else VolumeSplit.SOFTWARE

    /**
     * Before the FM audio starts: lowers the index to the index split of the current step (one index at a time),
     * then runs [ready]. The FM audio may not go through the player gain, so it starts only at the lower index.
     */
    fun prepareVolumeForFm(ready: Runnable) {
        fmVolumeMode = true
        val output = currentAudioOutput ?: detectAudioOutput().also { currentAudioOutput = it }
        val ms = applyVolumeStep(currentVirtualVolume.coerceAtLeast(0), output, showOverlay = false)
        fmVolumeHandler.removeCallbacksAndMessages(null)
        fmVolumeHandler.postDelayed(ready, ms + 50L)
    }

    /** FM radio is off: back to the software split (the gain first, then the index goes up). */
    fun leaveFmVolume() {
        fmVolumeHandler.removeCallbacksAndMessages(null)
        if (!fmVolumeMode) return
        fmVolumeMode = false
        val output = currentAudioOutput ?: detectAudioOutput().also { currentAudioOutput = it }
        applyVolumeStep(currentVirtualVolume.coerceAtLeast(0), output, showOverlay = false)
    }

    /** Sets one index and gain of a [VolumeSteps.indexSchedule]. Lowers the louder of the two first, so no short peak comes between them. */
    private fun writeVolumeChange(change: VolumeSteps.IndexChange) {
        val current = try { audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) } catch (_: Exception) { -1 }
        if (change.index < current &&
            android.os.SystemClock.uptimeMillis() - lastVolumeIndexWriteMs < VolumeSteps.MIN_DECREASE_GAP_MS
        ) {
            // The main thread was busy and the writes came late, too close together: plan again from this index.
            val output = currentAudioOutput ?: return
            applyVolumeStep(currentVirtualVolume.coerceAtLeast(0), output, showOverlay = false)
            return
        }
        val gainFirst = change.index > current
        if (gainFirst) VirtualVolumeManager.getInstance().setMasterGain(change.gain)
        if (change.index != current) {
            try {
                SafeVolumeBypasser.setStreamVolumeWithBypass(this, audioManager, AudioManager.STREAM_MUSIC, change.index, 0)
            } catch (_: Exception) {}
            lastVolumeIndexWriteMs = android.os.SystemClock.uptimeMillis()
        }
        if (!gainFirst) VirtualVolumeManager.getInstance().setMasterGain(change.gain)
    }

    /** Sets [step] for [output]. Returns the time (ms) until the last index write of the plan. */
    private fun applyVolumeStep(step: Int, output: AudioOutput, showOverlay: Boolean): Long {
        val target = step.coerceIn(0, VIRTUAL_VOLUME_STEPS)
        currentVirtualVolume = target
        volumeMemory.save(output, target)
        val sysMaxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val curve = VolumeCurves.forOutput(output)
        val split = volumeSplit()
        val setting = VolumeSteps.settingFor(target, curve, sysMaxVol, split)
        lastTargetVolume = setting.index
        val fromIndex = try { audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) } catch (_: Exception) { 0 }
        val plan = VolumeSteps.indexSchedule(
            fromIndex, target, curve, sysMaxVol, android.os.SystemClock.uptimeMillis() - lastVolumeIndexWriteMs, split
        )
        volumeIndexHandler.removeCallbacksAndMessages(null)
        var atMs = 0L
        for (change in plan) {
            atMs += change.delayMs
            if (atMs == 0L) writeVolumeChange(change) else volumeIndexHandler.postDelayed({ writeVolumeChange(change) }, atMs)
        }
        if (showOverlay) showDynamicVolumeOverlay(currentVirtualVolume, VIRTUAL_VOLUME_STEPS)
        return atMs
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
        IpodClassicLayout.refreshPlayIcon(this)
        val am = AudioPlayerManager.getInstance()
        sendBluetoothMetaToCar()
        BluetoothAvrcpManager.getInstance(this).updatePlaybackState(am.isPlaying())
    }

    fun sendBluetoothMetaToCar() {
        if (currentPlaylist.isNotEmpty() && currentIndex in currentPlaylist.indices) {
            val file = currentPlaylist[currentIndex]
            val song = com.themoon.y1.managers.LibrarySongs.songByPath(file.absolutePath)
            val title = song?.title ?: file.nameWithoutExtension
            val artist = song?.artist ?: t("Unknown Artist")
            val album = song?.album ?: t("Unknown Album")
            val bmp = (ivAlbumArt.drawable as? BitmapDrawable)?.bitmap
            BluetoothAvrcpManager.getInstance(this).updateMetadata(file.absolutePath, title, artist, album, bmp)
        }
    }

    fun refreshNowPlayingPreview() {
        homeWidgetsController?.let { hwc ->
            hwc.currentTrackTitle = tvPlayerTitle?.text?.toString() ?: ""
            hwc.currentTrackArtist = tvPlayerArtist?.text?.toString() ?: ""
            hwc.lastAlbumArtBytes = lastAlbumArtBytes
            if (isAudiobookLibraryMode && lastAlbumArtBytes != null) {
                hwc.setAudiobookCoverBytes(lastAlbumArtBytes)
            }
            hwc.refreshWidgets()
        }
    }

    fun findFolderCover(folder: File?): File? = MediaLibraryManager.getInstance().findFolderCover(folder)

    fun applyGaussianBlur(original: Bitmap?): Bitmap? = ImageEffects.applyGaussianBlur(this, original)

    fun updateMainMenuBackground() = updateMainMenuBackgroundInternal()
    fun applyTheme() = applyThemeInternal()
    fun applyCachedCoverArt(imagePath: String) = applyCachedCoverArtInternal(imagePath)
    fun fetchRandomIdleCoverAsync() = fetchRandomIdleCoverAsyncInternal()
    fun setupVisualizer() = setupVisualizerInternal()
    fun toggleVisualizer() = toggleVisualizerInternal()
    fun refreshVisualizerCapture() {
        if (::playerController.isInitialized) playerController.refreshVisualizerCapture()
    }
    fun rebootAudioEngine() = rebootAudioEngineInternal()
    fun loadLibraryCache(onLoaded: ((Boolean) -> Unit)? = null) = loadLibraryCacheInternal(onLoaded)
    fun startMediaLibraryScan() = startMediaLibraryScanInternal()
    fun rebuildUniqueAlbumList() = rebuildUniqueAlbumListInternal()
    fun scanVideos() = scanVideosInternal()

    fun runMigrationsAsync(onComplete: (() -> Unit)? = null) {
        Thread({
            try {
                val mgr = com.themoon.y1.migrations.MigrationManager.getInstance(applicationContext)
                val count = mgr.applyPendingMigrations()
                com.themoon.y1.migrations.Migration003MigrateVideoDirectories.updateSyncLedgerPaths()
                if (count > 0 && mgr.migrationContext?.isMediaRescanRequested == true) {
                    runOnUiThread {
                        scanVideos()
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("InniClassic", "Error applying migrations", e)
            } finally {
                onComplete?.let { runOnUiThread { it() } }
            }
        }, "y1-migrations").start()
    }

    /**
     * Shows the letter box in the middle of the screen with the letter of letter mode (BrowserController.jumpLetter).
     * Rows call this when they get focus; when the wheel steps row by row there is no jump letter, and nothing shows.
     */
    @Suppress("UNUSED_PARAMETER")
    fun showFastScrollLetter(rawText: String) {
        if (currentScreenState != STATE_BROWSER || !::browserController.isInitialized) return
        val letter = browserController.jumpLetter ?: return
        showJumpLetter(letter)
    }

    /**
     * Shows [letter] in the letter box. It stays until [hideFastScrollLetter]: the screen hides it at the end of
     * letter mode ([LetterJump.Mode]). The box gets its theme colours when it comes on; after that, only a new
     * letter changes it.
     */
    fun showJumpLetter(letter: String) {
        val tv = tvFastScrollLetter ?: return
        if (tv.visibility != View.VISIBLE) {
            tv.setTextColor(ThemeManager.getListButtonFocusedTextColor())
            tv.typeface = ThemeManager.getCustomFontBold()
            tv.background = GradientDrawable().apply {
                setColor(ThemeManager.getListButtonFocusedBg() or 0xFF000000.toInt())
                cornerRadius = 15f * resources.displayMetrics.density
            }
        }
        if (tv.text?.contentEquals(letter) != true) tv.text = letter
        tv.animate().cancel()
        tv.alpha = 1f
        tv.visibility = View.VISIBLE
    }

    /** Fades out the letter box (the end of letter mode). */
    fun hideFastScrollLetter() {
        val tv = tvFastScrollLetter ?: return
        if (tv.visibility != View.VISIBLE) return
        tv.animate().alpha(0f).setDuration(200).withEndAction { tv.visibility = View.GONE }
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
        if (title != null) {
            tvLoadingProgress?.text = title
        } else if (tvLoadingProgress?.text.isNullOrEmpty()) {
            tvLoadingProgress?.text = t("Preparing to scan...\nPlease wait.")
        }
        layoutLoadingOverlay?.apply {
            setBackgroundColor(0xDD000000.toInt())
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

    fun turnOffScreen() {
        if (isScreenSleeping) return
        isScreenSleeping = true
        updateScreenOffFeedback(false)
        powerBacklightManager?.cancelTimer()
        // A real screen off also while music or FM radio plays: both hold a wake lock and continue.
        // Only the web server holds the screen on (FLAG_KEEP_SCREEN_ON), so the screen is blanked for it.
        if (BacklightTimer.useFakeScreenOff(isServerRunning)) {
            isFakeScreenOff = true
            pbLoadingProgress?.visibility = View.GONE
            tvLoadingProgress?.text = ""
            powerBacklightManager?.enterFakeScreenOff(window, layoutLoadingOverlay) { sleep ->
                autoManageWifiPower(sleep)
            }
        } else {
            val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
            val isScreenOn = if (Build.VERSION.SDK_INT >= 20) {
                pm?.isInteractive ?: true
            } else {
                pm?.isScreenOn ?: true
            }
            if (isScreenOn) {
                try {
                    Runtime.getRuntime().exec(arrayOf("su", "-c", "input keyevent 26"))
                } catch (_: Exception) {}
            }
        }
    }

    fun wakeFromFakeScreenOff() {
        if (!isFakeScreenOff) return
        isFakeScreenOff = false
        isScreenSleeping = false
        // The Brightness screen writes the system value, so restore that one.
        val brightness = try {
            Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS, 255)
        } catch (_: Exception) { 255 }
        powerBacklightManager?.wakeFromFakeScreenOff(window, layoutLoadingOverlay, brightness) { sleep ->
            autoManageWifiPower(sleep)
        }
        powerBacklightManager?.resetTimer()
        updateScreenOffFeedback(true)
    }

    fun updateScreenOffFeedback(screenOn: Boolean) {
        try {
            if (screenOn) {
                // Always muted: the ROM plays Android's click on STREAM_SYSTEM on each key press (see ClickSound)
                setSystemSoundsMuted(true)
                Settings.System.putInt(
                    contentResolver,
                    Settings.System.SOUND_EFFECTS_ENABLED,
                    // Always off: else Android also plays its own view click (default gain, -6 dB) on each
                    // performClick and list item click, with ours and much louder. Our click (clickFeedback:
                    // ClickSound) does not read this setting, so Button Sound still works.
                    0
                )
                Settings.System.putInt(
                    contentResolver,
                    Settings.System.HAPTIC_FEEDBACK_ENABLED,
                    // Always off: else Android also vibrates on each key press, before our vibration on the
                    // release. Our vibration (clickFeedback) does not read this setting.
                    0
                )
            } else {
                if (!isScreenOffControlEnabled) {
                    setSystemSoundsMuted(true)
                    Settings.System.putInt(contentResolver, Settings.System.SOUND_EFFECTS_ENABLED, 0)
                    Settings.System.putInt(contentResolver, Settings.System.HAPTIC_FEEDBACK_ENABLED, 0)
                }
            }
        } catch (_: Exception) {}
    }

    /**
     * On API 19 setStreamMute is reference-counted per AudioManager: each mute needs its own unmute, and
     * extra mutes keep the stream muted. So mute or unmute only when the state changes, always through the
     * application's AudioManager, so that a recreated activity uses the same mute. Android removes the
     * mute when the app process dies.
     */
    private fun setSystemSoundsMuted(muted: Boolean) {
        if (muted == isSystemStreamMuted) return
        val am = applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.setStreamMute(AudioManager.STREAM_SYSTEM, muted)
        isSystemStreamMuted = muted
    }

    private fun autoManageWifiPower(isGoingToSleep: Boolean) {
        val isRadioActive = FmRadioManager.getInstance(this).isPowerUp
        val pbm = powerBacklightManager ?: PowerBacklightManager.getInstance(this)
        pbm.autoManageWifiPower(isGoingToSleep, isServerRunning, isRadioActive)
    }

    private fun updateStatusBarTitle() {
        tvStatusBarTitle?.text = when (currentScreenState) {
            STATE_MENU -> t("iPod")
            STATE_BROWSER -> if (::browserController.isInitialized) browserController.getCurrentBrowserTitle() else t("Music")
            STATE_PLAYER -> t("Now Playing")
            STATE_SETTINGS -> t("Settings")
            STATE_BLUETOOTH -> t("Bluetooth")
            STATE_WIFI -> t("Wi-Fi")
            STATE_WIFI_KEYBOARD -> t("Keyboard")
            STATE_BRIGHTNESS -> t("Brightness")
            STATE_STORAGE -> t("About")
            STATE_WEBSERVER -> t("Upload")
            STATE_QUIZ -> t("Music Quiz")
            STATE_VIDEOS -> if (::videoBrowserController.isInitialized) videoBrowserController.getCurrentTitle() else t("Videos")
            STATE_COVERFLOW -> {
                val total = uniqueAlbumList.size
                if (total > 0 && ::coverFlowController.isInitialized) {
                    com.themoon.y1.controllers.coverflow.CoverFlowTitle.format(t("Cover Flow"), coverFlowController.currentCoverFlowIndex + 1, total)
                } else {
                    t("Cover Flow")
                }
            }
            STATE_EQUALIZER -> t("Equalizer")
            STATE_FM_RADIO -> t("Radio")
            STATE_JELLYFIN -> t("Jellyfin")
            else -> t("iPod")
        }
    }

    fun updateStatusBarClock() {
        val tv = tvStatusTime ?: return
        if (isTimeInTitleBar) {
            tv.text = ClockSettings.formatStatusBarTime(Date(), is24HourFormat)
            tv.visibility = View.VISIBLE
        } else {
            tv.visibility = View.GONE
        }
    }

    fun onClockSettingsChanged() {
        updateStatusBarClock()
        homeWidgetsController?.refreshWidgets()
    }

    fun isPaneBasedScreen(): Boolean {
        return when (currentScreenState) {
            STATE_MENU -> true
            STATE_BROWSER -> ::browserController.isInitialized && browserController.isCoverPaneActive()
            STATE_VIDEOS -> ::videoBrowserController.isInitialized && videoBrowserController.isCoverPaneActive()
            else -> false
        }
    }

    fun updateStatusBarWidth() {
        val statusBar = findViewById<View>(R.id.layout_status_bar) ?: return
        val density = resources.displayMetrics.density
        val slp = statusBar.layoutParams ?: return
        val targetWidth = if (isPaneBasedScreen()) {
            (ClassicMenuStyle.PANE_WIDTH_DP * density).toInt()
        } else {
            ViewGroup.LayoutParams.MATCH_PARENT
        }
        if (slp.width != targetWidth) {
            slp.width = targetWidth
            statusBar.layoutParams = slp
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
                Intent.ACTION_BATTERY_CHANGED, Intent.ACTION_POWER_CONNECTED, Intent.ACTION_POWER_DISCONNECTED -> {
                    var batteryIntent = intent
                    if (Intent.ACTION_POWER_CONNECTED == action || Intent.ACTION_POWER_DISCONNECTED == action) {
                        val bi = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                        if (bi != null) batteryIntent = bi
                    }
                    val level = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
                    val scale = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
                    val status = batteryIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
                    val plugged = batteryIntent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) ?: -1
                    var isCharging = (status == BatteryManager.BATTERY_STATUS_CHARGING
                            || status == BatteryManager.BATTERY_STATUS_FULL
                            || plugged > 0)
                    if (Intent.ACTION_POWER_DISCONNECTED == action) {
                        isCharging = false
                    }
                    if (level != -1 && scale > 0) {
                        val pct = ((level / scale.toFloat()) * 100).toInt()
                        tvStatusBattery?.text = "$pct%"
                        batteryIconView?.setBatteryLevel(pct, isCharging)
                    }
                }
                Intent.ACTION_HEADSET_PLUG -> {
                    val state = intent.getIntExtra("state", 0)
                    ivStatusHeadphone?.visibility = if (state == 1) View.VISIBLE else View.GONE
                    if (state == 1) {
                        updateStatusBarHeadphone()
                    }
                    scheduleAudioOutputCheck()
                }
                BluetoothAdapter.ACTION_STATE_CHANGED -> {
                    val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                    onStatusBarBluetoothAdapterState(state)
                    if (state == BluetoothAdapter.STATE_ON) {
                        ivStatusBluetooth?.visibility = View.VISIBLE
                        updateStatusBarBluetooth()
                        BluetoothAdapter.getDefaultAdapter()?.getProfileProxy(
                            applicationContext,
                            object : BluetoothProfile.ServiceListener {
                                override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                                    if (profile == BluetoothProfile.A2DP) {
                                        bluetoothController.setGlobalA2dpProxy(proxy)
                                    }
                                }
                                override fun onServiceDisconnected(profile: Int) {
                                    if (profile == BluetoothProfile.A2DP) {
                                        bluetoothController.setGlobalA2dpProxy(null)
                                    }
                                }
                            },
                            BluetoothProfile.A2DP
                        )
                    } else {
                        ivStatusBluetooth?.visibility = View.GONE
                        bluetoothController.setGlobalA2dpProxy(null)
                        if (state == BluetoothAdapter.STATE_OFF) {
                            bluetoothController.clearTargetDeviceForAudio()
                        }
                    }
                    if (NavigationManager.getInstance().currentState is ScreenState.Bluetooth || currentScreenState == STATE_BLUETOOTH) {
                        if (state == BluetoothAdapter.STATE_ON || state == BluetoothAdapter.STATE_OFF) {
                            bluetoothController.startBluetoothScan()
                        }
                    }
                }
                BluetoothDevice.ACTION_FOUND -> {
                    val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                    if (device != null) {
                        bluetoothController.onDeviceDiscovered(device)
                    }
                }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    bluetoothController.onDiscoveryFinished()
                }
                BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                    val bondState = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR)
                    if (bondState == BluetoothDevice.BOND_BONDED) {
                        val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                        // Connect audio only when the Y2 started the pairing. A car that started it
                        // connects its profiles itself; a second connection at the same time fails.
                        if (device != null && bluetoothController.takeY2BondRequest(device)) {
                            bluetoothController.connectBluetoothAudio(device)
                        }
                    }
                    if (NavigationManager.getInstance().currentState is ScreenState.Bluetooth || currentScreenState == STATE_BLUETOOTH) {
                        bluetoothController.startBluetoothScan()
                    }
                }
                "android.bluetooth.a2dp.profile.action.CONNECTION_STATE_CHANGED" -> {
                    val profileState = intent.getIntExtra("android.bluetooth.profile.extra.STATE", -1)
                    val currentDevice = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                    onStatusBarA2dpState(currentDevice, profileState)
                    if (profileState == BluetoothProfile.STATE_DISCONNECTED) {
                        bluetoothController.clearTargetDeviceForAudio()
                        Toast.makeText(this@MainActivity, t("Audio Disconnected"), Toast.LENGTH_SHORT).show()
                    } else if (profileState == BluetoothProfile.STATE_CONNECTED) {
                        bluetoothController.clearTargetDeviceForAudio()
                        val name = currentDevice?.name ?: "Unknown"
                        Toast.makeText(this@MainActivity, t("Audio Connected to ") + name, Toast.LENGTH_SHORT).show()
                    }
                    if (profileState == BluetoothProfile.STATE_CONNECTED || profileState == BluetoothProfile.STATE_DISCONNECTED) {
                        bluetoothController.isBtConnectingState = false
                        scheduleAudioOutputCheck()
                        if (NavigationManager.getInstance().currentState is ScreenState.Bluetooth || currentScreenState == STATE_BLUETOOTH) {
                            window.decorView.postDelayed({
                                bluetoothController.startBluetoothScan()
                            }, 300)
                        }
                    }
                }
                BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                    bluetoothController.clearTargetDeviceForAudio()
                }
                WifiManager.WIFI_STATE_CHANGED_ACTION -> {
                    val state = intent.getIntExtra(WifiManager.EXTRA_WIFI_STATE, WifiManager.WIFI_STATE_UNKNOWN)
                    if (state == WifiManager.WIFI_STATE_ENABLED) {
                        ivStatusWifi?.visibility = View.VISIBLE
                        updateStatusBarWifi(false)
                    } else if (state == WifiManager.WIFI_STATE_DISABLED) {
                        ivStatusWifi?.visibility = View.GONE
                    }
                    if (NavigationManager.getInstance().currentState is ScreenState.Wifi || currentScreenState == STATE_WIFI) {
                        if (state == WifiManager.WIFI_STATE_ENABLED || state == WifiManager.WIFI_STATE_DISABLED) {
                            wifiController.startWifiScan()
                        }
                    }
                }
                WifiManager.SCAN_RESULTS_AVAILABLE_ACTION -> {
                    val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                    if (wm != null) {
                        try {
                            val results = wm.scanResults
                            if (NavigationManager.getInstance().currentState is ScreenState.Wifi || currentScreenState == STATE_WIFI) {
                                wifiController.updateWifiUI(results)
                            }
                        } catch (_: Exception) {}
                    }
                }
                WifiManager.NETWORK_STATE_CHANGED_ACTION -> {
                    val networkInfo = intent.getParcelableExtra<android.net.NetworkInfo>(WifiManager.EXTRA_NETWORK_INFO)
                    val isConnected = networkInfo != null && networkInfo.isConnected
                    if (ivStatusWifi?.visibility == View.VISIBLE) {
                        updateStatusBarWifi(isConnected)
                    }
                    if (isConnected && (NavigationManager.getInstance().currentState is ScreenState.Wifi || currentScreenState == STATE_WIFI)) {
                        wifiController.startWifiScan()
                    }
                }
                "com.themoon.y1.REBUILD_COVER_CACHE" -> {
                    rebuildUniqueAlbumList()
                    com.themoon.y1.managers.AlbumCoverManager.getInstance().pregenerateThumbnails(uniqueAlbumList, true)
                    Toast.makeText(this@MainActivity, t("Rebuilding album cover cache..."), Toast.LENGTH_SHORT).show()
                }
                "com.themoon.y1.RESCAN_LIBRARY" -> {
                    startMediaLibraryScan()
                }
                Intent.ACTION_SCREEN_ON -> {
                    isScreenSleeping = false
                    powerBacklightManager?.resetTimer()
                    updateScreenOffFeedback(true)
                    updateStatusBarClock()
                }
                Intent.ACTION_SCREEN_OFF -> {
                    isScreenSleeping = true
                    powerBacklightManager?.cancelTimer()
                    updateScreenOffFeedback(false)
                }
                Intent.ACTION_TIME_TICK, Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED -> {
                    updateStatusBarClock()
                    homeWidgetsController?.refreshWidgets()
                }
                Intent.ACTION_SHUTDOWN, "android.intent.action.QUICKBOOT_POWEROFF", "com.htc.intent.action.QUICKBOOT_POWEROFF" -> {
                    AudioPlayerManager.getInstance().savePlaybackState(sync = true)
                }
                "android.hardware.usb.action.USB_STATE" -> {
                    val connected = intent.getBooleanExtra("connected", false)
                    if (connected) {
                        if (!usbStateReceiverReady) return
                        if (!isUsbPromptEnabled || usbStorageManager.isStorageOverlayActive || usbStorageManager.isDialogShowing || usbStorageManager.usbStorageBusy) return
                        val now = System.currentTimeMillis()
                        if (now - usbStorageManager.lastUsbDialogShownMs > 8000) {
                            usbStorageManager.lastUsbDialogShownMs = now
                            showUsbStorageDialog()
                        }
                    } else {
                        usbStorageManager.dismissUsbStorageDialog()
                        if (usbStorageManager.isStorageOverlayActive) {
                            usbStorageManager.onUsbCableRemovedWhileShared()
                        }
                    }
                }
            }
        }
    }

    private fun registerReceivers() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(Intent.ACTION_HEADSET_PLUG)
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            addAction("android.bluetooth.a2dp.profile.action.CONNECTION_STATE_CHANGED")
            addAction(WifiManager.WIFI_STATE_CHANGED_ACTION)
            addAction(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
            addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION)
            addAction(ConnectivityManager.CONNECTIVITY_ACTION)
            addAction(Intent.ACTION_TIME_TICK)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
            addAction(Intent.ACTION_SHUTDOWN)
            addAction("android.intent.action.QUICKBOOT_POWEROFF")
            addAction("com.htc.intent.action.QUICKBOOT_POWEROFF")
            addAction("android.hardware.usb.action.USB_STATE")
            addAction("com.themoon.y1.REBUILD_COVER_CACHE")
            addAction("com.themoon.y1.RESCAN_LIBRARY")
        }
        registerReceiver(systemStatusReceiver, filter)
        pairingAgent = BluetoothPairingAgent(this, ::t).also { it.register() }
    }

    override fun onDestroy() {
        updateScreenOffFeedback(true)
        AudioPlayerManager.getInstance().savePlaybackState(sync = true)
        super.onDestroy()
        try {
            unregisterReceiver(systemStatusReceiver)
        } catch (_: Exception) {}
        powerMenuInterceptor?.unregister()
        com.themoon.y1.managers.CellRadioManager.stop(this)
        externalSdMountMonitor?.stop()
        audioVisualizer?.release()
        audioVisualizer = null
        AudioPlayerManager.getInstance().releasePlayer()
        pairingAgent?.unregister()
        NavigationManager.getInstance().reset()
        instance = null
    }

    class MediaBtnReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val event = intent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT) ?: return
            if (event.action != KeyEvent.ACTION_DOWN || event.repeatCount != 0) return

            val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            val isScreenOn = if (Build.VERSION.SDK_INT >= 20) {
                pm?.isInteractive ?: true
            } else {
                pm?.isScreenOn ?: true
            }

            if (!isScreenOn) {
                val isScreenOffCtrl = instance?.isScreenOffControlEnabled
                    ?: AppPrefs.get(context).getBoolean("screen_off_control", false)
                // Media keys from a remote (car, Bluetooth headphones, wired headset) always act
                val keys = com.themoon.y1.navigation.RemoteMediaKeys
                val device = keys.deviceName(event)
                android.util.Log.w("MediaButton", KeyEvent.keyCodeToString(event.keyCode) + " from " + (device ?: "unknown device") +
                    " with the screen off: " + keys.screenOffDecision(event.keyCode, device, isScreenOffCtrl))
                if (!keys.actsWithScreenOff(event.keyCode, device, isScreenOffCtrl)) {
                    return
                }
            }

            val am = AudioPlayerManager.getInstance()
            when (event.keyCode) {
                KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, 85,
                KeyEvent.KEYCODE_HEADSETHOOK -> {
                    com.themoon.y1.managers.AudioSources.app.playPauseKey()
                }
                KeyEvent.KEYCODE_MEDIA_NEXT, 87 -> {
                    com.themoon.y1.managers.AudioSources.app.skipKey(true)
                }
                KeyEvent.KEYCODE_MEDIA_PREVIOUS, 88 -> {
                    com.themoon.y1.managers.AudioSources.app.skipKey(false)
                }
            }
        }
    }
}
