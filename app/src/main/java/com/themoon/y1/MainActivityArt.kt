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
        val savedBgPath = prefs.getString(Prefs.BACKGROUND.key, null)
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
    // Not connected: the same icon, empty.
    setStatusBarIcon(ivStatusWifi ?: return, com.themoon.y1.views.StatusIcons.Icon.WIFI, isConnected, 6)
    // While a speaker plays, the Play On icon shows in its place (it needs the Wi-Fi)
    if (com.themoon.y1.cast.CastManager.isActive) ivStatusWifi?.visibility = android.view.View.GONE
}

/** The Wi-Fi icon while the Wi-Fi is on: full when connected to a network, else empty. */
internal fun MainActivity.refreshStatusBarWifi() {
    val wm = applicationContext.getSystemService(android.content.Context.WIFI_SERVICE) as? android.net.wifi.WifiManager ?: return
    if (!wm.isWifiEnabled) return
    ivStatusWifi?.visibility = android.view.View.VISIBLE
    updateStatusBarWifi(wm.connectionInfo?.let { it.networkId != -1 } == true)
}

/**
 * Play On: while a speaker plays, its icon in the place of the Wi-Fi icon and the speaker name under "1 of N" on
 * Now Playing. When it stops, the Wi-Fi icon shows again. Also refreshes the Wi-Fi icon.
 */
internal fun MainActivity.updateStatusBarPlayOn() {
    val device = com.themoon.y1.cast.CastManager.device
    ivStatusPlayOn?.let { iv ->
        if (device != null) {
            setStatusBarIcon(iv, com.themoon.y1.views.StatusIcons.Icon.PLAY_ON, true, 6)
            iv.visibility = android.view.View.VISIBLE
        } else {
            iv.visibility = android.view.View.GONE
        }
    }
    refreshStatusBarWifi()
    findViewById<android.widget.TextView>(R.id.tv_player_play_on)?.let { tv ->
        if (device == null) {
            tv.visibility = android.view.View.GONE
            return@let
        }
        tv.text = device.name
        tv.visibility = android.view.View.VISIBLE
        // After the Classic layout has set the font of the line
        tv.post { setPlayOnLineIcon(tv) }
    }
}

/**
 * The icon of the Play On line: as tall as the cap height and a half, its centre on the centre of the cap height
 * (the rule of the Bluetooth list and the low-battery banner). TextView centres a left drawable on the line box
 * (ascent to descent), so the bounds move it by the difference.
 */
private fun MainActivity.setPlayOnLineIcon(tv: android.widget.TextView) {
    val caps = android.graphics.Rect()
    tv.paint.getTextBounds("H", 0, 1, caps)
    val capHeight = caps.height().toFloat()
    val fm = tv.paint.fontMetrics
    val size = Math.round(capHeight * 1.5f)
    // Centres, from the baseline (y down): the cap height at -capHeight / 2, the line box at (ascent + descent) / 2
    val shift = Math.round(-capHeight / 2f - (fm.ascent + fm.descent) / 2f)
    val icon = resources.getDrawable(R.drawable.ic_play_on).mutate()
    icon.setBounds(0, shift, size, size + shift)
    icon.setColorFilter(tv.currentTextColor, android.graphics.PorterDuff.Mode.SRC_IN)
    tv.setCompoundDrawables(icon, null, null, null)
    // The icon goes 1-2 px above the line box: the text column must not cut it
    (tv.parent as? android.view.ViewGroup)?.clipChildren = false
}

/** The rune; with an audio device connected, the rune with a dot on each side. */
internal fun MainActivity.updateStatusBarBluetooth() {
    setStatusBarIcon(
        ivStatusBluetooth ?: return, com.themoon.y1.views.StatusIcons.Icon.BLUETOOTH, true, 8,
        com.themoon.y1.managers.BluetoothAudioState.shared.isConnected
    )
}

/** A2DP connection broadcast for [device]: the icon gets or loses its dots (MainActivity then draws the list again). */
internal fun MainActivity.onStatusBarA2dpState(device: android.bluetooth.BluetoothDevice?, profileState: Int) {
    if (com.themoon.y1.managers.BluetoothAudioState.shared.onA2dpState(device?.address, profileState)) updateStatusBarBluetooth()
}

/** Bluetooth adapter broadcast: off forgets the devices; on asks the A2DP proxy for the connected devices. */
internal fun MainActivity.onStatusBarBluetoothAdapterState(adapterState: Int) {
    val on = adapterState == android.bluetooth.BluetoothAdapter.STATE_ON
    if (com.themoon.y1.managers.BluetoothAudioState.shared.onAdapterState(on)) updateStatusBarBluetooth()
    if (on) queryStatusBarBluetooth()
}

