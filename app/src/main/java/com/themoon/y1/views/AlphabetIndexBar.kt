package com.themoon.y1.views

import android.content.Context
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import com.themoon.y1.ThemeManager
import java.util.LinkedHashMap

/**
 * Quick jump alphabet / initial character bar displayed at the top of scrollable lists.
 */
class AlphabetIndexBar(
    private val context: Context,
    private val hzIndexScroll: HorizontalScrollView,
    private val layoutIndexContainer: LinearLayout,
    private val listView: ListView?,
    private val clickFeedback: () -> Unit
) {

    fun build(items: List<String>) {
        if (items.isEmpty()) {
            hzIndexScroll.visibility = View.GONE
            return
        }

        layoutIndexContainer.removeAllViews()
        val indexMap = LinkedHashMap<String, Int>()

        for (i in items.indices) {
            val title = items[i]
            val firstChar = getInitialChar(title).toString()
            if (!indexMap.containsKey(firstChar)) {
                indexMap[firstChar] = i
            }
        }

        val density = context.resources.displayMetrics.density
        val themeColor = ThemeManager.getListButtonFocusedBg() or 0xFF000000.toInt()

        for ((key, value) in indexMap) {
            val tvAlpha = TextView(context).apply {
                text = key
                textSize = 18f
                setTextColor(ThemeManager.getTextColorPrimary())
                gravity = Gravity.CENTER
                typeface = ThemeManager.getCustomFontBold()
                isFocusable = true
                isClickable = true
                setPadding((15 * density).toInt(), 0, (15 * density).toInt(), 0)

                setOnFocusChangeListener { _, hasFocus ->
                    if (hasFocus) {
                        setTextColor(ThemeManager.getListButtonFocusedTextColor())
                        setBackgroundColor(themeColor)
                        hzIndexScroll.requestChildFocus(layoutIndexContainer, this)
                        listView?.setSelectionFromTop(value, 0)
                    } else {
                        setTextColor(ThemeManager.getTextColorPrimary())
                        setBackgroundColor(0x00000000)
                    }
                }

                setOnKeyListener { _, keyCode, event ->
                    if (event.action == KeyEvent.ACTION_DOWN) {
                        val childCount = layoutIndexContainer.childCount
                        val currentIndex = layoutIndexContainer.indexOfChild(this)

                        if (keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == 19 || keyCode == 21) {
                            val prevIndex = (currentIndex - 1 + childCount) % childCount
                            layoutIndexContainer.getChildAt(prevIndex)?.requestFocus()
                            clickFeedback()
                            return@setOnKeyListener true
                        } else if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN || keyCode == 20 || keyCode == 22) {
                            val nextIndex = (currentIndex + 1) % childCount
                            layoutIndexContainer.getChildAt(nextIndex)?.requestFocus()
                            clickFeedback()
                            return@setOnKeyListener true
                        }
                    }
                    false
                }

                setOnClickListener {
                    clickFeedback()
                    hzIndexScroll.visibility = View.GONE

                    listView?.postDelayed({
                        val visiblePos = value - (listView.firstVisiblePosition)
                        if (visiblePos in 0 until listView.childCount) {
                            listView.getChildAt(visiblePos)?.requestFocus()
                        } else if (listView.childCount > 0) {
                            listView.getChildAt(0)?.requestFocus()
                        }
                    }, 50)
                }
            }
            layoutIndexContainer.addView(tvAlpha)
        }
    }

    companion object {
        private val KOREAN_INITIALS = charArrayOf(
            'ㄱ', 'ㄱ', 'ㄴ', 'ㄷ', 'ㄷ', 'ㄹ', 'ㅁ', 'ㅂ', 'ㅂ', 'ㅅ', 'ㅅ', 'ㅇ', 'ㅈ', 'ㅈ', 'ㅊ', 'ㅋ', 'ㅌ', 'ㅍ', 'ㅎ'
        )

        fun getInitialChar(text: String?): Char {
            if (text.isNullOrEmpty()) return '#'

            var clean = text.replace("📁 ", "").replace("👤 ", "")
                .replace("💿 ", "").replace("🎵 ", "").replace("📦 [INSTALL] ", "").trim().uppercase()
            while (clean.isNotEmpty() && !clean[0].isLetterOrDigit() && clean[0] !in '\uAC00'..'\uD7A3') {
                clean = clean.substring(1).trim()
            }
            if (clean.isEmpty()) return '#'

            val c = clean[0]

            // English (A-Z)
            if (c in 'A'..'Z') {
                return c
            }

            // Korean (Hangul syllables)
            if (c in '\uAC00'..'\uD7A3') {
                val base = c.code - 0xAC00
                val initialIdx = base / (21 * 28)
                if (initialIdx in KOREAN_INITIALS.indices) {
                    return KOREAN_INITIALS[initialIdx]
                }
            }

            return '#'
        }
    }
}
