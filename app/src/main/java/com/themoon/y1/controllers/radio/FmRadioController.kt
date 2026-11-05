package com.themoon.y1.controllers.radio

import android.app.Activity
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.themoon.y1.controllers.settings.ActionScreens
import com.themoon.y1.controllers.settings.confirmScreen
import com.themoon.y1.MainActivity
import com.themoon.y1.R
import com.themoon.y1.ThemeManager
import com.themoon.y1.controllers.ScreenController
import com.themoon.y1.core.extensions.onOff
import com.themoon.y1.managers.AudioPlayerManager
import com.themoon.y1.managers.FmRadioManager
import com.themoon.y1.navigation.NavigationManager
import com.themoon.y1.navigation.ScreenState
import com.themoon.y1.views.Notice
import java.util.Locale

/**
 * The FM radio, as on the iPod nano (5th generation): three screens, each a back-stack entry, so they slide as
 * the other menus.
 * - Tuner ([ScreenState.FmRadio]): opens directly from the main menu and turns the radio on. The wheel tunes,
 *   the centre button turns the radio on or off, a held Next/Previous seeks. Back (Menu) goes to the Radio menu.
 * - Radio menu ([ScreenState.FmRadioMenu]): Tuner, Radio Power, Save Channel, Saved Channels, Auto Scan All.
 *   It is always under the tuner ([NavigationManager.setBackTarget]); Back goes to the screen before the radio.
 * - Saved Channels ([ScreenState.FmRadioSaved]): the centre button plays a channel and goes to the tuner;
 *   a held centre button deletes it (after a confirmation).
 * Play/Pause and a short Next/Previous control the radio from all screens (managers/AudioSources).
 */