private fun MainActivity.isBluetoothListShown(): Boolean =
    com.themoon.y1.navigation.NavigationManager.getInstance().currentState is com.themoon.y1.navigation.ScreenState.Bluetooth ||
        currentScreenState == MainActivity.STATE_BLUETOOTH

/**
 * Asks the A2DP proxy once for the connected audio devices, then closes the proxy: at start and when Bluetooth
 * comes on. After that, the A2DP broadcasts keep the state ([onStatusBarA2dpState]).
 */
internal fun MainActivity.queryStatusBarBluetooth() {
    val adapter = android.bluetooth.BluetoothAdapter.getDefaultAdapter() ?: return
    if (!adapter.isEnabled) return
    if (!com.themoon.y1.managers.BluetoothAudioState.shared.adapterOn) com.themoon.y1.managers.BluetoothAudioState.shared.onAdapterState(true)
    try {
        adapter.getProfileProxy(applicationContext, object : android.bluetooth.BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: android.bluetooth.BluetoothProfile) {
                val devices = try {
                    proxy.connectedDevices.mapNotNull { it?.address }
                } catch (_: Exception) {
                    emptyList()
                }
                try {
                    adapter.closeProfileProxy(profile, proxy)
                } catch (_: Exception) {}
                runOnUiThread {
                    val audio = com.themoon.y1.managers.BluetoothAudioState.shared
                    val before = audio.connectedAddresses
                    if (audio.onConnectedDevices(devices)) updateStatusBarBluetooth()
                    // The Bluetooth list ticks the connected devices: draw it again if it is open.
                    if (audio.connectedAddresses != before && isBluetoothListShown()) bluetoothController.startBluetoothScan()
                }
            }

            override fun onServiceDisconnected(profile: Int) {}
        }, android.bluetooth.BluetoothProfile.A2DP)
    } catch (_: Exception) {}
}

internal fun MainActivity.updateStatusBarHeadphone() {
    setStatusBarIcon(ivStatusHeadphone ?: return, com.themoon.y1.views.StatusIcons.Icon.HEADPHONES, true, 6)
}

/**
 * Draws a status bar icon at its pixel size. In the Classic themes (iPod Classic layout), the icon has the box of all
 * status items (the cap height of the text, 1 px more at the top for these round shapes) and the same gap;
 * in the other themes 16 dp with the margin of activity_main.xml.
 */
