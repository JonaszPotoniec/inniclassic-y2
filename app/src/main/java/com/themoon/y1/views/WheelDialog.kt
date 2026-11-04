package com.themoon.y1.views

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.themoon.y1.ThemeManager
import com.themoon.y1.core.extensions.dpToPx
import com.themoon.y1.core.extensions.vibrateFeedback

class WheelDialog private constructor(private val context: Context) {

    var title: String? = null
    var message: String? = null
    private val buttons = mutableListOf<ButtonConfig>()

    private data class ButtonConfig(
        val text: String,
        val icon: String? = null,
        val isDestructive: Boolean = false,
        val onClick: (() -> Unit)? = null
    )

    fun positiveButton(text: String, isDestructive: Boolean = false, onClick: (() -> Unit)? = null) {
        buttons.add(ButtonConfig(text = text, isDestructive = isDestructive, onClick = onClick))
    }

    fun negativeButton(text: String, onClick: (() -> Unit)? = null) {
        buttons.add(ButtonConfig(text = text, isDestructive = false, onClick = onClick))
    }

    fun addButton(text: String, icon: String? = null, isDestructive: Boolean = false, onClick: (() -> Unit)? = null) {
        buttons.add(ButtonConfig(text = text, icon = icon, isDestructive = isDestructive, onClick = onClick))
    }

