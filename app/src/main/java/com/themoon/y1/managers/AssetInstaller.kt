package com.themoon.y1.managers

import android.content.Context
import android.content.SharedPreferences
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipInputStream

object AssetInstaller {

    fun installBundledLanguages(context: Context, prefs: SharedPreferences) {
        val lastInstalledVersion = prefs.getInt("last_lang_version", 0)
        val currentPackVersion = 1010
        if (lastInstalledVersion >= currentPackVersion) return

        val langFolder = File(context.filesDir, "languages")
        if (!langFolder.exists()) langFolder.mkdirs()

        try {
            val assetFiles = context.assets.list("languages") ?: return
            for (filename in assetFiles) {
                if (!filename.endsWith(".json")) continue
                val outFile = File(langFolder, filename)
                context.assets.open("languages/$filename").use { input ->
                    FileOutputStream(outFile).use { output ->
                        input.copyTo(output)
                    }
                }
            }
            prefs.edit().putInt("last_lang_version", currentPackVersion).apply()
        } catch (_: Exception) {}
    }

    fun installBundledThemes(context: Context, prefs: SharedPreferences) {
        val themesDir = File(context.filesDir, "themes")
        if (!themesDir.exists()) themesDir.mkdirs()

        val lastThemeVersion = prefs.getInt("last_installed_theme_version", 0)
        val currentThemeVersion = 1005
        if (lastThemeVersion >= currentThemeVersion) return

        try {
            val themeAssets = context.assets.list("themes") ?: return
            for (assetName in themeAssets) {
                if (assetName.endsWith(".zip")) {
                    val targetDir = File(themesDir, assetName.removeSuffix(".zip"))
                    if (!targetDir.exists()) targetDir.mkdirs()
                    context.assets.open("themes/$assetName").use { input ->
                        val zipIn = ZipInputStream(input)
                        var entry = zipIn.nextEntry
                        while (entry != null) {
                            val outFile = File(targetDir, entry.name)
                            if (entry.isDirectory) {
                                outFile.mkdirs()
                            } else {
                                outFile.parentFile?.mkdirs()
                                FileOutputStream(outFile).use { out -> zipIn.copyTo(out) }
                            }
                            zipIn.closeEntry()
                            entry = zipIn.nextEntry
                        }
                    }
                }
            }
            prefs.edit().putInt("last_installed_theme_version", currentThemeVersion).apply()
        } catch (_: Exception) {}
    }
}
