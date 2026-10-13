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
        val btn = Button(context).apply {
            this.text = text
            setTextColor(ThemeManager.getTextColorPrimary())
            textSize = 16f
            typeface = ThemeManager.getCustomFont()
            gravity = Gravity.LEFT or Gravity.CENTER_VERTICAL
            setPadding((16f * d).toInt(), 0, (16f * d).toInt(), 0)
            isFocusable = true
            isClickable = true
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (48f * d).toInt())
        }

        fun updateBg(focused: Boolean) {
            val bg = GradientDrawable().apply {
                cornerRadius = 8f * d
                if (focused) {
                    setColor(ThemeManager.getListButtonFocusedBg())
                    btn.setTextColor(ThemeManager.getListButtonFocusedTextColor())
                } else {
                    setColor(ThemeManager.getListButtonNormalBg())
                    btn.setTextColor(ThemeManager.getTextColorPrimary())
                }
            }
            btn.background = bg
        }

        updateBg(false)
        btn.setOnFocusChangeListener { _, hasFocus -> updateBg(hasFocus) }
        return btn
    }

    fun createSettingRow(context: Context, title: String, value: String): LinearLayout {
        val d = context.resources.displayMetrics.density
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (48f * d).toInt())
            setPadding((16f * d).toInt(), 0, (16f * d).toInt(), 0)
            isFocusable = true
            isClickable = true
        }

        val tvTitle = TextView(context).apply {
            text = title
            setTextColor(ThemeManager.getTextColorPrimary())
            textSize = 16f
            typeface = ThemeManager.getCustomFont()
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f)
        }
        row.addView(tvTitle)

        val tvVal = TextView(context).apply {
            text = value
            setTextColor(ThemeManager.getTextColorSecondary())
            textSize = 14f
            typeface = ThemeManager.getCustomFont()
        }
        row.addView(tvVal)

        fun updateBg(focused: Boolean) {
            val bg = GradientDrawable().apply {
                cornerRadius = 8f * d
                if (focused) {
                    setColor(ThemeManager.getListButtonFocusedBg())
                    tvTitle.setTextColor(ThemeManager.getListButtonFocusedTextColor())
                    tvVal.setTextColor(ThemeManager.getListButtonFocusedTextColor())
                } else {
                    setColor(ThemeManager.getListButtonNormalBg())
                    tvTitle.setTextColor(ThemeManager.getTextColorPrimary())
                    tvVal.setTextColor(ThemeManager.getTextColorSecondary())
                }
            }
            row.background = bg
        }

        updateBg(false)
        row.setOnFocusChangeListener { _, hasFocus -> updateBg(hasFocus) }
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
        normalColor: Int,
        focusedColor: Int
    ): View = createTablerListButtonWithRightIcon(context, icon, text, null, normalColor, focusedColor)

    fun createTablerListButtonWithRightIcon(
        context: Context,
        iconPaths: Array<String>?,
        text: String,
        rightIconPaths: Array<String>?,
        normalColor: Int,
        focusedColor: Int
    ): View {
        val d = context.resources.displayMetrics.density
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (48f * d).toInt())
            setPadding((16f * d).toInt(), 0, (16f * d).toInt(), 0)
            isFocusable = true
            isClickable = true
        }

        val iconSize = (20f * d).toInt()
        var ivLeft: ImageView? = null
        if (iconPaths != null) {
            ivLeft = ImageView(context).apply {
                layoutParams = LinearLayout.LayoutParams(iconSize, iconSize).apply {
                    rightMargin = (12f * d).toInt()
                }
                setImageBitmap(TablerIcons.render(iconPaths, iconSize, normalColor))
            }
            row.addView(ivLeft)
        }

        val tv = TextView(context).apply {
            this.text = text
            setTextColor(normalColor)
            textSize = 16f
            typeface = ThemeManager.getCustomFont()
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f)
        }
        row.addView(tv)

        var ivRight: ImageView? = null
        if (rightIconPaths != null) {
            ivRight = ImageView(context).apply {
                layoutParams = LinearLayout.LayoutParams(iconSize, iconSize).apply {
                    leftMargin = (8f * d).toInt()
                }
                setImageBitmap(TablerIcons.render(rightIconPaths, iconSize, normalColor))
            }
            row.addView(ivRight)
        }

        fun updateBg(focused: Boolean) {
            val bg = GradientDrawable().apply {
                cornerRadius = 8f * d
                if (focused) {
                    setColor(ThemeManager.getListButtonFocusedBg())
                    tv.setTextColor(focusedColor)
                    if (iconPaths != null) ivLeft?.setImageBitmap(TablerIcons.render(iconPaths, iconSize, focusedColor))
                    if (rightIconPaths != null) ivRight?.setImageBitmap(TablerIcons.render(rightIconPaths, iconSize, focusedColor))
                } else {
                    setColor(ThemeManager.getListButtonNormalBg())
                    tv.setTextColor(normalColor)
                    if (iconPaths != null) ivLeft?.setImageBitmap(TablerIcons.render(iconPaths, iconSize, normalColor))
                    if (rightIconPaths != null) ivRight?.setImageBitmap(TablerIcons.render(rightIconPaths, iconSize, normalColor))
                }
            }
            row.background = bg
        }

        updateBg(false)
        row.setOnFocusChangeListener { _, hasFocus -> updateBg(hasFocus) }
        return row
    }
}
