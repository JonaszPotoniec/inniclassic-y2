package com.themoon.y1.views

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import com.themoon.y1.MainActivity
import com.themoon.y1.ThemeManager

/**
 * A short message in the iPod way, in place of an Android toast: a box in the centre of the screen with the shape and
 * the colours of the letter box (the iPod shows its short overlays, the quick-scroll letter and the Hold lock, as a box
 * in the centre). Keys still go to the screen below. A new notice replaces the one that shows.
 * Call from any thread. Without the app window (the activity is closed) it falls back to an Android toast.
 */
object Notice {

    const val MIN_MS = 2000L
    const val LONG_MIN_MS = 3500L
    const val MAX_MS = 5000L
    private const val BASE_MS = 1000L
    private const val PER_CHAR_MS = 50L

    /** How long a notice shows: longer for longer text; [long] (errors, messages to read) at least [LONG_MIN_MS]. */
    fun durationMs(text: CharSequence, long: Boolean): Long {
        val forText = (BASE_MS + PER_CHAR_MS * text.length).coerceIn(MIN_MS, MAX_MS)
        return if (long) maxOf(forText, LONG_MIN_MS) else forText
    }

    /** A result, a state or an empty result. [long]: a message that needs more time to read. */
    fun show(context: Context, text: CharSequence, long: Boolean = false) {
        post { display(context, text, long) }
    }

    /** An error: it shows for at least [LONG_MIN_MS]. */
    fun error(context: Context, text: CharSequence) {
        show(context, text, long = true)
    }

    /**
     * [text] for after [android.app.Activity.recreate]: the new activity shows it (MainActivity.onCreate). A notice
     * that shows now closes with the old window.
     */
    fun showAfterRecreate(text: CharSequence) {
        pending = text
    }

    private var pending: CharSequence? = null

    /** The text of [showAfterRecreate], once. */
    fun takePending(): CharSequence? = pending.also { pending = null }

    private val handler by lazy { Handler(Looper.getMainLooper()) }
    private val hideTask = Runnable { hide() }
    private var box: TextView? = null
    /** The activity of [box]; [release] lets it go when that activity closes. */
    private var boxOwner: Any? = null

    /** [owner] closes: drop the box of its window, so that this object does not keep the old activity in memory. */
    fun release(owner: Any) {
        if (boxOwner !== owner) return
        box = null
        boxOwner = null
    }

    /** True when the box of [owner] is kept (for tests). */
    internal fun holds(owner: Any): Boolean = boxOwner === owner && box != null

    internal fun keep(owner: Any, v: TextView) {
        box = v
        boxOwner = owner
    }

    private fun post(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action() else handler.post(action)
    }

    private fun display(context: Context, text: CharSequence, long: Boolean) {
        val main = MainActivity.instance
        if (main == null || main.isFinishing) {
            Toast.makeText(context.applicationContext, text, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
            return
        }
        // No message on the black screen of a fake screen off
        if (main.isFakeScreenOff) return
        val v = ensure(main) ?: return
        style(main, v)
        v.text = text
        v.animate().cancel()
        if (v.visibility != View.VISIBLE) {
            v.alpha = 0f
            v.visibility = View.VISIBLE
            v.animate().alpha(1f).setDuration(100).start()
        } else {
            v.alpha = 1f
        }
        v.bringToFront()
        handler.removeCallbacks(hideTask)
        handler.postDelayed(hideTask, durationMs(text, long))
    }

    private fun hide() {
        val v = box ?: return
        if (v.visibility != View.VISIBLE) return
        v.animate().alpha(0f).setDuration(200).withEndAction { v.visibility = View.GONE }.start()
    }

    private fun ensure(main: MainActivity): TextView? {
        box?.let { if (boxOwner === main && it.parent != null) return it }
        val root = main.findViewById<ViewGroup>(android.R.id.content) ?: return null
        val d = main.resources.displayMetrics.density
        val v = TextView(main).apply {
            gravity = Gravity.CENTER
            maxWidth = (400 * d).toInt()
            maxLines = 4
            ellipsize = TextUtils.TruncateAt.END
            setPadding((18 * d).toInt(), (12 * d).toInt(), (18 * d).toInt(), (12 * d).toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_PX, 19 * d)
            visibility = View.GONE
        }
        root.addView(v, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        keep(main, v)
        return v
    }

    /** The letter box colours (MainActivity.showJumpLetter): the theme's selection colour, its text colour. */
    private fun style(main: MainActivity, v: TextView) {
        v.setTextColor(ThemeManager.getListButtonFocusedTextColor())
        v.typeface = ThemeManager.getCustomFontBold()
        v.background = GradientDrawable().apply {
            setColor(ThemeManager.getListButtonFocusedBg() or 0xFF000000.toInt())
            cornerRadius = 15f * main.resources.displayMetrics.density
        }
    }
}
