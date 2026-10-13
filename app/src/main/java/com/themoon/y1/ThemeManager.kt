package com.themoon.y1

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipInputStream

object ThemeManager {

    class MenuElement(
        @JvmField var id: String,
        @JvmField var type: String,
        @JvmField var parentId: String,
        @JvmField var visibleOnFocus: String,
        @JvmField var x: Int,
        @JvmField var y: Int,
        @JvmField var width: Int,
        @JvmField var height: Int,
        @JvmField var textNormal: String,
        @JvmField var textFocused: String,
        @JvmField var textRight: String,
        @JvmField var textRightColor: String,
        @JvmField var textRightFocusedColor: String,
        @JvmField var iconNormal: String,
        @JvmField var iconFocused: String,
        @JvmField var previewImage: String,
        @JvmField var action: String,
        @JvmField var gravity: String,
        @JvmField var radius: Int,
        @JvmField var focusIndex: Int,
        @JvmField var textSize: Int,
        @JvmField var textSecondarySize: Int,
        @JvmField var textPosition: String,
        @JvmField var textAlign: String,
        @JvmField var bgColor: String,
        @JvmField var padding: Int,
        @JvmField var focusOffsetX: Int,
        @JvmField var focusOffsetY: Int,
        @JvmField var focusScale: Float
    )

    class ThemeData(
        @JvmField var folderPath: String,
        @JvmField var name: String,
        @JvmField var bgImage: String,
        @JvmField var customFont: Typeface,
        @JvmField var textPrimary: Int,
        @JvmField var textSecondary: Int,
        @JvmField var bgOverlay: Int,
        @JvmField var statusBarBg: Int,
        @JvmField var btnNormal: Int,
        @JvmField var btnFocused: Int,
        @JvmField var btnFocusedText: Int,
        @JvmField var buttonRadius: Int
    ) {
        @JvmField
        var customFontBold: Typeface? = null

        @JvmField
        var menuElements: MutableList<MenuElement> = ArrayList()
    }

    @JvmField
    var availableThemes: MutableList<ThemeData> = ArrayList()

    private var currentThemeIndex: Int = 0

    private val DEFAULT_FALLBACK_THEME = ThemeData(
        "", "iPod Classic", "", Typeface.DEFAULT,
        Color.WHITE, 0xFF888888.toInt(), 0xDD000000.toInt(), 0xFF000000.toInt(),
        0x33444444, 0xFF0275D8.toInt(), Color.WHITE, 6
    )

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

