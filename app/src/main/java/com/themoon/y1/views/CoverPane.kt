package com.themoon.y1.views

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
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
 * All panes share one motion ([motion]): when a menu opens or closes, the art continues on the same path, and the
 * same art has the same place and zoom in the next pane. New art fades in ([FADE_MS]) and does not jump.
 * The motion runs only while a pane is visible and the screen is on (not off and not blanked by FakeScreenOff).
 *
 * Each pane also draws the menu shadow on the left edge of the art, over the art ([drawShadow]), as on the iPod Classic.
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

    /** Cross-fade time when a pane shows different art (other menu, other song). */
    const val FADE_MS = 300L

    /** The motion of all panes. The same art is the same bitmap, or a bitmap with the same pixels. */
    val motion = SharedCoverMotion(CoverMotion()) { a, b ->
        a is Bitmap && b is Bitmap && !a.isRecycled && !b.isRecycled &&
            a.width == b.width && a.height == b.height && a.sameAs(b)
    }

    /**
     * Menu shadow on the art, measured on the iPod Classic main menu (`records/ipod-classic-ui/scripts/shadow.py`):
     * 16 px wide at 320 px (24 px on the 480 px Y2), black at 39 % on the first column and 31 % on the second,
     * then linear to 0.
     */
    const val SHADOW_WIDTH_DP = 24f
    private val SHADOW_COLORS = intArrayOf(0x64000000, 0x50000000, 0x00000000)
    private val SHADOW_STOPS = floatArrayOf(0f, 1.5f / SHADOW_WIDTH_DP, 1f)

    private var shadowPaint: Paint? = null
    private var shadowWidthPx = 0f

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

    /** True when the screen shows the panes (on and not blanked). */
    fun isScreenActive(): Boolean = screenOn && !blanked

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

    /**
     * Draws the menu shadow on the left [height] px high edge of a pane, over the art. One rectangle with a
     * shader that is made once: no cost in the frames of the motion. [alpha] (0..1) fades it.
     */
    fun drawShadow(canvas: Canvas, density: Float, height: Int, alpha: Float = 1f) {
        val w = SHADOW_WIDTH_DP * density
        val paint = shadowPaint?.takeIf { shadowWidthPx == w } ?: Paint().also {
            it.shader = LinearGradient(0f, 0f, w, 0f, SHADOW_COLORS, SHADOW_STOPS, Shader.TileMode.CLAMP)
            shadowPaint = it
            shadowWidthPx = w
        }
        paint.alpha = (alpha.coerceIn(0f, 1f) * 255f).toInt()
        canvas.drawRect(0f, 0f, w, height.toFloat(), paint)
    }

    /** The art pane that shows now (not a slide layer), or null when no split screen shows. */
    fun shownPane(): CoverArtView? {
        for (v in views.keys) {
            if (!v.slideLayer && v.isShown && v.width > 0 && v.height > 0) return v
        }
        return null
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
