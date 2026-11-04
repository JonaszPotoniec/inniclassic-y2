package com.themoon.y1.controllers.settings

import com.themoon.y1.navigation.RowFocus
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.media.AudioManager
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.themoon.y1.ClockSettings
import com.themoon.y1.MainActivity
import com.themoon.y1.R
import com.themoon.y1.changeTheme
import com.themoon.y1.StoragePaths
import com.themoon.y1.ThemeManager
import com.themoon.y1.controllers.ScreenController
import com.themoon.y1.core.extensions.onOff
import com.themoon.y1.managers.SafeVolumeBypasser
import com.themoon.y1.managers.AudioEffectManager
import com.themoon.y1.managers.AudioPlayerManager
import com.themoon.y1.managers.AutoThemeManager
import com.themoon.y1.managers.FmRadioManager
import com.themoon.y1.managers.LanguageManager
import com.themoon.y1.managers.LastFmScrobbler
import com.themoon.y1.managers.PowerMenu
import com.themoon.y1.models.AlbumKey
import com.themoon.y1.navigation.ScreenState
import com.themoon.y1.navigation.SettingsSubMode
import com.themoon.y1.sync.jellyfin.JellyfinSyncProvider
import com.themoon.y1.views.ListEdges
import com.themoon.y1.views.TablerIcons
import com.themoon.y1.views.ToggleStyle
import java.io.File
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * Controller for Settings screens:
 * Root settings menu, Date & Time, Theme selector, Language selector,
 * Background settings, Volume limit, Legal, Main Menu visibility, and Music Menu visibility.
 */
