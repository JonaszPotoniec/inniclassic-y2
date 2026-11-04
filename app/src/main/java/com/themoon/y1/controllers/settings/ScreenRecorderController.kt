package com.themoon.y1.controllers.settings

import android.app.Activity
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.Toast
import com.themoon.y1.R
import com.themoon.y1.controllers.ScreenController
import com.themoon.y1.navigation.ScreenState

/**
 * Controller for the Screen Recorder settings sub-menu.
 * Integrates with com.iwobanas.screenrecorder.pro on device.
 */
class ScreenRecorderController(
    private val activity: Activity,
    override val rootView: ViewGroup,
    private val createSettingRow: (title: String, value: String) -> LinearLayout,
    private val clickFeedback: () -> Unit,
    private val localize: (String) -> String
) : ScreenController {

    private val containerSettingsItems: LinearLayout = rootView.findViewById(R.id.container_settings_items)

    override fun onEnter(state: ScreenState) {
        buildRecordingSettingsUI()
    }

    fun buildRecordingSettingsUI() {
        containerSettingsItems.removeAllViews()

        // 1. Start Screen Record
        val btnRunScr = createSettingRow("Start Screen Record", "")
        btnRunScr.setOnClickListener {
            clickFeedback()
            try {
                val intent = activity.packageManager.getLaunchIntentForPackage("com.iwobanas.screenrecorder.pro")
                if (intent != null) {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    activity.startActivity(intent)
                    Toast.makeText(
                        activity,
                        localize("Preparing screen recording... Please wait!"),
                        Toast.LENGTH_SHORT
                    ).show()

                    Handler(Looper.getMainLooper()).postDelayed({
                        Thread {
                            try {
                                Runtime.getRuntime().exec(arrayOf("su", "-c", "input tap 181 40"))
                            } catch (ignored: Exception) {}
                        }.start()
                    }, 2000)
                } else {
                    Toast.makeText(activity, localize("App is not installed."), Toast.LENGTH_SHORT).show()
                }
            } catch (ignored: Exception) {}
        }
        containerSettingsItems.addView(btnRunScr)

        // 2. SCR Settings
        val btnSettingsScr = createSettingRow("SCR Settings", "")
        btnSettingsScr.setOnClickListener {
            clickFeedback()
            Toast.makeText(activity, localize("Opening settings..."), Toast.LENGTH_SHORT).show()
            Thread {
                try {
                    Runtime.getRuntime().exec(arrayOf("su", "-c", "input tap 245 40"))
                } catch (ignored: Exception) {}
            }.start()
        }
        containerSettingsItems.addView(btnSettingsScr)

        // 3. Close Screen Recorder
        val btnCloseScr = createSettingRow("Close Screen Recorder", "")
        btnCloseScr.setOnClickListener {
            clickFeedback()
            Toast.makeText(activity, localize("Closing screen recorder."), Toast.LENGTH_SHORT).show()
            Thread {
                try {
                    Runtime.getRuntime().exec(arrayOf("su", "-c", "input tap 304 41"))
                } catch (ignored: Exception) {}
            }.start()
        }
        containerSettingsItems.addView(btnCloseScr)

        // 4. Stop Recording & Save
        val btnStopScr = createSettingRow("Stop Recording & Save", "")
        btnStopScr.setOnClickListener {
            clickFeedback()
            try {
                val intent = activity.packageManager.getLaunchIntentForPackage("com.iwobanas.screenrecorder.pro")
                if (intent != null) {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    activity.startActivity(intent)
                    Toast.makeText(
                        activity,
                        localize("Recording saved safely!"),
                        Toast.LENGTH_LONG
                    ).show()
                }
            } catch (ignored: Exception) {}
        }
        containerSettingsItems.addView(btnStopScr)

        if (containerSettingsItems.childCount > 0) {
            containerSettingsItems.getChildAt(0).requestFocus()
        }
    }
}
