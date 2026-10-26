package com.themoon.y1.controllers.settings

import android.app.Activity
import android.content.Context
import android.util.Log
import android.widget.LinearLayout
import android.widget.Toast
import com.themoon.y1.StoragePaths
import com.themoon.y1.managers.AudioEffectManager
import com.themoon.y1.managers.LanguageManager
import com.themoon.y1.managers.SettingsBackup
import com.themoon.y1.views.WheelDialog
import java.io.File

/** The Export Settings and Import Settings rows of the Settings screen. */
internal object SettingsBackupUi {
    private const val TAG = "SettingsBackup"

    fun addRows(
        activity: Activity,
        container: LinearLayout,
        createSettingRow: (title: String, value: String) -> LinearLayout,
        clickFeedback: () -> Unit,
        localize: (String) -> String
    ) {
        val btnExport = createSettingRow(localize("Export Settings"), "〉 ")
        btnExport.setOnClickListener {
            clickFeedback()
            exportSettings(activity, localize)
        }
        container.addView(btnExport)

        val btnImport = createSettingRow(localize("Import Settings"), "〉 ")
        btnImport.setOnClickListener {
            clickFeedback()
            confirmImport(activity, localize)
        }
        container.addView(btnImport)
    }

    private fun exportSettings(activity: Activity, localize: (String) -> String) {
        try {
            val file = SettingsBackup.export(activity, appVersion(activity))
            Toast.makeText(activity, localize("Settings exported:") + "\n" + StoragePaths.toDisplayPath(file.absolutePath), Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Log.e(TAG, "Export failed", e)
            Toast.makeText(activity, localize("Export failed."), Toast.LENGTH_SHORT).show()
        }
    }

    private fun confirmImport(activity: Activity, localize: (String) -> String) {
        val file = SettingsBackup.findImportFile()
        if (file == null) {
            Toast.makeText(activity, localize("No settings backup found."), Toast.LENGTH_SHORT).show()
            return
        }
        WheelDialog.show(activity) {
            title = localize("Import Settings")
            message = localize("Replace the current settings with the backup? Music and playlists do not change.") +
                "\n" + StoragePaths.toDisplayPath(file.absolutePath)
            positiveButton(localize("Import"), isDestructive = true) {
                importSettings(activity, file, localize)
            }
            negativeButton(localize("Cancel"))
        }
    }

    private fun importSettings(activity: Activity, file: File, localize: (String) -> String) {
        try {
            SettingsBackup.import(activity, file)
        } catch (e: SettingsBackup.InvalidBackupException) {
            Log.w(TAG, "Invalid backup: ${e.message}")
            Toast.makeText(activity, localize("Not a valid settings backup."), Toast.LENGTH_SHORT).show()
            return
        } catch (e: Exception) {
            Log.e(TAG, "Import failed", e)
            Toast.makeText(activity, localize("Import failed."), Toast.LENGTH_SHORT).show()
            return
        }
        val prefs = activity.getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
        LanguageManager.getInstance(activity).applyLanguage(prefs.getString("app_language", "English (Default)"))
        try {
            AudioEffectManager.getInstance().applySoundCheck()
            AudioEffectManager.getInstance().applyEqProfile()
        } catch (_: Exception) {}
        Toast.makeText(activity, LanguageManager.getInstance(activity).t("Settings imported."), Toast.LENGTH_SHORT).show()
        // The activity reads the theme, the menus and the other settings again in onCreate.
        activity.recreate()
    }

    private fun appVersion(context: Context): String = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""
    } catch (_: Exception) {
        ""
    }
}