    fun show(): Dialog {
        val dialog = Dialog(context)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        val d = context.resources.displayMetrics.density
        // Cap the dialog to the screen; the item list scrolls inside it.
        val maxDialogHeight = context.resources.displayMetrics.heightPixels - (24f * d).toInt()
        val rootLayout = object : LinearLayout(context) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val specSize = MeasureSpec.getSize(heightMeasureSpec)
                val cap = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) {
                    maxDialogHeight
                } else {
                    minOf(specSize, maxDialogHeight)
                }
                super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(cap, MeasureSpec.AT_MOST))
            }
        }.apply {
            orientation = LinearLayout.VERTICAL
            val bg = GradientDrawable().apply {
                val baseBg = try {
                    ThemeManager.getOverlayBackgroundColor() or 0xFF000000.toInt()
                } catch (_: Exception) {
                    0xFF222222.toInt()
                }
                setColor(baseBg)
                cornerRadius = 15f * d
                val strokeColor = if (ThemeManager.isClassicLightTheme()) 0x33000000 else 0x33FFFFFF
                setStroke((1f * d).toInt(), strokeColor)
            }
            background = bg
            setPadding((15f * d).toInt(), (20f * d).toInt(), (15f * d).toInt(), (15f * d).toInt())
        }

        title?.let { t ->
            val tvTitle = TextView(context).apply {
                text = t
                setTextColor(0xFFFF5555.toInt())
                textSize = 18f
                try {
                    typeface = ThemeManager.getCustomFontBold()
                } catch (_: Exception) {
                }
                gravity = Gravity.CENTER
                setPadding(0, 0, 0, (10f * d).toInt())
            }
            rootLayout.addView(tvTitle)
        }

        message?.let { m ->
            val tvMsg = TextView(context).apply {
                text = m
                val primaryColor = try {
                    ThemeManager.getTextColorPrimary()
                } catch (_: Exception) {
                    0xFFFFFFFF.toInt()
                }
                setTextColor(primaryColor)
                textSize = 16f
                gravity = Gravity.CENTER
                setPadding(0, 0, 0, (20f * d).toInt())
                setLineSpacing(5f, 1.2f)
            }
            rootLayout.addView(tvMsg)
        }

        val focusableButtons = mutableListOf<View>()
        val itemsLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        val scrollView = ScrollView(context).apply {
            isFocusable = false
            isVerticalScrollBarEnabled = true
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            addView(itemsLayout)
        }

        // Scroll so the whole item, including its bottom margin, is on screen
        fun focusItem(item: View) {
            item.requestFocus()
            val margin = (item.layoutParams as? ViewGroup.MarginLayoutParams)?.bottomMargin ?: 0
            val top = item.top
            val bottom = item.bottom + margin
            val visibleTop = scrollView.scrollY
            val visibleBottom = visibleTop + scrollView.height
            if (top < visibleTop) {
                scrollView.scrollTo(0, top)
            } else if (bottom > visibleBottom) {
                scrollView.scrollTo(0, bottom - scrollView.height)
            }
        }

        val wheelKeyListener = View.OnKeyListener { v, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN) {
                if (keyCode == 21 || keyCode == 19) { // DPAD_LEFT or DPAD_UP (CCW)
                    val idx = focusableButtons.indexOf(v)
                    if (idx > 0) {
                        focusItem(focusableButtons[idx - 1])
                        context.vibrateFeedback(25)
                        return@OnKeyListener true
                    }
                    return@OnKeyListener true
                }
                if (keyCode == 22 || keyCode == 20) { // DPAD_RIGHT or DPAD_DOWN (CW)
                    val idx = focusableButtons.indexOf(v)
                    if (idx in 0 until focusableButtons.size - 1) {
                        focusItem(focusableButtons[idx + 1])
                        context.vibrateFeedback(25)
                        return@OnKeyListener true
                    }
                    return@OnKeyListener true
                }
            }
            false
        }

        for (btnConfig in buttons) {
            val btnView = createDialogButton(context, btnConfig.text, btnConfig.isDestructive)
            btnView.isFocusable = true
            btnView.setOnKeyListener(wheelKeyListener)
            btnView.setOnClickListener {
                context.vibrateFeedback(25)
                dialog.dismiss()
                btnConfig.onClick?.invoke()
            }
            itemsLayout.addView(btnView)
            focusableButtons.add(btnView)
        }
        if (focusableButtons.isNotEmpty()) {
            rootLayout.addView(scrollView)
        }

        dialog.setContentView(rootLayout)
        ListEdges.install(rootLayout)
        dialog.show()

        if (focusableButtons.isNotEmpty()) {
            rootLayout.postDelayed({
                focusItem(focusableButtons.first())
            }, 50)
        }

        return dialog
    }

    private fun createDialogButton(context: Context, label: String, isDestructive: Boolean): View {
        val d = context.resources.displayMetrics.density
        val btn = TextView(context).apply {
            text = label
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding((12f * d).toInt(), (10f * d).toInt(), (12f * d).toInt(), (10f * d).toInt())

            val textPrimary = try {
                ThemeManager.getTextColorPrimary()
            } catch (_: Exception) {
                0xFFEEEEEE.toInt()
            }
            val focusedText = try {
                ThemeManager.getListButtonFocusedTextColor()
            } catch (_: Exception) {
                Color.WHITE
            }
            val normalColor = if (isDestructive) 0xFFFF5555.toInt() else textPrimary
            val focusedColor = if (isDestructive) Color.WHITE else focusedText
            setTextColor(normalColor)

            // Tint the idle background with the text colour so it shows on light and dark overlays.
            val normalBg = GradientDrawable().apply {
                setColor((textPrimary and 0x00FFFFFF) or 0x14000000)
                cornerRadius = 8f * d
            }
            val focusedBg = if (isDestructive) {
                GradientDrawable().apply {
                    setColor(0xFFD93030.toInt())
                    cornerRadius = 8f * d
                }
            } else {
                ListItemViewFactory.createFocusedButtonBackground(context).apply {
                    cornerRadius = 8f * d
                }
            }

            background = normalBg
            setOnFocusChangeListener { _, hasFocus ->
                background = if (hasFocus) focusedBg else normalBg
                setTextColor(if (hasFocus) focusedColor else normalColor)
            }
        }
        val params = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            bottomMargin = (8f * d).toInt()
        }
        btn.layoutParams = params
        return btn
    }

    companion object {
        fun show(context: Context, init: WheelDialog.() -> Unit): Dialog {
            val builder = WheelDialog(context)
            builder.init()
            return builder.show()
        }
    }
}
