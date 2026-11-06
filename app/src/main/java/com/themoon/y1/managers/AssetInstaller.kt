package com.themoon.y1.managers

import com.themoon.y1.Prefs
import android.content.Context
import android.content.SharedPreferences
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipInputStream

object AssetInstaller {

    fun installBundledLanguages(context: Context, prefs: SharedPreferences) {
        val lastInstalledVersion = prefs.getInt("last_lang_version", 0)
        val currentPackVersion = 1011
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
        // 1012: Play On row in the main menu of Classic Light and Classic Dark
        val currentThemeVersion = 1012
        if (lastThemeVersion >= currentThemeVersion) return

        try {
            val obsoleteThemes = listOf(
                "Clean Light",
                "Cyberpunk Neon",
                "cyberpunk_neon_list",
                "Forest Green",
                "forest_green_list",
                "macOS26",
                "Royal Gold",
                "royal_gold_list",
                "Windows 8 Metro",
                "iPod Classic",
                "iPod Classic Dark",
                "iPod Classic (Dark)",
                "default"
            )
            for (name in obsoleteThemes) {
                val oldDir = File(themesDir, name)
                if (oldDir.exists()) oldDir.deleteRecursively()
                val oldZip = File(themesDir, "$name.zip")
                if (oldZip.exists()) oldZip.delete()
            }

            try {
                val extThemesDir = com.themoon.y1.StoragePaths.getThemesDir()
                for (name in obsoleteThemes) {
                    val oldExtDir = File(extThemesDir, name)
                    if (oldExtDir.exists()) oldExtDir.deleteRecursively()
                    val oldExtZip = File(extThemesDir, "$name.zip")
                    if (oldExtZip.exists()) oldExtZip.delete()
                }
            } catch (_: Exception) {}

            val currentAppTheme = prefs.getString(Prefs.THEME_NAME.key, null)
                ?: prefs.getString(Prefs.CURRENT_THEME_NAME.key, null)
            val migratedTheme = when (currentAppTheme) {
                "iPod Classic Dark", "iPod Classic (Dark)" -> "Classic Dark"
                "iPod Classic" -> "Classic Light"
                null -> null
                else -> if (obsoleteThemes.contains(currentAppTheme)) "Classic Light" else null
            }
            if (migratedTheme != null) {
                prefs.edit()
                    .putString(Prefs.THEME_NAME.key, migratedTheme)
                    .putString(Prefs.CURRENT_THEME_NAME.key, migratedTheme)
                    .apply()
            }

            val orderClassic = prefs.getString("custom_menu_order_iPod Classic", null)
            if (orderClassic != null && !prefs.contains("custom_menu_order_Classic Light")) {
                prefs.edit().putString("custom_menu_order_Classic Light", orderClassic).apply()
            }
            val orderDark = prefs.getString("custom_menu_order_iPod Classic Dark", null)
                ?: prefs.getString("custom_menu_order_iPod Classic (Dark)", null)
            if (orderDark != null && !prefs.contains("custom_menu_order_Classic Dark")) {
                prefs.edit().putString("custom_menu_order_Classic Dark", orderDark).apply()
            }

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
