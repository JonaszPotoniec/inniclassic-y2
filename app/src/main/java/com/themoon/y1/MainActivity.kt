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
import android.os.SystemClock
import android.os.Vibrator
import android.provider.Settings
import android.view.KeyEvent
import android.view.MotionEvent
import com.themoon.y1.views.AlphabetIndexBar
import com.themoon.y1.views.BatteryIconView
import com.themoon.y1.views.ClassicMenuStyle
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
import com.themoon.y1.models.TvSeries
import com.themoon.y1.models.VideoItem
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
    var isUsbPromptEnabled: Boolean
        get() = if (::prefs.isInitialized) prefs.getBoolean("usb_storage_prompt", true) else true
        set(value) {
            if (::prefs.isInitialized) {
                prefs.edit().putBoolean("usb_storage_prompt", value).apply()
            }
        }
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
    var lastFastScrollLetter: String? = null
    var classicWheelStreak: Int = 0
    var lastClassicWheelTime: Long = 0L
    var tvStatusBarTitle: TextView? = null
    var ivStatusWifi: ImageView? = null
    var ivStatusBluetooth: ImageView? = null
    var ivStatusHeadphone: ImageView? = null
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
    var powerBacklightManager: PowerBacklightManager? = null

    internal val usbStorageManager by lazy {
        UsbStorageUiManager(
            activity = this,
            getExternalSdMountMonitor = { externalSdMountMonitor },
            isFmRadioActive = { activePlayer == 1 },
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

    private val fastScrollHandler = Handler(Looper.getMainLooper())
    private val hideFastScrollTask = Runnable {
        tvFastScrollLetter?.visibility = View.GONE
        lastFastScrollLetter = null
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

        val savedBacklightIdx = prefs.getInt("backlight_timer_index", 3)
        powerBacklightManager = PowerBacklightManager.getInstance(this).apply {
            backlightTimerIndex = savedBacklightIdx
            setTimeoutAction { turnOffScreen() }
        }

        AudioPlayerManager.getInstance().initPlayer(applicationContext)
        BluetoothAvrcpManager.getInstance(this).init()

        var playbackRestored = false
        if (ExternalSdMountMonitor.hasSecondarySlot() && !ExternalSdMountMonitor.isSecondaryReady()) {
            android.util.Log.i("InniClassic", "Secondary storage not ready yet at onCreate, will load library once mounted")
            playbackRestored = AudioPlayerManager.getInstance().restoreLastPlaybackState(this)
        } else {
            runMigrationsAsync()
            loadLibraryCache()
            playbackRestored = AudioPlayerManager.getInstance().restoreLastPlaybackState(this)
        }

        externalSdMountMonitor = ExternalSdMountMonitor(this).apply {
            setListener(object : ExternalSdMountMonitor.Listener {
                override fun onSecondaryStorageReady() {
                    StoragePaths.invalidate()
                    runMigrationsAsync()
                    scanVideos()
                    runOnUiThread {
                        if (customLibrary.isEmpty()) {
                            loadLibraryCache()
                        }
                        if (customLibrary.isEmpty() && !isCustomScanning) {
                            startMediaLibraryScan()
                        }
                        if (!playbackRestored || isPendingRestorePlayback || currentPlaylist.isEmpty()) {
                            isPendingRestorePlayback = false
                            playbackRestored = AudioPlayerManager.getInstance().restoreLastPlaybackState(this@MainActivity)
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
            }
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            if (wm != null && wm.isWifiEnabled) {
                ivStatusWifi?.visibility = View.VISIBLE
                val info = wm.connectionInfo
                val isConnected = (info != null && info.networkId != -1)
                updateStatusBarWifi(isConnected)
            }
        } catch (_: Exception) {}

        updateMainMenuBackground()
        changeScreen(STATE_MENU)
        applyTheme()
        Handler(Looper.getMainLooper()).postDelayed({ usbStateReceiverReady = true }, 2000)
    }

    override fun onResume() {
        super.onResume()
        isScreenSleeping = false
        powerBacklightManager?.resetTimer()
        updateScreenOffFeedback(true)
        applyTheme()
    }

    override fun onPause() {
        super.onPause()
        AudioPlayerManager.getInstance().savePlaybackState(sync = false)
    }

    override fun onStop() {
        super.onStop()
        AudioPlayerManager.getInstance().savePlaybackState(sync = true)
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
            is ScreenState.Playlists, is ScreenState.M3uPlaylistSongs -> STATE_BROWSER
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
        classicWheelStreak = 0
        lastFastScrollLetter = null
        tvFastScrollLetter?.visibility = View.GONE
        syncCurrentScreenState(targetState)
        NavigationManager.getInstance().navigateTo(targetState)
        updateStatusBarTitle()
        applyTheme()
    }

    fun getActiveScreenController(): ScreenController? = NavigationManager.getInstance().activeController

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val keyCode = event.keyCode
        if (event.action == KeyEvent.ACTION_DOWN && (keyCode == 21 || keyCode == 22)) {
            val now = SystemClock.uptimeMillis()
            classicWheelStreak = if (now - lastClassicWheelTime < 200) classicWheelStreak + 1 else 1
            lastClassicWheelTime = now
        }
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
        val active = getActiveScreenController()
        if (active?.onBackPressed() == true) {
            return
        }
        if (NavigationManager.getInstance().navigateBack()) {
            return
        }
        clickFeedback()
        // Never call super.onBackPressed() because MainActivity is the HOME launcher.
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
    fun rebootAudioEngine() = rebootAudioEngineInternal()
    fun loadLibraryCache() = loadLibraryCacheInternal()
    fun startMediaLibraryScan() = startMediaLibraryScanInternal()
    fun rebuildUniqueAlbumList() = rebuildUniqueAlbumListInternal()
    fun scanVideos() = scanVideosInternal()

    fun runMigrationsAsync() {
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
            }
        }, "y1-migrations").start()
    }

    fun showFastScrollLetter(rawText: String) {
        val navState = NavigationManager.getInstance().currentState
        val bcMode = if (::browserController.isInitialized) browserController.currentBrowserMode else BrowserMode.Root
        val isRootMenu = bcMode is BrowserMode.Root || bcMode is BrowserMode.Audiobooks ||
                (navState is ScreenState.Browser && (navState.mode is BrowserMode.Root || navState.mode is BrowserMode.Audiobooks))
        val isBrowserList = currentScreenState == STATE_BROWSER && !isRootMenu
        if (!isBrowserList) return

        val tv = tvFastScrollLetter ?: return
        val clean = rawText.replace("📁 ", "").replace("👤 ", "")
            .replace("💿 ", "").replace("🎵 ", "")
            .replace("📦 [INSTALL] ", "").trim()
        if (clean.isEmpty()) return

        val firstChar = AlphabetIndexBar.getInitialChar(clean).toString()
        val isLetterChange = (lastFastScrollLetter != null && lastFastScrollLetter != firstChar)
        val shouldShow = isLetterChange || classicWheelStreak >= 3 || tv.visibility == View.VISIBLE
        lastFastScrollLetter = firstChar

        if (!shouldShow) return

        if (tv.visibility == View.VISIBLE && tv.text.toString() == firstChar) {
            fastScrollHandler.removeCallbacks(hideFastScrollTask)
            fastScrollHandler.postDelayed(hideFastScrollTask, 800)
            return
        }

        tv.text = firstChar

        val bgColor = ThemeManager.getListButtonFocusedBg()
        val textColor = ThemeManager.getListButtonFocusedTextColor()

        tv.setTextColor(textColor)
        tv.typeface = ThemeManager.getCustomFontBold()

        val letterBg = GradientDrawable().apply {
            setColor(bgColor or 0xDD000000.toInt())
            cornerRadius = 15f * resources.displayMetrics.density
        }
        tv.background = letterBg
        tv.visibility = View.VISIBLE

        fastScrollHandler.removeCallbacks(hideFastScrollTask)
        fastScrollHandler.postDelayed(hideFastScrollTask, 800)
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

    fun fetchTrackInfoFromInternet(track: File, originalQuery: String, hasValidTags: Boolean, title: String?, artist: String?) {
        // Tag lookups handled via MediaLibraryManager
    }

    fun turnOffScreen() {
        if (isScreenSleeping) return
        isScreenSleeping = true
        updateScreenOffFeedback(false)
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
        lastScreenOnTime = System.currentTimeMillis()
        powerBacklightManager?.wakeFromFakeScreenOff(window, layoutLoadingOverlay, currentSystemBrightness) { sleep ->
            autoManageWifiPower(sleep)
        }
        powerBacklightManager?.resetTimer()
        updateScreenOffFeedback(true)
    }

    fun updateScreenOffFeedback(screenOn: Boolean) {
        try {
            if (screenOn) {
                setSystemSoundsMuted(!isSoundEffectEnabled)
                Settings.System.putInt(
                    contentResolver,
                    Settings.System.SOUND_EFFECTS_ENABLED,
                    if (isSoundEffectEnabled) 1 else 0
                )
                Settings.System.putInt(
                    contentResolver,
                    Settings.System.HAPTIC_FEEDBACK_ENABLED,
                    if (isVibrationEnabled) 1 else 0
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
                    "${t("Cover Flow")} (${coverFlowController.currentCoverFlowIndex + 1}/$total)"
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
                }
                BluetoothAdapter.ACTION_STATE_CHANGED -> {
                    val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
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
                        if (device != null) {
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
                "android.bluetooth.device.action.PAIRING_REQUEST" -> {
                    val pairingDevice = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                    if (pairingDevice != null) {
                        try {
                            val confirmMethod = pairingDevice.javaClass.getMethod("setPairingConfirmation", Boolean::class.javaPrimitiveType)
                            confirmMethod.invoke(pairingDevice, true)
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
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
                }
                Intent.ACTION_SCREEN_OFF -> {
                    isScreenSleeping = true
                    powerBacklightManager?.cancelTimer()
                    updateScreenOffFeedback(false)
                }
                Intent.ACTION_TIME_TICK, Intent.ACTION_TIME_CHANGED -> {
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
            addAction("android.bluetooth.device.action.PAIRING_REQUEST")
            addAction(WifiManager.WIFI_STATE_CHANGED_ACTION)
            addAction(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
            addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION)
            addAction(ConnectivityManager.CONNECTIVITY_ACTION)
            addAction(Intent.ACTION_TIME_TICK)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_SHUTDOWN)
            addAction("android.intent.action.QUICKBOOT_POWEROFF")
            addAction("com.htc.intent.action.QUICKBOOT_POWEROFF")
            addAction("android.hardware.usb.action.USB_STATE")
            addAction("com.themoon.y1.REBUILD_COVER_CACHE")
            addAction("com.themoon.y1.RESCAN_LIBRARY")
        }
        registerReceiver(systemStatusReceiver, filter)
    }

    override fun onDestroy() {
        updateScreenOffFeedback(true)
        AudioPlayerManager.getInstance().savePlaybackState(sync = true)
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

            val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            val isScreenOn = if (Build.VERSION.SDK_INT >= 20) {
                pm?.isInteractive ?: true
            } else {
                pm?.isScreenOn ?: true
            }

            if (!isScreenOn) {
                val isScreenOffCtrl = instance?.isScreenOffControlEnabled
                    ?: context.getSharedPreferences("Y1_SETTINGS", Context.MODE_PRIVATE)
                        .getBoolean("screen_off_control", false)
                if (!isScreenOffCtrl) {
                    return
                }
            }

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
