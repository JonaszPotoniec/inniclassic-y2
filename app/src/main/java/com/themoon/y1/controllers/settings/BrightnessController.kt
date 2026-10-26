package com.themoon.y1.controllers.settings

import android.app.Activity
import android.provider.Settings
import android.view.KeyEvent
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ProgressBar
import android.widget.TextView
import com.themoon.y1.R
import com.themoon.y1.ThemeManager
import com.themoon.y1.controllers.ScreenController
import com.themoon.y1.navigation.ScreenState

/**
 * Controller for Display Brightness screen:
 * Displays progress bar, handles wheel/dpad left-right stepping, and writes to system brightness.
 */
class BrightnessController(
    private val activity: Activity,
    override val rootView: ViewGroup,
    private val clickFeedback: () -> Unit,
    private val onNavigateBack: () -> Unit
) : ScreenController {

    private val pbBrightness: ProgressBar = rootView.findViewById(R.id.pb_brightness)
    private val tvBrightnessVal: TextView = rootView.findViewById(R.id.tv_brightness_val)

    var currentSystemBrightness: Int = 255

    override fun onEnter(state: ScreenState) {
        loadBrightnessUI()
    }

    override fun onRefreshUI() {
        loadBrightnessUI()
    }

    override fun onBackPressed(): Boolean {
        onNavigateBack()
        return true
    }

    override fun onWheel(delta: Int, event: KeyEvent): Boolean {
        adjustBrightness(delta * 10)
        return true
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == 21) {
            adjustBrightness(-10)
            return true
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT || keyCode == 22) {
            adjustBrightness(10)
            return true
        }
        return false
    }

    fun loadBrightnessUI() {
        try {
            currentSystemBrightness = Settings.System.getInt(
                activity.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS,
                255
            )
        } catch (_: Exception) {}

        pbBrightness.progress = currentSystemBrightness
        val percent = ((currentSystemBrightness / 255.0f) * 100).toInt()
        tvBrightnessVal.text = "$percent%"
        // The layout has white text: in a light theme it does not show. Use the theme text colour (as the title).
        tvBrightnessVal.setTextColor(ThemeManager.getTextColorPrimary())
    }

    fun adjustBrightness(delta: Int) {
        val target = Math.max(10, Math.min(255, currentSystemBrightness + delta))
        if (target != currentSystemBrightness) {
            updateBrightness(target)
            clickFeedback()
        }
    }

    fun updateBrightness(newBrightness: Int) {
        currentSystemBrightness = newBrightness
        pbBrightness.progress = currentSystemBrightness
        val percent = ((currentSystemBrightness / 255.0f) * 100).toInt()
        tvBrightnessVal.text = "$percent%"

        try {
            Settings.System.putInt(
                activity.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
            )
            Settings.System.putInt(
                activity.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS,
                currentSystemBrightness
            )
            val lp: WindowManager.LayoutParams = activity.window.attributes
            lp.screenBrightness = currentSystemBrightness / 255.0f
            activity.window.attributes = lp
        } catch (_: Exception) {}
    }
}
