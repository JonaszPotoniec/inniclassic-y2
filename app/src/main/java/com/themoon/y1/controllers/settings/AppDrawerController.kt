package com.themoon.y1.controllers.settings

import android.app.Activity
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.themoon.y1.R
import com.themoon.y1.controllers.ScreenController
import com.themoon.y1.navigation.ScreenState
import java.util.Collections

/**
 * Controller for the installed applications drawer.
 * Lists launchable applications on device sorted alphabetically and allows starting them.
 */
class AppDrawerController(
    private val activity: Activity,
    override val rootView: ViewGroup,
    private val createListButton: (text: String) -> Button,
    private val clickFeedback: () -> Unit,
    private val localize: (String) -> String
) : ScreenController {

    private val containerSettingsItems: LinearLayout = rootView.findViewById(R.id.container_settings_items)

    override fun onEnter(state: ScreenState) {
        buildAppDrawerUI()
    }

    fun buildAppDrawerUI() {
        containerSettingsItems.removeAllViews()

        val pm = activity.packageManager
        val packages = pm.getInstalledApplications(PackageManager.GET_META_DATA)

        Collections.sort(packages) { a: ApplicationInfo, b: ApplicationInfo ->
            pm.getApplicationLabel(a).toString().compareTo(pm.getApplicationLabel(b).toString(), ignoreCase = true)
        }

        var appCount = 0
        for (appInfo in packages) {
            val launchIntent = pm.getLaunchIntentForPackage(appInfo.packageName)

            // Hide launcher itself from the list
            if (launchIntent != null && appInfo.packageName != activity.packageName) {
                val appName = pm.getApplicationLabel(appInfo).toString()
                val btnApp = createListButton(appName)

                btnApp.setOnClickListener {
                    clickFeedback()
                    try {
                        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        activity.startActivity(launchIntent)
                        Toast.makeText(
                            activity,
                            localize("Starting: ") + pm.getApplicationLabel(appInfo),
                            Toast.LENGTH_SHORT
                        ).show()
                    } catch (e: Exception) {
                        Toast.makeText(activity, localize("Unable to launch app."), Toast.LENGTH_SHORT).show()
                    }
                }

                containerSettingsItems.addView(btnApp)
                appCount++
            }
        }

        if (appCount == 0) {
            val tvEmpty = TextView(activity).apply {
                text = localize("No installed apps found.")
                setTextColor(0xFF888888.toInt())
                setPadding(20, 50, 20, 50)
                gravity = Gravity.CENTER
            }
            containerSettingsItems.addView(tvEmpty)
        } else {
            if (containerSettingsItems.childCount > 0) {
                containerSettingsItems.getChildAt(0).requestFocus()
            }
        }
    }
}