class SettingsController(
    private val activity: Activity,
    override val rootView: ViewGroup,
    private val eqPresetNames: List<String>,
    private val createSettingRow: (title: String, value: String) -> LinearLayout,
    private val createCategoryHeader: (title: String) -> Unit,
    private val createListButton: (text: String) -> Button,
    private val createTablerListButton: (icon: Array<String>?, text: String, normalColor: Int, focusedColor: Int) -> View,
    private val clickFeedback: () -> Unit,
    private val onNavigateBack: () -> Unit,
    private val onNavigateToScreen: (Int) -> Unit,
    private val onOpenEqualizer: () -> Unit,
    private val onOpenJellyfin: () -> Unit,
    private val onOpenUpdateCheck: () -> Unit,
    private val onClearCacheConfirmed: () -> Unit,
    private val onApplyThemeBackground: () -> Unit,
    private val onClearCustomBackground: () -> Unit,
    private val onPickBackgroundFromStorage: () -> Unit,
    private val resetBacklightTimer: () -> Unit,
    private val applySoundSetting: () -> Unit,
    private val applyAlbumTiltSetting: () -> Unit,
    private val toggleShuffleMode: () -> Unit,
    private val cycleRepeatMode: () -> Unit,
    private val getRepeatModeText: (Int) -> String,
    private val localize: (String) -> String
) : ScreenController {

    private val containerSettingsItems: LinearLayout = rootView.findViewById(R.id.container_settings_items)
    private val prefs = activity.getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
    private val audioManager = activity.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val mainActivity: MainActivity? get() = activity as? MainActivity

    var currentSettingsDepth: Int = 0
        set(value) {
            val old = field
            field = value
            // Title: the group name on a group list, the row name on a row's sub-screen (SettingsGroups.kt)
            groupPages.onDepthChanged(old, value, containerSettingsItems, lastSettingsFocusIndex, mainActivity?.tvStatusBarTitle, localize)
        }
    var lastSettingsFocusIndex: Int = 0
    var settingsSubMode: Int = 0
    private var isUiActive = false
    /** The open action screen (Power, or a screen in place of a dialog: ActionScreen.kt), or null. Back goes to the screen before it. */
    private var actionPage: SettingsSubMode? = null
    private val isActionScreen: Boolean get() = actionPage != null

    // Temporary variables for Date/Time picker
    var dtYear: Int = 2026
    var dtMonth: Int = 1
    var dtDay: Int = 1
    var dtHour: Int = 0
    var dtMinute: Int = 0

    // Reorder state for main menu visibility
    var isMenuReorderMode: Boolean = false
    private var currentReorderRow: View? = null
    private var materialIconFont: Typeface? = null

    companion object {
        /** Backlight Timer is the one screen timer: it also sets Android's SCREEN_OFF_TIMEOUT (see BacklightTimer). */
        val BACKLIGHT_TIMER_LABELS = com.themoon.y1.managers.BacklightTimer.LABELS
        val VIBE_STRENGTH_NAMES = com.themoon.y1.managers.ButtonVibration.LABELS

        // Common IANA zones covering the main UTC offsets, short enough to scroll with the wheel
        val TIME_ZONE_IDS = arrayOf(
            "Pacific/Honolulu", "America/Anchorage", "America/Los_Angeles", "America/Denver",
            "America/Chicago", "America/Mexico_City", "America/New_York", "America/Bogota",
            "America/Halifax", "America/St_Johns", "America/Sao_Paulo", "America/Argentina/Buenos_Aires",
            "Atlantic/South_Georgia", "Atlantic/Azores", "GMT", "Europe/London", "Europe/Lisbon",
            "Europe/Paris", "Europe/Berlin", "Europe/Madrid", "Europe/Rome", "Europe/Warsaw",
            "Africa/Lagos", "Europe/Athens", "Europe/Helsinki", "Africa/Cairo", "Africa/Johannesburg",
            "Europe/Moscow", "Europe/Istanbul", "Asia/Riyadh", "Asia/Tehran", "Asia/Dubai",
            "Asia/Karachi", "Asia/Kolkata", "Asia/Kathmandu", "Asia/Dhaka", "Asia/Bangkok",
            "Asia/Jakarta", "Asia/Shanghai", "Asia/Hong_Kong", "Asia/Singapore", "Australia/Perth",
            "Asia/Tokyo", "Asia/Seoul", "Australia/Adelaide", "Australia/Brisbane", "Australia/Sydney",
            "Pacific/Auckland"
        )

        fun timeZoneCityName(zoneId: String): String = zoneId.substringAfterLast('/').replace('_', ' ')

        // e.g. "UTC+01:00 London" (offset at the given time, so it includes summer time)
        fun timeZoneLabel(zoneId: String, nowMs: Long): String {
            val offsetMin = TimeZone.getTimeZone(zoneId).getOffset(nowMs) / 60000
            val sign = if (offsetMin < 0) "-" else "+"
            val absMin = Math.abs(offsetMin)
            return String.format(
                Locale.US, "UTC%s%02d:%02d %s",
                sign, absMin / 60, absMin % 60, timeZoneCityName(zoneId)
            )
        }
    }

    init {
        containerSettingsItems.viewTreeObserver?.addOnGlobalFocusChangeListener { _, newFocus ->
            if (isUiActive && currentSettingsDepth == 0 && newFocus != null) {
                var v: View? = newFocus
                while (v != null && v.parent != containerSettingsItems) {
                    v = v.parent as? View
                }
                if (v != null) {
                    val idx = containerSettingsItems.indexOfChild(v)
                    if (idx >= 0) {
                        lastSettingsFocusIndex = idx
                    }
                }
            }
        }
    }

    // Groups on the top level (SettingsGroups.kt). The rows stay in buildSettingsUI; the groups only move them.
    private val groupPages = SettingsGroupPages()

    override fun onEnter(state: ScreenState) {
        when (val sub = (state as? ScreenState.Settings)?.subMode) {
            is SettingsSubMode.Power -> { buildPowerUI(); return }
            is SettingsSubMode.Action -> { buildActionUI(sub, ActionScreens.spec(sub.key)); return }
            else -> {}
        }
        // From the main menu: start at the top level. From a row's screen (Bluetooth, Equalizer...): stay in its group.
        if (mainActivity?.backTargetForUtility == MainActivity.STATE_MENU) groupPages.close()?.let { lastSettingsFocusIndex = it }
        buildSettingsUI()
    }

    override fun onExit() {
        if (currentSettingsDepth == 0) {
            val curFocus = containerSettingsItems.findFocus()
            val idx = if (curFocus != null) containerSettingsItems.indexOfChild(curFocus) else -1
            if (idx >= 0) {
                lastSettingsFocusIndex = idx
            }
        }
        isUiActive = false
        actionPage = null
        containerSettingsItems.isFocusable = false
    }

    override fun onRefreshUI() {
        val shownAction = actionPage
        if (shownAction != null) {
            onEnter(ScreenState.Settings(shownAction))
        } else if (currentSettingsDepth == 0) {
            buildSettingsUI()
        }
    }

    // First row of the theme list when it was made; the list still shows while this row is in the container
    private var themeListFirstRow: View? = null

    override fun onThemeChanged() {
        when {
            currentSettingsDepth == 0 -> buildSettingsUI()
            themeListFirstRow != null && containerSettingsItems.getChildAt(0) === themeListFirstRow -> buildThemeSelectorUI()
            // Other sub-screens: one level up, as Back does
            else -> buildSettingsUI()
        }
    }

    override fun onBackPressed(): Boolean {
        if (isActionScreen) {
            ActionScreens.cancel((actionPage as? SettingsSubMode.Action)?.let { ActionScreens.spec(it.key) }) { onNavigateBack() }
            return true
        }
        if (isMenuReorderMode) {
            toggleMenuReorderMode()
            return true
        }
        if (currentSettingsDepth == 0 && groupPages.isOpen) {
            lastSettingsFocusIndex = groupPages.close() ?: 0
            buildSettingsUI()
            return true
        }
        if (currentSettingsDepth == 0) {
            onNavigateBack()
            return true
        } else if (currentSettingsDepth == 1) {
            buildSettingsUI()
            return true
        } else if (currentSettingsDepth == 2) {
            buildDateTimeUI()
            return true
        }
        buildSettingsUI()
        return true
    }

    override fun onWheel(delta: Int, event: KeyEvent): Boolean {
        if (settingsSubMode == 4 && isMenuReorderMode && currentReorderRow != null) {
            val idx = containerSettingsItems.indexOfChild(currentReorderRow)
            if (delta < 0) {
                if (idx > 0) {
                    containerSettingsItems.removeViewAt(idx)
                    containerSettingsItems.addView(currentReorderRow, idx - 1)
                    currentReorderRow?.requestFocus()
                    clickFeedback()
                    val p = containerSettingsItems.parent
                    if (p is ScrollView) {
                        p.requestChildFocus(containerSettingsItems, currentReorderRow)
                    }
                }
            } else {
                if (idx < containerSettingsItems.childCount - 1) {
                    containerSettingsItems.removeViewAt(idx)
                    containerSettingsItems.addView(currentReorderRow, idx + 1)
                    currentReorderRow?.requestFocus()
                    clickFeedback()
                    val p = containerSettingsItems.parent
                    if (p is ScrollView) {
                        p.requestChildFocus(containerSettingsItems, currentReorderRow)
                    }
                }
            }
            return true
        }

        val i = RowFocus.step(containerSettingsItems, delta) ?: return false
        if (currentSettingsDepth == 0) lastSettingsFocusIndex = i
        clickFeedback()
        return true
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (isMenuReorderMode && (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) &&
            (event.flags and KeyEvent.FLAG_CANCELED_LONG_PRESS) == 0
        ) {
            toggleMenuReorderMode()
            clickFeedback()
            return true
        }
        return false
    }

    override fun onKeyLongPress(keyCode: Int, event: KeyEvent): Boolean {
        if (settingsSubMode == 4 && (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)) {
            if (!isMenuReorderMode) {
                toggleMenuReorderMode()
                clickFeedback()
            }
            return true
        }
        return false
    }

    fun buildSettingsUI() {
        actionPage = null
        currentSettingsDepth = 0
        settingsSubMode = 0

        val targetFocusIndex = lastSettingsFocusIndex
        containerSettingsItems.removeAllViews()

        val isShuffleMode = prefs.getBoolean("shuffle", false)
        val btnShuffle = createSettingRow(
            localize("Shuffle Mode"),
            onOff(isShuffleMode, localize)
        )
        btnShuffle.setOnClickListener {
            clickFeedback()
            toggleShuffleMode()
            val tvStatus = btnShuffle.getChildAt(1) as? TextView
            val newShuffle = prefs.getBoolean("shuffle", false)
            tvStatus?.text = onOff(newShuffle, localize)
        }
        containerSettingsItems.addView(btnShuffle)

        val repeatMode = prefs.getInt("repeat_mode", 0)
        val btnRepeat = createSettingRow(
            localize("Repeat Mode"),
            localize(getRepeatModeText(repeatMode))
        )
        btnRepeat.setOnClickListener {
            clickFeedback()
            cycleRepeatMode()
            val tvStatus = btnRepeat.getChildAt(1) as? TextView
            val newRepeat = prefs.getInt("repeat_mode", 0)
            tvStatus?.text = localize(getRepeatModeText(newRepeat))
        }
        containerSettingsItems.addView(btnRepeat)

        val currentEqProfile = prefs.getString("eq_profile_id", "preset_0") ?: "preset_0"
        var eqDisplayName = "Normal"
        if (currentEqProfile.startsWith("preset_")) {
            val pIdx = currentEqProfile.replace("preset_", "").toIntOrNull() ?: 0
            if (pIdx < eqPresetNames.size) {
                eqDisplayName = localize(eqPresetNames[pIdx])
            }
        } else {
            eqDisplayName = currentEqProfile.replace("custom_", "")
        }
        val btnEq = createSettingRow(localize("Equalizer & Audio Effects"), "$eqDisplayName 〉")
        btnEq.setOnClickListener {
            clickFeedback()
            onOpenEqualizer()
        }
        containerSettingsItems.addView(btnEq)

        val volumeLimitMax = prefs.getInt("volume_limit_max", -1)
        val volumeLimitDisplay = if (volumeLimitMax < 0) localize("Off") else volumeLimitMax.toString()
        val btnVolumeLimit = createSettingRow(localize("Volume Limit"), "$volumeLimitDisplay 〉")
        btnVolumeLimit.setOnClickListener {
            clickFeedback()
            buildVolumeLimitSettingsUI()
        }
        containerSettingsItems.addView(btnVolumeLimit)

        var isSoundCheckEnabled = prefs.getBoolean("sound_check_enabled", false)
        val btnSoundCheck = createSettingRow(
            localize("Sound Check"),
            onOff(isSoundCheckEnabled, localize)
        )
        btnSoundCheck.setOnClickListener {
            clickFeedback()
            isSoundCheckEnabled = !isSoundCheckEnabled
            prefs.edit().putBoolean("sound_check_enabled", isSoundCheckEnabled).commit()
            mainActivity?.isSoundCheckEnabled = isSoundCheckEnabled
            val tvStatus = btnSoundCheck.getChildAt(1) as? TextView
            tvStatus?.text = onOff(isSoundCheckEnabled, localize)
            AudioEffectManager.getInstance().applySoundCheck()
        }
        containerSettingsItems.addView(btnSoundCheck)

        val videoStretchFill = prefs.getBoolean("video_stretch_fill", false)
        val btnVideoFill = createSettingRow(
            localize("Fill Video Screen"),
            onOff(videoStretchFill, localize)
        )
        btnVideoFill.setOnClickListener {
            clickFeedback()
            val newState = !prefs.getBoolean("video_stretch_fill", false)
            prefs.edit().putBoolean("video_stretch_fill", newState).commit()
            val tvStatus = btnVideoFill.getChildAt(1) as? TextView
            tvStatus?.text = onOff(newState, localize)
        }
        containerSettingsItems.addView(btnVideoFill)

        var isAlbumTiltEnabled = prefs.getBoolean("album_tilt_enabled", true)
        val btnAlbumTilt = createSettingRow(
            localize("Album Cover Tilt"),
            onOff(isAlbumTiltEnabled, localize)
        )
        btnAlbumTilt.setOnClickListener {
            clickFeedback()
            isAlbumTiltEnabled = !isAlbumTiltEnabled
            prefs.edit().putBoolean("album_tilt_enabled", isAlbumTiltEnabled).commit()
            val tvStatus = btnAlbumTilt.getChildAt(1) as? TextView
            tvStatus?.text = onOff(isAlbumTiltEnabled, localize)
            applyAlbumTiltSetting()
        }
        containerSettingsItems.addView(btnAlbumTilt)

        // Slow pan and zoom of the art in the split screens (main menu, Music, Audiobooks, Videos), as on the iPod
        val menuCoverMotion = { prefs.getBoolean(com.themoon.y1.views.CoverPane.PREF, com.themoon.y1.views.CoverPane.DEFAULT_ON) }
        val btnMenuCoverMotion = createSettingRow(
            localize("Menu Cover Motion"),
            com.themoon.y1.core.extensions.onOff(menuCoverMotion(), localize)
        )
        btnMenuCoverMotion.setOnClickListener {
            clickFeedback()
            val on = !menuCoverMotion()
            prefs.edit().putBoolean(com.themoon.y1.views.CoverPane.PREF, on).commit()
            (btnMenuCoverMotion.getChildAt(1) as? TextView)?.text = com.themoon.y1.core.extensions.onOff(on, localize)
        }
        containerSettingsItems.addView(btnMenuCoverMotion)

        val speedLabels = arrayOf("1.0x (Normal)", "1.2x (Fast)", "1.5x (Faster)", "2.0x (Very Fast)")
        val speedValues = floatArrayOf(1.0f, 1.2f, 1.5f, 2.0f)
        val currentSpd = AudioPlayerManager.getInstance().getCurrentSpeed()
        var spdIdx = 0
        for (i in speedValues.indices) {
            if (speedValues[i] == currentSpd) spdIdx = i
        }

        val btnSpeed = createSettingRow(localize("Playback Speed"), localize(speedLabels[spdIdx]))
        btnSpeed.setOnClickListener {
            clickFeedback()
            val current = AudioPlayerManager.getInstance().getCurrentSpeed()
            var nextIdx = 0
            for (i in speedValues.indices) {
                if (speedValues[i] == current) nextIdx = (i + 1) % speedValues.size
            }
            AudioPlayerManager.getInstance().setPlaybackSpeed(speedValues[nextIdx])
            val tvStatus = btnSpeed.getChildAt(1) as? TextView
            tvStatus?.text = localize(speedLabels[nextIdx])
            Toast.makeText(
                activity,
                localize("Speed set to ") + localize(speedLabels[nextIdx]),
                Toast.LENGTH_SHORT
            ).show()
        }
        containerSettingsItems.addView(btnSpeed)

        var isSoundEffectEnabled = prefs.getBoolean("sound", true)
        val btnSound = createSettingRow(
            localize("Button Sound"),
            onOff(isSoundEffectEnabled, localize)
        )
        btnSound.setOnClickListener {
            isSoundEffectEnabled = !isSoundEffectEnabled
            prefs.edit().putBoolean("sound", isSoundEffectEnabled).commit()
            applySoundSetting()
            clickFeedback()
            val tvStatus = btnSound.getChildAt(1) as? TextView
            tvStatus?.text = onOff(isSoundEffectEnabled, localize)
        }
        containerSettingsItems.addView(btnSound)

        // Click Volume: Low / Medium / High; each push plays a click at the new level.
        val clickVolume = com.themoon.y1.managers.ClickVolume
        var clickVolumeLevel = clickVolume.validLevel(prefs.getInt(clickVolume.PREF, clickVolume.DEFAULT))
        val btnClickVolume = createSettingRow(
            localize("Click Volume"),
            localize(clickVolume.LABELS[clickVolumeLevel])
        )
        btnClickVolume.setOnClickListener {
            clickVolumeLevel = clickVolume.next(clickVolumeLevel)
            prefs.edit().putInt(clickVolume.PREF, clickVolumeLevel).commit()
            val tvStatus = btnClickVolume.getChildAt(1) as? TextView
            tvStatus?.text = localize(clickVolume.LABELS[clickVolumeLevel])
            clickFeedback()
        }
        containerSettingsItems.addView(btnClickVolume)

        // Settings → Controls: Button Vibration and Vibration Strength (no Vibration sub-screen).
        // Vibration Strength is grey and the wheel skips it while Button Vibration is off.
        var isVibrationEnabled = prefs.getBoolean("vibrate", true)
        var vibrationStrengthLevel = prefs.getInt("vibrate_strength", 1)
        if (vibrationStrengthLevel !in VIBE_STRENGTH_NAMES.indices) vibrationStrengthLevel = 1
        val btnVibration = createSettingRow(
            localize("Button Vibration"),
            onOff(isVibrationEnabled, localize)
        )
        val btnVibrationStrength = createSettingRow(
            localize("Vibration Strength"),
            localize(VIBE_STRENGTH_NAMES[vibrationStrengthLevel])
        )
        btnVibration.setOnClickListener {
            isVibrationEnabled = !isVibrationEnabled
            mainActivity?.isVibrationEnabled = isVibrationEnabled
            clickFeedback()
            val tvStatus = btnVibration.getChildAt(1) as? TextView
            tvStatus?.text = onOff(isVibrationEnabled, localize)
            StatusBarSettings.setRowEnabled(btnVibrationStrength, isVibrationEnabled)
            try {
                prefs.edit().putBoolean("vibrate", isVibrationEnabled).commit()
                Settings.System.putInt(
                    activity.contentResolver,
                    Settings.System.HAPTIC_FEEDBACK_ENABLED,
                    0 // always off, as in MainActivity: else Android also vibrates on each key press
                )
            } catch (_: Exception) {}
        }
        btnVibrationStrength.setOnClickListener {
            vibrationStrengthLevel = (vibrationStrengthLevel + 1) % VIBE_STRENGTH_NAMES.size
            mainActivity?.vibrationStrengthLevel = vibrationStrengthLevel
            // The click vibration uses the new strength: a preview
            clickFeedback()
            val tvStatus = btnVibrationStrength.getChildAt(1) as? TextView
            tvStatus?.text = localize(VIBE_STRENGTH_NAMES[vibrationStrengthLevel])
            try {
                prefs.edit().putInt("vibrate_strength", vibrationStrengthLevel).commit()
            } catch (_: Exception) {}
        }
        containerSettingsItems.addView(btnVibration)
        containerSettingsItems.addView(btnVibrationStrength)
        StatusBarSettings.setRowEnabled(btnVibrationStrength, isVibrationEnabled)

        var isScreenOffControlEnabled = prefs.getBoolean("screen_off_control", false)
        val btnScreenOffCtrl = createSettingRow(
            localize("Screen-Off Control"),
            onOff(isScreenOffControlEnabled, localize)
        )
        btnScreenOffCtrl.setOnClickListener {
            clickFeedback()
            isScreenOffControlEnabled = !isScreenOffControlEnabled
            prefs.edit().putBoolean("screen_off_control", isScreenOffControlEnabled).commit()
            mainActivity?.isScreenOffControlEnabled = isScreenOffControlEnabled
            val tvStatus = btnScreenOffCtrl.getChildAt(1) as? TextView
            tvStatus?.text = onOff(isScreenOffControlEnabled, localize)
        }
        containerSettingsItems.addView(btnScreenOffCtrl)

        var isDoubleClickFavoriteEnabled = prefs.getBoolean("double_click_favorite", true)
        val btnDoubleClickFavorite = createSettingRow(
            localize("Double-Click to Favorite"),
            com.themoon.y1.core.extensions.onOff(isDoubleClickFavoriteEnabled, localize)
        )
        btnDoubleClickFavorite.setOnClickListener {
            clickFeedback()
            isDoubleClickFavoriteEnabled = !isDoubleClickFavoriteEnabled
            prefs.edit().putBoolean("double_click_favorite", isDoubleClickFavoriteEnabled).commit()
            val tvStatus = btnDoubleClickFavorite.getChildAt(1) as? TextView
            tvStatus?.text = com.themoon.y1.core.extensions.onOff(isDoubleClickFavoriteEnabled, localize)
        }
        containerSettingsItems.addView(btnDoubleClickFavorite)

        var isUsbPromptEnabled = prefs.getBoolean("usb_storage_prompt", true)
        val btnUsbPrompt = createSettingRow(
            localize("Ask on USB Connect"),
            onOff(isUsbPromptEnabled, localize)
        )
        btnUsbPrompt.setOnClickListener {
            clickFeedback()
            isUsbPromptEnabled = !isUsbPromptEnabled
            prefs.edit().putBoolean("usb_storage_prompt", isUsbPromptEnabled).commit()
            val tvStatus = btnUsbPrompt.getChildAt(1) as? TextView
            tvStatus?.text = onOff(isUsbPromptEnabled, localize)
        }
        containerSettingsItems.addView(btnUsbPrompt)

        var backlightTimerIndex = prefs.getInt("backlight_timer_index", 3)
        if (backlightTimerIndex >= BACKLIGHT_TIMER_LABELS.size) backlightTimerIndex = 0
        val btnBacklightTimer = createSettingRow(
            localize("Backlight Timer"),
            localize(BACKLIGHT_TIMER_LABELS[backlightTimerIndex])
        )
        btnBacklightTimer.setOnClickListener {
            clickFeedback()
            backlightTimerIndex = (backlightTimerIndex + 1) % BACKLIGHT_TIMER_LABELS.size
            prefs.edit().putInt("backlight_timer_index", backlightTimerIndex).commit()
            val tvStatus = btnBacklightTimer.getChildAt(1) as? TextView
            tvStatus?.text = localize(BACKLIGHT_TIMER_LABELS[backlightTimerIndex])
            com.themoon.y1.managers.PowerBacklightManager.getInstance(activity).backlightTimerIndex = backlightTimerIndex
            resetBacklightTimer()
        }
        containerSettingsItems.addView(btnBacklightTimer)

        val btnTheme = createSettingRow(localize("Theme"), AutoThemeManager.settingsRowValue(prefs, localize))
        btnTheme.setOnClickListener {
            clickFeedback()
            buildThemeSelectorUI()
        }
        containerSettingsItems.addView(btnTheme)

        var isSwitchToggleStyle = ToggleStyle.isSwitch(activity)
        val btnToggleStyle = createSettingRow(localize("Toggle Style"), ToggleStyle.label(isSwitchToggleStyle, localize))
        btnToggleStyle.setOnClickListener {
            clickFeedback()
            isSwitchToggleStyle = !isSwitchToggleStyle
            ToggleStyle.setSwitch(activity, isSwitchToggleStyle)
            val tvStatus = btnToggleStyle.getChildAt(1) as? TextView
            tvStatus?.text = ToggleStyle.label(isSwitchToggleStyle, localize)
        }
        containerSettingsItems.addView(btnToggleStyle)

        val btnMenuVisibility = createSettingRow(localize("Main Menu Items"), localize("Edit") + " 〉")
        btnMenuVisibility.setOnClickListener {
            clickFeedback()
            buildMainMenuVisibilitySettingsUI()
        }
        containerSettingsItems.addView(btnMenuVisibility)

        val btnMusicMenuVisibility = createSettingRow(localize("Music Menu Items"), localize("Edit") + " 〉")
        btnMusicMenuVisibility.setOnClickListener {
            clickFeedback()
            buildMusicMenuVisibilitySettingsUI()
        }
        containerSettingsItems.addView(btnMusicMenuVisibility)

        val btnGroupAlbums = createSettingRow(localize("Group Albums"), groupAlbumsLabel())
        btnGroupAlbums.setOnClickListener {
            clickFeedback()
            prefs.edit().putBoolean(AlbumKey.PREF_BY_ARTIST, !AlbumKey.byArtist).commit()
            applyAlbumGrouping()
            (btnGroupAlbums.getChildAt(1) as? TextView)?.text = groupAlbumsLabel()
        }
        containerSettingsItems.addView(btnGroupAlbums)

        var isLoopScrollOn = prefs.getBoolean("loop_scroll_on", true)
        val btnLoopScrollToggle = createSettingRow(
            localize("Wheel Loop Scroll"),
            onOff(isLoopScrollOn, localize)
        )
        btnLoopScrollToggle.setOnClickListener {
            clickFeedback()
            isLoopScrollOn = !isLoopScrollOn
            prefs.edit().putBoolean("loop_scroll_on", isLoopScrollOn).commit()
            val tvStatus = btnLoopScrollToggle.getChildAt(1) as? TextView
            tvStatus?.text = onOff(isLoopScrollOn, localize)
        }
        containerSettingsItems.addView(btnLoopScrollToggle)

        var isListEdgeFade = ListEdges.isFade(activity)
        val btnListEdges = createSettingRow(
            localize("List Edges"),
            if (isListEdgeFade) localize("Fade") else localize("Cut")
        )
        btnListEdges.setOnClickListener {
            clickFeedback()
            isListEdgeFade = !isListEdgeFade
            ListEdges.setFade(activity, isListEdgeFade)
            val tvStatus = btnListEdges.getChildAt(1) as? TextView
            tvStatus?.text = if (isListEdgeFade) localize("Fade") else localize("Cut")
        }
        containerSettingsItems.addView(btnListEdges)


        val btnPowerOff = createSettingRow(localize("Power Off"), "〉 ")
        btnPowerOff.setOnClickListener {
            clickFeedback()
            mainActivity?.showPowerMenu()
        }
        containerSettingsItems.addView(btnPowerOff)

        val btnServerMenu = createSettingRow(localize("Web Server"), "〉 ")
        btnServerMenu.setOnClickListener {
            clickFeedback()
            onNavigateToScreen(MainActivity.STATE_WEBSERVER)
        }
        containerSettingsItems.addView(btnServerMenu)

        val btnWifiMenu = createSettingRow(localize("Wi-Fi"), "〉 ")
        btnWifiMenu.setOnClickListener {
            clickFeedback()
            onNavigateToScreen(MainActivity.STATE_WIFI)
        }
        containerSettingsItems.addView(btnWifiMenu)

        val btnJellyfin = createSettingRow(
            localize("Jellyfin Sync"),
            JellyfinSyncProvider.getInstance(activity).getStatusSummary()
        )
        btnJellyfin.setOnClickListener {
            clickFeedback()
            onOpenJellyfin()
        }
        containerSettingsItems.addView(btnJellyfin)

        val btnClearCache = createSettingRow(localize("Clear Album Art & Info"), "〉 ")
        btnClearCache.setOnClickListener {
            clickFeedback()
            ActionScreens.open(confirmScreen(
                localize("Clear Album Art & Info"),
                localize("Delete all downloaded album covers and saved track information (Title/Artist)?"),
                localize("Clear")
            ) { onClearCacheConfirmed() })
        }
        containerSettingsItems.addView(btnClearCache)

        val btnBtMenu = createSettingRow(localize("Bluetooth"), "〉 ")
        btnBtMenu.setOnClickListener {
            clickFeedback()
            onNavigateToScreen(MainActivity.STATE_BLUETOOTH)
        }
        containerSettingsItems.addView(btnBtMenu)

        val scrobbler = LastFmScrobbler.getInstance(activity)
        val btnScrobbleToggle = createSettingRow(
            localize("Sync Scrobbles to Last.fm"),
            onOff(scrobbler.isEnabled(), localize)
        )
        btnScrobbleToggle.setOnClickListener {
            clickFeedback()
            val newState = !scrobbler.isEnabled()
            scrobbler.setEnabled(newState)
            val tvStatus = btnScrobbleToggle.getChildAt(1) as? TextView
            tvStatus?.text = onOff(newState, localize)
        }
        containerSettingsItems.addView(btnScrobbleToggle)

        val btnLastFmAccount = createSettingRow(
            localize("Last.fm Account"),
            if (scrobbler.isLoggedIn()) "@" + scrobbler.getUsername() else localize("Not Logged In")
        )
        btnLastFmAccount.setOnClickListener {
            clickFeedback()
            if (scrobbler.isLoggedIn()) {
                ActionScreens.open(confirmScreen(
                    localize("Last.fm Account"),
                    localize("Log out of ") + "@" + scrobbler.getUsername() + "?",
                    localize("Log Out")
                ) {
                    scrobbler.logout()
                    buildSettingsUI()
                })
            } else {
                onNavigateToScreen(MainActivity.STATE_WIFI_KEYBOARD)
            }
        }
        containerSettingsItems.addView(btnLastFmAccount)

        val btnBrightMenu = createSettingRow(localize("Display Brightness"), "〉 ")
        btnBrightMenu.setOnClickListener {
            clickFeedback()
            onNavigateToScreen(MainActivity.STATE_BRIGHTNESS)
        }
        containerSettingsItems.addView(btnBrightMenu)

        val isShowPct = mainActivity?.isShowBatteryPercentage ?: prefs.getBoolean("show_battery_percentage", false)
        val btnBatteryPct = createSettingRow(
            localize("Battery Percentage"),
            onOff(isShowPct, localize)
        )
        btnBatteryPct.setOnClickListener {
            clickFeedback()
            val newPct = !(mainActivity?.isShowBatteryPercentage ?: prefs.getBoolean("show_battery_percentage", false))
            mainActivity?.isShowBatteryPercentage = newPct
            prefs.edit().putBoolean("show_battery_percentage", newPct).commit()
            val tvStatus = btnBatteryPct.getChildAt(1) as? TextView
            tvStatus?.text = onOff(newPct, localize)
            mainActivity?.batteryIconView?.setShowPercentage(newPct)
        }
        containerSettingsItems.addView(btnBatteryPct)

        // Settings → Status Bar (SettingsGroups.kt): Clock, Battery, Battery Percentage
        addStatusBarRows(btnBatteryPct)

        val btnStorageMenu = createSettingRow(localize("About"), "〉 ")
        btnStorageMenu.setOnClickListener {
            clickFeedback()
            onNavigateToScreen(MainActivity.STATE_STORAGE)
        }
        containerSettingsItems.addView(btnStorageMenu)

        val btnBgMenu = createSettingRow(localize("Background"), "〉 ")
        btnBgMenu.setOnClickListener {
            clickFeedback()
            buildBackgroundSettingsUI()
        }
        containerSettingsItems.addView(btnBgMenu)

        val btnTime = createSettingRow(localize("Date & Time"), "〉")
        btnTime.setOnClickListener {
            clickFeedback()
            val c = Calendar.getInstance()
            dtYear = c.get(Calendar.YEAR)
            dtMonth = c.get(Calendar.MONTH) + 1
            dtDay = c.get(Calendar.DAY_OF_MONTH)
            dtHour = c.get(Calendar.HOUR_OF_DAY)
            dtMinute = c.get(Calendar.MINUTE)
            buildDateTimeUI()
        }
        containerSettingsItems.addView(btnTime)

        var myVersionName = "1.0"
        try {
            myVersionName = activity.packageManager.getPackageInfo(activity.packageName, 0).versionName ?: "1.0"
        } catch (_: Exception) {}

        val displayLang = LanguageManager.getInstance(activity).currentLangFileName.replace(".json", "")
        val btnLangMenu = createSettingRow(localize("Language"), displayLang)
        btnLangMenu.setOnClickListener {
            clickFeedback()
            buildLanguageSelectorUI()
        }
        containerSettingsItems.addView(btnLangMenu)

        val btnUpdateCheck = createSettingRow(localize("System Update"), "v$myVersionName")
        btnUpdateCheck.setOnClickListener {
            clickFeedback()
            buildUpdateSettingsUI()
        }
        containerSettingsItems.addView(btnUpdateCheck)

        val btnLegal = createSettingRow(localize("Legal"), "〉 ")
        btnLegal.setOnClickListener {
            clickFeedback()
            buildLegalSettingsUI()
        }
        containerSettingsItems.addView(btnLegal)

        SettingsBackupUi.addRows(activity, containerSettingsItems, createSettingRow, clickFeedback, localize)

        val btnResetAll = createSettingRow(localize("Reset All Settings"), "〉 ")
        btnResetAll.setOnClickListener {
            clickFeedback()
            ActionScreens.open(confirmScreen(
                localize("Reset All Settings"),
                localize("Reset all settings to their default values? This won't delete any music or playlists."),
                localize("Reset")
            ) { resetAllAppSettings() })
        }
        containerSettingsItems.addView(btnResetAll)

        groupPages.arrange(containerSettingsItems, createSettingRow, clickFeedback, localize, mainActivity?.tvStatusBarTitle) {
            // A row's screen (Bluetooth, Equalizer...) comes back into this group (see onEnter)
            mainActivity?.backTargetForUtility = MainActivity.STATE_SETTINGS
            lastSettingsFocusIndex = 0
            buildSettingsUI()
        }

        val sv = containerSettingsItems.parent as? ScrollView
        sv?.post {
            if (targetFocusIndex in 0 until containerSettingsItems.childCount) {
                val target = containerSettingsItems.getChildAt(targetFocusIndex)
                if (sv != null && target != null) {
                    val offset = (sv.height / 2) - (target.height / 2)
                    val targetY = (target.top - offset).coerceAtLeast(0)
                    sv.scrollTo(0, targetY)
                }
                target?.requestFocus()
                lastSettingsFocusIndex = targetFocusIndex
            } else if (containerSettingsItems.childCount > 0) {
                val first = containerSettingsItems.getChildAt(0)
                first?.requestFocus()
                sv?.scrollTo(0, 0)
                lastSettingsFocusIndex = 0
            }
            isUiActive = true
        }
    }

    /** The Power screen: Restart and Power Off. The centre button does the action at once; Back closes the screen. */
    private fun buildPowerUI() {
        val spec = ActionScreens.Spec(localize("Power"), actions = PowerMenu.Action.values().map { action ->
            ActionScreens.Action(localize(action.label)) {
                PowerMenu.run(activity, action) {
                    Toast.makeText(
                        activity,
                        localize("System security prevents powering off directly from the app."),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        })
        buildActionUI(SettingsSubMode.Power, spec)
    }

    /**
     * An action screen (ActionScreen.kt): the title in the status bar, grey info rows, one row for each action.
     * A row closes the screen (Back), then does its action. A screen whose content is gone (an old entry) closes.
     */
    private fun buildActionUI(page: SettingsSubMode, spec: ActionScreens.Spec?) {
        actionPage = page
        settingsSubMode = 0
        currentSettingsDepth = 1
        containerSettingsItems.removeAllViews()
        if (spec == null) {
            containerSettingsItems.post { if (actionPage == page) onNavigateBack() }
            return
        }
        for (line in spec.info) containerSettingsItems.addView(ActionScreens.infoRow(activity, line))
        var firstAction: View? = null
        for (action in spec.actions) {
            val row = createSettingRow(action.label, "")
            row.setOnClickListener {
                clickFeedback()
                ActionScreens.choose(action) { onNavigateBack() }
            }
            containerSettingsItems.addView(row)
            if (firstAction == null) firstAction = row
        }
        groupPages.showTitle(mainActivity?.tvStatusBarTitle, containerSettingsItems, spec.title)
        (containerSettingsItems.parent as? ScrollView)?.scrollTo(0, 0)
        if (spec.selectAction && firstAction != null) {
            firstAction.post { firstAction.requestFocus() }
        } else {
            // No row selected: the list itself has the focus, and the first wheel step selects the first action
            containerSettingsItems.isFocusable = true
            containerSettingsItems.post { containerSettingsItems.requestFocus() }
        }
    }

    private fun buildLanguageSelectorUI() {
        currentSettingsDepth = 1
        containerSettingsItems.removeAllViews()

        val langMgr = LanguageManager.getInstance(activity)
        langMgr.loadAvailableLanguages()

        val isEngSelected = langMgr.currentLangFileName == "English (Default)"
        val btnEng = createTablerListButton(
            if (isEngSelected) TablerIcons.CHECK else null,
            "English (Default)",
            ThemeManager.getTextColorPrimary(),
            ThemeManager.getListButtonFocusedTextColor()
        )
        btnEng.setOnClickListener {
            clickFeedback()
            prefs.edit().putString("app_language", "English (Default)").commit()
            langMgr.applyLanguage("English (Default)")
            activity.recreate()
        }
        containerSettingsItems.addView(btnEng)

        for (f in langMgr.availableLangFiles) {
            val isSelected = langMgr.currentLangFileName == f.name
            val btnLang = createTablerListButton(
                if (isSelected) TablerIcons.CHECK else null,
                f.name.replace(".json", ""),
                ThemeManager.getTextColorPrimary(),
                ThemeManager.getListButtonFocusedTextColor()
            )
            btnLang.setOnClickListener {
                clickFeedback()
                prefs.edit().putString("app_language", f.name).commit()
                langMgr.applyLanguage(f.name)
                activity.recreate()
            }
            containerSettingsItems.addView(btnLang)
        }

        if (containerSettingsItems.childCount > 0) {
            containerSettingsItems.getChildAt(0)?.requestFocus()
        }
    }

    fun buildThemeSelectorUI() {
        currentSettingsDepth = 1
        val themeFolder = File(activity.filesDir, "themes")
        ThemeManager.loadThemesFromStorage(themeFolder)
        containerSettingsItems.removeAllViews()
        containerSettingsItems.addView(
            AutoThemeManager.createSelectorRow(activity, prefs, createTablerListButton, clickFeedback, localize)
        )

        for (i in 0 until ThemeManager.availableThemes.size) {
            val theme = ThemeManager.availableThemes[i]
            val isSelected = ThemeManager.getCurrentThemeIndex() == i && !AutoThemeManager.isEnabled(prefs)
            val icon = if (isSelected) TablerIcons.CHECK else null
            val btn = createTablerListButton(
                icon,
                localize(theme.name),
                ThemeManager.getTextColorPrimary(),
                ThemeManager.getListButtonFocusedTextColor()
            )
            btn.setOnClickListener {
                clickFeedback()
                AutoThemeManager.turnOff(activity, prefs)
                // Live: the theme list is made again in the new theme, with the focus on this row
                mainActivity?.changeTheme(theme.name)
            }
            containerSettingsItems.addView(btn)
        }

        if (containerSettingsItems.childCount > 0) {
            containerSettingsItems.getChildAt(0)?.requestFocus()
        }
        themeListFirstRow = containerSettingsItems.getChildAt(0)
    }

    fun buildBackgroundSettingsUI() {
        currentSettingsDepth = 1
        containerSettingsItems.removeAllViews()

        val btnSelectBg = createSettingRow(localize("Select New Background"), "〉 ")
        btnSelectBg.setOnClickListener {
            clickFeedback()
            onPickBackgroundFromStorage()
        }
        containerSettingsItems.addView(btnSelectBg)

        val btnThemeBg = createSettingRow(localize("Apply Theme Background"), "〉 ")
        btnThemeBg.setOnClickListener {
            clickFeedback()
            onApplyThemeBackground()
        }
        containerSettingsItems.addView(btnThemeBg)

        val btnClearBg = createSettingRow(localize("Clear Custom Background"), "〉 ")
        btnClearBg.setOnClickListener {
            clickFeedback()
            onClearCustomBackground()
        }
        containerSettingsItems.addView(btnClearBg)

        if (containerSettingsItems.childCount > 0) {
            containerSettingsItems.getChildAt(0)?.requestFocus()
        }
    }

    fun buildDateTimeUI() {
        currentSettingsDepth = 1
        containerSettingsItems.removeAllViews()

        var is24HourFormat = prefs.getBoolean(ClockSettings.KEY_24_HOUR_FORMAT, ClockSettings.DEFAULT_24_HOUR_FORMAT)
        val formatRightText = if (is24HourFormat) localize("24 Hour") else localize("12 Hour")
        val rowFormat = createSettingRow(localize("Time Format"), formatRightText)
        rowFormat.setOnClickListener {
            clickFeedback()
            is24HourFormat = !is24HourFormat
            mainActivity?.is24HourFormat = is24HourFormat
            prefs.edit().putBoolean(ClockSettings.KEY_24_HOUR_FORMAT, is24HourFormat).commit()
            mainActivity?.onClockSettingsChanged()
            buildDateTimeUI()
        }
        containerSettingsItems.addView(rowFormat)
        addDateTimeValueRows()
    }

    /**
     * Settings → Status Bar: the Battery row (show or hide the battery icon) and the Clock row. Battery Percentage
     * ([batteryPercentRow], made in buildSettingsUI) is grey and the wheel skips it while the battery icon is hidden.
     */
    private fun addStatusBarRows(batteryPercentRow: LinearLayout) {
        val showBattery = StatusBarSettings.isBatteryShown(prefs)
        val btnBattery = createSettingRow(localize("Battery"), onOff(showBattery, localize))
        btnBattery.setOnClickListener {
            clickFeedback()
            StatusBarSettings.setBatteryShown(mainActivity, prefs, !StatusBarSettings.isBatteryShown(prefs))
            // Again, for the state of Battery Percentage (the focus stays on this row)
            buildSettingsUI()
        }
        containerSettingsItems.addView(btnBattery)
        StatusBarSettings.setRowEnabled(batteryPercentRow, showBattery)
        addClockRow()
    }

    /**
     * The clock in the status bar ("Time in Title"), shown as Settings → Status Bar → Clock.
     * Before the Settings groups, it was a row of Date & Time.
     */
    private fun addClockRow() {
        var isTimeInTitleBar = prefs.getBoolean(ClockSettings.KEY_TIME_IN_TITLE, ClockSettings.DEFAULT_TIME_IN_TITLE)
        val rowTitleClock = createSettingRow(
            localize("Time in Title"),
            onOff(isTimeInTitleBar, localize)
        )
        rowTitleClock.setOnClickListener {
            clickFeedback()
            isTimeInTitleBar = !isTimeInTitleBar
            mainActivity?.isTimeInTitleBar = isTimeInTitleBar
            prefs.edit().putBoolean(ClockSettings.KEY_TIME_IN_TITLE, isTimeInTitleBar).commit()
            mainActivity?.onClockSettingsChanged()
            val tvStatus = rowTitleClock.getChildAt(1) as? TextView
            tvStatus?.text = onOff(isTimeInTitleBar, localize)
        }
        containerSettingsItems.addView(rowTitleClock)
    }

    /** The rows of Date & Time after Time Format. */
    private fun addDateTimeValueRows() {
        val rowTimeZone = createSettingRow(localize("Time Zone"), timeZoneCityName(TimeZone.getDefault().id))
        rowTimeZone.setOnClickListener {
            clickFeedback()
            buildTimeZoneSelectorUI()
        }
        containerSettingsItems.addView(rowTimeZone)

        val rowYear = createSettingRow(localize("Year"), dtYear.toString())
        rowYear.setOnClickListener {
            clickFeedback()
            buildDateTimeSelectorUI("Year", 2020, 2035, dtYear)
        }
        containerSettingsItems.addView(rowYear)

        val rowMonth = createSettingRow(localize("Month"), String.format(Locale.US, "%02d", dtMonth))
        rowMonth.setOnClickListener {
            clickFeedback()
            buildDateTimeSelectorUI("Month", 1, 12, dtMonth)
        }
        containerSettingsItems.addView(rowMonth)

        val rowDay = createSettingRow(localize("Day"), String.format(Locale.US, "%02d", dtDay))
        rowDay.setOnClickListener {
            clickFeedback()
            buildDateTimeSelectorUI("Day", 1, 31, dtDay)
        }
        containerSettingsItems.addView(rowDay)

        val rowHour = createSettingRow(localize("Hour (24H)"), String.format(Locale.US, "%02d", dtHour))
        rowHour.setOnClickListener {
            clickFeedback()
            buildDateTimeSelectorUI("Hour", 0, 23, dtHour)
        }
        containerSettingsItems.addView(rowHour)

        val rowMinute = createSettingRow(localize("Minute"), String.format(Locale.US, "%02d", dtMinute))
        rowMinute.setOnClickListener {
            clickFeedback()
            buildDateTimeSelectorUI("Minute", 0, 59, dtMinute)
        }
        containerSettingsItems.addView(rowMinute)

        // A new value applies at once (setClockField): no Apply button.
        addSyncTimeRow()

        if (containerSettingsItems.childCount > 0) {
            containerSettingsItems.getChildAt(0)?.requestFocus()
        }
    }

    // Date & Time → Sync Time Now: the last result (shown as the row value for SYNC_RESULT_MS), and the row itself
    private var syncResult: String? = null
    private var syncResultAt = 0L
    private var syncRunning = false
    private var syncRow: View? = null
    private val SYNC_RESULT_MS = 120000L

    /** Date & Time → Sync Time Now: sets the clock from the internet now (DateTimeSync). Only with Wi-Fi, else grey. */
    private fun addSyncTimeRow() {
        val online = DateTimeSync.isWifiConnected(activity)
        val recent = syncResult?.takeIf { android.os.SystemClock.elapsedRealtime() - syncResultAt < SYNC_RESULT_MS }
        val value = when {
            !online -> localize("Requires Wi-Fi")
            syncRunning -> localize("Syncing…")
            else -> recent ?: ""
        }
        val row = createSettingRow(localize("Sync Time Now"), value)
        if (!online) {
            // Grey, and the wheel does not stop on it (onWheel skips rows that are not focusable)
            row.isFocusable = false
            for (i in 0 until row.childCount) row.getChildAt(i).alpha = 0.4f
        }
        row.setOnClickListener {
            if (syncRunning) return@setOnClickListener
            clickFeedback()
            syncRunning = true
            (row.getChildAt(1) as? TextView)?.text = localize("Syncing…")
            Thread {
                var error: String? = "No answer from the time server."
                for (host in DateTimeSync.servers(activity)) {
                    val time = DateTimeSync.query(host) ?: continue
                    error = DateTimeSync.setSystemTime(time.now(), manual = false)
                    break
                }
                activity.runOnUiThread { onSyncDone(error) }
            }.start()
        }
        containerSettingsItems.addView(row)
        syncRow = row
    }

    private fun onSyncDone(error: String?) {
        syncRunning = false
        val is24Hour = prefs.getBoolean("is_24h_format", true)
        val time = java.text.SimpleDateFormat(if (is24Hour) "HH:mm:ss" else "h:mm:ss a", Locale.US).format(java.util.Date())
        syncResult = if (error == null) localize("Set to") + " " + time else localize("Failed")
        syncResultAt = android.os.SystemClock.elapsedRealtime()
        val message = if (error == null) localize("Time set from the internet:") + " " + time else localize(error)
        Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()
        refreshDateTimeScreen()
    }

    /** A value from the Year...Minute list: the clock changes at once, the other values stay (DateTimeSync.withField). */
    private fun setClockField(field: String, value: Int) {
        val target = DateTimeSync.withField(System.currentTimeMillis(), TimeZone.getDefault(), field, value)
        Thread {
            val error = DateTimeSync.setSystemTime(target, manual = true)
            activity.runOnUiThread {
                if (error != null) Toast.makeText(activity, localize(error), Toast.LENGTH_SHORT).show()
                refreshDateTimeScreen()
            }
        }.start()
    }

    /** Back from a value list: the focus on the row of that value (Year...Minute), not on the first row. */
    private fun focusDateTimeRow(label: String) {
        val title = localize(label)
        for (i in 0 until containerSettingsItems.childCount) {
            val row = containerSettingsItems.getChildAt(i) as? ViewGroup ?: continue
            if ((row.getChildAt(0) as? TextView)?.text?.toString() == title) {
                row.requestFocus()
                return
            }
        }
    }

    /** Takes Year...Minute from the clock again, and shows them if the Date & Time list shows (the focus stays). */
    private fun refreshDateTimeScreen() {
        val c = Calendar.getInstance()
        dtYear = c.get(Calendar.YEAR)
        dtMonth = c.get(Calendar.MONTH) + 1
        dtDay = c.get(Calendar.DAY_OF_MONTH)
        dtHour = c.get(Calendar.HOUR_OF_DAY)
        dtMinute = c.get(Calendar.MINUTE)
        val row = syncRow ?: return
        if (currentSettingsDepth != 1 || containerSettingsItems.indexOfChild(row) < 0) return
        val focus = containerSettingsItems.findFocus()
        val index = if (focus != null) containerSettingsItems.indexOfChild(focus) else -1
        buildDateTimeUI()
        if (index >= 0) containerSettingsItems.getChildAt(index)?.requestFocus()
    }

    // Time Zone list: su runs, so a second click waits
    private var timeZoneRunning = false

    /** Sets the zone through su on a background thread, then calls [onDone] on the main thread. */
    private fun applySystemTimeZone(zoneId: String, onDone: () -> Unit) {
        timeZoneRunning = true
        Thread {
            val ok = try {
                // AlarmManager.setTimeZone needs SET_TIME_ZONE (signature|system), so call it through su.
                // Transaction 3 is IAlarmManager.setTimeZone on API 19: it saves persist.sys.timezone,
                // updates the kernel zone and broadcasts ACTION_TIMEZONE_CHANGED to refresh the clocks.
                val proc = Runtime.getRuntime().exec(arrayOf("su", "-c", "service call alarm 3 s16 $zoneId"))
                proc.waitFor()
                true
            } catch (_: Exception) {
                false
            }
            activity.runOnUiThread {
                timeZoneRunning = false
                TimeZone.setDefault(null)
                if (!ok || TimeZone.getDefault().id != zoneId) {
                    Toast.makeText(activity, localize("Failed: Root access required."), Toast.LENGTH_SHORT).show()
                }
                onDone()
            }
        }.start()
    }

    private fun buildTimeZoneSelectorUI() {
        currentSettingsDepth = 2
        containerSettingsItems.removeAllViews()

        val currentId = TimeZone.getDefault().id
        val now = System.currentTimeMillis()
        val ids = if (currentId in TIME_ZONE_IDS) TIME_ZONE_IDS else arrayOf(currentId) + TIME_ZONE_IDS
        var focusBtn: View? = null
        for (id in ids.sortedBy { TimeZone.getTimeZone(it).getOffset(now) }) {
            val isSelected = id == currentId
            val btn = createTablerListButton(
                if (isSelected) TablerIcons.CHECK else null,
                timeZoneLabel(id, now),
                ThemeManager.getTextColorPrimary(),
                ThemeManager.getListButtonFocusedTextColor()
            )
            btn.setOnClickListener {
                if (timeZoneRunning) return@setOnClickListener
                clickFeedback()
                applySystemTimeZone(id) {
                    // The clock itself is unchanged, but its local time moved, so reload the picker values
                    refreshDateTimeScreen()
                    // Back to Date & Time, if the user did not leave this list meanwhile
                    if (containerSettingsItems.indexOfChild(btn) >= 0) buildDateTimeUI()
                }
            }
            containerSettingsItems.addView(btn)
            if (isSelected) focusBtn = btn
        }

        focusBtn?.requestFocus() ?: containerSettingsItems.getChildAt(0)?.requestFocus()
    }

    private fun buildDateTimeSelectorUI(type: String, min: Int, max: Int, currentValue: Int) {
        currentSettingsDepth = 2
        containerSettingsItems.removeAllViews()

        var focusBtn: Button? = null
        for (i in min..max) {
            val valNum = i
            val displayVal = if (type == "Minute" || type == "Hour" || type == "Month" || type == "Day") {
                String.format(Locale.US, "%02d", valNum)
            } else {
                valNum.toString()
            }
            val btn = createListButton(displayVal)
            btn.gravity = Gravity.CENTER
            btn.setOnClickListener {
                clickFeedback()
                when (type) {
                    "Year" -> dtYear = valNum
                    "Month" -> dtMonth = valNum
                    "Day" -> dtDay = valNum
                    "Hour" -> dtHour = valNum
                    "Minute" -> dtMinute = valNum
                }
                setClockField(type, valNum)
                buildDateTimeUI()
                focusDateTimeRow(if (type == "Hour") "Hour (24H)" else type)
            }
            containerSettingsItems.addView(btn)
            if (valNum == currentValue) focusBtn = btn
        }

        focusBtn?.requestFocus() ?: containerSettingsItems.getChildAt(0)?.requestFocus()
    }

    /** Volume Limit: the loudest volume step (1..30, see VolumeSteps), or Off. */
    private fun buildVolumeLimitSettingsUI() {
        currentSettingsDepth = 1
        containerSettingsItems.removeAllViews()

        val volumeLimitMax = prefs.getInt(com.themoon.y1.managers.VolumeSteps.PREF_LIMIT, -1)
        var focusBtn: Button? = null

        val btnOff = createListButton(localize("Off"))
        btnOff.gravity = Gravity.CENTER
        btnOff.setOnClickListener {
            clickFeedback()
            mainActivity?.setVolumeLimit(-1)
            buildSettingsUI()
        }
        containerSettingsItems.addView(btnOff)
        if (volumeLimitMax < 0) focusBtn = btnOff

        for (i in 1..com.themoon.y1.managers.VolumeSteps.STEPS) {
            val level = i
            val btn = createListButton(level.toString())
            btn.gravity = Gravity.CENTER
            btn.setOnClickListener {
                clickFeedback()
                mainActivity?.setVolumeLimit(level)
                buildSettingsUI()
            }
            containerSettingsItems.addView(btn)
            if (level == volumeLimitMax) focusBtn = btn
        }

        focusBtn?.requestFocus() ?: containerSettingsItems.getChildAt(0)?.requestFocus()
    }

    private fun buildLegalSettingsUI() {
        currentSettingsDepth = 1
        containerSettingsItems.removeAllViews()

        val tvLegal = TextView(activity).apply {
            text = "JJ Launcher Classic Version\n\n" +
                "A fan-made, non-commercial fork of JJ Launcher (github.com/ismileblue/y1_launcher), " +
                "built to bring an iPod Classic-style experience to the Innioasis Y1.\n\n" +
                "This software is not affiliated with or endorsed by Apple Inc.\n\n" +
                "Open source components used:\n" +
                "- ExoPlayer (Apache License 2.0)\n" +
                "- OkHttp (Apache License 2.0)\n" +
                "- Conscrypt (Apache License 2.0)\n" +
                "- Nimbus Sans (URW++, GUST Font License)\n\n" +
                "See the project repository for full license texts and source code."
            setTextColor(ThemeManager.getTextColorPrimary())
            textSize = 14f
            setLineSpacing(6f, 1.2f)
            val d = resources.displayMetrics.density
            setPadding((10 * d).toInt(), 0, (10 * d).toInt(), 0)
        }
        containerSettingsItems.addView(tvLegal)
    }

    private fun buildUpdateSettingsUI() {
        currentSettingsDepth = 1
        containerSettingsItems.removeAllViews()

        var myVersionName = "1.0"
        try {
            val pInfo = activity.packageManager.getPackageInfo(activity.packageName, 0)
            myVersionName = pInfo.versionName ?: "1.0"
        } catch (_: Exception) {}

        val rowCurrent = createSettingRow(
            localize("Current Version"),
            if (myVersionName.startsWith("v")) myVersionName else "v$myVersionName"
        )
        containerSettingsItems.addView(rowCurrent)

        createCategoryHeader("━━━━━━━━━━━━━━")

        val btnCheckUpdates = createTablerListButton(
            TablerIcons.REFRESH,
            localize("Check for Updates"),
            ThemeManager.getTextColorPrimary(),
            ThemeManager.getListButtonFocusedTextColor()
        )
        btnCheckUpdates.setOnClickListener {
            clickFeedback()
            onOpenUpdateCheck()
        }
        containerSettingsItems.addView(btnCheckUpdates)

        btnCheckUpdates.post { btnCheckUpdates.requestFocus() }
    }

    private fun buildMainMenuVisibilitySettingsUI() {
        currentSettingsDepth = 1
        settingsSubMode = 4
        containerSettingsItems.removeAllViews()

        val buttons = ArrayList<ThemeManager.MenuElement>()
        for (el in ThemeManager.getCurrentTheme().menuElements) {
            if (el.type == "button") buttons.add(el)
        }
        sortMenuElements(buttons)

        if (materialIconFont == null) {
            try {
                materialIconFont = Typeface.createFromAsset(activity.assets, "fonts/MaterialIcons-Regular.ttf")
            } catch (_: Exception) {}
        }

        for (el in buttons) {
            val isHidden = prefs.getBoolean("hide_btn_" + el.id, false)
            val btnName = if (!el.textNormal.isNullOrBlank()) el.textNormal else el.id
            val stateIcon = if (isHidden) "\uE5CD" else "\uE8F4"

            val row = createSettingRow(btnName, stateIcon)
            row.tag = el.id
            row.isFocusable = true

            val tvRight = row.getChildAt(1) as? TextView
            if (materialIconFont != null) {
                tvRight?.typeface = materialIconFont
                tvRight?.textSize = 22f
                tvRight?.let { centreIconInRow(it) }
            }

            // Keep the row highlight of createSettingRow: this listener replaces its listener.
            val rowHighlight = row.onFocusChangeListener
            row.setOnFocusChangeListener { v, hasFocus ->
                if (isMenuReorderMode) return@setOnFocusChangeListener
                rowHighlight?.onFocusChange(v, hasFocus)
                val currHidden = prefs.getBoolean("hide_btn_" + el.id, false)
                if (hasFocus) {
                    tvRight?.setTextColor(ThemeManager.getListButtonFocusedTextColor())
                } else {
                    tvRight?.setTextColor(
                        if (currHidden) ThemeManager.getTextColorSecondary() else ThemeManager.getTextColorPrimary()
                    )
                }
            }

            row.setOnLongClickListener {
                clickFeedback()
                if (!isMenuReorderMode) toggleMenuReorderMode()
                true
            }

            row.setOnClickListener {
                if (isMenuReorderMode) {
                    clickFeedback()
                    toggleMenuReorderMode()
                    return@setOnClickListener
                }
                clickFeedback()
                val newState = !prefs.getBoolean("hide_btn_" + el.id, false)
                prefs.edit().putBoolean("hide_btn_" + el.id, newState).commit()
                com.themoon.y1.FeatureVisibility.onMenuButtonToggled(activity, el.action, newState)
                tvRight?.text = if (newState) "\uE5CD" else "\uE8F4"
                if (row.hasFocus()) {
                    tvRight?.setTextColor(ThemeManager.getListButtonFocusedTextColor())
                } else {
                    tvRight?.setTextColor(
                        if (newState) ThemeManager.getTextColorSecondary() else ThemeManager.getTextColorPrimary()
                    )
                }
            }
            containerSettingsItems.addView(row)
        }
        containerSettingsItems.getChildAt(0)?.requestFocus()
    }

    private fun buildMusicMenuVisibilitySettingsUI() {
        currentSettingsDepth = 1
        containerSettingsItems.removeAllViews()

        if (materialIconFont == null) {
            try {
                materialIconFont = Typeface.createFromAsset(activity.assets, "fonts/MaterialIcons-Regular.ttf")
            } catch (_: Exception) {}
        }

        for (item in com.themoon.y1.controllers.browser.MusicMenuItems.ITEMS) {
            val id = item.id
            val isHidden = com.themoon.y1.controllers.browser.MusicMenuItems.isHidden(prefs, id)
            val stateIcon = if (isHidden) "\uE5CD" else "\uE8F4"

            val row = createSettingRow(localize(item.label), stateIcon)
            val tvRight = row.getChildAt(1) as? TextView
            if (materialIconFont != null) {
                tvRight?.typeface = materialIconFont
                tvRight?.textSize = 22f
                tvRight?.let { centreIconInRow(it) }
            }
            tvRight?.setTextColor(
                if (isHidden) ThemeManager.getTextColorSecondary() else ThemeManager.getTextColorPrimary()
            )

            // Keep the row highlight of createSettingRow: this listener replaces its listener.
            val rowHighlight = row.onFocusChangeListener
            row.setOnFocusChangeListener { v, hasFocus ->
                rowHighlight?.onFocusChange(v, hasFocus)
                val currHidden = com.themoon.y1.controllers.browser.MusicMenuItems.isHidden(prefs, id)
                if (hasFocus) {
                    tvRight?.setTextColor(ThemeManager.getListButtonFocusedTextColor())
                } else {
                    tvRight?.setTextColor(
                        if (currHidden) ThemeManager.getTextColorSecondary() else ThemeManager.getTextColorPrimary()
                    )
                }
            }

            row.setOnClickListener {
                clickFeedback()
                val newState = !com.themoon.y1.controllers.browser.MusicMenuItems.isHidden(prefs, id)
                prefs.edit().putBoolean("hide_music_btn_$id", newState).commit()
                tvRight?.text = if (newState) "\uE5CD" else "\uE8F4"
                if (row.hasFocus()) {
                    tvRight?.setTextColor(ThemeManager.getListButtonFocusedTextColor())
                } else {
                    tvRight?.setTextColor(
                        if (newState) ThemeManager.getTextColorSecondary() else ThemeManager.getTextColorPrimary()
                    )
                }
            }
            containerSettingsItems.addView(row)
        }
        containerSettingsItems.getChildAt(0)?.requestFocus()
    }

    private fun sortMenuElements(buttons: ArrayList<ThemeManager.MenuElement>) {
        com.themoon.y1.controllers.menu.MenuOrder.sort(buttons, prefs)
    }

    /**
     * The icon glyphs fill the em box of the icon font (no descent), so a box of one line without font
     * padding, centred in the row, centres the icons. A list style can have set a top padding for text.
     */
    private fun centreIconInRow(tv: TextView) {
        tv.includeFontPadding = false
        tv.gravity = Gravity.CENTER_VERTICAL
        tv.setPadding(tv.paddingLeft, 0, tv.paddingRight, 0)
        (tv.layoutParams as? LinearLayout.LayoutParams)?.let {
            it.height = ViewGroup.LayoutParams.WRAP_CONTENT
            it.gravity = Gravity.CENTER_VERTICAL
            tv.layoutParams = it
        }
    }

    fun toggleMenuReorderMode() {
        // Material icon "swap_vert", from the same font as the eye icon. The font has no "↕" (and no space),
        // so a fallback glyph sat on the icon baseline, below the row centre.
        val reorderMark = "\uE8D5"
        if (!isMenuReorderMode) {
            val focused = activity.currentFocus
            if (focused != null && focused.parent == containerSettingsItems) {
                isMenuReorderMode = true
                currentReorderRow = focused
                currentReorderRow?.setBackgroundColor(0xFFD32F2F.toInt())
                val tvRight = (currentReorderRow as? LinearLayout)?.getChildAt(1) as? TextView
                val currentIcon = tvRight?.text?.toString()?.replace(reorderMark, "") ?: ""
                tvRight?.text = currentIcon + reorderMark
                tvRight?.setTextColor(0xFFFFFFFF.toInt())
            }
        } else {
            isMenuReorderMode = false
            if (currentReorderRow != null) {
                // Replace the red reorder background with the normal row highlight.
                currentReorderRow?.let { it.onFocusChangeListener?.onFocusChange(it, it.hasFocus()) }
                val tvRight = (currentReorderRow as? LinearLayout)?.getChildAt(1) as? TextView
                val currentIcon = tvRight?.text?.toString()?.replace(reorderMark, "") ?: ""
                tvRight?.text = currentIcon
                tvRight?.setTextColor(ThemeManager.getListButtonFocusedTextColor())
                currentReorderRow = null
            }
            val sb = StringBuilder()
            for (i in 0 until containerSettingsItems.childCount) {
                val child = containerSettingsItems.getChildAt(i)
                val tagStr = child.tag as? String
                if (!tagStr.isNullOrEmpty()) {
                    sb.append(tagStr).append(",")
                }
            }
            com.themoon.y1.controllers.menu.MenuOrder.save(prefs, sb.toString())
            Toast.makeText(activity, localize("Menu order saved!"), Toast.LENGTH_SHORT).show()
        }
    }

    private fun groupAlbumsLabel(): String = localize(if (AlbumKey.byArtist) "By Title and Artist" else "By Title")

    /** Reads Group Albums and rebuilds the album lists, so the next Albums or Cover Flow screen uses it. */
    private fun applyAlbumGrouping() {
        AlbumKey.byArtist = prefs.getBoolean(AlbumKey.PREF_BY_ARTIST, false)
        mainActivity?.rebuildUniqueAlbumList()
    }

    private fun resetAllAppSettings() {
        AutoThemeManager.reset(activity, prefs)
        try {
            prefs.edit()
                .remove(ClockSettings.KEY_24_HOUR_FORMAT)
                .remove(ClockSettings.KEY_TIME_IN_TITLE)
                .remove("show_battery_percentage")
                .remove(StatusBarSettings.KEY_SHOW_BATTERY)
                .remove("volume_limit_max")
                .remove(ToggleStyle.PREF_KEY)
                .remove("sound_check_enabled")
                .remove("video_stretch_fill")
                .remove("album_tilt_enabled")
                .remove("backlight_timer_index")
                .remove(com.themoon.y1.managers.ClickVolume.PREF)
                .remove("screen_off_control")
                .remove("double_click_favorite")
                .remove("repeat_mode")
                .remove("loop_scroll_on")
                .remove(ListEdges.PREF_KEY)
                .remove("eq_preset")
                .remove("eq_profile_id")
                .remove(AlbumKey.PREF_BY_ARTIST)
                .remove(com.themoon.y1.views.CoverPane.PREF)
                .commit()
        } catch (_: Exception) {}

        mainActivity?.isSoundCheckEnabled = false
        AudioEffectManager.getInstance().applySoundCheck()
        ToggleStyle.reload(activity)
        AudioEffectManager.getInstance().applyEqProfile()
        applyAlbumTiltSetting()
        ListEdges.reload(activity)
        com.themoon.y1.managers.PowerBacklightManager.getInstance(activity).backlightTimerIndex = 3
        resetBacklightTimer()
        applyAlbumGrouping()

        // The player keeps its own repeat mode: set it back to Off too.
        mainActivity?.repeatMode = 0
        AudioPlayerManager.getInstance().setRepeatMode(0)

        mainActivity?.isScreenOffControlEnabled = false
        mainActivity?.isShowBatteryPercentage = false
        mainActivity?.isTimeInTitleBar = ClockSettings.DEFAULT_TIME_IN_TITLE
        mainActivity?.is24HourFormat = ClockSettings.DEFAULT_24_HOUR_FORMAT
        mainActivity?.onClockSettingsChanged()
        mainActivity?.batteryIconView?.setShowPercentage(false)
        mainActivity?.setVolumeLimit(-1)
        mainActivity?.let { StatusBarSettings.applyBattery(it) }

        Toast.makeText(activity, localize("All settings have been reset."), Toast.LENGTH_SHORT).show()
        buildSettingsUI()
    }
}
