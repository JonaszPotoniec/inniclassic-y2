package com.themoon.y1

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import com.themoon.y1.managers.MediaLibraryManager
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream

internal fun MainActivity.createStatusBarBackground(): GradientDrawable {
    return ThemeManager.createStatusBarBackground()
}

internal fun MainActivity.updateMainMenuBackgroundInternal() {
    val bgView = ivMainBg ?: return
    try {
        val savedBgPath = prefs.getString("bg_path", null)
        val currentTheme = ThemeManager.getCurrentTheme()

        if ("THEME_DEFAULT" == savedBgPath) {
            var themeBg: Bitmap? = null
            if (!currentTheme.bgImage.isNullOrEmpty()) {
                val bgFile = File(currentTheme.folderPath, currentTheme.bgImage)
                if (bgFile.exists()) {
                    themeBg = BitmapFactory.decodeFile(bgFile.absolutePath)
                }
            }
            if (themeBg == null) themeBg = ThemeManager.getCustomIcon("background.png", this, 0)
            if (themeBg == null) themeBg = ThemeManager.getCustomIcon("bg.png", this, 0)
            if (themeBg != null) {
                bgView.setImageBitmap(themeBg)
                return
            }
        } else if (!savedBgPath.isNullOrEmpty()) {
            val bgFile = File(savedBgPath)
            if (bgFile.exists()) {
                val opts = BitmapFactory.Options().apply { inSampleSize = 2 }
                val customBg = BitmapFactory.decodeFile(savedBgPath, opts)
                if (customBg != null) {
                    bgView.setImageBitmap(customBg)
                    return
                }
            }
        }

        val sourceBitmap = if (lastAlbumArtBytes != null && lastAlbumArtBytes!!.isNotEmpty()) {
            val opts = BitmapFactory.Options().apply { inSampleSize = 4 }
            BitmapFactory.decodeByteArray(lastAlbumArtBytes, 0, lastAlbumArtBytes!!.size, opts)
        } else {
            val opts = BitmapFactory.Options().apply { inSampleSize = 2 }
            BitmapFactory.decodeResource(resources, R.drawable.default_back, opts)
        }

        if (sourceBitmap != null) {
            val blurred = applyGaussianBlur(sourceBitmap)
            bgView.setImageBitmap(blurred)
            if (sourceBitmap != blurred) sourceBitmap.recycle()
        } else {
            bgView.setImageResource(R.drawable.default_back)
        }
    } catch (_: Throwable) {
        bgView.setImageResource(R.drawable.default_back)
    }
}

internal fun MainActivity.applyCachedCoverArtInternal(imagePath: String) {
    try {
        val opts = BitmapFactory.Options().apply { inSampleSize = 2 }
        val bmp = BitmapFactory.decodeFile(imagePath, opts) ?: return
        ivAlbumArt.setImageBitmap(bmp)

        val file = File(imagePath)
        val bytes = ByteArray(file.length().toInt())
        val buf = BufferedInputStream(FileInputStream(file))
        buf.read(bytes, 0, bytes.size)
        buf.close()

        lastAlbumArtBytes = bytes
        updateMainMenuBackgroundInternal()
        refreshNowPlayingPreview()
        sendBluetoothMetaToCar()
    } catch (_: Exception) {}
}

internal fun MainActivity.fetchRandomIdleCoverAsyncInternal() {
    Thread({
        if (MainActivity.customLibrary.isNotEmpty()) {
            val randomSong = MainActivity.customLibrary.randomOrNull()
            if (randomSong != null) {
                val cover = MediaLibraryManager.getInstance().findFolderCover(randomSong.file.parentFile)
                if (cover != null && cover.exists()) {
                    val bmp = BitmapFactory.decodeFile(cover.absolutePath)
                    if (bmp != null) {
                        runOnUiThread {
                            homeWidgetsController?.idleRandomCoverBitmap = bmp
                            homeWidgetsController?.refreshWidgets()
                        }
                    }
                }
            }
        }
    }, "y1-random-cover").start()
}

