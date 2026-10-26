package com.themoon.y1

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipInputStream

object ThemeManager {

    class MenuElement(
        var id: String,
        var type: String,
        var parentId: String,
        var visibleOnFocus: String,
        var x: Int,
        var y: Int,
        var width: Int,
        var height: Int,
        var textNormal: String,
        var textFocused: String,
        var textRight: String,
        var textRightColor: String,
        var textRightFocusedColor: String,
        var iconNormal: String,
        var iconFocused: String,
        var previewImage: String,
        var action: String,
        var gravity: String,
        var radius: Int,
        var focusIndex: Int,
        var textSize: Int,
        var textSecondarySize: Int,
        var textPosition: String,
        var textAlign: String,
        var bgColor: String,
        var padding: Int,
        var focusOffsetX: Int,
        var focusOffsetY: Int,
        var focusScale: Float
    )

    class ThemeData(
        var folderPath: String,
        var name: String,
        var bgImage: String,
        var customFont: Typeface,
        var textPrimary: Int,
        var textSecondary: Int,
        var bgOverlay: Int,
        var statusBarBg: Int,
        var btnNormal: Int,
        var btnFocused: Int,
        var btnFocusedText: Int,
        var buttonRadius: Int
    ) {
        var customFontBold: Typeface? = null
        var menuElements: MutableList<MenuElement> = ArrayList()
    }

    var availableThemes: MutableList<ThemeData> = ArrayList()

    private var currentThemeIndex: Int = 0

    /** Goes up by one at each theme change (see [selectTheme]). */
    @Volatile
    var themeGeneration: Int = 0
        private set

    // Fonts by file, path, size and time, so that a new load of the themes does not make new Typeface objects
    private val fontCache = HashMap<String, Typeface>()

    private val WIDGET_TYPES = listOf(
        "widget_clock", "widget_analog_clock", "widget_battery",
        "widget_circular_battery", "widget_album", "widget_focus_image"
    )

    private val OBSOLETE_THEMES = setOf(
        "Clean Light",
        "Cyberpunk Neon",
        "cyberpunk_neon_list",
        "Forest Green",
        "forest_green_list",
        "macOS26",
        "Royal Gold",
        "royal_gold_list",
        "Windows 8 Metro",
        "Dark (Default)",
        "default"
    )

