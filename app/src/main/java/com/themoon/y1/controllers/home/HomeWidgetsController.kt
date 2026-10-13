package com.themoon.y1.controllers.home

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.os.BatteryManager
import android.text.SpannableString
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.util.TypedValue
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import com.themoon.y1.R
import com.themoon.y1.ThemeManager
import com.themoon.y1.views.CircularBatteryView
import com.themoon.y1.views.CustomAnalogClockView
import com.themoon.y1.views.WidgetBatteryBarView
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
    private val widgetViewRegistry: Map<View, ThemeManager.MenuElement>,
    private val tvWidgetClock: TextView?,
    private val widgetBatteryView: WidgetBatteryBarView?,
    private val ivWidgetAlbum: ImageView?,
    private val tvWidgetAlbumTitle: TextView?,
    private val tvWidgetAlbumArtist: TextView?,
    private val ivAnimatedCover: ImageView?,
    private val ivBrowserCover: ImageView?,
    private val frameBrowserCover: View?,
    private val customAnalogClockView: CustomAnalogClockView?,
    private val customCircularBatteryView: CircularBatteryView?,
    private val ivWidgetFocusImage: ImageView?,
    private val fetchRandomIdleCoverAsync: () -> Unit
) {

    var isWidgetClockOn = true
    var isWidgetBatteryOn = true
    var isWidgetAlbumOn = true
    var isWidgetAnalogClockOn = false
    var isWidgetCircularBatteryOn = false
    var isWidgetFocusImageOn = false

    var is24HourFormat = true
    var currentClockSize = 14

    var lastAlbumArtBytes: ByteArray? = null
    var idleRandomCoverBitmap: Bitmap? = null
    var currentTrackTitle = ""
    var currentTrackArtist = ""

    fun refreshWidgets() {
        // 1. Digital Clock
        if (tvWidgetClock != null) {
            val el = widgetViewRegistry[tvWidgetClock]
            if (el?.visibleOnFocus.isNullOrBlank()) {
                tvWidgetClock.visibility = if (isWidgetClockOn) View.VISIBLE else View.GONE
            }

            if (tvWidgetClock.visibility == View.VISIBLE) {
                val d = context.resources.displayMetrics.density
                tvWidgetClock.setTextSize(TypedValue.COMPLEX_UNIT_PX, (currentClockSize * 2.1f) * d)
                tvWidgetClock.setLineSpacing(0f, 1.1f)

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
                tvWidgetClock.text = spannable
            }
        }

        // 2. Battery Bar
        if (widgetBatteryView != null) {
            val el = widgetViewRegistry[widgetBatteryView]
            if (el?.visibleOnFocus.isNullOrBlank()) {
                widgetBatteryView.visibility = if (isWidgetBatteryOn) View.VISIBLE else View.GONE
            }
            if (widgetBatteryView.visibility == View.VISIBLE) {
                widgetBatteryView.setColor(ThemeManager.getTextColorPrimary())
                val batteryIntent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                if (batteryIntent != null) {
                    val level = batteryIntent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                    val scale = batteryIntent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                    val status = batteryIntent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                    val isCharging = (status == BatteryManager.BATTERY_STATUS_CHARGING ||
                            status == BatteryManager.BATTERY_STATUS_FULL)
                    if (level != -1 && scale != -1) {
                        widgetBatteryView.setBatteryLevel(((level / scale.toFloat()) * 100).toInt(), isCharging)
                    }
                }
            }
        }

        // 3. Album Art Preview Widget
        if (ivWidgetAlbum != null && tvWidgetAlbumTitle != null && tvWidgetAlbumArtist != null) {
            val parent = ivWidgetAlbum.parent as? View
            val el = widgetViewRegistry[parent]

            if (el?.visibleOnFocus.isNullOrBlank()) {
                parent?.visibility = if (isWidgetAlbumOn) View.VISIBLE else View.GONE
            }

            if (parent != null && parent.visibility == View.VISIBLE) {
                tvWidgetAlbumTitle.text = currentTrackTitle
                tvWidgetAlbumArtist.text = currentTrackArtist

                val artBytes = lastAlbumArtBytes
                if (artBytes != null && artBytes.isNotEmpty()) {
                    try {
                        ivWidgetAlbum.setImageBitmap(decodeAlbumArtSmart(artBytes))
                    } catch (ignored: Exception) {}
                } else {
                    ivWidgetAlbum.setImageBitmap(
                        ThemeManager.getCustomIcon("icon_default_album.png", context, R.drawable.default_album)
                    )
                }
            }
        }

        // 4. Drift animated cover
        if (ivAnimatedCover != null) {
            val artBytes = lastAlbumArtBytes
            if (artBytes != null && artBytes.isNotEmpty()) {
                try {
                    ivAnimatedCover.setImageBitmap(decodeAlbumArtSmart(artBytes))
                } catch (ignored: Exception) {}
            } else if (idleRandomCoverBitmap != null) {
                ivAnimatedCover.setImageBitmap(idleRandomCoverBitmap)
            } else {
                ivAnimatedCover.setImageResource(R.drawable.default_album)
                fetchRandomIdleCoverAsync()
            }
        }

        // 5. Music menu split cover
        if (ivBrowserCover != null && frameBrowserCover != null && frameBrowserCover.visibility == View.VISIBLE) {
            val artBytes = lastAlbumArtBytes
            if (artBytes != null && artBytes.isNotEmpty()) {
                try {
                    ivBrowserCover.setImageBitmap(decodeAlbumArtSmart(artBytes))
                } catch (ignored: Exception) {}
            } else if (idleRandomCoverBitmap != null) {
                ivBrowserCover.setImageBitmap(idleRandomCoverBitmap)
            } else {
                ivBrowserCover.setImageResource(R.drawable.default_album)
                fetchRandomIdleCoverAsync()
            }
        }

        // 6. Analog clock
        if (customAnalogClockView != null) {
            val el = widgetViewRegistry[customAnalogClockView]
            if (el?.visibleOnFocus.isNullOrBlank()) {
                val parent = customAnalogClockView.parent as? View
                if (parent != null && "analog_wrapper" == parent.tag) {
                    parent.visibility = if (isWidgetAnalogClockOn) View.VISIBLE else View.GONE
                } else {
                    customAnalogClockView.visibility = if (isWidgetAnalogClockOn) View.VISIBLE else View.GONE
                }
            }
        }

        // 7. Circular battery
        if (customCircularBatteryView != null) {
            val el = widgetViewRegistry[customCircularBatteryView]
            if (el?.visibleOnFocus.isNullOrBlank()) {
                val parent = customCircularBatteryView.parent as? View
                if (parent != null && "circular_wrapper" == parent.tag) {
                    parent.visibility = if (isWidgetCircularBatteryOn) View.VISIBLE else View.GONE
                } else {
                    customCircularBatteryView.visibility = if (isWidgetCircularBatteryOn) View.VISIBLE else View.GONE
                }
            }

            if (customCircularBatteryView.visibility == View.VISIBLE) {
                val batteryIntent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                if (batteryIntent != null) {
                    val level = batteryIntent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                    val scale = batteryIntent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                    val status = batteryIntent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                    val isCharging = (status == BatteryManager.BATTERY_STATUS_CHARGING ||
                            status == BatteryManager.BATTERY_STATUS_FULL)
                    if (level != -1 && scale != -1) {
                        customCircularBatteryView.setBatteryLevel(((level / scale.toFloat()) * 100).toInt(), isCharging)
                    }
                }
            }
        }

        // 8. Dynamic focus image
        if (ivWidgetFocusImage != null) {
            val el = widgetViewRegistry[ivWidgetFocusImage]
            if (el?.visibleOnFocus != null && el.visibleOnFocus.isNotBlank()) {
                ivWidgetFocusImage.visibility = if (isWidgetFocusImageOn) View.VISIBLE else View.GONE
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

