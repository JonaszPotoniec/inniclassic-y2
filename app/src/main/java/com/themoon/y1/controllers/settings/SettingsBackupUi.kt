package com.themoon.y1.controllers.settings

import com.themoon.y1.Prefs
import android.app.Activity
import android.content.Context
import android.util.Log
import com.themoon.y1.StoragePaths
import com.themoon.y1.managers.AudioEffectManager
import com.themoon.y1.managers.LanguageManager
import com.themoon.y1.managers.SettingsBackup
import com.themoon.y1.views.Notice
import java.io.File

/** The actions of the Export Settings and Import Settings rows (Settings → General). */
internal object SettingsBackupUi {
    private const val TAG = "SettingsBackup"

    fun exportSettings(activity: Activity, localize: (String) -> String) {
        try {
            val file = SettingsBackup.export(activity, appVersion(activity))
            Notice.show(activity, localize("Settings exported:") + "\n" + StoragePaths.toDisplayPath(file.absolutePath), long = true)
        } catch (e: Exception) {
            Log.e(TAG, "Export failed", e)
            Notice.error(activity, localize("Export failed."))
        }
    }

    fun confirmImport(activity: Activity, localize: (String) -> String) {
        val file = SettingsBackup.findImportFile()
        if (file == null) {
            Notice.show(activity, localize("No settings backup found."))
            return
        }
        // No row is selected at the start: one centre push must not replace the settings
        ActionScreens.open(confirmScreen(
            localize("Import Settings"),
            localize("Replace the current settings with the backup? Music and playlists do not change.") +
                "\n" + StoragePaths.toDisplayPath(file.absolutePath),
            localize("Import"),
            selectAction = false
        ) { importSettings(activity, file, localize) })
    }

    private fun importSettings(activity: Activity, file: File, localize: (String) -> String) {
        try {
            SettingsBackup.import(activity, file)
        } catch (e: SettingsBackup.InvalidBackupException) {
            Log.w(TAG, "Invalid backup: ${e.message}")
            Notice.error(activity, localize("Not a valid settings backup."))
            return
        } catch (e: Exception) {
            Log.e(TAG, "Import failed", e)
            Notice.error(activity, localize("Import failed."))
            return
        }
        val prefs = activity.getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
        LanguageManager.getInstance(activity).applyLanguage(Prefs.LANGUAGE.get(prefs))
        try {
            AudioEffectManager.getInstance().applySoundCheck()
            AudioEffectManager.getInstance().applyEqProfile()
        } catch (_: Exception) {}
        Notice.showAfterRecreate(LanguageManager.getInstance(activity).t("Settings imported."))
        // The activity reads the theme, the menus and the other settings again in onCreate.
        activity.recreate()
    }

    private fun appVersion(context: Context): String = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""
    } catch (_: Exception) {
        ""
    }
}
