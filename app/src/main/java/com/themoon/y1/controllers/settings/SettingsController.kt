package com.themoon.y1.controllers.settings

import com.themoon.y1.lists.ListScreen
import com.themoon.y1.navigation.RowFocus
import android.app.Activity
import android.content.Context
import android.graphics.Typeface
import android.provider.Settings
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.themoon.y1.Prefs
import com.themoon.y1.battery.BatteryUsageView
import com.themoon.y1.MainActivity
import com.themoon.y1.R
import com.themoon.y1.changeTheme
import com.themoon.y1.FeatureVisibility
import com.themoon.y1.ThemeManager
import com.themoon.y1.controllers.ScreenController
import com.themoon.y1.controllers.browser.MusicMenuItems
import com.themoon.y1.controllers.menu.MenuOrder
import com.themoon.y1.managers.AudioEffectManager
import com.themoon.y1.managers.AudioPlayerManager
import com.themoon.y1.managers.AutoThemeManager
import com.themoon.y1.managers.ClickVolume
import com.themoon.y1.managers.PowerBacklightManager
import com.themoon.y1.managers.VolumeSteps
import com.themoon.y1.managers.LanguageManager
import com.themoon.y1.managers.LastFmScrobbler
import com.themoon.y1.managers.PowerMenu
import com.themoon.y1.lists.Focus
import com.themoon.y1.lists.ListSpec
import com.themoon.y1.lists.RowLook
import com.themoon.y1.lists.RowSpec
import com.themoon.y1.models.AlbumKey
import com.themoon.y1.navigation.NavigationManager
import com.themoon.y1.navigation.ScreenState
import com.themoon.y1.navigation.SettingsSubMode
import com.themoon.y1.sync.jellyfin.JellyfinSyncProvider
import com.themoon.y1.views.ListEdges
import com.themoon.y1.views.ListItemViewFactory
import com.themoon.y1.views.Notice
import com.themoon.y1.views.TablerIcons
import com.themoon.y1.views.ToggleStyle
import java.io.File
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * The Settings screens: the top level (one row for each group), the group pages, and the screens that the rows open
 * (value lists, Date & Time, Main Menu Items...). Each list is a back-stack entry (ScreenState.Settings(page)).
 * The rows are one spec ([groups], A11): a setting is one line, and its group is where the line is.
 */