internal fun MainActivity.isLightStatusBar(): Boolean {
    if (ThemeManager.isClassicLightTheme()) return true
    val primaryText = ThemeManager.getTextColorPrimary()
    val r = (primaryText shr 16) and 0xFF
    val g = (primaryText shr 8) and 0xFF
    val b = primaryText and 0xFF
    val luminance = 0.299 * r + 0.587 * g + 0.114 * b
    return luminance < 128
}

internal fun MainActivity.updateStatusBarWifi(isConnected: Boolean) {
    val iv = ivStatusWifi ?: return
    iv.colorFilter = null
    val density = resources.displayMetrics.density
    val iconSizePx = (18 * density).toInt()
    val light = isLightStatusBar()
    val color = if (isConnected) {
        if (light) 0xFF006425.toInt() else 0xFF34C759.toInt()
    } else {
        if (light) 0xFF9E4800.toInt() else 0xFFFFB300.toInt()
    }
    iv.setImageBitmap(com.themoon.y1.views.TablerIcons.render(com.themoon.y1.views.TablerIcons.WIFI, iconSizePx, color))
}

internal fun MainActivity.updateStatusBarBluetooth() {
    val iv = ivStatusBluetooth ?: return
    val density = resources.displayMetrics.density
    val iconSizePx = (16 * density).toInt()
    val color = ThemeManager.getTextColorPrimary()
    iv.setImageBitmap(com.themoon.y1.views.TablerIcons.render(com.themoon.y1.views.TablerIcons.BLUETOOTH, iconSizePx, color))
}

internal fun MainActivity.updateStatusBarHeadphone() {
    val iv = ivStatusHeadphone ?: return
    val density = resources.displayMetrics.density
    val iconSizePx = (16 * density).toInt()
    val color = ThemeManager.getTextColorPrimary()
    iv.setImageBitmap(com.themoon.y1.views.TablerIcons.render(com.themoon.y1.views.TablerIcons.HEADPHONES, iconSizePx, color))
}