private fun MainActivity.setStatusBarIcon(
    iv: android.widget.ImageView,
    icon: com.themoon.y1.views.StatusIcons.Icon,
    active: Boolean,
    rightMarginDp: Int,
    connected: Boolean = false
) {
    val d = resources.displayMetrics.density
    val ipod = com.themoon.y1.views.ClassyLayout.isActive
    val heightPx = if (ipod) {
        com.themoon.y1.views.ClassyLayout.px(com.themoon.y1.views.ClassyMetrics.STATUS_ROUND_ICON_HEIGHT, d)
    } else {
        com.themoon.y1.views.ClassyLayout.px(16, d)
    }
    // The gradient spans the status item box (the cap height), as in the play/pause icon.
    val boxPx = if (ipod) {
        com.themoon.y1.views.ClassyLayout.px(com.themoon.y1.views.ClassyMetrics.STATUS_ICON_HEIGHT, d)
    } else {
        heightPx
    }
    val bmp = com.themoon.y1.views.StatusIcons.render(icon, heightPx, active, isLightStatusBar(), connected, boxPx)
    iv.colorFilter = null
    iv.scaleType = android.widget.ImageView.ScaleType.CENTER
    iv.setImageBitmap(bmp)

    val lp = iv.layoutParams as? android.widget.LinearLayout.LayoutParams ?: return
    val bottom = if (ipod) com.themoon.y1.views.ClassyLayout.px(com.themoon.y1.views.ClassyMetrics.STATUS_BAR_RULE, d) else 0
    if (ipod) {
        // The optical bearings of the new bitmap; updateStatusItemGaps() sets the right margin.
        val sideDots = icon == com.themoon.y1.views.StatusIcons.Icon.BLUETOOTH && connected
        val pixels = if (sideDots) IntArray(0) else IntArray(bmp.width * bmp.height)
        if (!sideDots) bmp.getPixels(pixels, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        val box = com.themoon.y1.views.StatusIcons.boxInBitmap(icon, heightPx, boxPx)
        statusBearings[iv] = com.themoon.y1.views.StatusSpacing.iconBearings(
            pixels, bmp.width, box.first.toInt(), box.second.toInt(), sideDots
        )
    } else {
        statusBearings.remove(iv)
        lp.rightMargin = com.themoon.y1.views.ClassyLayout.px(rightMarginDp, d)
    }
    if (lp.width != bmp.width || lp.height != bmp.height || lp.bottomMargin != bottom ||
        lp.gravity != android.view.Gravity.CENTER_VERTICAL
    ) {
        lp.width = bmp.width
        lp.height = bmp.height
        lp.bottomMargin = bottom
        lp.gravity = android.view.Gravity.CENTER_VERTICAL
    }
    iv.layoutParams = lp
    if (ipod) updateStatusItemGaps()
}

/** Optical bearings (left, right) of the status icons, from their bitmaps (StatusSpacing). */
private val statusBearings = java.util.WeakHashMap<android.view.View, FloatArray>()
private val statusGapListeners = java.util.WeakHashMap<android.view.View, Boolean>()

/**
 * Classic themes (Classy layout): sets the gap after each visible status item (icons, play/pause) to the next visible one, from
 * their optical bearings (StatusSpacing), so the spaces look equal. Items without bearings (play/pause, battery) are
 * flat: 8 px. The gap from the battery to the clock is the text's own. A layout listener on the group runs it again
 * when an item shows or hides; it changes the margins only when they are different, so it stops after one pass.
 */
internal fun MainActivity.updateStatusItemGaps() {
    if (!com.themoon.y1.views.ClassyLayout.isActive) return
    val battery = batteryIconView ?: return
    val group = battery.parent as? android.view.ViewGroup ?: return
    if (statusGapListeners[group] == null) {
        statusGapListeners[group] = true
        group.viewTreeObserver.addOnGlobalLayoutListener { updateStatusItemGaps() }
    }
    val d = resources.displayMetrics.density
    val end = group.indexOfChild(battery)
    var i = 0
    while (i < end) {
        val v = group.getChildAt(i)
        if (v.visibility == android.view.View.GONE || v is android.widget.TextView) {
            i++
            continue
        }
        var j = i + 1
        while (j < end) {
            val n = group.getChildAt(j)
            if (n.visibility != android.view.View.GONE && n !is android.widget.TextView) break
            j++
        }
        val next = if (j < end) group.getChildAt(j) else battery
        val right = statusBearings[v]?.get(1) ?: 0f
        val left = statusBearings[next]?.get(0) ?: 0f
        val gap = com.themoon.y1.views.ClassyLayout.px(com.themoon.y1.views.StatusSpacing.gap(right / d, left / d), d)
        val lp = v.layoutParams as? android.view.ViewGroup.MarginLayoutParams
        if (lp != null && lp.rightMargin != gap) {
            lp.rightMargin = gap
            v.layoutParams = lp
        }
        i = j
    }
}

/**
 * Puts the text of the right status group (the clock of "Time in Title Bar") after the battery, at the right
 * edge, as in the macOS menu bar. The order is then icons, play/pause, battery, clock. The gap before the
 * moved text is its old right margin (the gap to the next item); its right margin is 0.
 * Only the first call moves views; later calls find no text before the battery.
 */
private fun MainActivity.moveStatusTextLast() {
    val battery = batteryIconView ?: return
    val group = battery.parent as? android.view.ViewGroup ?: return
    var i = 0
    while (i < group.indexOfChild(battery)) {
        val v = group.getChildAt(i)
        if (v !is android.widget.TextView) {
            i++
            continue
        }
        group.removeViewAt(i)
        group.addView(v)
        val lp = v.layoutParams as? android.view.ViewGroup.MarginLayoutParams ?: continue
        val batteryRight = (battery.layoutParams as? android.view.ViewGroup.MarginLayoutParams)?.rightMargin ?: 0
        lp.leftMargin = (lp.rightMargin - batteryRight).coerceAtLeast(0)
        lp.rightMargin = 0
        v.layoutParams = lp
    }
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
        tvStatusTime?.setTextColor(primary)
        tvStatusTime?.setShadowLayer(0f, 0f, 0f, 0)
        tvStatusTime?.typeface = ThemeManager.getCustomFontBold()
        tvStatusBattery?.setTextColor(primary)
        tvStatusBattery?.setShadowLayer(0f, 0f, 0f, 0)
        batteryIconView?.setColor(primary)
        batteryIconView?.setShowPercentage(isShowBatteryPercentage)
        moveStatusTextLast()

        updateStatusBarBluetooth()
        updateStatusBarHeadphone()
        // Also the Wi-Fi icon
        updateStatusBarPlayOn()

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

        com.themoon.y1.views.ClassyLayout.apply(this)
    } catch (_: Exception) {}
}