class SettingsController(
    private val activity: Activity,
    override val rootView: ViewGroup,
    private val eqPresetNames: List<String>,
    private val createSettingRow: (title: String, value: String) -> LinearLayout,
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
    private val setShuffle: (Boolean) -> Unit,
    private val setRepeatMode: (Int) -> Unit,
    private val localize: (String) -> String
) : ScreenController {

    private val containerSettingsItems: LinearLayout = rootView.findViewById(R.id.container_settings_items)
    private val prefs = activity.getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
    private val mainActivity: MainActivity? get() = activity as? MainActivity
    private val screen = ListScreen(containerSettingsItems, clickFeedback = clickFeedback)
    private val rows = SettingRows(prefs, localize) { refresh() }

    private var isUiActive = false
    /** The open action screen (Power, or a screen in place of a dialog: ActionScreen.kt), or null. Back goes to the screen before it. */
    private var actionPage: SettingsSubMode? = null
    private val isActionScreen: Boolean get() = actionPage != null
    /** The status bar title of the list that shows. */
    private var shownTitle: String? = null

    // The values of the Date & Time rows (Year...Minute): read from the clock when Date & Time opens and after a change
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
        /** The page of Settings → Battery. */
        const val BATTERY_USAGE = "BatteryUsage"

        /** The values of each Date & Time row's screen (Settings → Date & Time → Year...). */
        val DATE_TIME_RANGES = mapOf("Year" to 2020..2035, "Month" to 1..12, "Day" to 1..31, "Hour" to 0..23, "Minute" to 0..59)

        /** Backlight Timer is the one screen timer: it also sets Android's SCREEN_OFF_TIMEOUT (see BacklightTimer). */
        val BACKLIGHT_TIMER_LABELS = com.themoon.y1.managers.BacklightTimer.LABELS
        val VIBE_STRENGTH_NAMES = com.themoon.y1.managers.ButtonVibration.LABELS
        val SPEED_LABELS = arrayOf("1.0x (Normal)", "1.2x (Fast)", "1.5x (Faster)", "2.0x (Very Fast)")
        val SPEED_VALUES = floatArrayOf(1.0f, 1.2f, 1.5f, 2.0f)

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

    /**
     * The Settings groups and their rows, in the order of the top level. One line for each setting: its label (the
     * shown English text), its pref or value, and its effect.
     */
    private val groups: List<SettingsGroup> by lazy { with(rows) { listOf(
        SettingsGroup("Playback", listOf(
            toggle("Shuffle", { Prefs.SHUFFLE.get(prefs) }, setShuffle),
            cycle("Repeat", { Prefs.REPEAT_MODE.get(prefs).let { if (it in 0..2) it else 0 } }, setRepeatMode, arrayOf("Off", "One", "All")),
            action("EQ", { eqName() + " 〉" }) { onOpenEqualizer() },
            toggle("Sound Check", Prefs.SOUND_CHECK) { AudioEffectManager.getInstance().applySoundCheck() },
            choice("Volume Limit", { volumeLimitText() + " 〉" }) { openPage("VolumeLimit") },
            cycle("Playback Speed", { speedIndex() }, { setSpeed(it) }, SPEED_LABELS),
        )),
        SettingsGroup("Display", listOf(
            action("Brightness") { onNavigateToScreen(MainActivity.STATE_BRIGHTNESS) },
            cycle("Backlight", Prefs.BACKLIGHT_TIMER, BACKLIGHT_TIMER_LABELS) {
                PowerBacklightManager.getInstance(activity).backlightTimerIndex = it
                resetBacklightTimer()
            },
        )),
        // What Now Playing shows (the cover tilt is only on Now Playing: the group gives the place)
        SettingsGroup("Now Playing", listOf(
            toggle("Spectrum", { mainActivity?.isVisualizerShowing == true }, { mainActivity?.toggleVisualizer() }),
            toggle("Total Time", Prefs.TOTAL_TIME),
            // The screen stays on while the lyrics show on Now Playing and music plays (LyricsKeepScreenOn)
            toggle("Lyrics Keep Screen On", Prefs.LYRICS_KEEP_SCREEN_ON),
            toggle("Cover Tilt", Prefs.ALBUM_TILT) { applyAlbumTiltSetting() },
        )),
        SettingsGroup("Status Bar", listOf(
            toggle("Clock", Prefs.TIME_IN_TITLE) { mainActivity?.onClockSettingsChanged() },
            toggle("Battery Icon", Prefs.SHOW_BATTERY) { mainActivity?.let { StatusBarSettings.applyBattery(it) } },
            // Grey, and the wheel skips it, while the battery icon is hidden
            toggle("Battery Percentage", Prefs.BATTERY_PERCENTAGE, enabled = { Prefs.SHOW_BATTERY.get(prefs) }) {
                mainActivity?.batteryIconView?.setShowPercentage(it)
            },
        )),
        SettingsGroup("Appearance", listOf(
            choice("Theme", { AutoThemeManager.settingsRowValue(prefs, localize) }) { openPage("Theme") },
            action("Background") { openPage("Background") },
            toggle("Toggle Style", { ToggleStyle.isSwitch(activity) }, { ToggleStyle.setSwitch(activity, it) }, { ToggleStyle.label(it, localize) }),
            toggle("List Edges", { ListEdges.isFade(activity) }, { ListEdges.setFade(activity, it) }, { localize(if (it) "Fade" else "Cut") }),
            // Slow pan and zoom of the art in the split screens (main menu, Music, Audiobooks, Videos), as on the iPod
            toggle("Menu Cover Motion", Prefs.MENU_COVER_MOTION),
            toggle("Fill Video Screen", Prefs.VIDEO_FILL),
        )),
        SettingsGroup("Menus", listOf(
            action("Main Menu Items", { localize("Edit") + " 〉" }) { openPage("MainMenuItems") },
            action("Music Menu Items", { localize("Edit") + " 〉" }) { openPage("MusicMenuItems") },
            toggle("Group Albums", Prefs.GROUP_ALBUMS_BY_ARTIST, { localize(if (it) "By Title and Artist" else "By Title") }) {
                applyAlbumGrouping()
            },
        )),
        // The rows that change the click sound or vibration give the click after the change: it plays the new value
        SettingsGroup("Controls", listOf(
            toggle("Button Sound", Prefs.BUTTON_SOUND, feedbackAfter = true) { applySoundSetting() },
            cycle("Click Volume", Prefs.CLICK_VOLUME, ClickVolume.LABELS, ClickVolume::validLevel, feedbackAfter = true),
            toggle("Button Vibration", Prefs.VIBRATE, feedbackAfter = true) {
                // Always off, as in MainActivity: else Android also vibrates on each key press
                try {
                    Settings.System.putInt(activity.contentResolver, Settings.System.HAPTIC_FEEDBACK_ENABLED, 0)
                } catch (_: Exception) {}
            },
            // Grey, and the wheel skips it, while Button Vibration is off
            cycle("Vibration Strength", Prefs.VIBRATE_STRENGTH, VIBE_STRENGTH_NAMES, { if (it in VIBE_STRENGTH_NAMES.indices) it else 1 },
                enabled = { Prefs.VIBRATE.get(prefs) }, feedbackAfter = true),
            toggle("Wheel Loop Scroll", Prefs.LOOP_SCROLL),
            toggle("Screen-Off Control", Prefs.SCREEN_OFF_CONTROL),
            toggle("Double-Click to Favorite", Prefs.DOUBLE_CLICK_FAVORITE),
        )),
        SettingsGroup("Connectivity", listOf(
            action("Bluetooth") { onNavigateToScreen(MainActivity.STATE_BLUETOOTH) },
            action("Wi-Fi") { onNavigateToScreen(MainActivity.STATE_WIFI) },
            action("Web Server") { onNavigateToScreen(MainActivity.STATE_WEBSERVER) },
            toggle("Ask on USB Connect", Prefs.USB_PROMPT),
        )),
        // Battery level, charging, screen on and screen off playing of the last 24 hours or 10 days (BatteryUsageView):
        // on the top level, as Settings → Battery of iOS and macOS (macOS: after the connections)
        SettingsGroup("Battery", listOf(
            action("Battery", { "〉" }) { openPage(BATTERY_USAGE) },
        ), direct = true),
        SettingsGroup("Library", listOf(
            action("Clear Album Art & Info") {
                ActionScreens.open(confirmScreen(
                    localize("Clear Album Art & Info"),
                    localize("Delete all downloaded album covers and saved track information (Title/Artist)?"),
                    localize("Clear")
                ) { onClearCacheConfirmed() })
            },
            action("Jellyfin Sync", { JellyfinSyncProvider.getInstance(activity).getStatusSummary() }) { onOpenJellyfin() },
            toggle("Sync Scrobbles to Last.fm", { scrobbler().isEnabled() }, { scrobbler().setEnabled(it) }),
            action("Last.fm Account", { lastFmAccountText() }) { openLastFmAccount() },
        )),
        SettingsGroup("Date & Time", listOf(
            action("Date & Time", { "〉" }) {
                readClock()
                openPage("DateTime")
            },
        ), direct = true),
        SettingsGroup("General", listOf(
            action("About") { onNavigateToScreen(MainActivity.STATE_STORAGE) },
            choice("Language", { LanguageManager.getInstance(activity).currentLangFileName.replace(".json", "") }) { openPage("Language") },
            action("System Update", { "v" + versionName() }) { openPage("Update") },
            action("Legal") { openPage("Legal") },
            action("Export Settings") { SettingsBackupUi.exportSettings(activity, localize) },
            action("Import Settings") { SettingsBackupUi.confirmImport(activity, localize) },
            action("Reset Settings") {
                ActionScreens.open(confirmScreen(
                    localize("Reset All Settings"),
                    localize("Reset all settings to their default values? This won't delete any music or playlists."),
                    localize("Reset")
                ) { resetAllAppSettings() })
            },
            // The Power screen (Restart, Power Off), as a long press of the Power button opens it
            action("Power") { mainActivity?.showPowerMenu() },
        )),
    ) } }

    /** The list that shows, as its route key: the top level, a group, or the screen of a row (a back-stack entry). */
    private var page: SettingsSubMode = SettingsSubMode.Root

    /** Builds the list of [state]: when it opens going down, and when Back comes back to it (NavigationManager). */
    override fun onEnter(state: ScreenState) {
        endMenuReorder()
        page = (state as? ScreenState.Settings)?.subMode ?: SettingsSubMode.Root
        isUiActive = true
        actionPage = null
        when (val shown = page) {
            is SettingsSubMode.Power -> buildPowerUI()
            is SettingsSubMode.Action -> buildActionUI(shown, ActionScreens.spec(shown.key))
            else -> show(pageSpec(shown))
        }
    }

    private fun show(spec: ListSpec) {
        shownTitle = spec.title
        screen.show(spec)
    }

    /** The list of a route key. A key that this build has no list for (an old group) shows the top level. */
    private fun pageSpec(shown: SettingsSubMode): ListSpec {
        if (shown is SettingsSubMode.Group) {
            val group = groups.firstOrNull { it.title == shown.title && it.rows.isNotEmpty() }
            if (group != null) return ListSpec(localize(group.title), group.rows.map { it.make() })
        }
        if (shown is SettingsSubMode.Page) {
            when (shown.id) {
                "Language" -> return languageList()
                "Theme" -> return themeList()
                "Background" -> return backgroundSpec()
                "DateTime" -> {
                    val range = DATE_TIME_RANGES[shown.arg]
                    return if (range == null) dateTimeSpec() else dateTimeValueList(shown.arg, range)
                }
                "TimeZone" -> return timeZoneList()
                "VolumeLimit" -> return volumeLimitList()
                "Legal" -> return legalSpec()
                "Update" -> return updateSpec()
                "MainMenuItems" -> return mainMenuItemsSpec()
                "MusicMenuItems" -> return musicMenuItemsSpec()
                BATTERY_USAGE -> return batteryUsageSpec()
            }
        }
        return topLevelSpec()
    }

    /** The top level: one row for each group that has rows in this build (a direct group: its row itself). */
    private fun topLevelSpec(): ListSpec = ListSpec(localize("Settings"), groups.filter { it.rows.isNotEmpty() }.map { group ->
        if (group.direct) group.rows.first().make()
        else RowSpec(localize(group.title), RowLook.VALUE, value = SettingRows.CHEVRON, onClick = {
            mainActivity?.changeScreen(ScreenState.Settings(SettingsSubMode.Group(group.title)))
        })
    })

    /** A value changed: the list that shows is made again, and the selected row stays. */
    private fun refresh() {
        if (!isUiActive || isActionScreen) return
        val spec = pageSpec(page)
        shownTitle = spec.title
        screen.update(spec)
    }

    /** Opens the screen of a row. When it is the list under this one (a value was chosen), Back goes to it. */
    private fun openPage(id: String, arg: String = "") {
        val state = ScreenState.Settings(SettingsSubMode.Page(id, arg))
        val nav = NavigationManager.getInstance()
        if (nav.previousState() == state) nav.popBackStack() else mainActivity?.changeScreen(state)
    }

    override fun title(): String? = shownTitle

    override fun onExit() {
        endMenuReorder()
        isUiActive = false
        actionPage = null
        containerSettingsItems.isFocusable = false
    }

    override fun onRefreshUI() {
        val shownAction = actionPage
        when {
            // The same action screen again (new info rows): buildActionUI keeps the selected row; onEnter would not
            shownAction is SettingsSubMode.Action -> buildActionUI(shownAction, ActionScreens.spec(shownAction.key))
            shownAction != null -> buildPowerUI()
            page is SettingsSubMode.Root || page is SettingsSubMode.Group -> refresh()
        }
    }

    /** All lists are made again from their route key; the theme change then selects the same row (applyThemeLive). */
    override fun onThemeChanged() {
        onEnter(ScreenState.Settings(page))
    }

    /** Back: the list of the top back-stack entry (NavigationManager builds it again and selects its row). */
    override fun onBackPressed(): Boolean {
        if (isActionScreen) {
            ActionScreens.cancel((actionPage as? SettingsSubMode.Action)?.let { ActionScreens.spec(it.key) }) { onNavigateBack() }
            return true
        }
        if (isMenuReorderMode) {
            toggleMenuReorderMode()
            return true
        }
        onNavigateBack()
        return true
    }

    private val isMainMenuItems: Boolean get() = page == SettingsSubMode.Page("MainMenuItems")

    override fun onWheel(delta: Int, event: KeyEvent): Boolean {
        if (isMainMenuItems && isMenuReorderMode && currentReorderRow != null) {
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

        if (page == SettingsSubMode.Page(BATTERY_USAGE)) {
            // The wheel changes the range: down = Last 10 Days, up = Last 24 Hours
            if (batteryUsageView?.step(delta) == true) clickFeedback()
            return true
        }
        RowFocus.step(containerSettingsItems, delta) ?: return false
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
        if (isMainMenuItems && (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)) {
            if (!isMenuReorderMode) {
                toggleMenuReorderMode()
                clickFeedback()
            }
            return true
        }
        return false
    }

    // --- Values and effects of the rows ---

    private fun eqName(): String {
        val profile = Prefs.EQ_PROFILE.get(prefs)
        if (!profile.startsWith("preset_")) return profile.replace("custom_", "")
        val index = profile.replace("preset_", "").toIntOrNull() ?: 0
        return if (index < eqPresetNames.size) localize(eqPresetNames[index]) else "Normal"
    }

    private fun volumeLimitText(): String = Prefs.VOLUME_LIMIT.get(prefs).let { if (it < 0) localize("Off") else it.toString() }

    private fun speedIndex(): Int = SPEED_VALUES.indexOfLast { it == AudioPlayerManager.getInstance().getCurrentSpeed() }.coerceAtLeast(0)

    private fun setSpeed(index: Int) {
        AudioPlayerManager.getInstance().setPlaybackSpeed(SPEED_VALUES[index])
        Notice.show(activity, localize("Speed set to ") + localize(SPEED_LABELS[index]))
    }

    private fun scrobbler() = LastFmScrobbler.getInstance(activity)

    private fun lastFmAccountText(): String =
        if (scrobbler().isLoggedIn()) "@" + scrobbler().getUsername() else localize("Not Logged In")

    private fun openLastFmAccount() {
        val scrobbler = scrobbler()
        if (!scrobbler.isLoggedIn()) {
            onNavigateToScreen(MainActivity.STATE_WIFI_KEYBOARD)
            return
        }
        ActionScreens.open(confirmScreen(
            localize("Last.fm Account"),
            localize("Log out of ") + "@" + scrobbler.getUsername() + "?",
            localize("Log Out")
        ) {
            scrobbler.logout()
            refresh()
        })
    }

    private fun versionName(): String = try {
        activity.packageManager.getPackageInfo(activity.packageName, 0).versionName ?: "1.0"
    } catch (_: Exception) {
        "1.0"
    }

    /** The Power screen: Restart and Power Off. The centre button does the action at once; Back closes the screen. */
    private fun buildPowerUI() {
        val spec = ActionScreens.Spec(localize("Power"), actions = PowerMenu.Action.values().map { action ->
            ActionScreens.Action(localize(action.label)) {
                PowerMenu.run(activity, action) {
                    Notice.error(
                        activity,
                        localize("System security prevents powering off directly from the app.")
                    )
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
        // The same screen again (new info rows of a progress screen, a theme change): the selected row stays
        val again = actionPage == page
        actionPage = page
        if (spec == null) {
            screen.clear()
            containerSettingsItems.post { if (actionPage == page) onNavigateBack() }
            return
        }
        val list = ActionScreens.list(spec) { onNavigateBack() }
        if (again) screen.update(list) else screen.show(list)
        shownTitle = spec.title
        mainActivity?.tvStatusBarTitle?.text = spec.title
    }

    // --- The screens that the rows open ---

    private fun languageList(): ListSpec {
        val langMgr = LanguageManager.getInstance(activity)
        langMgr.loadAvailableLanguages()
        val files = listOf("English (Default)") + langMgr.availableLangFiles.map { it.name }
        return rows.valueList(localize("Language"), files.map { name ->
            SettingValue(name.replace(".json", ""), langMgr.currentLangFileName == name) {
                Prefs.LANGUAGE.set(prefs, name)
                langMgr.applyLanguage(name)
                activity.recreate()
            }
        }, focus = Focus.FIRST)
    }

    /** Auto, then the themes. A theme applies live: the list is made again in the new theme, with the same row. */
    private fun themeList(): ListSpec {
        ThemeManager.loadThemesFromStorage(File(activity.filesDir, "themes"))
        val auto = AutoThemeManager.isEnabled(prefs)
        val themes = rows.valueList(localize("Theme"), ThemeManager.availableThemes.mapIndexed { i, theme ->
            SettingValue(localize(theme.name), ThemeManager.getCurrentThemeIndex() == i && !auto) {
                AutoThemeManager.turnOff(activity, prefs)
                mainActivity?.changeTheme(theme.name)
            }
        }, focus = Focus.FIRST)
        val autoRow = AutoThemeManager.createSelectorRow(activity, prefs, createTablerListButton, clickFeedback, localize)
        return ListSpec(themes.title, listOf(RowSpec(localize("Auto"), RowLook.CUSTOM, view = autoRow)) + themes.rows)
    }

    /** Background: the main menu background (an image from the memory, the theme's image, or none). */
    private fun backgroundSpec(): ListSpec = ListSpec(localize("Background"), listOf(
        RowSpec(localize("Select New Background"), RowLook.VALUE, value = SettingRows.CHEVRON, onClick = onPickBackgroundFromStorage),
        RowSpec(localize("Apply Theme Background"), RowLook.VALUE, value = SettingRows.CHEVRON, onClick = onApplyThemeBackground),
        RowSpec(localize("Clear Custom Background"), RowLook.VALUE, value = SettingRows.CHEVRON, onClick = onClearCustomBackground)
    ))

    /** Volume Limit: the loudest volume step (1..30, see VolumeSteps), or Off. */
    private fun volumeLimitList(): ListSpec {
        val limit = Prefs.VOLUME_LIMIT.get(prefs)
        val values = listOf(-1) + (1..VolumeSteps.STEPS)
        return rows.valueList(localize("Volume Limit"), values.map { level ->
            SettingValue(if (level < 0) localize("Off") else level.toString(), level == limit) {
                mainActivity?.setVolumeLimit(level)
                onNavigateBack()
            }
        }, view = ::centredRow)
    }

    private fun centredRow(text: String): View = ListItemViewFactory.createListButtonSimple(activity, text).apply { gravity = Gravity.CENTER }

    private fun readClock() {
        val c = Calendar.getInstance()
        dtYear = c.get(Calendar.YEAR)
        dtMonth = c.get(Calendar.MONTH) + 1
        dtDay = c.get(Calendar.DAY_OF_MONTH)
        dtHour = c.get(Calendar.HOUR_OF_DAY)
        dtMinute = c.get(Calendar.MINUTE)
    }

    private fun dateTimeValue(type: String): Int = when (type) {
        "Year" -> dtYear
        "Month" -> dtMonth
        "Day" -> dtDay
        "Hour" -> dtHour
        else -> dtMinute
    }

    private fun twoDigits(value: Int): String = String.format(Locale.US, "%02d", value)

    /** Date & Time: Time Format, Time Zone, Year...Minute (a new value applies at once: no Apply row), Sync Time Now. */
    private fun dateTimeSpec(): ListSpec {
        val is24Hour = Prefs.CLOCK_24_HOUR.get(prefs)
        val list = ArrayList<RowSpec>()
        list += RowSpec(localize("Time Format"), RowLook.VALUE, value = localize(if (is24Hour) "24 Hour" else "12 Hour"), onClick = {
            Prefs.CLOCK_24_HOUR.toggle(prefs)
            mainActivity?.onClockSettingsChanged()
            refresh()
        })
        list += RowSpec(localize("Time Zone"), RowLook.VALUE, value = timeZoneCityName(TimeZone.getDefault().id), onClick = { openPage("TimeZone") })
        for ((type, label) in listOf("Year" to "Year", "Month" to "Month", "Day" to "Day", "Hour" to "Hour (24H)", "Minute" to "Minute")) {
            val value = dateTimeValue(type)
            list += RowSpec(localize(label), RowLook.VALUE, value = if (type == "Year") value.toString() else twoDigits(value),
                onClick = { openPage("DateTime", type) })
        }
        list += syncTimeRow()
        return ListSpec(localize("Date & Time"), list)
    }

    /** Year...Minute: the clock changes at once, the other values stay (DateTimeSync.withField); then Back to Date & Time. */
    private fun dateTimeValueList(type: String, range: IntRange): ListSpec {
        val current = dateTimeValue(type)
        return rows.valueList(localize(if (type == "Hour") "Hour (24H)" else type), range.map { value ->
            SettingValue(if (type == "Year") value.toString() else twoDigits(value), value == current) {
                when (type) {
                    "Year" -> dtYear = value
                    "Month" -> dtMonth = value
                    "Day" -> dtDay = value
                    "Hour" -> dtHour = value
                    "Minute" -> dtMinute = value
                }
                setClockField(type, value)
                onNavigateBack()
            }
        }, view = ::centredRow)
    }

    // Date & Time → Sync Time Now: the last result (shown as the row value for SYNC_RESULT_MS)
    private var syncResult: String? = null
    private var syncResultAt = 0L
    private var syncRunning = false
    private val SYNC_RESULT_MS = 120000L

    /** Date & Time → Sync Time Now: sets the clock from the internet now (DateTimeSync). Only with Wi-Fi, else grey. */
    private fun syncTimeRow(): RowSpec {
        val online = DateTimeSync.isWifiConnected(activity)
        val recent = syncResult?.takeIf { android.os.SystemClock.elapsedRealtime() - syncResultAt < SYNC_RESULT_MS }
        val value = when {
            !online -> localize("Requires Wi-Fi")
            syncRunning -> localize("Syncing…")
            else -> recent ?: ""
        }
        // While it runs, the row does nothing (no click)
        return RowSpec(localize("Sync Time Now"), RowLook.VALUE, value = value, enabled = online, onClick = if (syncRunning) null else ({
            syncRunning = true
            refresh()
            Thread {
                var error: String? = "No answer from the time server."
                for (host in DateTimeSync.servers(activity)) {
                    val time = DateTimeSync.query(host) ?: continue
                    error = DateTimeSync.setSystemTime(time.now(), manual = false)
                    break
                }
                activity.runOnUiThread { onSyncDone(error) }
            }.start()
        }))
    }

    private fun onSyncDone(error: String?) {
        syncRunning = false
        val is24Hour = Prefs.CLOCK_24_HOUR.get(prefs)
        val time = java.text.SimpleDateFormat(if (is24Hour) "HH:mm:ss" else "h:mm:ss a", Locale.US).format(java.util.Date())
        syncResult = if (error == null) localize("Set to") + " " + time else localize("Failed")
        syncResultAt = android.os.SystemClock.elapsedRealtime()
        val message = if (error == null) localize("Time set from the internet:") + " " + time else localize(error)
        Notice.show(activity, message)
        refreshDateTimeScreen()
    }

    /** A value from the Year...Minute list: the clock changes at once, the other values stay (DateTimeSync.withField). */
    private fun setClockField(field: String, value: Int) {
        val target = DateTimeSync.withField(System.currentTimeMillis(), TimeZone.getDefault(), field, value)
        Thread {
            val error = DateTimeSync.setSystemTime(target, manual = true)
            activity.runOnUiThread {
                if (error != null) Notice.error(activity, localize(error))
                refreshDateTimeScreen()
            }
        }.start()
    }

    /** Takes Year...Minute from the clock again, and shows them if the Date & Time list shows (the focus stays). */
    private fun refreshDateTimeScreen() {
        readClock()
        if (page == SettingsSubMode.Page("DateTime")) refresh()
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
                    Notice.error(activity, localize("Failed: Root access required."))
                }
                onDone()
            }
        }.start()
    }

    private fun timeZoneList(): ListSpec {
        val currentId = TimeZone.getDefault().id
        val now = System.currentTimeMillis()
        val ids = if (currentId in TIME_ZONE_IDS) TIME_ZONE_IDS else arrayOf(currentId) + TIME_ZONE_IDS
        val timeZone = SettingsSubMode.Page("TimeZone")
        return rows.valueList(localize("Time Zone"), ids.sortedBy { TimeZone.getTimeZone(it).getOffset(now) }.map { id ->
            SettingValue(timeZoneLabel(id, now), id == currentId) {
                if (!timeZoneRunning) applySystemTimeZone(id) {
                    // The clock itself is unchanged, but its local time moved, so reload the picker values
                    readClock()
                    // Back to Date & Time, if the user did not leave this list meanwhile
                    if (isUiActive && page == timeZone) onNavigateBack()
                }
            }
        })
    }

    private fun legalSpec(): ListSpec {
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
        return ListSpec(localize("Legal"), listOf(RowSpec("legal", RowLook.CUSTOM, view = tvLegal)))
    }

    /** System Update: the version, a divider, and Check for Updates (selected). */
    private fun updateSpec(): ListSpec {
        val version = versionName()
        // Made in a vertical list, as in the Settings list: the full width
        val divider = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
            .also { ListItemViewFactory.createCategoryHeader(activity, it, "━━━━━━━━━━━━━━") }.getChildAt(0)
        val check = createTablerListButton(TablerIcons.REFRESH, localize("Check for Updates"),
            ThemeManager.getTextColorPrimary(), ThemeManager.getListButtonFocusedTextColor())
        return ListSpec(localize("System Update"), listOf(
            RowSpec(localize("Current Version"), RowLook.VALUE, value = if (version.startsWith("v")) version else "v$version"),
            RowSpec("divider", RowLook.CUSTOM, view = divider),
            RowSpec(localize("Check for Updates"), RowLook.CUSTOM, view = check, checked = true, onClick = { onOpenUpdateCheck() })
        ), focus = Focus.CHECKED)
    }

    private var batteryUsageView: BatteryUsageView? = null

    /**
     * Settings → Battery: one view with the range and the two charts; the centre button also changes the range. The range
     * stays when the view is made again (a theme change, the next visit).
     */
    private fun batteryUsageSpec(): ListSpec {
        val tenDays = batteryUsageView?.tenDays == true
        val view = BatteryUsageView(activity, localize) { Prefs.CLOCK_24_HOUR.get(prefs) }.apply {
            this.tenDays = tenDays
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        batteryUsageView = view
        return ListSpec(localize("Battery"), listOf(RowSpec(BATTERY_USAGE, RowLook.CUSTOM, view = view, onClick = { view.toggle() })))
    }

    // --- Main Menu Items and Music Menu Items ---

    private fun mainMenuItemsSpec(): ListSpec {
        val buttons = ArrayList<ThemeManager.MenuElement>()
        for (el in ThemeManager.getCurrentTheme().menuElements) {
            if (el.type == "button") buttons.add(el)
        }
        MenuOrder.sort(buttons, prefs)
        return ListSpec(localize("Main Menu Items"), buttons.map { el ->
            val key = Prefs.MAIN_MENU_HIDDEN.key(el.id)
            val name = if (!el.textNormal.isNullOrBlank()) el.textNormal else el.id
            val row = visibilityRow(name, { prefs.getBoolean(key, false) }, reorderable = true) { hidden ->
                prefs.edit().putBoolean(key, hidden).commit()
                FeatureVisibility.onMenuButtonToggled(activity, el.action, hidden)
            }
            row.tag = el.id
            RowSpec(name, RowLook.CUSTOM, view = row)
        })
    }

    private fun musicMenuItemsSpec(): ListSpec = ListSpec(localize("Music Menu Items"), MusicMenuItems.ITEMS.map { item ->
        val label = localize(item.label)
        val row = visibilityRow(label, { MusicMenuItems.isHidden(prefs, item.id) }, reorderable = false) { hidden ->
            prefs.edit().putBoolean(Prefs.MUSIC_MENU_HIDDEN.key(item.id), hidden).commit()
        }
        RowSpec(label, RowLook.CUSTOM, view = row)
    })

    /**
     * A row of Main Menu Items or Music Menu Items: an eye (shown) or a cross (hidden) on the right; a push changes it.
     * [reorderable] (Main Menu Items): a hold starts the reorder, and the eye keeps the row colour until the row
     * was selected once (as before: Music Menu Items colours it at once).
     */
    private fun visibilityRow(label: String, isHidden: () -> Boolean, reorderable: Boolean, setHidden: (Boolean) -> Unit): View {
        if (materialIconFont == null) {
            try {
                materialIconFont = Typeface.createFromAsset(activity.assets, "fonts/MaterialIcons-Regular.ttf")
            } catch (_: Exception) {}
        }
        fun icon(hidden: Boolean) = if (hidden) "" else ""
        val row = createSettingRow(label, icon(isHidden()))
        row.isFocusable = true
        val tvRight = row.getChildAt(1) as? TextView
        if (materialIconFont != null) {
            tvRight?.typeface = materialIconFont
            tvRight?.textSize = 22f
            tvRight?.let { centreIconInRow(it) }
        }
        fun colourIcon(hidden: Boolean, focused: Boolean = row.hasFocus()) {
            tvRight?.setTextColor(when {
                focused -> ThemeManager.getListButtonFocusedTextColor()
                hidden -> ThemeManager.getTextColorSecondary()
                else -> ThemeManager.getTextColorPrimary()
            })
        }
        if (!reorderable) colourIcon(isHidden())
        // Keep the row highlight of createSettingRow: this listener replaces its listener.
        val rowHighlight = row.onFocusChangeListener
        row.setOnFocusChangeListener { v, hasFocus ->
            if (reorderable && isMenuReorderMode) return@setOnFocusChangeListener
            rowHighlight?.onFocusChange(v, hasFocus)
            colourIcon(isHidden(), hasFocus)
        }
        if (reorderable) {
            row.setOnLongClickListener {
                clickFeedback()
                if (!isMenuReorderMode) toggleMenuReorderMode()
                true
            }
        }
        row.setOnClickListener {
            clickFeedback()
            if (reorderable && isMenuReorderMode) {
                toggleMenuReorderMode()
                return@setOnClickListener
            }
            val hidden = !isHidden()
            setHidden(hidden)
            tvRight?.text = icon(hidden)
            colourIcon(hidden)
        }
        return row
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

    /**
     * New rows replace the Main Menu Items rows (another page, Power, a theme change): end the reorder and save
     * the order first. Later, the moved row is not in the list, and a save would read the rows of the other page.
     */
    private fun endMenuReorder() {
        if (isMenuReorderMode) toggleMenuReorderMode()
    }

    fun toggleMenuReorderMode() {
        // Material icon "swap_vert", from the same font as the eye icon. The font has no "↕" (and no space),
        // so a fallback glyph sat on the icon baseline, below the row centre.
        val reorderMark = ""
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
            MenuOrder.save(prefs, sb.toString())
            Notice.show(activity, localize("Menu order saved!"))
        }
    }

    /** Reads Group Albums and rebuilds the album lists, so the next Albums or Cover Flow screen uses it. */
    private fun applyAlbumGrouping() {
        AlbumKey.byArtist = Prefs.GROUP_ALBUMS_BY_ARTIST.get(prefs)
        mainActivity?.rebuildUniqueAlbumList()
    }

    private fun resetAllAppSettings() {
        AutoThemeManager.reset(activity, prefs)
        try {
            // The keys with resettable = true in Prefs
            Prefs.resetAll(prefs)
        } catch (_: Exception) {}

        AudioEffectManager.getInstance().applySoundCheck()
        ToggleStyle.reload(activity)
        AudioEffectManager.getInstance().applyEqProfile()
        applyAlbumTiltSetting()
        ListEdges.reload(activity)
        PowerBacklightManager.getInstance(activity).backlightTimerIndex = 3
        resetBacklightTimer()
        applyAlbumGrouping()

        // The player keeps its own repeat mode: set it back to Off too.
        AudioPlayerManager.getInstance().setRepeatMode(0)

        mainActivity?.onClockSettingsChanged()
        mainActivity?.batteryIconView?.setShowPercentage(false)
        mainActivity?.setVolumeLimit(-1)
        mainActivity?.let { StatusBarSettings.applyBattery(it) }

        Notice.show(activity, localize("All settings have been reset."))
        refresh()
    }
}