internal fun MainActivity.applyThemeInternal() {
    try {
        val overlayColor = ThemeManager.getOverlayBackgroundColor()
        layoutMainMenu.setBackgroundColor(0x00000000)
        layoutBrowserMode.setBackgroundColor(overlayColor)
        layoutSettingsMode.setBackgroundColor(overlayColor)
        layoutBluetoothMode.setBackgroundColor(overlayColor)
        layoutWifiMode.setBackgroundColor(overlayColor)
        layoutBrightnessMode.setBackgroundColor(overlayColor)
        layoutStorageMode.setBackgroundColor(overlayColor)
        layoutWebServerMode.setBackgroundColor(overlayColor)
        layoutVideosMode.setBackgroundColor(overlayColor)

        if (ivMainBg != null) {
            val softTint = (overlayColor and 0x00FFFFFF) or 0x66000000
            ivMainBg?.setColorFilter(softTint, android.graphics.PorterDuff.Mode.SRC_ATOP)
        }

        val statusBar = findViewById<android.view.View>(R.id.layout_status_bar)
        if (statusBar != null) {
            if (currentScreenState == MainActivity.STATE_COVERFLOW || currentScreenState == MainActivity.STATE_VIDEO_PLAYER) {
                statusBar.setBackgroundColor(0x00000000)
            } else {
                statusBar.background = createStatusBarBackground()
            }
            updateStatusBarWidth()
        }

        val primary = ThemeManager.getTextColorPrimary()
        tvStatusBarTitle?.setTextColor(primary)
        tvStatusBarTitle?.setShadowLayer(0f, 0f, 0f, 0)
        tvStatusBarTitle?.typeface = ThemeManager.getCustomFontBold()
        tvStatusBattery?.setTextColor(primary)
        tvStatusBattery?.setShadowLayer(0f, 0f, 0f, 0)
        batteryIconView?.setColor(primary)
        batteryIconView?.setShowPercentage(isShowBatteryPercentage)

        updateStatusBarBluetooth()
        updateStatusBarHeadphone()

        val wm = applicationContext.getSystemService(android.content.Context.WIFI_SERVICE) as? android.net.wifi.WifiManager
        if (wm != null && wm.isWifiEnabled) {
            val info = wm.connectionInfo
            val isConnected = info != null && info.networkId != -1
            updateStatusBarWifi(isConnected)
        } else {
            updateStatusBarWifi(false)
        }

        try {
            (layoutSettingsMode as? android.view.ViewGroup)?.let { vg ->
                (vg.getChildAt(0) as? android.widget.TextView)?.apply {
                    text = t("Settings")
                    setTextColor(primary)
                }
            }
            (layoutBluetoothMode as? android.view.ViewGroup)?.let { vg ->
                (vg.getChildAt(0) as? android.widget.TextView)?.apply {
                    text = t("Bluetooth")
                    setTextColor(primary)
                }
            }
            (layoutWifiMode as? android.view.ViewGroup)?.let { vg ->
                (vg.getChildAt(0) as? android.widget.TextView)?.apply {
                    text = t("Wi-Fi")
                    setTextColor(primary)
                }
            }
            (layoutBrightnessMode as? android.view.ViewGroup)?.let { vg ->
                (vg.getChildAt(0) as? android.widget.TextView)?.apply {
                    text = t("Display Brightness")
                    setTextColor(primary)
                }
            }
            findViewById<android.widget.TextView>(R.id.tv_storage_header)?.apply {
                text = t("About")
                setTextColor(primary)
            }
            (layoutWebServerMode as? android.view.ViewGroup)?.let { vg ->
                (vg.getChildAt(0) as? android.widget.TextView)?.apply {
                    text = t("Wireless PC Upload")
                    setTextColor(primary)
                }
            }
            (layoutVideosMode as? android.view.ViewGroup)?.let { vg ->
                (vg.getChildAt(0) as? android.widget.TextView)?.apply {
                    text = t("Videos")
                    setTextColor(primary)
                }
            }
        } catch (_: Exception) {}

        val btnScanBt = findViewById<android.widget.Button>(R.id.btn_scan_bt)
        if (btnScanBt != null) {
            if (btnScanBt.hasFocus()) {
                btnScanBt.background = createFocusedButtonBackground()
                btnScanBt.setTextColor(ThemeManager.getListButtonFocusedTextColor())
            } else {
                btnScanBt.background = createButtonBackground(ThemeManager.getListButtonNormalBg())
                btnScanBt.setTextColor(primary)
            }
            btnScanBt.typeface = ThemeManager.getCustomFontBold()
        }

        val btnScanWifi = findViewById<android.widget.Button>(R.id.btn_scan_wifi)
        if (btnScanWifi != null) {
            if (btnScanWifi.hasFocus()) {
                btnScanWifi.background = createFocusedButtonBackground()
                btnScanWifi.setTextColor(ThemeManager.getListButtonFocusedTextColor())
            } else {
                btnScanWifi.background = createButtonBackground(ThemeManager.getListButtonNormalBg())
                btnScanWifi.setTextColor(primary)
            }
            btnScanWifi.typeface = ThemeManager.getCustomFontBold()
        }

        val btnServerToggle = findViewById<android.widget.Button>(R.id.btn_server_toggle)
        if (btnServerToggle != null) {
            if (btnServerToggle.hasFocus()) {
                btnServerToggle.background = createFocusedButtonBackground()
                btnServerToggle.setTextColor(ThemeManager.getListButtonFocusedTextColor())
                btnServerToggle.typeface = ThemeManager.getCustomFontBold()
            } else {
                btnServerToggle.background = createButtonBackground(ThemeManager.getListButtonNormalBg())
                btnServerToggle.setTextColor(primary)
                btnServerToggle.typeface = ThemeManager.getCustomFont()
            }
        }
    } catch (_: Exception) {}
}

