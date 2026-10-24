package com.themoon.y1

import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView

internal fun MainActivity.initViewBindings() {
    layoutMainMenu = findViewById(R.id.layout_main_menu)
    layoutBrowserMode = findViewById(R.id.layout_browser_mode)
    layoutPlayerMode = findViewById(R.id.layout_player_mode)
    layoutSettingsMode = findViewById(R.id.layout_settings_mode)
    layoutBluetoothMode = findViewById(R.id.layout_bluetooth_mode)
    layoutWifiMode = findViewById(R.id.layout_wifi_mode)
    layoutWifiKeyboard = findViewById(R.id.layout_wifi_keyboard)
    layoutBrightnessMode = findViewById(R.id.layout_brightness_mode)
    layoutStorageMode = findViewById(R.id.layout_storage_mode)
    layoutWebServerMode = findViewById(R.id.layout_webserver_mode)
    layoutMusicQuizMode = findViewById(R.id.layout_music_quiz_mode)
    layoutVideosMode = findViewById(R.id.layout_videos_mode)
    layoutVideoPlayerMode = findViewById(R.id.layout_video_player_mode)
    layoutVolumeOverlay = findViewById(R.id.layout_volume_overlay)

    val rootContent = findViewById<ViewGroup>(android.R.id.content)
    val d = resources.displayMetrics.density
    tvFastScrollLetter = TextView(this).apply {
        textSize = 50f
        gravity = Gravity.CENTER
        visibility = View.GONE
        // The letter box of speed scrolling, in the middle of the screen (see LetterJump).
        val lp = FrameLayout.LayoutParams((96 * d).toInt(), (96 * d).toInt(), Gravity.CENTER)
        rootContent.addView(this, lp)
    }

    volumeProgress = findViewById(R.id.volume_progress)
    volumeProgress?.max = MainActivity.VIRTUAL_VOLUME_STEPS

    findViewById<View>(R.id.tv_overlay_volume_down)?.setOnClickListener {
        adjustVolume(false)
        clickFeedback()
    }
    findViewById<View>(R.id.tv_overlay_volume_up)?.setOnClickListener {
        adjustVolume(true)
        clickFeedback()
    }

    ivMainBg = findViewById(R.id.iv_main_bg)
    ivAlbumArt = findViewById(R.id.iv_album_art)
    ivAlbumReflection = findViewById(R.id.iv_album_reflection)
    ivPauseOverlay = findViewById(R.id.iv_pause_overlay)
    tvPlayerTitle = findViewById(R.id.tv_player_title)
    tvPlayerArtist = findViewById(R.id.tv_player_artist)
    tvPlayerAlbum = findViewById(R.id.tv_player_album)
    tvPlayerTrackCount = findViewById(R.id.tv_player_track_count)
    tvPlayerTimeCurrent = findViewById(R.id.tv_player_time_current)
    tvPlayerTimeTotal = findViewById(R.id.tv_player_time_total)
    playerProgress = findViewById(R.id.player_progress)

    layoutLoadingOverlay = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setBackgroundColor(0xDD000000.toInt())
        isClickable = true
        isFocusable = true
        visibility = View.GONE

        val density = resources.displayMetrics.density
        val pb = ProgressBar(this@initViewBindings, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
        }
        pbLoadingProgress = pb
        val pbLp = LinearLayout.LayoutParams(
            (250 * density).toInt(),
            (20 * density).toInt()
        )
        addView(pb, pbLp)

        val tv = TextView(this@initViewBindings).apply {
            setTextColor(Color.WHITE)
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(0, (20 * density).toInt(), 0, 0)
        }
        tvLoadingProgress = tv
        addView(tv, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
    }
    rootContent.addView(layoutLoadingOverlay, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

    tvStatusBarTitle = findViewById(R.id.tv_status_clock)
    tvStatusBarTitle?.setShadowLayer(0f, 0f, 0f, 0)
    tvStatusBarTitle?.typeface = ThemeManager.getCustomFontBold()
    ivStatusWifi = findViewById(R.id.iv_status_wifi)
    ivStatusBluetooth = findViewById(R.id.iv_status_bluetooth)
    ivStatusHeadphone = findViewById(R.id.iv_status_headphone)
    tvStatusTime = findViewById(R.id.tv_status_time)
    tvStatusTime?.setShadowLayer(0f, 0f, 0f, 0)
    tvStatusTime?.typeface = ThemeManager.getCustomFontBold()
    tvStatusBattery = findViewById(R.id.tv_status_battery)
    tvStatusBattery?.setShadowLayer(0f, 0f, 0f, 0)

    val statusParent = tvStatusBattery?.parent as? ViewGroup
    if (statusParent != null && tvStatusBattery != null) {
        tvStatusBattery?.visibility = View.GONE
        val bIdx = statusParent.indexOfChild(tvStatusBattery)
        val biv = com.themoon.y1.views.BatteryIconView(this).apply {
            setShowPercentage(isShowBatteryPercentage)
            setColor(ThemeManager.getTextColorPrimary())
        }
        batteryIconView = biv
        val d = resources.displayMetrics.density
        val blp = LinearLayout.LayoutParams((30 * d).toInt(), (16 * d).toInt()).apply {
            gravity = Gravity.CENTER_VERTICAL
            setMargins((8 * d).toInt(), 0, (4 * d).toInt(), 0)
        }
        statusParent.addView(biv, bIdx, blp)
    }
}

internal fun MainActivity.applyRootSystemSettings() {
    try {
        val pkg = packageName
        val combinedCmd = "setprop persist.bluetooth.avrcpversion 1.6 && " +
                "settings put global wifi_sleep_policy 2 && " +
                "pm grant $pkg android.permission.DUMP && " +
                "pm grant $pkg android.permission.BATTERY_STATS && " +
                "pm grant $pkg android.permission.PACKAGE_USAGE_STATS && " +
                "pm grant $pkg android.permission.WRITE_SECURE_SETTINGS"
        Runtime.getRuntime().exec(arrayOf("su", "-c", combinedCmd))
    } catch (_: Exception) {}
}

internal fun MainActivity.loadPreferences() {
    isShuffleMode = prefs.getBoolean("shuffle", false)
    repeatMode = prefs.getInt("repeat_mode", 0)
    isSoundEffectEnabled = prefs.getBoolean("sound", true)
    isVibrationEnabled = prefs.getBoolean("vibrate", true)
    vibrationStrengthLevel = prefs.getInt("vibrate_strength", 1)
    isScreenOffControlEnabled = prefs.getBoolean("screen_off_control", false)
    isUsbPromptEnabled = prefs.getBoolean("usb_storage_prompt", true)
    isAutoFetchEnabled = prefs.getBoolean("auto_fetch", true)
    is24HourFormat = prefs.getBoolean(ClockSettings.KEY_24_HOUR_FORMAT, ClockSettings.DEFAULT_24_HOUR_FORMAT)
    isTimeInTitleBar = prefs.getBoolean(ClockSettings.KEY_TIME_IN_TITLE, ClockSettings.DEFAULT_TIME_IN_TITLE)
    isShowBatteryPercentage = prefs.getBoolean("show_battery_percentage", false)
    isSoundCheckEnabled = prefs.getBoolean("sound_check_enabled", false)
    isAlbumTiltEnabled = com.themoon.y1.controllers.player.AlbumTiltSetting.isEnabled(prefs)
    com.themoon.y1.models.AlbumKey.byArtist = prefs.getBoolean(com.themoon.y1.models.AlbumKey.PREF_BY_ARTIST, false)
    volumeLimitMax = prefs.getInt("volume_limit_max", -1)
    isSoftwareEqEnabled = prefs.getBoolean("software_eq_enabled", false)
    currentTimeoutIndex = prefs.getInt("timeout_idx", 1)

    eqPresetNames.clear()
    eqPresetNames.addAll(listOf("Off", "Acoustic", "Bass Booster", "Bass Reducer", "Classical", "Electronic", "Hip Hop", "Jazz", "Latin", "Loudness", "Pop", "R&B", "Rock", "Vocal Booster"))

    favoritePaths.clear()
    favoritePaths.addAll(prefs.getStringSet("favorite_songs_set", emptySet()) ?: emptySet())

    blacklist.clear()
    blacklist.addAll(prefs.getStringSet("blacklisted_files", emptySet()) ?: emptySet())
}
