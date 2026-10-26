package com.themoon.y1.controllers.radio

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.DialogInterface
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
import android.widget.TextView
import android.widget.Toast
import com.themoon.y1.MainActivity
import com.themoon.y1.R
import com.themoon.y1.ThemeManager
import com.themoon.y1.controllers.ScreenController
import com.themoon.y1.managers.AudioPlayerManager
import com.themoon.y1.managers.FmRadioManager
import com.themoon.y1.navigation.ScreenState
import java.util.Locale

/**
 * Controller for MediaTek FM Radio UI:
 * - Main player view with digital frequency readout, status indicator, and horizontal channel pills.
 * - Settings subpage for toggling power, saving channels, managing stations, and auto-scan.
 * - Saved channels manager for viewing and removing presets.
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

    private val containerSettingsItems: LinearLayout = rootView.findViewById(R.id.container_settings_items)
    private val prefs = activity.getSharedPreferences("Y1_SETTINGS", Context.MODE_PRIVATE)
    private val fmManager = FmRadioManager.getInstance(activity)

    val savedRadioStations = ArrayList<Float>()
    var isRadioUIShowing: Boolean = false
    var isRadioSettingsMode: Boolean = false
    var isRadioScanning: Boolean = false
    var lastRadioFocusIndex: Int = 0

    private var layoutRadioCandyContainer: LinearLayout? = null
    private var lastRadioPowerToggleTime: Long = 0
    private var lastCenterUpTime: Long = 0
    private var lastSeekTime: Long = 0
    private var hasPromptedRadioScan: Boolean = false

    init {
        loadSavedRadioStations()
    }

    override fun onEnter(state: ScreenState) {
        isRadioUIShowing = true
        buildRadioUI()
        if (savedRadioStations.isEmpty() && !hasPromptedRadioScan) {
            promptInitialRadioScan()
        }
    }

    override fun onExit() {
        isRadioUIShowing = false
        isRadioSettingsMode = false
    }

    override fun onRefreshUI() {
        if (isRadioUIShowing) {
            if (!isRadioSettingsMode) {
                updateRadioMainPlayerUI()
            } else {
                buildRadioUI()
            }
        }
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
            Toast.makeText(activity, localize("No saved channels."), Toast.LENGTH_SHORT).show()
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
        target = Math.round(target * 10.0f) / 10.0f
        if (fmManager.isPowerUp) {
            fmManager.tune(target)
        } else {
            fmManager.currentFreq = target
        }

        saveLastRadioFreq(target)

        if (isRadioUIShowing) {
            if (!isRadioSettingsMode) {
                updateRadioMainPlayerUI()
            } else {
                buildRadioUI()
            }
        }
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
                Toast.makeText(activity, "Radio Error: ${fmManager.lastError}", Toast.LENGTH_SHORT).show()
            }
        }

        updateGlobalStatusPlayIcon()
        if (isRadioUIShowing) {
            if (!isRadioSettingsMode) {
                updateRadioMainPlayerUI()
            } else {
                buildRadioUI()
            }
        }
    }

    fun seekRadioStation(isUp: Boolean) {
        if (!fmManager.isPowerUp) return
        Toast.makeText(activity, localize("Seeking FM Station..."), Toast.LENGTH_SHORT).show()
        val found = fmManager.seekStation(isUp)
        if (found > 0) {
            val clean = Math.round(found * 10.0f) / 10.0f
            saveLastRadioFreq(clean)
            if (isRadioUIShowing && !isRadioSettingsMode) {
                updateRadioMainPlayerUI()
            } else {
                buildRadioUI()
            }
        }
    }

    fun promptInitialRadioScan() {
        if (savedRadioStations.isNotEmpty() || hasPromptedRadioScan) return
        hasPromptedRadioScan = true

        AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(localize("FM Radio"))
            .setMessage(localize("No saved stations found.\nWould you like to scan for FM channels now?"))
            .setPositiveButton(localize("Scan")) { _, _ ->
                startRadioAutoScan()
            }
            .setNegativeButton(localize("Cancel"), null)
            .show()
    }

    fun startRadioAutoScan() {
        if (isRadioScanning) return

        if (!fmManager.isPowerUp) {
            val audioPlayerManager = AudioPlayerManager.getInstance()
            if (audioPlayerManager.isPlaying()) {
                audioPlayerManager.playOrPauseMusic()
            }
            if (!fmManager.powerUp(fmManager.currentFreq)) {
                Toast.makeText(activity, "Radio Error: ${fmManager.lastError}", Toast.LENGTH_SHORT).show()
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
                    updateLoadingProgress(
                        p,
                        String.format(Locale.US, localize("Scanning FM Frequencies...\nSearching around %.1f MHz"), f)
                    )
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

                    Toast.makeText(
                        activity,
                        localize("Scan Complete!\nFound") + " ${savedRadioStations.size} " +
                                localize("channels.\nTuning to") + " " + String.format(Locale.US, "%.1f MHz", first),
                        Toast.LENGTH_LONG
                    ).show()
                } else {
                    Toast.makeText(
                        activity,
                        localize("No stations found.") + if (fmManager.lastError.isNotEmpty()) " (${fmManager.lastError})" else "",
                        Toast.LENGTH_LONG
                    ).show()
                }
                buildRadioUI()
            }
        }.start()
    }

    fun buildRadioUI() {
        isRadioUIShowing = true
        containerSettingsItems.removeAllViews()

        val density = activity.resources.displayMetrics.density

        if (!isRadioSettingsMode) {
            // Mode 1: Main Radio Player
            val freqPanel = FrameLayout(activity).apply {
                tag = "radio_main_freq_panel"
                isFocusable = true
                isClickable = true
                setOnClickListener { toggleRadioPower() }
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins((15 * density).toInt(), (20 * density).toInt(), (15 * density).toInt(), (12 * density).toInt())
                }
            }

            val themeHighlightColor = ThemeManager.getListButtonFocusedBg() or -0x1000000
            val panelBg = GradientDrawable().apply {
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
            freqPanel.background = panelBg

            val freqInnerLayout = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(0, (25 * density).toInt(), 0, (20 * density).toInt())
            }

            val tvFreq = TextView(activity).apply {
                tag = "radio_main_freq_text"
                text = String.format(Locale.US, "%.1f MHz", fmManager.currentFreq)
                setTextColor(if (fmManager.isPowerUp) themeHighlightColor else 0xFF888888.toInt())
                textSize = 50f
                gravity = Gravity.CENTER
                typeface = ThemeManager.getCustomFontBold()
            }
            freqInnerLayout.addView(tvFreq)

            val tvStatus = TextView(activity).apply {
                tag = "radio_main_status_text"
                if (fmManager.isPowerUp) {
                    text = "● " + localize("PLAYING")
                    setTextColor(themeHighlightColor)
                } else {
                    text = localize("OFF") + " (" + localize("Press Center or Play to Turn ON") + ")"
                    setTextColor(0xFF888888.toInt())
                }
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
                        isClickable = true
                        setOnClickListener {
                            clickFeedback()
                            if (fmManager.isPowerUp) {
                                fmManager.tune(stationFreq)
                            } else {
                                fmManager.currentFreq = stationFreq
                            }
                            saveLastRadioFreq(stationFreq)
                            updateRadioMainPlayerUI()
                        }
                    }

                    val candyBg = GradientDrawable().apply {
                        cornerRadius = 20 * density
                    }

                    if (Math.abs(fmManager.currentFreq - stationFreq) < 0.05f) {
                        candyBg.setColor(themeHighlightColor)
                        tvCandy.setTextColor(ThemeManager.getListButtonFocusedTextColor())
                        hzScroll.post {
                            val scrollX = tvCandy.left - (hzScroll.width / 2) + (tvCandy.width / 2)
                            hzScroll.scrollTo(Math.max(0, scrollX), 0)
                        }
                    } else {
                        candyBg.setColor(ThemeManager.getListButtonNormalBg())
                        tvCandy.setTextColor(ThemeManager.getTextColorSecondary())
                    }
                    tvCandy.background = candyBg

                    val candyLp = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply {
                        setMargins((6 * density).toInt(), 0, (6 * density).toInt(), 0)
                    }
                    tvCandy.layoutParams = candyLp
                    candyContainer.addView(tvCandy)
                }

                hzScroll.addView(candyContainer, FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                ))
                containerSettingsItems.addView(hzScroll)
                layoutRadioCandyContainer = candyContainer
            }

            val spacer = View(activity).apply {
                layoutParams = LinearLayout.LayoutParams(0, 0, 1.0f)
            }
            containerSettingsItems.addView(spacer)

            val btnSettings = createListButton(localize("Radio Settings")).apply {
                tag = "radio_settings_button"
                gravity = Gravity.CENTER
                val settingsLp = layoutParams as? LinearLayout.LayoutParams
                if (settingsLp != null) {
                    settingsLp.bottomMargin = (15 * density).toInt()
                    layoutParams = settingsLp
                }
                setOnClickListener {
                    clickFeedback()
                    isRadioSettingsMode = true
                    buildRadioUI()
                }
            }
            containerSettingsItems.addView(btnSettings)

            containerSettingsItems.postDelayed({
                freqPanel.requestFocus()
            }, 50)

        } else {
            // Mode 2: Radio Settings Sub-screen
            val btnClose = createListButton(localize("Close Settings")).apply {
                setTextColor(0xFFFF8800.toInt())
                setOnClickListener {
                    clickFeedback()
                    isRadioSettingsMode = false
                    buildRadioUI()
                }
            }
            containerSettingsItems.addView(btnClose)

            val btnPower = createSettingRow(
                "Radio Power",
                if (fmManager.isPowerUp) localize("ON") else localize("OFF")
            ).apply {
                setOnClickListener { toggleRadioPower() }
            }
            containerSettingsItems.addView(btnPower)

            val isSaved = savedRadioStations.contains(fmManager.currentFreq)
            val btnSaveFreq = createSettingRow(
                "Save Channel",
                if (isSaved) "★ " + localize("SAVED") else "☆ " + localize("SAVE")
            ).apply {
                setOnClickListener {
                    clickFeedback()
                    if (isSaved) {
                        savedRadioStations.remove(fmManager.currentFreq)
                        Toast.makeText(activity, localize("Removed from saved channels."), Toast.LENGTH_SHORT).show()
                    } else {
                        val cleanFreq = Math.round(fmManager.currentFreq * 10.0f) / 10.0f
                        if (!savedRadioStations.contains(cleanFreq)) {
                            savedRadioStations.add(cleanFreq)
                        }
                        savedRadioStations.sort()
                        Toast.makeText(activity, localize("Channel saved!"), Toast.LENGTH_SHORT).show()
                    }
                    saveRadioStationsToPrefs()
                    buildRadioUI()
                }
            }
            containerSettingsItems.addView(btnSaveFreq)

            val btnManageChannels = createSettingRow("Saved Channels", localize("Edit") + " 〉").apply {
                setOnClickListener {
                    clickFeedback()
                    buildRadioSavedChannelsUI()
                }
            }
            containerSettingsItems.addView(btnManageChannels)

            val btnAutoScan = createSettingRow("Auto Scan All", localize("Start") + " >").apply {
                setOnClickListener {
                    clickFeedback()
                    startRadioAutoScan()
                }
            }
            containerSettingsItems.addView(btnAutoScan)

            if (fmManager.isPowerUp) {
                activity.volumeControlStream = fmManager.getFmStreamType()
            }

            containerSettingsItems.postDelayed({
                val targetIdx = lastRadioFocusIndex
                if (targetIdx in 0 until containerSettingsItems.childCount) {
                    containerSettingsItems.getChildAt(targetIdx).requestFocus()
                } else if (containerSettingsItems.childCount > 0) {
                    containerSettingsItems.getChildAt(0).requestFocus()
                }
            }, 50)
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
                tvStatus.text = localize("OFF") + " (" + localize("Press Center or Play to Turn ON") + ")"
                tvStatus.setTextColor(0xFF888888.toInt())
            }
        }

        val freqPanel = containerSettingsItems.findViewWithTag<View>("radio_main_freq_panel")
        if (freqPanel != null) {
            val panelBg = GradientDrawable().apply {
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
            freqPanel.background = panelBg
        }

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

    fun buildRadioSavedChannelsUI() {
        containerSettingsItems.removeAllViews()
        createCategoryHeader("━ " + localize("SAVED CHANNELS") + " ━")

        if (savedRadioStations.isEmpty()) {
            val tvEmpty = TextView(activity).apply {
                text = "   " + localize("No saved channels.")
                setTextColor(0xFF888888.toInt())
                setPadding(20, 10, 20, 10)
            }
            containerSettingsItems.addView(tvEmpty)
        } else {
            for (i in savedRadioStations.indices) {
                val freq = savedRadioStations[i]
                val label = "CH ${i + 1} :  " + String.format(Locale.US, "%.1f MHz", freq)
                val btnFreq = createListButtonWithIcon("\uE03E", label)

                btnFreq.setOnClickListener {
                    clickFeedback()
                    if (fmManager.isPowerUp) {
                        fmManager.tune(freq)
                    } else {
                        fmManager.currentFreq = freq
                    }
                    saveLastRadioFreq(freq)
                    isRadioSettingsMode = false
                    buildRadioUI()
                }

                btnFreq.setOnLongClickListener {
                    clickFeedback()
                    AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                        .setTitle(localize("Delete Channel"))
                        .setMessage(String.format(Locale.US, localize("Remove %.1f MHz from saved channels?"), freq))
                        .setPositiveButton(localize("Delete")) { _, _ ->
                            clickFeedback()
                            savedRadioStations.remove(freq)
                            saveRadioStationsToPrefs()
                            buildRadioSavedChannelsUI()
                        }
                        .setNegativeButton(localize("Cancel"), null)
                        .show()
                    true
                }

                containerSettingsItems.addView(btnFreq)
            }
        }
    }

    override fun onWheel(delta: Int, event: KeyEvent): Boolean {
        if (!isRadioSettingsMode) {
            val step = if (delta > 0) 0.1f else -0.1f
            var newFreq = fmManager.currentFreq + step
            if (newFreq > 108.0f) newFreq = 87.5f
            if (newFreq < 87.5f) newFreq = 108.0f
            newFreq = Math.round(newFreq * 10.0f) / 10.0f
            if (fmManager.isPowerUp) {
                fmManager.tune(newFreq)
            } else {
                fmManager.currentFreq = newFreq
            }
            saveLastRadioFreq(newFreq)
            updateRadioMainPlayerUI()
            clickFeedback()
            return true
        }
        return false
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_MEDIA_NEXT || keyCode == 87) {
            if (event.repeatCount > 0) {
                val now = System.currentTimeMillis()
                if (now - lastSeekTime > 800) {
                    lastSeekTime = now
                    seekRadioStation(true)
                }
                return true
            }
        }
        if (keyCode == KeyEvent.KEYCODE_MEDIA_PREVIOUS || keyCode == 88) {
            if (event.repeatCount > 0) {
                val now = System.currentTimeMillis()
                if (now - lastSeekTime > 800) {
                    lastSeekTime = now
                    seekRadioStation(false)
                }
                return true
            }
        }
        return false
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
            if (!isRadioSettingsMode) {
                val now = System.currentTimeMillis()
                if (now - lastCenterUpTime < 350) {
                    lastCenterUpTime = 0
                    clickFeedback()
                    isRadioSettingsMode = true
                    buildRadioUI()
                } else {
                    lastCenterUpTime = now
                    val focused = activity.currentFocus
                    if (focused != null && "radio_settings_button" == focused.tag) {
                        focused.performClick()
                    } else {
                        toggleRadioPower()
                    }
                }
                return true
            }
        }
        return false
    }

    override fun onKeyLongPress(keyCode: Int, event: KeyEvent): Boolean {
        if (!isRadioSettingsMode && (keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE || keyCode == 85
                || keyCode == KeyEvent.KEYCODE_MEDIA_STOP || keyCode == 86 || keyCode == 126 || keyCode == 127)) {
            clickFeedback()
            (activity as? MainActivity)?.let { main ->
                main.isMediaLongPressConsumed = true
            }
            isRadioSettingsMode = true
            buildRadioUI()
            return true
        }
        return false
    }

    override fun onBackPressed(): Boolean {
        if (isRadioSettingsMode) {
            isRadioSettingsMode = false
            buildRadioUI()
            clickFeedback()
            return true
        }
        onExitRadio()
        return true
    }
}