    @JvmStatic
    fun getCustomIcon(iconFileName: String?, context: Context, defaultResId: Int): Bitmap? {
        if (availableThemes.isNotEmpty() && !iconFileName.isNullOrEmpty()) {
            val folder = getCurrentTheme().folderPath

            if (folder != "default") {
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

    @JvmStatic
    fun getStatusBarBackgroundColor(): Int {
        if (availableThemes.isEmpty()) return 0x66000000
        return availableThemes[currentThemeIndex].statusBarBg
    }

    @JvmStatic
    fun loadThemesFromStorage(themeFolder: File) {
        availableThemes.clear()

        val defaultTheme = ThemeData(
            "default", "Dark (Default)", "", Typeface.DEFAULT,
            0xFFFFFFFF.toInt(), 0xFF888888.toInt(), 0x00000000, 0x88000000.toInt(),
            0x15FFFFFF, 0xDDFFFFFF.toInt(), 0xFF000000.toInt(), 15
        )

        defaultTheme.menuElements.add(MenuElement("box", "box", "", "", 0, 0, 240, 325, "", "", "", "", "", "", "", "", "NONE", "top|left", 0, -1, 16, -1, "bottom", "left", "#A0000000", 0, 0, 0, 1.0f))
        defaultTheme.menuElements.add(MenuElement("main_scroll_list", "list_box", "", "", 10, 15, 220, 290, "", "", "", "", "", "", "", "", "NONE", "top|left", -1, -1, 16, -1, "bottom", "left", "", 0, 0, 0, 1.0f))
        defaultTheme.menuElements.add(MenuElement("btn_now", "button", "main_scroll_list", "", 0, 0, -1, 48, "Now Playing", "Now Playing", ">", "", "", "", "", "music_circle.png", "OPEN_PLAYER", "top|left", -1, 0, 22, -1, "bottom", "left", "#00000000", 0, 0, 0, 1.0f))
        defaultTheme.menuElements.add(MenuElement("btn_coverflow", "button", "main_scroll_list", "", 0, 8, -1, 48, "Cover Flow", "Cover Flow", ">", "", "", "", "", "cover.png", "OPEN_COVER_FLOW", "top|left", -1, 1, 22, -1, "bottom", "left", "#00000000", 0, 0, 0, 1.0f))
        defaultTheme.menuElements.add(MenuElement("btn_music", "button", "main_scroll_list", "", 0, 8, -1, 48, "Music", "Music", ">", "", "", "", "", "music_list.png", "OPEN_BROWSER", "top|left", -1, 2, 22, -1, "bottom", "left", "#00000000", 0, 0, 0, 1.0f))
        defaultTheme.menuElements.add(MenuElement("btn_videos", "button", "main_scroll_list", "", 0, 8, -1, 48, "Videos", "Videos", ">", "", "", "", "", "cover.png", "OPEN_VIDEOS", "top|left", -1, 3, 22, -1, "bottom", "left", "#00000000", 0, 0, 0, 1.0f))
        defaultTheme.menuElements.add(MenuElement("btn_playlist", "button", "main_scroll_list", "", 0, 8, -1, 48, "Playlists", "Playlists", ">", "", "", "", "", "playlist.png", "OPEN_PLAYLISTS", "top|left", -1, 4, 22, -1, "bottom", "left", "#00000000", 0, 0, 0, 1.0f))
        defaultTheme.menuElements.add(MenuElement("btn_radio", "button", "main_scroll_list", "", 0, 8, -1, 48, "Radio", "Radio", ">", "", "", "", "", "radio_circle.png", "OPEN_RADIO", "top|left", -1, 5, 22, -1, "bottom", "left", "#00000000", 0, 0, 0, 1.0f))
        defaultTheme.menuElements.add(MenuElement("btn_audiobook", "button", "main_scroll_list", "", 0, 8, -1, 48, "Audiobooks", "Audiobooks", ">", "", "", "", "", "book.png", "OPEN_AUDIOBOOKS", "top|left", -1, 6, 22, -1, "bottom", "left", "#00000000", 0, 0, 0, 1.0f))
        defaultTheme.menuElements.add(MenuElement("btn_podcast", "button", "main_scroll_list", "", 0, 8, -1, 48, "Podcasts", "Podcasts", ">", "", "", "", "", "icon_podcast.png", "OPEN_PODCASTS", "top|left", -1, 7, 22, -1, "bottom", "left", "#00000000", 0, 0, 0, 1.0f))
        defaultTheme.menuElements.add(MenuElement("btn_bt", "button", "main_scroll_list", "", 0, 8, -1, 48, "Bluetooth", "Bluetooth", ">", "", "", "", "", "bluetooth_circle.png", "OPEN_BLUETOOTH", "top|left", -1, 8, 22, -1, "bottom", "left", "#00000000", 0, 0, 0, 1.0f))
        defaultTheme.menuElements.add(MenuElement("btn_wifi", "button", "main_scroll_list", "", 0, 8, -1, 48, "Wi-Fi", "Wi-Fi", ">", "", "", "", "", "wifi_circle.png", "OPEN_WIFI", "top|left", -1, 9, 22, -1, "bottom", "left", "#00000000", 0, 0, 0, 1.0f))
        defaultTheme.menuElements.add(MenuElement("btn_set", "button", "main_scroll_list", "", 0, 8, -1, 48, "Settings", "Settings", ">", "", "", "", "", "setting_circle.png", "OPEN_SETTINGS", "top|left", -1, 10, 22, -1, "bottom", "left", "#00000000", 0, 0, 0, 1.0f))
        defaultTheme.menuElements.add(MenuElement("btn_web", "button", "main_scroll_list", "", 0, 8, -1, 48, "PC Upload", "PC Upload", ">", "", "", "", "", "file_sync.png", "OPEN_WEBSERVER", "top|left", -1, 11, 22, -1, "bottom", "left", "#00000000", 0, 0, 0, 1.0f))
        defaultTheme.menuElements.add(MenuElement("widget_clock", "widget_clock", "", "btn_now", 284, 18, 150, 81, "", "", "", "", "", "", "", "", "NONE", "top|left", 0, -1, 16, -1, "bottom", "left", "", 8, 0, 0, 1.0f))
        defaultTheme.menuElements.add(MenuElement("widget_album", "widget_album", "", "btn_now", 254, 13, 211, 212, "", "", "", "", "", "", "", "", "NONE", "bottom|left", -1, -1, 16, 12, "bottom", "center", "", 0, 0, 0, 1.0f))
        defaultTheme.menuElements.add(MenuElement("preview", "widget_focus_image", "", "", 290, 90, 140, 140, "", "", "", "", "", "", "", "", "NONE", "top|left", -1, -1, 16, -1, "bottom", "center", "", 0, 0, 0, 1.0f))

        availableThemes.add(defaultTheme)

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
                                    try {
                                        parsedFont = Typeface.createFromFile(fontFile)
                                    } catch (_: Exception) {
                                    }
                                }
                            }

                            var parsedFontBold: Typeface? = null
                            if (json.has("fontBold")) {
                                val fontBoldFile = File(subFolder, json.getString("fontBold"))
                                if (fontBoldFile.exists() && fontBoldFile.isFile) {
                                    try {
                                        parsedFontBold = Typeface.createFromFile(fontBoldFile)
                                    } catch (_: Exception) {
                                    }
                                }
                            }

                            val parsedBgImage = json.optString("bg_image", "")

                            val theme = ThemeData(
                                subFolder.absolutePath,
                                json.optString("name", subFolder.name),
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
                            availableThemes.add(theme)
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                }
            }
        }
    }

    @JvmStatic
    fun setThemeIndex(index: Int) {
        currentThemeIndex = if (index in 0 until availableThemes.size) index else 0
    }

    @JvmStatic
    fun findThemeIndexByName(name: String?): Int {
        if (name == null) return -1
        for (i in availableThemes.indices) {
            if (availableThemes[i].name == name) return i
        }
        return -1
    }

    @JvmStatic
    fun getCurrentThemeIndex(): Int = currentThemeIndex

    @JvmStatic
    fun getCurrentTheme(): ThemeData {
        if (availableThemes.isEmpty() || currentThemeIndex < 0 || currentThemeIndex >= availableThemes.size) {
            return DEFAULT_FALLBACK_THEME
        }
        return availableThemes[currentThemeIndex]
    }

    @JvmStatic
    fun getCustomFont(): Typeface {
        if (availableThemes.isEmpty()) return Typeface.DEFAULT
        return availableThemes[currentThemeIndex].customFont
    }

    @JvmStatic
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

    @JvmStatic
    fun isClassicTheme(): Boolean {
        val name = getCurrentTheme().name
        return "iPod Classic" == name || "iPod Classic Dark" == name
    }

    @JvmStatic
    fun isClassicLightTheme(): Boolean = "iPod Classic" == getCurrentTheme().name

    @JvmStatic
    fun getListTextSize(): Float = if (isClassicTheme()) 21f else 23f

    @JvmStatic
    fun getTextColorPrimary(): Int = getCurrentTheme().textPrimary

    @JvmStatic
    fun getTextColorSecondary(): Int = getCurrentTheme().textSecondary

    @JvmStatic
    fun getOverlayBackgroundColor(): Int = getCurrentTheme().bgOverlay

    @JvmStatic
    fun getListButtonNormalBg(): Int = getCurrentTheme().btnNormal

    @JvmStatic
    fun getListButtonFocusedBg(): Int = getCurrentTheme().btnFocused

    @JvmStatic
    fun getListButtonFocusedTextColor(): Int = getCurrentTheme().btnFocusedText

    @JvmStatic
    fun getButtonRadius(): Int = getCurrentTheme().buttonRadius
}
