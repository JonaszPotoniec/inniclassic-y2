package com.themoon.y1.controllers.home

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.media.MediaMetadataRetriever
import android.os.BatteryManager
import android.text.SpannableString
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.util.TypedValue
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import com.themoon.y1.MainActivity
import com.themoon.y1.R
import com.themoon.y1.StoragePaths
import com.themoon.y1.ThemeManager
import com.themoon.y1.controllers.menu.MainMenuController
import com.themoon.y1.managers.MediaLibraryManager
import com.themoon.y1.views.CircularBatteryView
import com.themoon.y1.views.CustomAnalogClockView
import com.themoon.y1.views.WidgetBatteryBarView
import java.io.File
import java.io.FileInputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Controller for home/main menu side pane widgets:
 * - Digital clock with date
 * - Widget battery bar
 * - Circular battery gauge
 * - Custom analog clock
 * - Now playing album art preview & dynamic focus images
 */
class HomeWidgetsController(
    private val context: Context,
    private val mainMenuProvider: () -> MainMenuController?,
    private val ivBrowserCover: ImageView?,
    private val frameBrowserCover: View?,
    private val fetchRandomIdleCoverAsync: () -> Unit,
    private val isAudiobookMode: () -> Boolean = { false }
) {

    private val widgetViewRegistry: Map<View, ThemeManager.MenuElement>
        get() = mainMenuProvider()?.widgetViewRegistry ?: emptyMap<View, ThemeManager.MenuElement>()
    private val tvWidgetClock: TextView? get() = mainMenuProvider()?.tvWidgetClock
    private val widgetBatteryView: WidgetBatteryBarView? get() = mainMenuProvider()?.widgetBatteryView
    private val ivWidgetAlbum: ImageView? get() = mainMenuProvider()?.ivWidgetAlbum
    private val tvWidgetAlbumTitle: TextView? get() = mainMenuProvider()?.tvWidgetAlbumTitle
    private val tvWidgetAlbumArtist: TextView? get() = mainMenuProvider()?.tvWidgetAlbumArtist
    private val ivAnimatedCover: ImageView? get() = mainMenuProvider()?.ivAnimatedCover
    private val customAnalogClockView: CustomAnalogClockView? get() = mainMenuProvider()?.customAnalogClockView
    private val customCircularBatteryView: CircularBatteryView? get() = mainMenuProvider()?.customCircularBatteryView
    private val ivWidgetFocusImage: ImageView? get() = mainMenuProvider()?.ivWidgetFocusImage

    var isWidgetClockOn = true
    var isWidgetBatteryOn = true
    var isWidgetAlbumOn = true
    var isWidgetAnalogClockOn = false
    var isWidgetCircularBatteryOn = false
    var isWidgetFocusImageOn = false

    var is24HourFormat = true
    var currentClockSize = 14

    var lastAlbumArtBytes: ByteArray? = null
        set(value) {
            field = value
            cachedAlbumArtBitmap = if (value != null && value.isNotEmpty()) {
                try {
                    decodeAlbumArtSmart(value)
                } catch (_: Exception) {
                    null
                }
            } else {
                null
            }
        }
    private var cachedAlbumArtBitmap: Bitmap? = null
    var cachedBookCoverBitmap: Bitmap? = null

    fun setAudiobookCoverBytes(bytes: ByteArray?) {
        if (bytes != null && bytes.isNotEmpty()) {
            cachedBookCoverBitmap = try { decodeAlbumArtSmart(bytes) } catch (_: Exception) { null }
        }
    }

    var idleRandomCoverBitmap: Bitmap? = null
    var currentTrackTitle = ""
    var currentTrackArtist = ""

    fun refreshWidgets() {
        // 1. Digital Clock
        val clockTv = tvWidgetClock
        if (clockTv != null) {
            val el = widgetViewRegistry[clockTv]
            if (el?.visibleOnFocus.isNullOrBlank()) {
                clockTv.visibility = if (isWidgetClockOn) View.VISIBLE else View.GONE
            }

            if (clockTv.visibility == View.VISIBLE) {
                val d = context.resources.displayMetrics.density
                clockTv.setTextSize(TypedValue.COMPLEX_UNIT_PX, (currentClockSize * 2.1f) * d)
                clockTv.setLineSpacing(0f, 1.1f)

                val now = Date()
                val widgetTimeFormat = if (is24HourFormat) "HH:mm" else "hh:mm"
                val sdfTime = SimpleDateFormat(widgetTimeFormat, Locale.US)
                val sdfDate = SimpleDateFormat("EEE, MMM dd", Locale.US)
                val timeStr = sdfTime.format(now)
                val dateStr = sdfDate.format(now)
                val fullText = "$timeStr\n$dateStr"

                val spannable = SpannableString(fullText).apply {
                    setSpan(
                        RelativeSizeSpan(0.47f),
                        timeStr.length + 1,
                        fullText.length,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                    setSpan(
                        StyleSpan(Typeface.NORMAL),
                        timeStr.length + 1,
                        fullText.length,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                }
                clockTv.text = spannable
            }
        }

        // 2. Battery Bar
        val batteryBar = widgetBatteryView
        if (batteryBar != null) {
            val el = widgetViewRegistry[batteryBar]
            if (el?.visibleOnFocus.isNullOrBlank()) {
                batteryBar.visibility = if (isWidgetBatteryOn) View.VISIBLE else View.GONE
            }
            if (batteryBar.visibility == View.VISIBLE) {
                batteryBar.setColor(ThemeManager.getTextColorPrimary())
                val batteryIntent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                if (batteryIntent != null) {
                    val level = batteryIntent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                    val scale = batteryIntent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                    val status = batteryIntent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                    val isCharging = (status == BatteryManager.BATTERY_STATUS_CHARGING ||
                            status == BatteryManager.BATTERY_STATUS_FULL)
                    if (level != -1 && scale != -1) {
                        batteryBar.setBatteryLevel(((level / scale.toFloat()) * 100).toInt(), isCharging)
                    }
                }
            }
        }

        // 3. Album Art Preview Widget
        val widgetAlbum = ivWidgetAlbum
        val titleView = tvWidgetAlbumTitle
        val artistView = tvWidgetAlbumArtist
        if (widgetAlbum != null && titleView != null && artistView != null) {
            val parent = widgetAlbum.parent as? View
            val el = widgetViewRegistry[parent]

            if (el?.visibleOnFocus.isNullOrBlank()) {
                parent?.visibility = if (isWidgetAlbumOn) View.VISIBLE else View.GONE
            }

            if (parent != null && parent.visibility == View.VISIBLE) {
                titleView.text = currentTrackTitle
                artistView.text = currentTrackArtist

                val bmp = cachedAlbumArtBitmap ?: lastAlbumArtBytes?.takeIf { it.isNotEmpty() }?.let {
                    try { decodeAlbumArtSmart(it) } catch (_: Exception) { null }
                }
                if (bmp != null) {
                    widgetAlbum.setImageBitmap(bmp)
                } else {
                    widgetAlbum.setImageBitmap(
                        ThemeManager.getCustomIcon("icon_default_album.png", context, R.drawable.default_album)
                    )
                }
            }
        }

        // 4. Drift animated cover
        val animCover = ivAnimatedCover
        if (animCover != null) {
            val bmp = cachedAlbumArtBitmap ?: lastAlbumArtBytes?.takeIf { it.isNotEmpty() }?.let {
                try { decodeAlbumArtSmart(it) } catch (_: Exception) { null }
            }
            if (bmp != null) {
                animCover.setImageBitmap(bmp)
            } else if (idleRandomCoverBitmap != null) {
                animCover.setImageBitmap(idleRandomCoverBitmap)
            } else {
                animCover.setImageResource(R.drawable.default_album)
                fetchRandomIdleCoverAsync()
            }
        }

        // 5. Music / Audiobooks menu split cover
        if (ivBrowserCover != null && frameBrowserCover != null && frameBrowserCover.visibility == View.VISIBLE) {
            if (isAudiobookMode()) {
                val cached = cachedBookCoverBitmap
                if (cached != null) {
                    ivBrowserCover.setImageBitmap(cached)
                } else {
                    ivBrowserCover.setImageResource(R.drawable.default_album)
                    fetchAudiobookCoverAsync { bmp ->
                        ivBrowserCover.setImageBitmap(bmp)
                    }
                }
            } else {
                val bmp = cachedAlbumArtBitmap ?: lastAlbumArtBytes?.takeIf { it.isNotEmpty() }?.let {
                    try { decodeAlbumArtSmart(it) } catch (_: Exception) { null }
                }
                if (bmp != null) {
                    ivBrowserCover.setImageBitmap(bmp)
                } else if (idleRandomCoverBitmap != null) {
                    ivBrowserCover.setImageBitmap(idleRandomCoverBitmap)
                } else {
                    ivBrowserCover.setImageResource(R.drawable.default_album)
                    fetchRandomIdleCoverAsync()
                }
            }
        }

        // 6. Analog clock
        val analogClock = customAnalogClockView
        if (analogClock != null) {
            val el = widgetViewRegistry[analogClock]
            if (el?.visibleOnFocus.isNullOrBlank()) {
                val parent = analogClock.parent as? View
                if (parent != null && "analog_wrapper" == parent.tag) {
                    parent.visibility = if (isWidgetAnalogClockOn) View.VISIBLE else View.GONE
                } else {
                    analogClock.visibility = if (isWidgetAnalogClockOn) View.VISIBLE else View.GONE
                }
            }
        }

        // 7. Circular battery
        val circularBattery = customCircularBatteryView
        if (circularBattery != null) {
            val el = widgetViewRegistry[circularBattery]
            if (el?.visibleOnFocus.isNullOrBlank()) {
                val parent = circularBattery.parent as? View
                if (parent != null && "circular_wrapper" == parent.tag) {
                    parent.visibility = if (isWidgetCircularBatteryOn) View.VISIBLE else View.GONE
                } else {
                    circularBattery.visibility = if (isWidgetCircularBatteryOn) View.VISIBLE else View.GONE
                }
            }

            if (circularBattery.visibility == View.VISIBLE) {
                val batteryIntent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                if (batteryIntent != null) {
                    val level = batteryIntent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                    val scale = batteryIntent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                    val status = batteryIntent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                    val isCharging = (status == BatteryManager.BATTERY_STATUS_CHARGING ||
                            status == BatteryManager.BATTERY_STATUS_FULL)
                    if (level != -1 && scale != -1) {
                        circularBattery.setBatteryLevel(((level / scale.toFloat()) * 100).toInt(), isCharging)
                    }
                }
            }
        }

        // 8. Dynamic focus image
        val focusImg = ivWidgetFocusImage
        if (focusImg != null) {
            val el = widgetViewRegistry[focusImg]
            if (el?.visibleOnFocus != null && el.visibleOnFocus.isNotBlank()) {
                focusImg.visibility = if (isWidgetFocusImageOn) View.VISIBLE else View.GONE
            }
        }
    }

    private fun decodeAlbumArtSmart(data: ByteArray): Bitmap? {
        val opts = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        BitmapFactory.decodeByteArray(data, 0, data.size, opts)

        var sample = 1
        var w = opts.outWidth
        var h = opts.outHeight
        while (w > 512 || h > 512) {
            sample *= 2
            w /= 2
            h /= 2
        }

        opts.inJustDecodeBounds = false
        opts.inSampleSize = sample
        return BitmapFactory.decodeByteArray(data, 0, data.size, opts)
    }

    @Volatile
    private var isFetchingAudiobookCover = false

    private fun fetchAudiobookCoverAsync(onLoaded: (Bitmap) -> Unit) {
        if (isFetchingAudiobookCover) return
        isFetchingAudiobookCover = true
        Thread({
            try {
                val bmp = resolveAudiobookCover()
                if (bmp != null) {
                    cachedBookCoverBitmap = bmp
                    (context as? Activity)?.runOnUiThread {
                        onLoaded(bmp)
                    }
                }
            } finally {
                isFetchingAudiobookCover = false
            }
        }, "y1-book-cover").start()
    }

    private fun resolveAudiobookCover(): Bitmap? {
        val main = MainActivity.instance
        val savedPath = main?.prefs?.getString("last_played_audiobook_path", null)
            ?: if (main?.prefs?.getBoolean("last_played_is_audiobook", false) == true) {
                main.prefs.getString("last_played_song_path", null)
            } else null

        if (!savedPath.isNullOrEmpty()) {
            val file = File(savedPath)
            if (file.exists()) {
                val bmp = extractCoverFromFileOrFolder(file)
                if (bmp != null) return bmp
            }
        }

        val libraryBook = MainActivity.audiobookLibrary.firstOrNull()?.file
        if (libraryBook != null && libraryBook.exists()) {
            val bmp = extractCoverFromFileOrFolder(libraryBook)
            if (bmp != null) return bmp
        }

        for (dir in StoragePaths.getAudiobooksDirs()) {
            if (dir.exists()) {
                val folderCover = MediaLibraryManager.getInstance().findFolderCover(dir)
                if (folderCover != null && folderCover.exists()) {
                    val bmp = decodeFileSmart(folderCover)
                    if (bmp != null) return bmp
                }
                val subdirs = dir.listFiles()
                if (subdirs != null) {
                    for (sub in subdirs) {
                        if (sub.isDirectory) {
                            val subCover = MediaLibraryManager.getInstance().findFolderCover(sub)
                            if (subCover != null && subCover.exists()) {
                                val bmp = decodeFileSmart(subCover)
                                if (bmp != null) return bmp
                            }
                        }
                    }
                }
            }
        }
        return null
    }

    private fun decodeFileSmart(file: File): Bitmap? {
        return try {
            val opts = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeFile(file.absolutePath, opts)
            var sample = 1
            var w = opts.outWidth
            var h = opts.outHeight
            while (w > 512 || h > 512) {
                sample *= 2
                w /= 2
                h /= 2
            }
            opts.inJustDecodeBounds = false
            opts.inSampleSize = sample
            BitmapFactory.decodeFile(file.absolutePath, opts)
        } catch (_: Exception) {
            null
        }
    }

    private fun extractCoverFromFileOrFolder(file: File): Bitmap? {
        val parent = file.parentFile
        val folderCover = MediaLibraryManager.getInstance().findFolderCover(parent)
        if (folderCover != null && folderCover.exists()) {
            val bmp = decodeFileSmart(folderCover)
            if (bmp != null) return bmp
        }

        val safeName = file.name.substringBeforeLast(".")
        val coverFile = File(StoragePaths.getCoversDir(), "$safeName.jpg")
        if (coverFile.exists()) {
            val bmp = decodeFileSmart(coverFile)
            if (bmp != null) return bmp
        }

        return try {
            val mmr = MediaMetadataRetriever()
            val fis = FileInputStream(file)
            mmr.setDataSource(fis.fd)
            val pic = mmr.embeddedPicture
            fis.close()
            mmr.release()
            if (pic != null) {
                decodeAlbumArtSmart(pic)
            } else null
        } catch (_: Exception) {
            null
        }
    }

    fun updateFocusPreview(focusedElement: ThemeManager.MenuElement) {
        var hasLiveWidgetActivated = false
        for ((widgetView, el) in widgetViewRegistry) {
            if (focusedElement.id == el.visibleOnFocus) {
                widgetView.visibility = View.VISIBLE
                hasLiveWidgetActivated = true
                if (el.type == "widget_album") {
                    refreshWidgets()
                }
            } else if (el.visibleOnFocus.isNullOrBlank()) {
                when (el.type) {
                    "widget_clock" -> widgetView.visibility = if (isWidgetClockOn) View.VISIBLE else View.GONE
                    "widget_battery" -> widgetView.visibility = if (isWidgetBatteryOn) View.VISIBLE else View.GONE
                    "widget_album" -> widgetView.visibility = if (isWidgetAlbumOn) View.VISIBLE else View.GONE
                    "widget_analog_clock" -> widgetView.visibility = if (isWidgetAnalogClockOn) View.VISIBLE else View.GONE
                    "widget_circular_battery" -> widgetView.visibility = if (isWidgetCircularBatteryOn) View.VISIBLE else View.GONE
                    "widget_focus_image" -> widgetView.visibility = if (isWidgetFocusImageOn) View.VISIBLE else View.GONE
                }
            } else {
                widgetView.visibility = View.GONE
            }
        }

        ivWidgetFocusImage?.let { iv ->
            if (!hasLiveWidgetActivated && !focusedElement.previewImage.isNullOrEmpty()) {
                iv.visibility = View.VISIBLE
                val bmp = ThemeManager.getCustomIcon(focusedElement.previewImage, context, 0)
                iv.setImageBitmap(bmp)
            } else if (!hasLiveWidgetActivated) {
                iv.setImageDrawable(null)
            }
        }
    }
}

