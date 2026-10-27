package com.themoon.y1.views

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.PowerManager
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import java.util.WeakHashMap

/**
 * The album art in the right pane of the split screens (main menu, Music, Audiobooks, Videos). All of them use one
 * rule: a [CoverArtView] that fills the pane and draws the art with CENTER_CROP and a zoom. With the setting
 * Menu Cover Motion ([PREF], default on) the art moves slowly ([CoverMotion]), as on the iPod Classic; with it
 * off, the art stays at the centre with the zoom [ZOOM].
 *
 * The motion runs only while a pane is visible and the screen is on (not off and not blanked by FakeScreenOff).
 */
object CoverPane {

    /** Zoom of the art when it does not move (size of the art / size of the pane). */
    const val ZOOM = 1.35f

    /** AppPrefs key of Settings → Menu Cover Motion. */
    const val PREF = "cover_pan_zoom"
    const val DEFAULT_ON = true

    /**
     * Delay before the next frame: the frame then comes at about every third display refresh (~21 fps). The Y2
     * draws the full screen for each frame (~8 ms on its CPU), so fewer frames save battery; the motion is slow
     * (~5 px/s), so 21 fps is smooth.
     */
    const val FRAME_DELAY_MS = 33L

    private var prefs: SharedPreferences? = null
    private var enabled = DEFAULT_ON
    private var screenOn = true
    private var blanked = false
    private val views = WeakHashMap<CoverArtView, Boolean>()

    // SharedPreferences keeps its listeners in a weak map: keep a reference here.
    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { p, key ->
        if (key == PREF) {
            enabled = p.getBoolean(PREF, DEFAULT_ON)
            refreshAll()
        }
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            screenOn = intent.action != Intent.ACTION_SCREEN_OFF
            refreshAll()
        }
    }

    /** True when the art must move now. */
    fun isMoving(): Boolean = CoverMotion.moves(enabled, screenOn, blanked)

    /** True when the setting is on. */
    fun isEnabled(): Boolean = enabled

    /** FakeScreenOff (black overlay while the screen stays on) starts or stops. */
    fun setBlanked(value: Boolean) {
        if (blanked == value) return
        blanked = value
        refreshAll()
    }

    /**
     * Makes [image] (a child of its pane) fill the pane with CENTER_CROP. The art pans and zooms if [image] is a
     * [CoverArtView].
     */
    fun attach(image: ImageView) {
        image.scaleType = ImageView.ScaleType.CENTER_CROP
        val lp = image.layoutParams
        if (lp == null || lp.width != ViewGroup.LayoutParams.MATCH_PARENT || lp.height != ViewGroup.LayoutParams.MATCH_PARENT) {
            image.layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
    }

    internal fun register(view: CoverArtView) {
        init(view.context)
        views[view] = true
    }

    internal fun unregister(view: CoverArtView) {
        views.remove(view)
    }

    private fun init(context: Context) {
        if (prefs != null) return
        val app = context.applicationContext ?: context
        val p = app.getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
        prefs = p
        enabled = p.getBoolean(PREF, DEFAULT_ON)
        p.registerOnSharedPreferenceChangeListener(prefListener)
        screenOn = try {
            @Suppress("DEPRECATION")
            (app.getSystemService(Context.POWER_SERVICE) as? PowerManager)?.isScreenOn ?: true
        } catch (_: Exception) {
            true
        }
        try {
            app.registerReceiver(screenReceiver, IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
            })
        } catch (_: Exception) {}
    }

    /** Draws all panes again: a moving pane starts its frames again, a stopped pane stops at its next frame. */
    private fun refreshAll() {
        for (v in views.keys.toList()) v.onMotionStateChanged()
    }
}