    private val DEFAULT_FALLBACK_THEME = ThemeData(
        "", "Classic Light", "", Typeface.DEFAULT,
        0xFF1C1C1E.toInt(), 0xFF666666.toInt(), 0xFFFFFFFF.toInt(), 0xFFC8C9C8.toInt(),
        0x00FFFFFF, 0xFF2588DC.toInt(), Color.WHITE, 0
    ).apply {
        menuElements.add(MenuElement("main_scroll_list", "list_box", "", "", 0, 0, 242, 325, "", "", "", "", "", "", "", "", "NONE", "top|left", -1, -1, 16, -1, "bottom", "left", "", 0, 0, 0, 1.0f))
        menuElements.add(MenuElement("btn_now", "button", "main_scroll_list", "", 0, 0, -1, 33, "Now Playing", "Now Playing", "›", "#FF8E8E93", "#FFFFFFFF", "", "", "", "OPEN_PLAYER", "top|left", -1, 0, 21, -1, "bottom", "left", "", 0, 0, 0, 1.0f))
        menuElements.add(MenuElement("btn_music", "button", "main_scroll_list", "", 0, 0, -1, 33, "Music", "Music", "›", "#FF8E8E93", "#FFFFFFFF", "", "", "", "OPEN_BROWSER", "top|left", -1, 1, 21, -1, "bottom", "left", "", 0, 0, 0, 1.0f))
        menuElements.add(MenuElement("btn_videos", "button", "main_scroll_list", "", 0, 0, -1, 33, "Videos", "Videos", "›", "#FF8E8E93", "#FFFFFFFF", "", "", "", "OPEN_VIDEOS", "top|left", -1, 2, 21, -1, "bottom", "left", "", 0, 0, 0, 1.0f))
        menuElements.add(MenuElement("btn_musicquiz", "button", "main_scroll_list", "", 0, 0, -1, 33, "Music Quiz", "Music Quiz", "›", "#FF8E8E93", "#FFFFFFFF", "", "", "", "OPEN_MUSIC_QUIZ", "top|left", -1, 3, 21, -1, "bottom", "left", "", 0, 0, 0, 1.0f))
        menuElements.add(MenuElement("btn_podcasts", "button", "main_scroll_list", "", 0, 0, -1, 33, "Podcasts", "Podcasts", "›", "#FF8E8E93", "#FFFFFFFF", "", "", "", "OPEN_PODCASTS", "top|left", -1, 4, 21, -1, "bottom", "left", "", 0, 0, 0, 1.0f))
        menuElements.add(MenuElement("btn_audiobooks", "button", "main_scroll_list", "", 0, 0, -1, 33, "Audiobooks", "Audiobooks", "›", "#FF8E8E93", "#FFFFFFFF", "", "", "", "OPEN_AUDIOBOOKS", "top|left", -1, 5, 21, -1, "bottom", "left", "", 0, 0, 0, 1.0f))
        menuElements.add(MenuElement("btn_radio", "button", "main_scroll_list", "", 0, 0, -1, 33, "FM Radio", "FM Radio", "›", "#FF8E8E93", "#FFFFFFFF", "", "", "", "OPEN_RADIO", "top|left", -1, 6, 21, -1, "bottom", "left", "", 0, 0, 0, 1.0f))
        menuElements.add(MenuElement("btn_bt", "button", "main_scroll_list", "", 0, 0, -1, 33, "Bluetooth", "Bluetooth", "›", "#FF8E8E93", "#FFFFFFFF", "", "", "", "OPEN_BLUETOOTH", "top|left", -1, 7, 21, -1, "bottom", "left", "", 0, 0, 0, 1.0f))
        menuElements.add(MenuElement("btn_wifi", "button", "main_scroll_list", "", 0, 0, -1, 33, "Wi-Fi", "Wi-Fi", "›", "#FF8E8E93", "#FFFFFFFF", "", "", "", "OPEN_WIFI", "top|left", -1, 8, 21, -1, "bottom", "left", "", 0, 0, 0, 1.0f))
        menuElements.add(MenuElement("btn_set", "button", "main_scroll_list", "", 0, 0, -1, 33, "Settings", "Settings", "›", "#FF8E8E93", "#FFFFFFFF", "", "", "", "OPEN_SETTINGS", "top|left", -1, 9, 21, -1, "bottom", "left", "", 0, 0, 0, 1.0f))
        menuElements.add(MenuElement("btn_web", "button", "main_scroll_list", "", 0, 0, -1, 33, "Web Server", "Web Server", "›", "#FF8E8E93", "#FFFFFFFF", "", "", "", "OPEN_WEBSERVER", "top|left", -1, 10, 21, -1, "bottom", "left", "", 0, 0, 0, 1.0f))
        menuElements.add(MenuElement("widget_cover", "widget_animated_cover", "", "", 242, 0, 238, 325, "", "", "", "", "", "", "", "", "NONE", "top|left", -1, -1, 16, -1, "bottom", "left", "", 0, 0, 0, 1.0f))
    }

    private var cachedBoldFont: Typeface? = null
    private var cachedBoldFontSource: Typeface? = null

    private fun safeParseColor(colorStr: String?, defaultColor: Int): Int {
        try {
            if (!colorStr.isNullOrBlank()) {
                return Color.parseColor(colorStr.trim())
            }
        } catch (_: Exception) {
        }
        return defaultColor
    }

    fun getCustomIcon(iconFileName: String?, context: Context, defaultResId: Int): Bitmap? {
        if (availableThemes.isNotEmpty() && !iconFileName.isNullOrEmpty()) {
            val folder = getCurrentTheme().folderPath

            if (folder.isNotEmpty() && folder != "default") {
                val iconFile = File(folder, iconFileName)
                if (iconFile.exists()) {
                    try {
                        return BitmapFactory.decodeFile(iconFile.absolutePath)
                    } catch (_: Exception) {
                    }
                }
            } else {
                try {
                    var resName = iconFileName
                    if (resName.contains(".")) {
                        resName = resName.substring(0, resName.lastIndexOf("."))
                    }
                    val resId = context.resources.getIdentifier(resName, "drawable", context.packageName)
                    if (resId != 0) {
                        return BitmapFactory.decodeResource(context.resources, resId)
                    }
                } catch (_: Exception) {
                }
            }
        }
        if (defaultResId != 0) {
            try {
                return BitmapFactory.decodeResource(context.resources, defaultResId)
            } catch (_: Exception) {
            }
        }
        return null
    }

