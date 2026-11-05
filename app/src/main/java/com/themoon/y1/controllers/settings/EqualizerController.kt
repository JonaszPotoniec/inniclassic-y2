package com.themoon.y1.controllers.settings

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import com.themoon.y1.R
import com.themoon.y1.ThemeManager
import com.themoon.y1.controllers.ScreenController
import com.themoon.y1.core.extensions.onOff
import com.themoon.y1.managers.AudioEffectManager
import com.themoon.y1.managers.AudioEffectPlan
import com.themoon.y1.managers.AudioPlayerManager
import com.themoon.y1.navigation.ScreenState
import com.themoon.y1.views.EqSliderView
import com.themoon.y1.views.ListItemViewFactory
import com.themoon.y1.views.Notice
import com.themoon.y1.views.TablerIcons

/**
 * Controller for Equalizer settings:
 * Hardware 5-band vs Software 10-band toggle, presets, bass boost, virtualizer, crossfeed,
 * custom EQ profile management, and Graphic Equalizer editor.
 */
class EqualizerController(
    private val activity: Activity,
    override val rootView: ViewGroup,
    private val eqPresetNames: List<String>,
    private val createSettingRow: (title: String, value: String) -> LinearLayout,
    private val createCategoryHeader: (title: String) -> Unit,
    private val createListButton: (icon: String, text: String) -> View,
    private val createListButtonWithColor: (icon: String, text: String, color: Int) -> View,
    private val clickFeedback: () -> Unit,
    private val rebootAudioEngine: () -> Unit,
    private val onNavigateBack: () -> Unit,
    private val localize: (String) -> String
) : ScreenController {

    private val containerSettingsItems: LinearLayout = rootView.findViewById(R.id.container_settings_items)
    private val prefs = activity.getSharedPreferences("AppPrefs", 0)

    var isSoftwareEqEnabled: Boolean
        get() = prefs.getBoolean("software_eq_enabled", false)
        set(value) {
            prefs.edit().putBoolean("software_eq_enabled", value).apply()
            (activity as? com.themoon.y1.MainActivity)?.isSoftwareEqEnabled = value
        }

    var currentEqProfile: String
        get() = prefs.getString("eq_profile_id", "preset_0") ?: "preset_0"
        set(value) = prefs.edit().putString("eq_profile_id", value).apply()

    var currentBassBoostStep: Int
        get() = prefs.getInt("bass_boost_step", 0)
        set(value) = prefs.edit().putInt("bass_boost_step", value).apply()

    var currentVirtualizerStep: Int
        get() = prefs.getInt("virtualizer_step", 0)
        set(value) = prefs.edit().putInt("virtualizer_step", value).apply()

    var currentCrossfeedStep: Int
        get() = prefs.getInt("crossfeed_step", 0)
        set(value) = prefs.edit().putInt("crossfeed_step", value).apply()

    private var currentAdjustingBand: Short = -1
    /** The sliders of the open Graphic Equalizer, one for each band. */
    private val bandSliders = ArrayList<EqSliderView>()
    private var isGraphicEqOpen = false
    private var isProfileSelectorOpen = false

    override fun onEnter(state: ScreenState) {
        buildEqualizerSettingsUI()
    }

    override fun onExit() {
        isGraphicEqOpen = false
        isProfileSelectorOpen = false
        currentAdjustingBand = -1
    }

    override fun onThemeChanged() {
        // The graphic EQ and the profile list keep their colours until they close (no lost band state)
        if (!isGraphicEqOpen && !isProfileSelectorOpen) buildEqualizerSettingsUI()
    }

    fun buildEqualizerSettingsUI() {
        isGraphicEqOpen = false
        isProfileSelectorOpen = false
        currentAdjustingBand = -1
        AudioEffectManager.getInstance().loadAndSyncExternalEqProfiles()
        AudioEffectManager.getInstance().ensureAudioEffectsReady()
        containerSettingsItems.removeAllViews()

        val btnSoftwareEq = createSettingRow(
            "10-Band Software EQ",
            onOff(isSoftwareEqEnabled, localize)
        )
        btnSoftwareEq.setOnClickListener {
            clickFeedback()
            if (isSoftwareEqEnabled) {
                isSoftwareEqEnabled = false
                currentEqProfile = "preset_0"
                rebootAudioEngine()
                Notice.show(activity, localize("Switched to Hardware 5-Band EQ"))
                buildEqualizerSettingsUI()
            } else {
                showSoftwareEqDialog()
            }
        }
        containerSettingsItems.addView(btnSoftwareEq)

        var activeName = "Normal"
        if (currentEqProfile.startsWith("preset_")) {
            val pIdx = currentEqProfile.removePrefix("preset_").toIntOrNull() ?: 0
            if (pIdx < eqPresetNames.size) {
                activeName = localize(eqPresetNames[pIdx])
            }
        } else {
            activeName = currentEqProfile.removePrefix("custom_")
        }

        val rowSelect = createSettingRow("EQ Profile / Preset", "$activeName 〉")
        rowSelect.setOnClickListener {
            clickFeedback()
            buildProfileSelectorUI()
        }
        containerSettingsItems.addView(rowSelect)

        val steps = arrayOf("Off", "Weak", "Normal", "Strong")
        val rowBass = createSettingRow("Bass Boost", localize(steps[currentBassBoostStep % 4]))
        rowBass.setOnClickListener {
            clickFeedback()
            currentBassBoostStep = (currentBassBoostStep + 1) % 4
            (rowBass.getChildAt(1) as? TextView)?.text = localize(steps[currentBassBoostStep])
            AudioEffectManager.getInstance().applyAudioEffects()
        }
        containerSettingsItems.addView(rowBass)

        val rowVirt = createSettingRow("Virtualizer", localize(steps[currentVirtualizerStep % 4]))
        rowVirt.setOnClickListener {
            clickFeedback()
            currentVirtualizerStep = (currentVirtualizerStep + 1) % 4
            (rowVirt.getChildAt(1) as? TextView)?.text = localize(steps[currentVirtualizerStep])
            AudioEffectManager.getInstance().applyAudioEffects()
        }
        containerSettingsItems.addView(rowVirt)

        val rowCrossfeed = createSettingRow("Crossfeed", localize(steps[currentCrossfeedStep % 4]))
        rowCrossfeed.setOnClickListener {
            clickFeedback()
            currentCrossfeedStep = (currentCrossfeedStep + 1) % 4
            (rowCrossfeed.getChildAt(1) as? TextView)?.text = localize(steps[currentCrossfeedStep])
            AudioPlayerManager.getInstance().crossfeedProcessor.setIntensity(currentCrossfeedStep)
        }
        containerSettingsItems.addView(rowCrossfeed)

        createCategoryHeader("━ " + localize("PROFILE MANAGEMENT") + " ━")

        if (currentEqProfile.startsWith("custom_")) {
            val customName = currentEqProfile.removePrefix("custom_")
            val btnSave = createListButton("\uE161", localize("Save Current Configuration"))
            btnSave.setOnClickListener {
                clickFeedback()
                AudioEffectManager.getInstance().saveCustomEqProfile(customName)
                Notice.show(activity, localize("Configuration Saved!"))
            }
            containerSettingsItems.addView(btnSave)

            val btnDel = createListButtonWithColor("\uE872", localize("Delete Current Profile"), 0xFFFF4444.toInt())
            btnDel.setOnClickListener {
                clickFeedback()
                AudioEffectManager.getInstance().deleteCustomEqProfile(customName)
                currentEqProfile = "preset_0"
                AudioEffectManager.getInstance().applyEqProfile()
                buildEqualizerSettingsUI()
            }
            containerSettingsItems.addView(btnDel)
        }

        val btnCreate = createListButton("\uE145", localize("Create New Profile"))
        btnCreate.setOnClickListener {
            clickFeedback()
            var listStr = prefs.getString("custom_eq_list", "") ?: ""
            var count = 1
            while (listStr.contains("User EQ $count")) {
                count++
            }
            val newName = "User EQ $count"
            if (listStr.isNotEmpty()) listStr += ","
            listStr += newName
            prefs.edit().putString("custom_eq_list", listStr).apply()
            currentEqProfile = "custom_$newName"

            AudioEffectManager.getInstance().resetCustomEqProfile(newName)
            AudioEffectManager.getInstance().applyEqProfile()
            buildGraphicEqualizerUI()
        }
        containerSettingsItems.addView(btnCreate)

        createCategoryHeader("━ " + localize("GRAPHIC EQUALIZER") + " ━")
        val btnGraphicEq = createSettingRow("Graphic Equalizer", localize("Open Editor") + " 〉")
        btnGraphicEq.setOnClickListener {
            clickFeedback()
            if (currentEqProfile.startsWith("preset_")) {
                Notice.show(activity, localize("Please create a Custom Profile to edit!"), long = true)
            } else {
                buildGraphicEqualizerUI()
            }
        }
        containerSettingsItems.addView(btnGraphicEq)
        // Focus goes to the first row that can take it (not to a category header).
        containerSettingsItems.requestFocus()
    }

    fun buildGraphicEqualizerUI() {
        isGraphicEqOpen = true
        currentAdjustingBand = -1
        containerSettingsItems.removeAllViews()

        createCategoryHeader("━ " + localize("GRAPHIC EQUALIZER") + " ━")

        val tvTitle = TextView(activity).apply {
            text = "${localize("Editing: ")}${currentEqProfile.removePrefix("custom_")}"
            setTextColor(0xFFFF8800.toInt())
            gravity = Gravity.CENTER
            setPadding(0, 10, 0, 20)
        }
        containerSettingsItems.addView(tvTitle)

        val d = activity.resources.displayMetrics.density
        val hsv = HorizontalScrollView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (280 * d).toInt())
            isHorizontalScrollBarEnabled = false
            isFillViewport = true
        }

        val eqContainer = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            gravity = Gravity.CENTER
        }

        val bands: Short = if (isSoftwareEqEnabled) 10 else 5
        val range: IntArray = intArrayOf(-1500, 1500)
        val centerFreqs: IntArray = if (isSoftwareEqEnabled) {
            intArrayOf(31000, 62000, 125000, 250000, 500000, 1000000, 2000000, 4000000, 8000000, 16000000)
        } else {
            intArrayOf(60000, 230000, 910000, 3600000, 14000000)
        }

        // The saved levels are the levels that play (AudioEffectManager reads the same prefs)
        val profileName = currentEqProfile.removePrefix("custom_")
        bandSliders.clear()

        for (i in 0 until bands) {
            val bandIdx = i.toShort()
            val freq = centerFreqs[i.toInt()] / 1000
            val currentLevel = prefs.getInt(AudioEffectPlan.bandLevelKey(profileName, i), 0)

            val bandLayout = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                isFocusable = true
                gravity = Gravity.CENTER
                val marginDp = if (isSoftwareEqEnabled) 2 else 5
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.0f).apply {
                    if (i > 0) leftMargin = (marginDp * d).toInt()
                }
            }

            val slider = EqSliderView(activity).apply {
                setRange(range[0], range[1])
                setLevel(currentLevel)
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.0f)
            }
            bandSliders.add(slider)

            val tvFreq = TextView(activity).apply {
                text = if (freq >= 1000) "${freq / 1000}k" else "$freq"
                setTextColor(0xFFFFFFFF.toInt())
                textSize = 14.5f
                typeface = ThemeManager.getCustomFontBold()
                gravity = Gravity.CENTER
                setPadding(0, 0, 0, 10)
            }

            bandLayout.addView(slider)
            bandLayout.addView(tvFreq)

            bandLayout.onFocusChangeListener = View.OnFocusChangeListener { _, hasFocus ->
                if (hasFocus) {
                    bandLayout.setBackgroundColor(ThemeManager.getListButtonFocusedBg() and 0x66FFFFFF)
                } else {
                    bandLayout.setBackgroundColor(0)
                    if (currentAdjustingBand == bandIdx) {
                        currentAdjustingBand = -1
                        slider.setAdjusting(false)
                    }
                }
                slider.setFocused(hasFocus)
            }

            bandLayout.setOnClickListener {
                clickFeedback()
                if (currentAdjustingBand == bandIdx) {
                    currentAdjustingBand = -1
                    slider.setAdjusting(false)
                } else {
                    currentAdjustingBand = bandIdx
                    slider.setAdjusting(true)
                }
            }

            eqContainer.addView(bandLayout)
        }

        hsv.addView(eqContainer)
        containerSettingsItems.addView(hsv)

        val btnBack = createListButton("\uE5C4", localize("Back to EQ Settings"))
        btnBack.setOnClickListener {
            clickFeedback()
            buildEqualizerSettingsUI()
        }
        containerSettingsItems.addView(btnBack)

        if (eqContainer.childCount > 0) {
            eqContainer.postDelayed({
                eqContainer.getChildAt(0).requestFocus()
            }, 50)
        }
    }

    private fun buildProfileSelectorUI() {
        isProfileSelectorOpen = true
        containerSettingsItems.removeAllViews()
        createCategoryHeader("━ " + localize("SELECT PRESET / PROFILE") + " ━")
        var currentRow: View? = null

        for (i in eqPresetNames.indices) {
            val presetId = "preset_$i"
            val isCurrent = currentEqProfile == presetId
            val row = ListItemViewFactory.createCheckListButton(activity, localize(eqPresetNames[i]), isCurrent)
            row.setOnClickListener {
                clickFeedback()
                currentEqProfile = presetId
                AudioEffectManager.getInstance().applyEqProfile()
                buildEqualizerSettingsUI()
            }
            containerSettingsItems.addView(row)
            if (isCurrent) currentRow = row
        }

        val customList = prefs.getString("custom_eq_list", "") ?: ""
        if (customList.isNotEmpty()) {
            createCategoryHeader("━ " + localize("CUSTOM PROFILES") + " ━")
            for (name in customList.split(",")) {
                if (name.isBlank()) continue
                val profileId = "custom_$name"
                val isCurrent = currentEqProfile == profileId
                val row = ListItemViewFactory.createCheckListButton(activity, name, isCurrent)
                row.setOnClickListener {
                    clickFeedback()
                    currentEqProfile = profileId
                    AudioEffectManager.getInstance().applyEqProfile()
                    buildEqualizerSettingsUI()
                }
                containerSettingsItems.addView(row)
                if (isCurrent) currentRow = row
            }
        }

        val btnBack = createListButton("\uE5C4", localize("Back"))
        btnBack.setOnClickListener {
            clickFeedback()
            buildEqualizerSettingsUI()
        }
        containerSettingsItems.addView(btnBack)
        (currentRow ?: containerSettingsItems).requestFocus()
    }

    /** Asks before the 10-band EQ (it uses the CPU), on an action screen; Back cancels. */
    private fun showSoftwareEqDialog() {
        ActionScreens.open(confirmScreen(
            localize("Enable 10-Band Software EQ"),
            localize("Enabling 10-Band EQ processes audio via CPU. Switch to 10-band mode?"),
            localize("Enable 10-Band EQ")
        ) {
            isSoftwareEqEnabled = true
            currentEqProfile = "preset_0"
            rebootAudioEngine()
            Notice.show(activity, localize("Switched to Software 10-Band EQ"))
            buildEqualizerSettingsUI()
        })
    }

    // The band edit is a wheel step (onWheel), so a general wheel step of the router does not take it first
    override fun onWheel(delta: Int, event: KeyEvent): Boolean {
        if (!isGraphicEqOpen || currentAdjustingBand < 0) return false
        val idx = currentAdjustingBand.toInt()
        if (idx in bandSliders.indices) {
            val name = currentEqProfile.removePrefix("custom_")
            // CCW (up) raises the band, CW (down) lowers it
            val level = AudioEffectPlan.stepBandLevel(prefs.getInt(AudioEffectPlan.bandLevelKey(name, idx), 0), -100 * delta)
            AudioEffectManager.getInstance().setCustomBandLevel(name, idx, level)
            // Update the slider in place: a rebuild would end the band edit and move the focus to the first band
            bandSliders[idx].setLevel(level)
        }
        clickFeedback()
        return true
    }

    override fun onBackPressed(): Boolean {
        if (isGraphicEqOpen || isProfileSelectorOpen) {
            buildEqualizerSettingsUI()
            return true
        }
        onNavigateBack()
        return true
    }
}
