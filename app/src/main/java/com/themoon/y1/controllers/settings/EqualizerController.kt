package com.themoon.y1.controllers.settings

import android.app.Activity
import com.themoon.y1.Prefs
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
import com.themoon.y1.lists.Focus
import com.themoon.y1.lists.ListScreen
import com.themoon.y1.lists.ListSpec
import com.themoon.y1.lists.RowLook
import com.themoon.y1.lists.RowSpec
import com.themoon.y1.managers.AudioEffectManager
import com.themoon.y1.managers.AudioEffectPlan
import com.themoon.y1.managers.AudioPlayerManager
import com.themoon.y1.navigation.ScreenState
import com.themoon.y1.views.EqSliderView
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
    private val createCategoryHeader: (title: String) -> Unit,
    private val createListButton: (icon: String, text: String) -> View,
    private val clickFeedback: () -> Unit,
    private val rebootAudioEngine: () -> Unit,
    private val onNavigateBack: () -> Unit,
    private val localize: (String) -> String
) : ScreenController {

    private val containerSettingsItems: LinearLayout = rootView.findViewById(R.id.container_settings_items)
    private val prefs = activity.getSharedPreferences("AppPrefs", 0)
    private val screen = ListScreen(containerSettingsItems, clickFeedback = clickFeedback)

    var isSoftwareEqEnabled: Boolean
        get() = Prefs.SOFTWARE_EQ.get(prefs)
        set(value) = Prefs.SOFTWARE_EQ.setAsync(prefs, value)

    var currentEqProfile: String
        get() = Prefs.EQ_PROFILE.get(prefs)
        set(value) = Prefs.EQ_PROFILE.setAsync(prefs, value)

    var currentBassBoostStep: Int
        get() = Prefs.BASS_BOOST.get(prefs)
        set(value) = Prefs.BASS_BOOST.setAsync(prefs, value)

    var currentVirtualizerStep: Int
        get() = Prefs.VIRTUALIZER.get(prefs)
        set(value) = Prefs.VIRTUALIZER.setAsync(prefs, value)

    var currentCrossfeedStep: Int
        get() = Prefs.CROSSFEED.get(prefs)
        set(value) = Prefs.CROSSFEED.setAsync(prefs, value)

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
        screen.clear()
    }

    override fun onThemeChanged() {
        // The graphic EQ keeps its colours until it closes (no lost band state)
        when {
            isProfileSelectorOpen -> screen.update(profilesSpec())
            !isGraphicEqOpen -> screen.update(settingsSpec())
        }
    }

    override fun title(): String? = if (isGraphicEqOpen) null else screen.title

    /**
     * The EQ settings. [back]: from the profile list or the Graphic Equalizer, so the row that opened it is
     * selected again; else the first row.
     */
    fun buildEqualizerSettingsUI(back: Boolean = false) {
        isGraphicEqOpen = false
        isProfileSelectorOpen = false
        currentAdjustingBand = -1
        AudioEffectManager.getInstance().loadAndSyncExternalEqProfiles()
        AudioEffectManager.getInstance().ensureAudioEffectsReady()
        screen.show(settingsSpec(if (back) Focus.LAST else Focus.FIRST))
    }

    private fun settingsSpec(focus: Focus = Focus.FIRST): ListSpec {
        val rows = ArrayList<RowSpec>()
        rows += RowSpec("10-Band Software EQ", RowLook.VALUE, value = onOff(isSoftwareEqEnabled, localize), onClick = {
            if (isSoftwareEqEnabled) {
                isSoftwareEqEnabled = false
                currentEqProfile = "preset_0"
                rebootAudioEngine()
                Notice.show(activity, localize("Switched to Hardware 5-Band EQ"))
                screen.update(settingsSpec())
            } else {
                showSoftwareEqDialog()
            }
        })

        var activeName = "Normal"
        if (currentEqProfile.startsWith("preset_")) {
            val pIdx = currentEqProfile.removePrefix("preset_").toIntOrNull() ?: 0
            if (pIdx < eqPresetNames.size) {
                activeName = localize(eqPresetNames[pIdx])
            }
        } else {
            activeName = currentEqProfile.removePrefix("custom_")
        }
        rows += RowSpec("EQ Profile / Preset", RowLook.VALUE, value = "$activeName 〉", onClick = { buildProfileSelectorUI() })

        val steps = arrayOf("Off", "Weak", "Normal", "Strong")
        rows += RowSpec("Bass Boost", RowLook.VALUE, value = localize(steps[currentBassBoostStep % 4]), onClick = {
            currentBassBoostStep = (currentBassBoostStep + 1) % 4
            AudioEffectManager.getInstance().applyAudioEffects()
            screen.update(settingsSpec())
        })
        rows += RowSpec("Virtualizer", RowLook.VALUE, value = localize(steps[currentVirtualizerStep % 4]), onClick = {
            currentVirtualizerStep = (currentVirtualizerStep + 1) % 4
            AudioEffectManager.getInstance().applyAudioEffects()
            screen.update(settingsSpec())
        })
        rows += RowSpec("Crossfeed", RowLook.VALUE, value = localize(steps[currentCrossfeedStep % 4]), onClick = {
            currentCrossfeedStep = (currentCrossfeedStep + 1) % 4
            AudioPlayerManager.getInstance().crossfeedProcessor.setIntensity(currentCrossfeedStep)
            screen.update(settingsSpec())
        })

        rows += RowSpec(localize("PROFILE MANAGEMENT"), RowLook.HEADER)
        if (currentEqProfile.startsWith("custom_")) {
            val customName = currentEqProfile.removePrefix("custom_")
            rows += RowSpec(localize("Save Current Configuration"), onClick = {
                AudioEffectManager.getInstance().saveCustomEqProfile(customName)
                Notice.show(activity, localize("Configuration Saved!"))
            })
            rows += RowSpec(localize("Delete Current Profile"), color = 0xFFFF4444.toInt(), onClick = {
                AudioEffectManager.getInstance().deleteCustomEqProfile(customName)
                currentEqProfile = "preset_0"
                AudioEffectManager.getInstance().applyEqProfile()
                screen.update(settingsSpec())
            })
        }
        rows += RowSpec(localize("Create New Profile"), onClick = {
            var listStr = Prefs.CUSTOM_EQ_LIST.get(prefs)
            var count = 1
            while (listStr.contains("User EQ $count")) {
                count++
            }
            val newName = "User EQ $count"
            if (listStr.isNotEmpty()) listStr += ","
            listStr += newName
            Prefs.CUSTOM_EQ_LIST.setAsync(prefs, listStr)
            currentEqProfile = "custom_$newName"

            AudioEffectManager.getInstance().resetCustomEqProfile(newName)
            AudioEffectManager.getInstance().applyEqProfile()
            buildGraphicEqualizerUI()
        })

        rows += RowSpec(localize("GRAPHIC EQUALIZER"), RowLook.HEADER)
        rows += RowSpec("Graphic Equalizer", RowLook.VALUE, value = localize("Open Editor") + " 〉", onClick = {
            if (currentEqProfile.startsWith("preset_")) {
                Notice.show(activity, localize("Please create a Custom Profile to edit!"), long = true)
            } else {
                buildGraphicEqualizerUI()
            }
        })
        return ListSpec(localize("Equalizer"), rows, focus = focus, id = "eq")
    }

    fun buildGraphicEqualizerUI() {
        isGraphicEqOpen = true
        currentAdjustingBand = -1
        screen.clear()

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
            buildEqualizerSettingsUI(back = true)
        }
        containerSettingsItems.addView(btnBack)

        if (eqContainer.childCount > 0) {
            eqContainer.postDelayed({
                eqContainer.getChildAt(0).requestFocus()
            }, 50)
        }
    }

    /** The presets, then the custom profiles; the profile in use has the tick and is selected. */
    private fun buildProfileSelectorUI() {
        isProfileSelectorOpen = true
        screen.show(profilesSpec())
        screen.select(currentEqProfile)
    }

    private fun profilesSpec(): ListSpec {
        val rows = ArrayList<RowSpec>()
        fun profile(id: String, name: String) {
            rows += RowSpec(name, RowLook.CHOICE, checked = currentEqProfile == id, key = id, onClick = {
                currentEqProfile = id
                AudioEffectManager.getInstance().applyEqProfile()
                buildEqualizerSettingsUI(back = true)
            })
        }
        rows += RowSpec(localize("SELECT PRESET / PROFILE"), RowLook.HEADER)
        for (i in eqPresetNames.indices) profile("preset_$i", localize(eqPresetNames[i]))
        val customList = Prefs.CUSTOM_EQ_LIST.get(prefs)
        if (customList.isNotEmpty()) {
            rows += RowSpec(localize("CUSTOM PROFILES"), RowLook.HEADER)
            for (name in customList.split(",")) if (name.isNotBlank()) profile("custom_$name", name)
        }
        rows += RowSpec(localize("Back"), onClick = { buildEqualizerSettingsUI(back = true) })
        return ListSpec(localize("Equalizer"), rows, id = "eq-profiles")
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
            screen.update(settingsSpec())
        })
    }

    // The band edit is a wheel step (onWheel), so a general wheel step of the router does not take it first
    override fun onWheel(delta: Int, event: KeyEvent): Boolean {
        if (!isGraphicEqOpen) return screen.wheel(delta)
        if (currentAdjustingBand < 0) return false
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
            buildEqualizerSettingsUI(back = true)
            return true
        }
        onNavigateBack()
        return true
    }
}
