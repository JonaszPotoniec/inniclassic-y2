package com.themoon.y1.views

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.themoon.y1.ThemeManager

object ListItemViewFactory {

    fun createButtonBackground(context: Context, color: Int): GradientDrawable {
        val shape = GradientDrawable()
        shape.setColor(color)
        val radius = ThemeManager.getButtonRadius() * context.resources.displayMetrics.density
        shape.cornerRadius = radius
        return shape
    }

    fun createFocusedButtonBackground(context: Context): GradientDrawable {
        val density = context.resources.displayMetrics.density
        if (ThemeManager.isClassicTheme()) {
            return GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0xFF62B7F5.toInt(), 0xFF2588DC.toInt(), 0xFF0865C4.toInt())
            )
        }
        val base = ThemeManager.getListButtonFocusedBg()
        val hsv = FloatArray(3)
        Color.colorToHSV(base, hsv)
        hsv[2] = Math.min(1f, hsv[2] * 1.25f)
        val lighter = (base and 0xFF000000.toInt()) or (Color.HSVToColor(hsv) and 0x00FFFFFF)
        Color.colorToHSV(base, hsv)
        hsv[2] = Math.max(0f, hsv[2] * 0.65f)
        val darker = (base and 0xFF000000.toInt()) or (Color.HSVToColor(hsv) and 0x00FFFFFF)

        val shape = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(lighter, base, darker)
        )
        val radius = ThemeManager.getButtonRadius() * density
        shape.cornerRadius = radius
        return shape
    }

    fun createListButtonWithIcon(
        context: Context,
        iconUnicode: String,
        textLabel: String,
        customColor: Int = 0,
        onFastScrollLetter: ((String) -> Unit)? = null
    ): View {
        val d = context.resources.displayMetrics.density
        val rowButton = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isFocusable = true
            isClickable = true
            isSoundEffectsEnabled = false
            background = createButtonBackground(context, ThemeManager.getListButtonNormalBg())

            val padLeft = (8 * d).toInt()
            val padTopBottom = ((if (ThemeManager.isClassicTheme()) 4 else 6) * d).toInt()
            val padRight = (10 * d).toInt()
            setPadding(padLeft, padTopBottom, padRight, padTopBottom)

            val rowLp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                val margin = if (ThemeManager.isClassicTheme()) 0 else (2 * d).toInt()
                setMargins(0, margin, 0, margin)
            }
            layoutParams = rowLp
        }

        val normalColor = if (customColor != 0) customColor else ThemeManager.getTextColorPrimary()

        val tvIcon = TextView(context).apply {
            text = iconUnicode
            textSize = 21f
            setTextColor(normalColor)
            val iconLp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                rightMargin = (15 * d).toInt()
            }
            layoutParams = iconLp
            visibility = View.GONE
        }

        val tvText = TextView(context).apply {
            text = textLabel
            setTextSize(TypedValue.COMPLEX_UNIT_PX, ThemeManager.getListTextSize() * d)
            setTextColor(normalColor)
            typeface = ThemeManager.getCustomFontBold()
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.MARQUEE
            marqueeRepeatLimit = -1
            isHorizontalFadingEdgeEnabled = true
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val tvArrow = TextView(context).apply {
            text = "〉"
            setTextSize(TypedValue.COMPLEX_UNIT_PX, 20f * d)
            setTextColor(0xFFFFFFFF.toInt())
            visibility = View.GONE
            val arrowLp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                leftMargin = (10 * d).toInt()
            }
            layoutParams = arrowLp
        }

        rowButton.addView(tvIcon)
        rowButton.addView(tvText)
        rowButton.addView(tvArrow)
        ClassicMenuStyle.apply(rowButton, tvText, tvArrow, ClassicMenuStyle.ROW_HEIGHT_DP)

        rowButton.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                rowButton.background = createFocusedButtonBackground(context)
                tvIcon.setTextColor(ThemeManager.getListButtonFocusedTextColor())
                tvText.setTextColor(ThemeManager.getListButtonFocusedTextColor())
                tvText.isSelected = true
                tvArrow.visibility = View.VISIBLE
                onFastScrollLetter?.invoke(tvText.text.toString())
            } else {
                rowButton.background = createButtonBackground(context, ThemeManager.getListButtonNormalBg())
                tvIcon.setTextColor(normalColor)
                tvText.setTextColor(normalColor)
                tvArrow.visibility = View.GONE
                tvText.isSelected = false
            }
        }

        return rowButton
    }

    fun setupAudiobookProgress(
        context: Context,
        btn: View,
        pos: Int,
        dur: Int,
        onFastScrollLetter: ((String) -> Unit)? = null
    ) {
        applyProgressBackground(context, btn, pos, dur, btn.hasFocus())
        btn.setOnFocusChangeListener { v, hasFocus ->
            if (hasFocus) {
                if (v is Button) {
                    v.setTextColor(ThemeManager.getListButtonFocusedTextColor())
                    onFastScrollLetter?.invoke(v.text.toString())
                } else if (v is LinearLayout && v.childCount > 1) {
                    (v.getChildAt(0) as? TextView)?.setTextColor(ThemeManager.getListButtonFocusedTextColor())
                    (v.getChildAt(1) as? TextView)?.apply {
                        setTextColor(ThemeManager.getListButtonFocusedTextColor())
                        onFastScrollLetter?.invoke(text.toString())
                    }
                }
                applyProgressBackground(context, btn, pos, dur, true)
            } else {
                if (v is Button) {
                    v.setTextColor(ThemeManager.getTextColorPrimary())
                } else if (v is LinearLayout && v.childCount > 1) {
                    (v.getChildAt(0) as? TextView)?.setTextColor(ThemeManager.getTextColorPrimary())
                    (v.getChildAt(1) as? TextView)?.setTextColor(ThemeManager.getTextColorPrimary())
                }
                applyProgressBackground(context, btn, pos, dur, false)
            }
        }
    }

    fun applyProgressBackground(
        context: Context,
        btn: View,
        currentMs: Int,
        totalMs: Int,
        isFocused: Boolean
    ) {
        if (currentMs <= 0 || totalMs <= 0) return
        var progressPercent = ((currentMs.toFloat() / totalMs) * 10000).toInt()
        if (progressPercent > 10000) progressPercent = 10000

        val baseColor = if (isFocused) ThemeManager.getListButtonFocusedBg() else ThemeManager.getListButtonNormalBg()
        val baseBg = createButtonBackground(context, baseColor)

        val progressColor = if (isFocused) 0x66FFFFFF.toInt() else ((ThemeManager.getListButtonFocusedBg() and 0x00FFFFFF) or 0x44000000.toInt())
        val progressBg = createButtonBackground(context, progressColor)

        val clipProgress = ClipDrawable(progressBg, Gravity.LEFT, ClipDrawable.HORIZONTAL)
        clipProgress.level = progressPercent

        val layerBg = LayerDrawable(arrayOf(baseBg, clipProgress))

        val pLeft = btn.paddingLeft
        val pTop = btn.paddingTop
        val pRight = btn.paddingRight
        val pBottom = btn.paddingBottom

        btn.background = layerBg
        btn.setPadding(pLeft, pTop, pRight, pBottom)
    }

    fun createListButtonSimple(context: Context, text: String): Button {
        val d = context.resources.displayMetrics.density
        val rowHeight = if (ThemeManager.isClassicTheme()) (ClassicMenuStyle.ROW_HEIGHT_DP * d).toInt() else (48f * d).toInt()
        val padX = ((if (ThemeManager.isClassicTheme()) 8 else 12) * d).toInt()
        val btn = Button(context).apply {
            this.text = text
            setTextColor(ThemeManager.getTextColorPrimary())
            setTextSize(TypedValue.COMPLEX_UNIT_PX, (if (ThemeManager.isClassicTheme()) 21f else 16f) * d)
            typeface = if (ThemeManager.isClassicTheme()) ThemeManager.getCustomFontBold() else ThemeManager.getCustomFont()
            includeFontPadding = false
            gravity = Gravity.LEFT or Gravity.CENTER_VERTICAL
            setPadding(padX, 0, padX, 0)
            isFocusable = true
            isClickable = true
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, rowHeight)
        }

        fun updateBg(focused: Boolean) {
            btn.background = if (focused) {
                btn.setTextColor(ThemeManager.getListButtonFocusedTextColor())
                createFocusedButtonBackground(context)
            } else {
                btn.setTextColor(ThemeManager.getTextColorPrimary())
                createButtonBackground(context, ThemeManager.getListButtonNormalBg())
            }
        }

        updateBg(false)
        btn.setOnFocusChangeListener { _, hasFocus -> updateBg(hasFocus) }
        IpodClassicLists.styleListRow(btn, btn, null)
        return btn
    }

    fun createDialogButton(context: Context, text: String): View {
        val d = context.resources.displayMetrics.density
        val btnHeight = (36f * d).toInt()
        val cornerRad = (if (ThemeManager.isClassicTheme()) 6f else ThemeManager.getButtonRadius().toFloat()) * d

        val btn = TextView(context).apply {
            this.text = text
            setTextSize(TypedValue.COMPLEX_UNIT_PX, (if (ThemeManager.isClassicTheme()) 16f else 15f) * d)
            typeface = ThemeManager.getCustomFontBold()
            gravity = Gravity.CENTER
            isFocusable = true
            isClickable = true
            includeFontPadding = false
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                btnHeight
            ).apply {
                topMargin = (6f * d).toInt()
            }
        }

        fun updateBg(focused: Boolean) {
            val bg = if (focused) {
                btn.setTextColor(ThemeManager.getListButtonFocusedTextColor())
                val grad = createFocusedButtonBackground(context)
                grad.cornerRadius = cornerRad
                grad
            } else {
                btn.setTextColor(ThemeManager.getTextColorPrimary())
                val shape = createButtonBackground(context, ThemeManager.getListButtonNormalBg())
                shape.cornerRadius = cornerRad
                shape
            }
            btn.background = bg
        }

        updateBg(false)
        btn.setOnFocusChangeListener { _, hasFocus -> updateBg(hasFocus) }
        return btn
    }

    fun createSettingRow(context: Context, title: String, value: String): LinearLayout {
        val d = context.resources.displayMetrics.density
        val rowHeight = if (ThemeManager.isClassicTheme()) (ClassicMenuStyle.ROW_HEIGHT_DP * d).toInt() else (48f * d).toInt()
        val padX = ((if (ThemeManager.isClassicTheme()) 8 else 12) * d).toInt()
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, rowHeight)
            setPadding(padX, 0, padX, 0)
            isFocusable = true
            isClickable = true
        }

        val tvTitle = TextView(context).apply {
            text = title
            setTextColor(ThemeManager.getTextColorPrimary())
            setTextSize(TypedValue.COMPLEX_UNIT_PX, (if (ThemeManager.isClassicTheme()) 21f else 16f) * d)
            typeface = if (ThemeManager.isClassicTheme()) ThemeManager.getCustomFontBold() else ThemeManager.getCustomFont()
            includeFontPadding = false
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f)
        }
        row.addView(tvTitle)

        val displayVal = if (ThemeManager.isClassicTheme()) value.replace("〉", "›") else value
        // Shows an on/off value as a switch when Settings → Toggle Style is Switch.
        val tvVal = SettingValueView(context).apply {
            stateKey = title
            text = displayVal
            setTextColor(ThemeManager.getTextColorSecondary())
            setTextSize(TypedValue.COMPLEX_UNIT_PX, (if (ThemeManager.isClassicTheme()) 21f else 14f) * d)
            typeface = ThemeManager.getCustomFont()
            includeFontPadding = false
        }
        row.addView(tvVal)
        // A row that opens a deeper screen: a drawn chevron (ChevronView) in place of the small "〉" text.
        val (valueText, hasChevron) = DisclosureChevron.split(displayVal)
        if (hasChevron) {
            tvVal.text = valueText
            row.addView(ChevronView(context, tvTitle))
        }

        fun updateBg(focused: Boolean) {
            row.background = if (focused) {
                tvTitle.setTextColor(ThemeManager.getListButtonFocusedTextColor())
                tvVal.setTextColor(ThemeManager.getListButtonFocusedTextColor())
                createFocusedButtonBackground(context)
            } else {
                tvTitle.setTextColor(ThemeManager.getTextColorPrimary())
                tvVal.setTextColor(ThemeManager.getTextColorSecondary())
                createButtonBackground(context, ThemeManager.getListButtonNormalBg())
            }
        }

        updateBg(false)
        row.setOnFocusChangeListener { _, hasFocus -> updateBg(hasFocus) }
        IpodClassicLists.styleListRow(row, tvTitle, tvVal)
        return row
    }

    fun createCategoryHeader(context: Context, container: LinearLayout, title: String) {
        val tv = TextView(context).apply {
            text = title
            setTextColor(ThemeManager.getTextColorSecondary())
            textSize = 12f
            gravity = Gravity.CENTER
            val d = context.resources.displayMetrics.density
            setPadding(0, (8 * d).toInt(), 0, (4 * d).toInt())
        }
        container.addView(tv)
    }

    fun createTablerListButton(
        context: Context,
        icon: Array<String>?,
        text: String,
        normalColor: Int = 0,
        focusedColor: Int = 0
    ): View = createTablerListButtonWithRightIcon(
        context = context,
        iconPaths = icon,
        text = text,
        rightIconPaths = null,
        iconColor = normalColor,
        textColor = normalColor,
        focusedTextColor = focusedColor,
        focusedIconColor = focusedColor
    )

    /**
     * A row of a single-choice list (Theme, Language, EQ preset...). Each row keeps the tick column on the
     * left, so the text does not move; only the chosen row shows the tick ([CheckMarkView]). Normal text and
     * focus colours.
     */
    fun createCheckListButton(context: Context, text: String, isChecked: Boolean): View {
        val row = createTablerListButton(context, TablerIcons.CHECK, text)
        if (!isChecked) (row as ViewGroup).getChildAt(0).visibility = View.INVISIBLE
        return row
    }

    fun createTablerListButtonWithRightIcon(
        context: Context,
        iconPaths: Array<String>?,
        text: String,
        rightIconPaths: Array<String>? = null,
        iconColor: Int = 0,
        textColor: Int = 0,
        focusedTextColor: Int = 0,
        focusedIconColor: Int = 0
    ): View {
        val d = context.resources.displayMetrics.density
        val rowHeight = if (ThemeManager.isClassicTheme()) (ClassicMenuStyle.ROW_HEIGHT_DP * d).toInt() else (48f * d).toInt()
        val padX = ((if (ThemeManager.isClassicTheme()) 8 else 12) * d).toInt()
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, rowHeight)
            setPadding(padX, 0, padX, 0)
            isFocusable = true
            isClickable = true
        }

        val actualIconColor = if (iconColor != 0) iconColor else ThemeManager.getTextColorPrimary()
        val actualTextColor = if (textColor != 0) textColor else ThemeManager.getTextColorPrimary()
        val actualFocusedTextColor = if (focusedTextColor != 0) focusedTextColor else ThemeManager.getListButtonFocusedTextColor()
        val actualFocusedIconColor = if (focusedIconColor != 0) focusedIconColor else ThemeManager.getListButtonFocusedTextColor()

        val iconSize = (18f * d).toInt()
        var ivLeft: ImageView? = null
        // The tick of a selection list: the same column, but as tall as the capital letters of the text.
        val tick = if (iconPaths === TablerIcons.CHECK) CheckMarkView(context) else null
        if (tick != null) {
            row.addView(tick)
        } else if (iconPaths != null) {
            ivLeft = ImageView(context).apply {
                layoutParams = LinearLayout.LayoutParams(iconSize, iconSize).apply {
                    rightMargin = (10f * d).toInt()
                }
                setImageBitmap(TablerIcons.render(iconPaths, iconSize, actualIconColor))
            }
            row.addView(ivLeft)
        }

        val tv = TextView(context).apply {
            this.text = text
            setTextColor(actualTextColor)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, ThemeManager.getListTextSize() * d)
            typeface = if (ThemeManager.isClassicTheme()) ThemeManager.getCustomFontBold() else ThemeManager.getCustomFont()
            includeFontPadding = false
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.MARQUEE
            marqueeRepeatLimit = -1
            isHorizontalFadingEdgeEnabled = true
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f)
        }
        row.addView(tv)
        tick?.textView = tv

        val rightIconSize = (16f * d).toInt()
        var ivRight: ImageView? = null
        if (rightIconPaths != null) {
            ivRight = ImageView(context).apply {
                layoutParams = LinearLayout.LayoutParams(rightIconSize, rightIconSize).apply {
                    leftMargin = (8f * d).toInt()
                }
                setImageBitmap(TablerIcons.render(rightIconPaths, rightIconSize, ThemeManager.getTextColorSecondary()))
            }
            row.addView(ivRight)
        }

        fun updateBg(focused: Boolean) {
            row.background = if (focused) {
                tv.setTextColor(actualFocusedTextColor)
                tv.isSelected = true
                tick?.color = actualFocusedIconColor
                if (iconPaths != null) ivLeft?.setImageBitmap(TablerIcons.render(iconPaths, iconSize, actualFocusedIconColor))
                if (rightIconPaths != null) ivRight?.setImageBitmap(TablerIcons.render(rightIconPaths, rightIconSize, actualFocusedIconColor))
                createFocusedButtonBackground(context)
            } else {
                tv.setTextColor(actualTextColor)
                tv.isSelected = false
                tick?.color = actualIconColor
                if (iconPaths != null) ivLeft?.setImageBitmap(TablerIcons.render(iconPaths, iconSize, actualIconColor))
                if (rightIconPaths != null) ivRight?.setImageBitmap(TablerIcons.render(rightIconPaths, rightIconSize, ThemeManager.getTextColorSecondary()))
                createButtonBackground(context, ThemeManager.getListButtonNormalBg())
            }
        }

        updateBg(false)
        row.setOnFocusChangeListener { _, hasFocus -> updateBg(hasFocus) }
        IpodClassicLists.styleListRow(row, tv, null)
        return row
    }

    /**
     * A row of the Wi-Fi and Bluetooth lists, like macOS: a tick column on the left (the tick shows only on the
     * chosen row, so the text does not move), the name, then status glyphs in fixed slots on the right (lock, signal,
     * device type; [RowGlyphsView]). The tick column has the same size as the single-choice lists. The glyphs are as
     * tall as the capital letters of the name and on its baseline, in the primary text colour (as macOS: black or
     * white, not grey), and in the focused text colour on the focused row.
     */
    fun createChoiceRowWithRightIcons(
        context: Context,
        text: String,
        isChecked: Boolean,
        slots: List<RowGlyphs.Slot>
    ): View {
        val row = createTablerListButtonWithRightIcon(context, TablerIcons.CHECK, text) as LinearLayout
        if (!isChecked) row.getChildAt(0).visibility = View.INVISIBLE

        val glyphs = RowGlyphsView(context, slots).apply {
            textView = row.getChildAt(1) as TextView
            color = ThemeManager.getTextColorPrimary()
        }
        row.addView(glyphs)

        val rowFocusListener = row.onFocusChangeListener
        row.setOnFocusChangeListener { v, hasFocus ->
            rowFocusListener?.onFocusChange(v, hasFocus)
            glyphs.color = if (hasFocus) ThemeManager.getListButtonFocusedTextColor() else ThemeManager.getTextColorPrimary()
        }
        return row
    }
}
