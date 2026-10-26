package com.themoon.y1.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import android.view.animation.AnimationUtils
import android.widget.TextView
import com.themoon.y1.ThemeManager
import com.themoon.y1.core.extensions.onOffState
import com.themoon.y1.core.extensions.withoutOnOffMark

/**
 * The value on the right of a setting row ([ListItemViewFactory.createSettingRow]).
 * With Toggle Style = Switch ([ToggleStyle]), an on/off value (text from `onOff()`) shows as a small switch.
 * [getText] always gives the plain text ("On"/"Off"), for tests and accessibility.
 *
 * The switch uses only API 19 drawing calls (drawRoundRect, drawCircle), at whole pixels.
 *
 * When the value changes on the screen (a click), the knob slides and the colours cross-fade
 * ([ToggleStyle.ANIMATION_MS], ease out). A new row, or a row that is not on the screen, shows the value at once.
 * If the controller makes the list again after the click, the new row with the same [stateKey] continues the
 * animation from the knob position of the removed row ([ToggleStyle.takeRemoved]).
 */
class SettingValueView(context: Context) : TextView(context) {
    // setText() is also called from the TextView constructor, before these initialisers: they then set the defaults.
    private var ready = false
    private var state: Boolean? = null
    /** The look of the last onMeasure(): a switch is narrower than the text, so a new look needs a new layout. */
    private var measuredLook: ToggleStyle.Look? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val track = RectF()
    /** The row title: identifies the row when the controller makes the list again. */
    var stateKey: String? = null
    // The running animation: start knob position and start time (NO_ANIMATION = none).
    private var animFrom = 0f
    private var animStart = NO_ANIMATION

    init {
        // The row has the focus: take its state, so the switch draws again when the row is selected.
        isDuplicateParentStateEnabled = true
        ToggleStyle.register(this)
        ready = true
    }

    override fun setText(text: CharSequence?, type: TextView.BufferType?) {
        if (!ready) {
            super.setText(withoutOnOffMark(text), type)
            return
        }
        val before = look()
        val previous = state
        val now = AnimationUtils.currentAnimationTimeMillis()
        val shownPosition = knobPosition(now)
        state = ToggleStyle.nextState(state, text, this.text)
        val next = state
        if (previous != null && next != null && previous != next && isShown && ToggleStyle.isSwitch(context)) {
            animFrom = shownPosition
            animStart = now
        } else if (next != previous) {
            animStart = NO_ANIMATION
        }
        super.setText(withoutOnOffMark(text), type)
        // TextView itself does not lay out again when it has no text layout yet (switch shown until now).
        if (look() != before || look() != measuredLook) requestLayout()
        invalidate()
    }

    private fun look(): ToggleStyle.Look = ToggleStyle.look(ToggleStyle.isSwitch(context), state)

    /** Knob position (0 = Off, 1 = On) at [now]. */
    private fun knobPosition(now: Long): Float {
        val on = state ?: return 0f
        if (animStart == NO_ANIMATION) return if (on) 1f else 0f
        return ToggleStyle.knobPosition(animFrom, on, now - animStart)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        val key = stateKey ?: return
        val on = state ?: return
        if (!ToggleStyle.isSwitch(context)) return
        val now = AnimationUtils.currentAnimationTimeMillis()
        val from = ToggleStyle.takeRemoved(key, on, now) ?: return
        animFrom = from
        animStart = now
    }

    override fun onDetachedFromWindow() {
        val key = stateKey
        if (key != null && state != null) {
            val now = AnimationUtils.currentAnimationTimeMillis()
            ToggleStyle.rememberRemoved(key, knobPosition(now), now)
        }
        animStart = NO_ANIMATION
        super.onDetachedFromWindow()
    }

    private fun px(dp: Float): Int = Math.max(1, Math.round(dp * resources.displayMetrics.density))

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        measuredLook = look()
        if (measuredLook == ToggleStyle.Look.TEXT) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            return
        }
        val w = px(SWITCH_WIDTH_DP) + paddingLeft + paddingRight
        val h = px(SWITCH_HEIGHT_DP) + paddingTop + paddingBottom
        setMeasuredDimension(View.resolveSize(w, widthMeasureSpec), View.resolveSize(h, heightMeasureSpec))
    }

    override fun drawableStateChanged() {
        super.drawableStateChanged()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val look = look()
        // The size is for the other look (text in a switch-wide view breaks into lines): measure again.
        if (look != measuredLook) requestLayout()
        if (look == ToggleStyle.Look.TEXT) {
            super.onDraw(canvas)
            return
        }
        val on = look == ToggleStyle.Look.SWITCH_ON
        val w = px(SWITCH_WIDTH_DP)
        val h = px(SWITCH_HEIGHT_DP)
        val inset = px(KNOB_INSET_DP)
        // Centre in the full view height: some layouts make the value as high as the row, with a top padding.
        val right = width - paddingRight
        val left = right - w
        val top = (height - h) / 2
        track.set(left.toFloat(), top.toFloat(), right.toFloat(), (top + h).toFloat())

        val selected = (parent as? View)?.isFocused == true || isFocused
        val accent = if (ThemeManager.isClassicTheme()) CLASSIC_ACCENT else ThemeManager.getListButtonFocusedBg()
        val selectedText = ThemeManager.getListButtonFocusedTextColor()
        val secondaryText = ThemeManager.getTextColorSecondary()

        // During an animation: knob between the two ends, colours between the Off and the On colours.
        // No objects are made here: this runs in each animation frame.
        val now = AnimationUtils.currentAnimationTimeMillis()
        val pos = knobPosition(now)
        if (animStart != NO_ANIMATION) {
            if (now - animStart < ToggleStyle.durationMs(animFrom, on)) postInvalidateOnAnimation()
            else animStart = NO_ANIMATION
        }
        val trackColor = ToggleStyle.blend(
            ToggleStyle.trackColor(false, selected, accent, selectedText, secondaryText),
            ToggleStyle.trackColor(true, selected, accent, selectedText, secondaryText), pos
        )
        val knobColor = ToggleStyle.blend(
            ToggleStyle.knobColor(false, selected, accent, selectedText),
            ToggleStyle.knobColor(true, selected, accent, selectedText), pos
        )

        val radius = h / 2f
        paint.style = Paint.Style.FILL
        paint.color = trackColor
        canvas.drawRoundRect(track, radius, radius, paint)

        val cx = left + radius + pos * (w - 2 * radius)
        paint.color = knobColor
        canvas.drawCircle(cx, top + radius, radius - inset, paint)
    }

    companion object {
        // iOS proportions (track height = 0.58 × width). 38 × 22 dp: on the Y2 (density 1), 38 × 22 px; the row
        // text has a cap height of ~15 px. The row centres the view, so the switch stays on the text line.
        const val SWITCH_WIDTH_DP = 38f
        const val SWITCH_HEIGHT_DP = 22f
        const val KNOB_INSET_DP = 2f

        private const val NO_ANIMATION = -1L

        /** The middle colour of the Classic themes' selected-row gradient (ListItemViewFactory). */
        private const val CLASSIC_ACCENT = 0xFF2588DC.toInt()
    }
}