    fun getStatusBarBackgroundColor(): Int {
        if (availableThemes.isEmpty()) return 0x66000000
        return availableThemes[currentThemeIndex].statusBarBg
    }

    fun createStatusBarBackground(): GradientDrawable {
        if (isClassicLightTheme()) {
            return GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0xFFF7F7F7.toInt(), 0xFFDADADA.toInt(), 0xFFB6B6B6.toInt())
            )
        }
        val base = getStatusBarBackgroundColor()
        val hsv = FloatArray(3)
        Color.colorToHSV(base, hsv)
        hsv[2] = Math.min(1f, hsv[2] * 1.15f)
        val lighter = (base and 0xFF000000.toInt()) or (Color.HSVToColor(hsv) and 0x00FFFFFF)
        Color.colorToHSV(base, hsv)
        hsv[2] = Math.max(0f, hsv[2] * 0.85f)
        val darker = (base and 0xFF000000.toInt()) or (Color.HSVToColor(hsv) and 0x00FFFFFF)
        return GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(lighter, base, darker))
    }

    fun loadThemesFromStorage(themeFolder: File) {
        // A new theme folder can sort before the current theme, so find the current theme again by name
        val currentName = if (availableThemes.isEmpty()) null else getCurrentTheme().name
        availableThemes.clear()

        if (!themeFolder.exists()) {
            themeFolder.mkdirs()
        }

        val allFiles = themeFolder.listFiles()
        if (allFiles != null) {
            for (file in allFiles) {
                if (file.isFile && file.name.lowercase().endsWith(".zip")) {
                    try {
                        val folderName = file.name.substring(0, file.name.lastIndexOf("."))
                        val extractDir = File(themeFolder, folderName)
                        if (!extractDir.exists()) extractDir.mkdirs()

                        val fis = FileInputStream(file)
                        val zis = ZipInputStream(BufferedInputStream(fis))
                        var ze = zis.nextEntry

                        while (ze != null) {
                            val extractFile = File(extractDir, ze.name)
                            if (ze.isDirectory) {
                                extractFile.mkdirs()
                            } else {
                                val parent = extractFile.parentFile
                                if (parent != null && !parent.exists()) parent.mkdirs()
                                val fout = FileOutputStream(extractFile)
                                val buffer = ByteArray(8192)
                                var count: Int
                                while (zis.read(buffer).also { count = it } != -1) {
                                    fout.write(buffer, 0, count)
                                }
                                fout.close()
                            }
                            zis.closeEntry()
                            ze = zis.nextEntry
                        }
                        zis.close()
                        fis.close()
                        file.delete()
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
        }

        val folders = themeFolder.listFiles()
        if (folders != null) {
            for (subFolder in folders) {
                if (subFolder.isDirectory) {
                    val configFile = File(subFolder, "config.json")
                    if (configFile.exists() && configFile.isFile) {
                        try {
                            val fis = FileInputStream(configFile)
                            val data = ByteArray(configFile.length().toInt())
                            fis.read(data)
                            fis.close()

                            val jsonStr = String(data, Charsets.UTF_8).replace("\uFEFF", "")
                            val json = JSONObject(jsonStr)

                            val rawName = json.optString("name", subFolder.name)
                            val normalizedName = when (rawName) {
                                "iPod Classic" -> "Classic Light"
                                "iPod Classic Dark", "iPod Classic (Dark)" -> "Classic Dark"
                                else -> rawName
                            }

                            if (OBSOLETE_THEMES.contains(rawName) || OBSOLETE_THEMES.contains(subFolder.name)) {
                                continue
                            }

                            val parsedOverlayBg = safeParseColor(json.optString("bgOverlay"), 0x88000000.toInt())
                            val parsedStatusBarBg = safeParseColor(json.optString("statusBarBg"), parsedOverlayBg)
                            val parsedTextPrimary = safeParseColor(json.optString("textPrimary"), 0xFFFFFFFF.toInt())
                            val parsedTextSecondary = safeParseColor(json.optString("textSecondary"), 0xFF888888.toInt())
                            val parsedBtnNormal = safeParseColor(json.optString("btnNormal"), 0x15FFFFFF)
                            val parsedBtnFocused = safeParseColor(json.optString("btnFocused"), 0xDDFFFFFF.toInt())
                            val parsedBtnFocusedText = safeParseColor(json.optString("btnFocusedText"), 0xFF000000.toInt())
                            val parsedRadius = json.optInt("button_radius", 15)

                            var parsedFont = Typeface.DEFAULT
                            if (json.has("font")) {
                                val fontFile = File(subFolder, json.getString("font"))
                                if (fontFile.exists() && fontFile.isFile) {
                                    parsedFont = loadFont(fontFile) ?: Typeface.DEFAULT
                                }
                            }

                            var parsedFontBold: Typeface? = null
                            if (json.has("fontBold")) {
                                val fontBoldFile = File(subFolder, json.getString("fontBold"))
                                if (fontBoldFile.exists() && fontBoldFile.isFile) {
                                    parsedFontBold = loadFont(fontBoldFile)
                                }
                            }

                            val parsedBgImage = json.optString("bg_image", "")

                            val theme = ThemeData(
                                subFolder.absolutePath,
                                normalizedName,
                                parsedBgImage,
                                parsedFont,
                                parsedTextPrimary, parsedTextSecondary,
                                parsedOverlayBg, parsedStatusBarBg,
                                parsedBtnNormal, parsedBtnFocused, parsedBtnFocusedText,
                                parsedRadius
                            )
                            theme.customFontBold = parsedFontBold

                            if (json.has("main_menu")) {
                                val menuArray = json.getJSONArray("main_menu")
                                for (i in 0 until menuArray.length()) {
                                    val el = menuArray.getJSONObject(i)
                                    theme.menuElements.add(
                                        MenuElement(
                                            el.optString("id", "item_$i"),
                                            el.optString("type", "button"),
                                            el.optString("parent_id", ""),
                                            el.optString("visible_on_focus", ""),
                                            el.optInt("x", 0),
                                            el.optInt("y", i * 60),
                                            el.optInt("width", 200),
                                            el.optInt("height", 50),
                                            el.optString("text_normal", ""),
                                            el.optString("text_focused", ""),
                                            el.optString("text_right", ""),
                                            el.optString("text_right_color", ""),
                                            el.optString("text_right_focused_color", ""),
                                            el.optString("icon_normal", ""),
                                            el.optString("icon_focused", ""),
                                            el.optString("preview_image", ""),
                                            el.optString("action", "NONE"),
                                            el.optString("gravity", "top|left"),
                                            el.optInt("radius", -1),
                                            el.optInt("focus_index", i + 1),
                                            el.optInt("text_size", -1),
                                            el.optInt("text_secondary_size", -1),
                                            el.optString("text_position", "bottom"),
                                            el.optString("text_align", "center"),
                                            el.optString("bg_color", ""),
                                            el.optInt("padding", 0),
                                            el.optInt("focus_offset_x", 0),
                                            el.optInt("focus_offset_y", 0),
                                            el.optDouble("focus_scale", 1.0).toFloat()
                                        )
                                    )
                                }
                            }
                            if (availableThemes.none { it.name == theme.name }) {
                                availableThemes.add(theme)
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                }
            }
        }

        if (availableThemes.isEmpty()) {
            availableThemes.add(DEFAULT_FALLBACK_THEME)
        }

        availableThemes.sortWith(Comparator { a, b ->
            if (a.name == "Classic Light") -1
            else if (b.name == "Classic Light") 1
            else a.name.compareTo(b.name)
        })

        if (currentName != null) {
            val index = findThemeIndexByName(currentName)
            if (index >= 0) currentThemeIndex = index
        }
    }

    private fun loadFont(file: File): Typeface? {
        val key = file.absolutePath + "|" + file.length() + "|" + file.lastModified()
        fontCache[key]?.let { return it }
        return try {
            Typeface.createFromFile(file).also { fontCache[key] = it }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Makes [name] the current theme and saves it, with its background and main menu widgets, as a tap
     * in Settings → Theme does. False if the theme is not installed or is already the current theme.
     */
    fun selectTheme(prefs: SharedPreferences, name: String): Boolean {
        val index = findThemeIndexByName(name)
        if (index < 0) return false
        val theme = availableThemes[index]
        val changed = index != currentThemeIndex || getCurrentTheme().name != prefs.getString("app_theme_name", null)
        setThemeIndex(index)
        try {
            val editor = prefs.edit()
                .putInt("app_theme_index", index)
                .putString("app_theme_name", theme.name)
                .putString("current_theme_name", theme.name)
            if (hasThemeBackground(theme)) editor.putString("bg_path", "THEME_DEFAULT")
            val types = theme.menuElements.map { it.type }.toSet()
            for (widget in WIDGET_TYPES) editor.putBoolean(widget, widget in types)
            editor.commit()
        } catch (_: Exception) {
        }
        if (changed) themeGeneration++
        return changed
    }

    fun hasThemeBackground(theme: ThemeData): Boolean {
        if (theme.bgImage.isNotEmpty() && File(theme.folderPath, theme.bgImage).exists()) return true
        return File(theme.folderPath, "background.png").exists() || File(theme.folderPath, "bg.png").exists()
    }

    fun setThemeIndex(index: Int) {
        currentThemeIndex = if (index in 0 until availableThemes.size) index else 0
    }

    fun findThemeIndexByName(name: String?): Int {
        if (name == null) return -1
        val targetName = when (name) {
            "iPod Classic" -> "Classic Light"
            "iPod Classic Dark", "iPod Classic (Dark)" -> "Classic Dark"
            else -> name
        }
        for (i in availableThemes.indices) {
            if (availableThemes[i].name == targetName || availableThemes[i].name == name) return i
        }
        return -1
    }

    fun getCurrentThemeIndex(): Int = currentThemeIndex

    fun getCurrentTheme(): ThemeData {
        if (availableThemes.isEmpty() || currentThemeIndex < 0 || currentThemeIndex >= availableThemes.size) {
            return DEFAULT_FALLBACK_THEME
        }
        return availableThemes[currentThemeIndex]
    }

    fun getCustomFont(): Typeface {
        if (availableThemes.isEmpty()) return Typeface.DEFAULT
        return availableThemes[currentThemeIndex].customFont
    }

    fun getCustomFontBold(): Typeface {
        if (availableThemes.isNotEmpty()) {
            val realBold = getCurrentTheme().customFontBold
            if (realBold != null) return realBold
        }

        val base = getCustomFont()
        if (cachedBoldFont == null || cachedBoldFontSource != base) {
            cachedBoldFontSource = base
            cachedBoldFont = Typeface.create(base, Typeface.BOLD)
        }
        return cachedBoldFont ?: Typeface.DEFAULT_BOLD
    }

    fun isClassicTheme(): Boolean {
        val name = getCurrentTheme().name
        return "Classic Light" == name || "Classic Dark" == name || "iPod Classic" == name || "iPod Classic Dark" == name || "iPod Classic (Dark)" == name
    }

    fun isClassicLightTheme(): Boolean {
        val name = getCurrentTheme().name
        return "Classic Light" == name || "iPod Classic" == name
    }

    fun getListTextSize(): Float = if (isClassicTheme()) 21f else 23f

    fun getTextColorPrimary(): Int = getCurrentTheme().textPrimary

    fun getTextColorSecondary(): Int = getCurrentTheme().textSecondary

    fun getOverlayBackgroundColor(): Int = getCurrentTheme().bgOverlay

    fun getListButtonNormalBg(): Int = getCurrentTheme().btnNormal

    fun getListButtonFocusedBg(): Int = getCurrentTheme().btnFocused

    fun getListButtonFocusedTextColor(): Int = getCurrentTheme().btnFocusedText

    fun getButtonRadius(): Int = getCurrentTheme().buttonRadius
}