class FmRadioController(
    private val activity: Activity,
    override val rootView: ViewGroup,
    private val createSettingRow: (title: String, value: String) -> LinearLayout,
    private val createCategoryHeader: (title: String) -> Unit,
    private val createListButton: (text: String) -> Button,
    private val createListButtonWithIcon: (icon: String, text: String) -> View,
    private val clickFeedback: () -> Unit,
    private val updateGlobalStatusPlayIcon: () -> Unit,
    private val onExitRadio: () -> Unit,
    private val showLoadingPopup: () -> Unit,
    private val dismissLoadingOverlay: () -> Unit,
    private val updateLoadingProgress: (progress: Int, message: String) -> Unit,
    private val localize: (String) -> String
) : ScreenController {

    /** The radio screen that shows. */
    enum class Page { TUNER, MENU, SAVED }

    private val containerSettingsItems: LinearLayout = rootView.findViewById(R.id.container_settings_items)
    private val prefs = activity.getSharedPreferences("Y1_SETTINGS", Context.MODE_PRIVATE)
    private val fmManager = FmRadioManager.getInstance(activity)

    val savedRadioStations = ArrayList<Float>()
    var isRadioUIShowing: Boolean = false
    var isRadioScanning: Boolean = false
    var page: Page = Page.TUNER
        private set

    private var layoutRadioCandyContainer: LinearLayout? = null
    private var lastRadioPowerToggleTime: Long = 0
    private var lastSeekTime: Long = 0
    private var hasPromptedRadioScan: Boolean = false

    init {
        loadSavedRadioStations()
        // The radio plays the last station again (the frequency was saved but not read)
        if (!fmManager.isPowerUp) fmManager.currentFreq = loadLastRadioFreq()
    }

    override fun onEnter(state: ScreenState) {
        isRadioUIShowing = true
        page = pageOf(state)
        when (page) {
            Page.TUNER -> {
                val nav = NavigationManager.getInstance()
                val opened = opensRadio(nav.previousState())
                nav.setBackTarget(ScreenState.FmRadioMenu)
                buildTunerUI()
                // After the slide: the power-up takes some time on the main thread
                if (opened) containerSettingsItems.postDelayed({ onRadioOpened() }, START_DELAY_MS)
            }
            Page.MENU -> buildMenuUI(0)
            Page.SAVED -> buildSavedUI(-1)
        }
    }

    override fun onExit() {
        isRadioUIShowing = false
    }

    override fun onRefreshUI() {
        refresh()
    }

    /** The status bar title of the screen that shows. */
    fun title(): String = localize(if (isRadioUIShowing && page == Page.SAVED) "Saved Channels" else "Radio")

    /** Shows the radio state again on the screen that shows; a list keeps its selected row. */
    fun refresh() {
        if (!isRadioUIShowing) return
        when (page) {
            Page.TUNER -> updateRadioMainPlayerUI()
            Page.MENU -> buildMenuUI(focusedRow())
            Page.SAVED -> buildSavedUI(focusedRow())
        }
    }

    /** As the iPod: choosing the radio in a menu plays it. With no saved channel, offer a scan once. */
    private fun onRadioOpened() {
        if (!isRadioUIShowing || page != Page.TUNER) return
        if (!fmManager.isPowerUp) toggleRadioPower()
        promptInitialRadioScan()
    }

    fun loadSavedRadioStations() {
        try {
            savedRadioStations.clear()
            val raw = prefs.getString("radio_stations", "") ?: ""
            if (raw.isNotEmpty()) {
                val parts = raw.split(",")
                for (p in parts) {
                    try {
                        val freq = p.trim().toFloat()
                        val cleanFreq = Math.round(freq * 10.0f) / 10.0f
                        if (!savedRadioStations.contains(cleanFreq)) {
                            savedRadioStations.add(cleanFreq)
                        }
                    } catch (ignored: Exception) {}
                }
                savedRadioStations.sort()
            }
        } catch (ignored: Exception) {}
    }

    fun saveRadioStationsToPrefs() {
        try {
            val sb = StringBuilder()
            for (i in savedRadioStations.indices) {
                sb.append(String.format(Locale.US, "%.1f", savedRadioStations[i]))
                if (i < savedRadioStations.size - 1) {
                    sb.append(",")
                }
            }
            prefs.edit().putString("radio_stations", sb.toString()).apply()
        } catch (ignored: Exception) {}
    }

    fun saveLastRadioFreq(freq: Float) {
        val clean = Math.round(freq * 10.0f) / 10.0f
        prefs.edit().putFloat("radio_last_freq", clean).apply()
    }

    fun loadLastRadioFreq(): Float {
        return prefs.getFloat("radio_last_freq", 87.5f)
    }

    fun tuneToNextSavedRadioChannel(isNext: Boolean) {
        if (savedRadioStations.isEmpty()) {
            Notice.show(activity, localize("No saved channels."))
            return
        }
        var target = savedRadioStations[0]
        if (isNext) {
            for (f in savedRadioStations) {
                if (f > fmManager.currentFreq) {
                    target = f
                    break
                }
            }
        } else {
            target = savedRadioStations[savedRadioStations.size - 1]
            for (i in savedRadioStations.size - 1 downTo 0) {
                if (savedRadioStations[i] < fmManager.currentFreq) {
                    target = savedRadioStations[i]
                    break
                }
            }
        }
        tuneTo(target)
        refresh()
    }

    private fun tuneTo(freq: Float) {
        val clean = Math.round(freq * 10.0f) / 10.0f
        if (fmManager.isPowerUp) {
            fmManager.tune(clean)
        } else {
            fmManager.currentFreq = clean
        }
        saveLastRadioFreq(clean)
    }

    fun toggleRadioPower() {
        val now = System.currentTimeMillis()
        if (now - lastRadioPowerToggleTime < 800) {
            return
        }
        lastRadioPowerToggleTime = now
        clickFeedback()

        if (fmManager.isPowerUp) {
            fmManager.powerDown()
            activity.volumeControlStream = AudioManager.STREAM_MUSIC
        } else {
            val audioPlayerManager = AudioPlayerManager.getInstance()
            if (audioPlayerManager.isPlaying()) {
                audioPlayerManager.playOrPauseMusic()
            }
            try {
                Thread.sleep(50)
            } catch (ignored: Exception) {}

            if (fmManager.powerUp(fmManager.currentFreq)) {
                fmManager.routeFmAudio()
                activity.volumeControlStream = fmManager.getFmStreamType()
            } else {
                Notice.error(activity, "Radio Error: ${fmManager.lastError}")
            }
        }

        updateGlobalStatusPlayIcon()
        refresh()
    }

    fun seekRadioStation(isUp: Boolean) {
        if (!fmManager.isPowerUp) return
        Notice.show(activity, localize("Seeking FM Station..."))
        val found = fmManager.seekStation(isUp)
        if (found > 0) {
            val clean = Math.round(found * 10.0f) / 10.0f
            saveLastRadioFreq(clean)
            refresh()
        }
    }

    fun promptInitialRadioScan() {
        if (savedRadioStations.isNotEmpty() || hasPromptedRadioScan) return
        hasPromptedRadioScan = true

        if (!isRadioUIShowing) return
        ActionScreens.open(confirmScreen(
            localize("FM Radio"),
            localize("No saved stations found.\nWould you like to scan for FM channels now?"),
            localize("Scan")
        ) { startRadioAutoScan() })
    }

    fun startRadioAutoScan() {
        if (isRadioScanning) return

        if (!fmManager.isPowerUp) {
            val audioPlayerManager = AudioPlayerManager.getInstance()
            if (audioPlayerManager.isPlaying()) {
                audioPlayerManager.playOrPauseMusic()
            }
            if (!fmManager.powerUp(fmManager.currentFreq)) {
                Notice.error(activity, "Radio Error: ${fmManager.lastError}")
                return
            }
            fmManager.routeFmAudio()
            activity.volumeControlStream = fmManager.getFmStreamType()
            updateGlobalStatusPlayIcon()
        }

        isRadioScanning = true
        showLoadingPopup()

        Thread {
            var fakeFreq = 87.5f
            var progress = 0
            while (isRadioScanning) {
                val p = progress
                val f = fakeFreq
                activity.runOnUiThread {
                    // The scan can end before this step runs: its end hid the layer, and an update would show it again
                    if (isRadioScanning) {
                        updateLoadingProgress(
                            p,
                            String.format(Locale.US, localize("Scanning FM Frequencies...\nSearching around %.1f MHz"), f)
                        )
                    }
                }
                try {
                    Thread.sleep(70)
                } catch (ignored: Exception) {}
                progress += 1
                if (progress > 100) progress = 0
                fakeFreq += 0.1f
                if (fakeFreq > 108.0f) fakeFreq = 87.5f
            }
        }.start()

        Thread {
            val foundStations = fmManager.autoScan()
            isRadioScanning = false

            activity.runOnUiThread {
                dismissLoadingOverlay()
                if (foundStations != null && foundStations.isNotEmpty()) {
                    savedRadioStations.clear()
                    for (f in foundStations) {
                        val cleanF = Math.round(f * 10.0f) / 10.0f
                        if (!savedRadioStations.contains(cleanF)) {
                            savedRadioStations.add(cleanF)
                        }
                    }
                    savedRadioStations.sort()
                    saveRadioStationsToPrefs()

                    val first = savedRadioStations[0]
                    fmManager.tune(first)
                    saveLastRadioFreq(first)

                    Notice.show(
                        activity,
                        localize("Scan Complete!\nFound") + " ${savedRadioStations.size} " +
                                localize("channels.\nTuning to") + " " + String.format(Locale.US, "%.1f MHz", first),
                        long = true
                    )
                } else {
                    Notice.error(
                        activity,
                        localize("No stations found.") + if (fmManager.lastError.isNotEmpty()) " (${fmManager.lastError})" else ""
                    )
                }
                refresh()
            }
        }.start()
    }

    private fun isSaved(freq: Float): Boolean = savedRadioStations.any { Math.abs(it - freq) < 0.05f }

    /** Saves the channel that is tuned, or removes it when it is saved (the row shows the new state). */
    private fun toggleSavedChannel() {
        val freq = Math.round(fmManager.currentFreq * 10.0f) / 10.0f
        if (isSaved(freq)) {
            savedRadioStations.removeAll { Math.abs(it - freq) < 0.05f }
        } else {
            savedRadioStations.add(freq)
            savedRadioStations.sort()
        }
        saveRadioStationsToPrefs()
        refresh()
    }

    /** The tuner: the frequency, the radio state and the saved channels. */
    fun buildTunerUI() {
        containerSettingsItems.removeAllViews()
        layoutRadioCandyContainer = null

        val density = activity.resources.displayMetrics.density

        val freqPanel = FrameLayout(activity).apply {
            tag = "radio_main_freq_panel"
            isFocusable = true
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins((15 * density).toInt(), (20 * density).toInt(), (15 * density).toInt(), (12 * density).toInt())
            }
        }

        val themeHighlightColor = ThemeManager.getListButtonFocusedBg() or -0x1000000
        freqPanel.background = freqPanelBackground(themeHighlightColor, density)

        val freqInnerLayout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(0, (25 * density).toInt(), 0, (20 * density).toInt())
        }

        val tvFreq = TextView(activity).apply {
            tag = "radio_main_freq_text"
            textSize = 50f
            gravity = Gravity.CENTER
            typeface = ThemeManager.getCustomFontBold()
        }
        freqInnerLayout.addView(tvFreq)

        val tvStatus = TextView(activity).apply {
            tag = "radio_main_status_text"
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(0, (6 * density).toInt(), 0, 0)
        }
        freqInnerLayout.addView(tvStatus)
        freqPanel.addView(freqInnerLayout)
        containerSettingsItems.addView(freqPanel)

        if (savedRadioStations.isNotEmpty()) {
            val hzScroll = HorizontalScrollView(activity).apply {
                isHorizontalScrollBarEnabled = false
                clipChildren = false
                clipToPadding = false
                isFillViewport = true
                setPadding(0, 15, 0, 15)
            }

            val candyContainer = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
            }

            for (stationFreq in savedRadioStations) {
                val tvCandy = TextView(activity).apply {
                    text = String.format(Locale.US, "%.1f", stationFreq)
                    textSize = 18f
                    gravity = Gravity.CENTER
                    setPadding((14 * density).toInt(), (6 * density).toInt(), (14 * density).toInt(), (6 * density).toInt())
                    typeface = ThemeManager.getCustomFontBold()
                    tag = stationFreq
                }
                tvCandy.layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins((6 * density).toInt(), 0, (6 * density).toInt(), 0)
                }
                candyContainer.addView(tvCandy)
            }

            hzScroll.addView(candyContainer, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            ))
            containerSettingsItems.addView(hzScroll)
            layoutRadioCandyContainer = candyContainer
        }

        (containerSettingsItems.parent as? ScrollView)?.scrollTo(0, 0)
        updateRadioMainPlayerUI()
        freqPanel.post { freqPanel.requestFocus() }
    }

    private fun freqPanelBackground(themeHighlightColor: Int, density: Float) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = 18 * density
        if (fmManager.isPowerUp) {
            val backlitColor = (themeHighlightColor and 0x00FFFFFF) or 0x42000000
            setColor(backlitColor)
            setStroke((4 * density).toInt(), themeHighlightColor)
        } else {
            setColor(0x15FFFFFF)
            setStroke((1 * density).toInt(), 0x33FFFFFF)
        }
    }

    fun updateRadioMainPlayerUI() {
        val themeHighlightColor = ThemeManager.getListButtonFocusedBg() or -0x1000000
        val density = activity.resources.displayMetrics.density

        val tvFreq = containerSettingsItems.findViewWithTag<TextView>("radio_main_freq_text")
        if (tvFreq != null) {
            tvFreq.text = String.format(Locale.US, "%.1f MHz", fmManager.currentFreq)
            tvFreq.setTextColor(if (fmManager.isPowerUp) themeHighlightColor else 0xFF888888.toInt())
        }

        val tvStatus = containerSettingsItems.findViewWithTag<TextView>("radio_main_status_text")
        if (tvStatus != null) {
            if (fmManager.isPowerUp) {
                tvStatus.text = "● " + localize("PLAYING")
                tvStatus.setTextColor(themeHighlightColor)
            } else {
                tvStatus.text = localize("Off (press Center or Play to turn on)")
                tvStatus.setTextColor(0xFF888888.toInt())
            }
        }

        containerSettingsItems.findViewWithTag<View>("radio_main_freq_panel")?.background =
            freqPanelBackground(themeHighlightColor, density)

        layoutRadioCandyContainer?.let { container ->
            for (i in 0 until container.childCount) {
                val child = container.getChildAt(i)
                if (child is TextView && child.tag is Float) {
                    val stationFreq = child.tag as Float
                    val candyBg = GradientDrawable().apply {
                        cornerRadius = 20 * density
                    }

                    if (Math.abs(fmManager.currentFreq - stationFreq) < 0.05f) {
                        candyBg.setColor(themeHighlightColor)
                        child.setTextColor(ThemeManager.getListButtonFocusedTextColor())
                        container.post {
                            val parent = container.parent
                            if (parent is HorizontalScrollView) {
                                val scrollX = child.left - (parent.width / 2) + (child.width / 2)
                                parent.smoothScrollTo(Math.max(0, scrollX), 0)
                            }
                        }
                    } else {
                        candyBg.setColor(ThemeManager.getListButtonNormalBg())
                        child.setTextColor(ThemeManager.getTextColorSecondary())
                    }
                    child.background = candyBg
                }
            }
        }

        updateGlobalStatusPlayIcon()
    }

    /** The Radio menu. [focus]: the row to select (a list that opens going down selects its first row). */
    private fun buildMenuUI(focus: Int) {
        containerSettingsItems.removeAllViews()
        val freq = fmManager.currentFreq
        addRow("Tuner", String.format(Locale.US, "%.1f MHz", freq) + " 〉") {
            clickFeedback()
            (activity as? MainActivity)?.changeScreen(ScreenState.FmRadio)
        }
        addRow("Radio Power", onOff(fmManager.isPowerUp, localize)) { toggleRadioPower() }
        addRow("Save Channel", if (isSaved(freq)) "★ " + localize("SAVED") else "☆ " + localize("SAVE")) {
            clickFeedback()
            toggleSavedChannel()
        }
        addRow("Saved Channels", "${savedRadioStations.size} 〉") {
            clickFeedback()
            (activity as? MainActivity)?.changeScreen(ScreenState.FmRadioSaved)
        }
        addRow("Auto Scan All", localize("Start") + " 〉") {
            clickFeedback()
            startRadioAutoScan()
        }
        if (fmManager.isPowerUp) {
            activity.volumeControlStream = fmManager.getFmStreamType()
        }
        showList(focus)
    }

    private fun addRow(title: String, value: String, onClick: () -> Unit) {
        val row = createSettingRow(localize(title), value)
        row.setOnClickListener { onClick() }
        containerSettingsItems.addView(row)
    }

    /** The saved channels. [focus]: the row to select; -1 = the channel that is tuned (else the first). */
    private fun buildSavedUI(focus: Int) {
        containerSettingsItems.removeAllViews()
        if (savedRadioStations.isEmpty()) {
            containerSettingsItems.addView(ActionScreens.infoRow(activity, localize("No saved channels.")))
            return
        }
        var tuned = 0
        for (i in savedRadioStations.indices) {
            val freq = savedRadioStations[i]
            if (Math.abs(freq - fmManager.currentFreq) < 0.05f) tuned = i
            val label = "CH ${i + 1} :  " + String.format(Locale.US, "%.1f MHz", freq)
            val btnFreq = createListButtonWithIcon("", label)
            btnFreq.setOnClickListener { playSavedChannel(freq) }
            btnFreq.setOnLongClickListener {
                clickFeedback()
                (activity as? MainActivity)?.isLongPressConsumed = true
                ActionScreens.open(confirmScreen(
                    localize("Delete Channel"),
                    String.format(Locale.US, localize("Remove %.1f MHz from saved channels?"), freq),
                    localize("Delete")
                ) {
                    savedRadioStations.remove(freq)
                    saveRadioStationsToPrefs()
                    refresh()
                })
                true
            }
            containerSettingsItems.addView(btnFreq)
        }
        showList(if (focus >= 0) focus else tuned)
    }

    /** As the iPod Favorites list: the chosen channel plays, and the tuner shows (Back: the Radio menu). */
    private fun playSavedChannel(freq: Float) {
        clickFeedback()
        tuneTo(freq)
        if (!fmManager.isPowerUp) toggleRadioPower()
        (activity as? MainActivity)?.changeScreen(ScreenState.FmRadio)
    }

    /** Top of the list, and row [focus] (or the last row) selected. */
    private fun showList(focus: Int) {
        containerSettingsItems.isFocusable = false
        (containerSettingsItems.parent as? ScrollView)?.scrollTo(0, 0)
        val rows = (0 until containerSettingsItems.childCount).map { containerSettingsItems.getChildAt(it) }.filter { it.isFocusable }
        val row = rows.getOrNull(focus.coerceIn(0, Math.max(0, rows.size - 1))) ?: return
        row.post { row.requestFocus() }
    }

    /** The index of the selected row among the rows that can be selected, or 0. */
    private fun focusedRow(): Int {
        val focused = containerSettingsItems.findFocus() ?: return 0
        val rows = (0 until containerSettingsItems.childCount).map { containerSettingsItems.getChildAt(it) }.filter { it.isFocusable }
        return Math.max(0, rows.indexOfFirst { it === focused || (it is ViewGroup && isDescendant(it, focused)) })
    }

    private fun isDescendant(group: ViewGroup, view: View): Boolean {
        var p = view.parent
        while (p != null) {
            if (p === group) return true
            p = p.parent
        }
        return false
    }

    override fun onWheel(delta: Int, event: KeyEvent): Boolean {
        if (page != Page.TUNER) return false
        val step = if (delta > 0) 0.1f else -0.1f
        var newFreq = fmManager.currentFreq + step
        if (newFreq > 108.0f) newFreq = 87.5f
        if (newFreq < 87.5f) newFreq = 108.0f
        tuneTo(newFreq)
        updateRadioMainPlayerUI()
        clickFeedback()
        return true
    }

    /** On the tuner, a held Next/Previous seeks the next or previous station (a short press: AudioSources). */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (page != Page.TUNER || event.repeatCount == 0) return false
        val up = when (keyCode) {
            KeyEvent.KEYCODE_MEDIA_NEXT -> true
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> false
            else -> return false
        }
        val now = System.currentTimeMillis()
        if (now - lastSeekTime > 800) {
            lastSeekTime = now
            seekRadioStation(up)
        }
        return true
    }

    /** On the tuner, the centre button turns the radio on or off. On the lists, it chooses the row. */
    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (page == Page.TUNER && (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)) {
            toggleRadioPower()
            return true
        }
        return false
    }

    override fun onBackPressed(): Boolean {
        onExitRadio()
        return true
    }

    companion object {
        /** After the slide of the tuner (~280 ms), the radio turns on. */
        const val START_DELAY_MS = 350L

        fun pageOf(state: ScreenState): Page = when (state) {
            ScreenState.FmRadioMenu -> Page.MENU
            ScreenState.FmRadioSaved -> Page.SAVED
            else -> Page.TUNER
        }

        /**
         * The tuner opens from outside the radio (the main menu, a test command), not from a radio list or Back:
         * [under] is the entry under the tuner before the Radio menu goes there.
         */
        fun opensRadio(under: ScreenState?): Boolean =
            under != ScreenState.FmRadioMenu && under != ScreenState.FmRadioSaved
    }
}
