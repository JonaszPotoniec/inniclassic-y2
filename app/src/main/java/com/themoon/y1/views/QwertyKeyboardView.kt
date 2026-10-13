package com.themoon.y1.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.PathParser
import com.themoon.y1.ThemeManager

/**
 * Standard QWERTY On-Screen Keyboard for InniClassic (480x360 reference).
 * Supports arrow navigation, linear wheel wrapping, center-click select,
 * center-hold special characters (Polish diacritics), Shift, and Symbols modes.
 */
class QwertyKeyboardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {

    interface OnKeyboardActionListener {
        fun onText(text: String)
        fun onDelete()
        fun onClear()
        fun onSubmit()
        fun onSpecialChar(specialChar: String, replaceLast: Boolean)
    }

    private var isShiftActive = false
    private var isSymbolsActive = false
    private var currentRow = 1 // Default focus on 'q'
    private var currentCol = 0
    private var rememberedCol10 = 0
    private var submitActionLabel = "Connect"

    private lateinit var keyViews: Array<Array<KeyView>>
    private lateinit var rowLayouts: Array<LinearLayout>
    private var actionListener: OnKeyboardActionListener? = null

    init {
        initKeyboard()
    }

    fun setOnKeyboardActionListener(listener: OnKeyboardActionListener?) {
        this.actionListener = listener
    }

    fun setSubmitActionLabel(label: String) {
        this.submitActionLabel = label
        updateKeyLabels()
    }

    fun resetState() {
        isShiftActive = false
        isSymbolsActive = false
        currentRow = 1
        currentCol = 0
        rememberedCol10 = 0
        updateKeyLabels()
        updateKeyFocus()
    }

    private fun initKeyboard() {
        orientation = VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL

        val density = resources.displayMetrics.density
        val rowHeight = (34f * density).toInt()
        val rowMargin = (2f * density).toInt()

        keyViews = Array(5) { emptyArray() }
        rowLayouts = Array(5) { LinearLayout(context) }

        for (r in 0 until 5) {
            val rowLayout = LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER
                val rowLp = LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, rowHeight).apply {
                    setMargins(0, rowMargin, 0, rowMargin)
                }
                layoutParams = rowLp
            }
            rowLayouts[r] = rowLayout

            val colCount = ROWS_LOWER[r].size
            val rowKeyViews = Array(colCount) { c ->
                val rowIdx = r
                val colIdx = c
                val keyView = KeyView(context).apply {
                    gravity = Gravity.CENTER
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                    try {
                        typeface = ThemeManager.getCustomFontBold()
                    } catch (_: Exception) {
                        typeface = Typeface.DEFAULT_BOLD
                    }
                    includeFontPadding = false

                    var weight = 1.0f
                    if (r == 4) {
                        when (c) {
                            0 -> weight = 2.0f
                            1 -> weight = 1.4f
                            2 -> weight = 4.2f
                            3 -> weight = 1.4f
                            4 -> weight = 2.5f
                        }
                    } else if (r == 3) {
                        if (c == 0 || c == colCount - 1) weight = 1.35f
                    }

                    val keyLp = LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, weight).apply {
                        val marginH = (1.5f * density).toInt()
                        setMargins(marginH, 0, marginH, 0)
                    }
                    layoutParams = keyLp

                    setOnClickListener {
                        currentRow = rowIdx
                        currentCol = colIdx
                        rememberedCol10 = if (rowIdx == 4) row4ToCol10(colIdx) else colIdx
                        updateKeyFocus()
                        performCenterClick()
                    }
                }
                rowLayout.addView(keyView)
                keyView
            }

            keyViews[r] = rowKeyViews
            addView(rowLayout)
        }

        updateKeyLabels()
        updateKeyFocus()
    }

    private fun getCurrentKeyMatrix(): Array<Array<String>> {
        return when {
            isSymbolsActive -> ROWS_SYMBOLS
            isShiftActive -> ROWS_UPPER
            else -> ROWS_LOWER
        }
    }

    fun updateKeyLabels() {
        val matrix = getCurrentKeyMatrix()
        for (r in matrix.indices) {
            for (c in matrix[r].indices) {
                val key = matrix[r][c]
                val tv = keyViews.getOrNull(r)?.getOrNull(c) ?: continue
                when {
                    r == 4 && c == 4 -> {
                        tv.setIconType(KeyView.ICON_NONE)
                        tv.text = submitActionLabel
                    }
                    key == KEY_DEL -> {
                        tv.text = ""
                        tv.setIconType(KeyView.ICON_BACKSPACE)
                    }
                    key == KEY_SHIFT -> {
                        tv.text = ""
                        tv.setIconType(if (isShiftActive) KeyView.ICON_SHIFT_FILLED else KeyView.ICON_SHIFT)
                    }
                    else -> {
                        tv.setIconType(KeyView.ICON_NONE)
                        tv.text = key
                    }
                }
            }
        }
    }

    fun updateKeyFocus() {
        val density = resources.displayMetrics.density
        val cornerRadius = (4f * density).toInt()
        val strokeWidthNormal = maxOf(1, (1.0f * density).toInt())
        val strokeWidthFocused = maxOf(2, (2.0f * density).toInt())

        val focusedBg = 0xFF007AFF.toInt()
        val focusedDoneBg = 0xFF34C759.toInt()
        val normalCharBg = 0xFF323236.toInt()
        val normalSpecialBg = 0xFF242426.toInt()
        val normalDoneBg = 0xFF1B5E20.toInt()
        val shiftActiveBg = 0xFF0A84FF.toInt()

        val normalTextColor = 0xFFFFFFFF.toInt()
        val specialTextColor = 0xFFE5E5EA.toInt()

        val matrix = getCurrentKeyMatrix()
        for (r in keyViews.indices) {
            for (c in keyViews[r].indices) {
                val tv = keyViews[r][c]

                val isFocused = (r == currentRow && c == currentCol)
                val isDoneKey = (r == 4 && c == 4)
                val key = if (r < matrix.size && c < matrix[r].size) matrix[r][c] else ""
                val isShiftKey = key == KEY_SHIFT
                val isDelKey = key == KEY_DEL
                val isSpecialKey = isShiftKey || isDelKey || key == KEY_SYM ||
                    key == KEY_ABC || key == KEY_SPACE || key == "@" || key == "_"

                val bg = GradientDrawable().apply {
                    setCornerRadius(cornerRadius.toFloat())
                }

                if (isFocused) {
                    bg.setColor(if (isDoneKey) focusedDoneBg else focusedBg)
                    tv.setTextColor(Color.WHITE)
                    bg.setStroke(strokeWidthFocused, Color.WHITE)
                } else {
                    when {
                        isDoneKey -> {
                            bg.setColor(normalDoneBg)
                            tv.setTextColor(Color.WHITE)
                            bg.setStroke(strokeWidthNormal, 0x884CAF50.toInt())
                        }
                        isShiftKey && isShiftActive -> {
                            bg.setColor(shiftActiveBg)
                            tv.setTextColor(Color.WHITE)
                            bg.setStroke(strokeWidthNormal, Color.WHITE)
                        }
                        isSpecialKey -> {
                            bg.setColor(normalSpecialBg)
                            tv.setTextColor(specialTextColor)
                            bg.setStroke(strokeWidthNormal, 0x33FFFFFF)
                        }
                        else -> {
                            bg.setColor(normalCharBg)
                            tv.setTextColor(normalTextColor)
                            bg.setStroke(strokeWidthNormal, 0x44FFFFFF)
                        }
                    }
                }

                val text = tv.text.toString()
                if (text.length > 3) {
                    tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                } else {
                    tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                }

                tv.background = bg
            }
        }
    }

    private fun col10ToRow4(col10: Int): Int {
        if (col10 <= 1) return 0
        if (col10 == 2) return 1
        if (col10 <= 6) return 2
        if (col10 == 7) return 3
        return 4
    }

    private fun row4ToCol10(col4: Int): Int {
        return when (col4) {
            0 -> 0
            1 -> 2
            2 -> 4
            3 -> 7
            else -> 9
        }
    }

    @JvmOverloads
    fun moveFocus(dx: Int, dy: Int, wrapAcrossRows: Boolean = false) {
        val matrix = getCurrentKeyMatrix()
        val rowCount = matrix.size

        if (dy != 0) {
            val prevRow = currentRow
            currentRow = (currentRow + dy + rowCount) % rowCount

            val prevCols = matrix[prevRow].size
            val newCols = matrix[currentRow].size

            if (prevCols != newCols) {
                if (prevCols == 10 && newCols == 5) {
                    rememberedCol10 = currentCol
                    currentCol = col10ToRow4(currentCol)
                } else if (prevCols == 5 && newCols == 10) {
                    if (col10ToRow4(rememberedCol10) == currentCol) {
                        currentCol = rememberedCol10
                    } else {
                        currentCol = row4ToCol10(currentCol)
                        rememberedCol10 = currentCol
                    }
                } else {
                    currentCol = minOf(currentCol, newCols - 1)
                    if (newCols == 10) {
                        rememberedCol10 = currentCol
                    }
                }
            } else {
                currentCol = minOf(currentCol, newCols - 1)
                if (newCols == 10) {
                    rememberedCol10 = currentCol
                }
            }
        }

        if (dx != 0) {
            val colsInCurrentRow = matrix[currentRow].size
            if (wrapAcrossRows) {
                if (dx > 0) {
                    currentCol++
                    if (currentCol >= colsInCurrentRow) {
                        currentRow = (currentRow + 1) % rowCount
                        currentCol = 0
                    }
                } else {
                    currentCol--
                    if (currentCol < 0) {
                        currentRow = (currentRow - 1 + rowCount) % rowCount
                        currentCol = matrix[currentRow].size - 1
                    }
                }
            } else {
                currentCol = (currentCol + dx + colsInCurrentRow) % colsInCurrentRow
            }

            if (matrix[currentRow].size == 10) {
                rememberedCol10 = currentCol
            } else {
                rememberedCol10 = row4ToCol10(currentCol)
            }
        }

        updateKeyFocus()
    }

    fun performCenterClick() {
        val matrix = getCurrentKeyMatrix()
        if (currentRow !in matrix.indices) return
        if (currentCol !in matrix[currentRow].indices) return

        val key = matrix[currentRow][currentCol]

        if (key == KEY_SHIFT) {
            isShiftActive = !isShiftActive
            updateKeyLabels()
            updateKeyFocus()
            return
        }

        if (key == KEY_SYM) {
            isSymbolsActive = true
            isShiftActive = false
            updateKeyLabels()
            updateKeyFocus()
            return
        }

        if (key == KEY_ABC) {
            isSymbolsActive = false
            updateKeyLabels()
            updateKeyFocus()
            return
        }

        val listener = actionListener ?: return

        if (key == KEY_DEL) {
            listener.onDelete()
            return
        }

        if (currentRow == 4 && currentCol == 4) {
            listener.onSubmit()
            return
        }

        if (key == KEY_SPACE) {
            listener.onText(" ")
            return
        }

        listener.onText(key)

        if (isShiftActive && !isSymbolsActive) {
            isShiftActive = false
            updateKeyLabels()
            updateKeyFocus()
        }
    }

    fun performCenterLongPress(currentInputText: String?) {
        val matrix = getCurrentKeyMatrix()
        if (currentRow !in matrix.indices) return
        if (currentCol !in matrix[currentRow].indices) return

        val key = matrix[currentRow][currentCol]
        val listener = actionListener ?: return

        if (key == KEY_DEL) {
            listener.onClear()
            return
        }

        if (key == KEY_SHIFT || key == KEY_SYM || key == KEY_ABC) {
            performCenterClick()
            return
        }

        if (key.equals("z", ignoreCase = true)) {
            val isUpper = isShiftActive || key == "Z"
            val ż = if (isUpper) "Ż" else "ż"
            val ź = if (isUpper) "Ź" else "ź"

            if (currentInputText != null && (currentInputText.endsWith(ż) || currentInputText.endsWith("z") || currentInputText.endsWith("Z"))) {
                if (currentInputText.endsWith(ż)) {
                    listener.onSpecialChar(ź, true)
                } else {
                    listener.onSpecialChar(ż, true)
                }
            } else if (currentInputText != null && currentInputText.endsWith(ź)) {
                listener.onSpecialChar(ż, true)
            } else {
                listener.onSpecialChar(ż, false)
            }
            return
        }

        val special = SPECIAL_CHAR_MAP[key]
        if (special != null) {
            val replaceLast = currentInputText != null && currentInputText.endsWith(key)
            listener.onSpecialChar(special, replaceLast)
        } else {
            performCenterClick()
        }
    }

    class KeyView(context: Context) : TextView(context) {
        private var iconType = ICON_NONE
        private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG)

        init {
            initIcons()
        }

        fun setIconType(type: Int) {
            if (this.iconType != type) {
                this.iconType = type
                invalidate()
            }
        }

        fun getIconType(): Int = iconType

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (iconType == ICON_NONE) return

            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0f || h <= 0f) return

            val density = resources.displayMetrics.density
            val targetSize = minOf(minOf(w * 0.55f, h * 0.58f), 20f * density)
            val scale = targetSize / 24f

            iconPaint.color = currentTextColor

            canvas.save()
            canvas.translate(w / 2f, h / 2f)
            canvas.scale(scale, scale)
            canvas.translate(-12f, -12f)

            val strokeInPixels = maxOf(1.8f * density, 2.0f * scale)
            val strokeInDesign = strokeInPixels / scale

            when (iconType) {
                ICON_BACKSPACE -> {
                    iconPaint.style = Paint.Style.STROKE
                    iconPaint.strokeWidth = strokeInDesign
                    iconPaint.strokeCap = Paint.Cap.ROUND
                    iconPaint.strokeJoin = Paint.Join.ROUND
                    pathBackspace?.let { canvas.drawPath(it, iconPaint) }
                }
                ICON_SHIFT -> {
                    iconPaint.style = Paint.Style.STROKE
                    iconPaint.strokeWidth = strokeInDesign
                    iconPaint.strokeCap = Paint.Cap.ROUND
                    iconPaint.strokeJoin = Paint.Join.ROUND
                    pathShift?.let { canvas.drawPath(it, iconPaint) }
                }
                ICON_SHIFT_FILLED -> {
                    iconPaint.style = Paint.Style.FILL
                    if (pathShiftFilled != null) {
                        pathShiftFilled?.let { canvas.drawPath(it, iconPaint) }
                    } else if (pathShift != null) {
                        iconPaint.style = Paint.Style.FILL_AND_STROKE
                        iconPaint.strokeWidth = strokeInDesign
                        iconPaint.strokeCap = Paint.Cap.ROUND
                        iconPaint.strokeJoin = Paint.Join.ROUND
                        pathShift?.let { canvas.drawPath(it, iconPaint) }
                    }
                }
            }

            canvas.restore()
        }

        companion object {
            const val ICON_NONE = 0
            const val ICON_SHIFT = 1
            const val ICON_SHIFT_FILLED = 2
            const val ICON_BACKSPACE = 3

            private var pathBackspace: Path? = null
            private var pathShift: Path? = null
            private var pathShiftFilled: Path? = null

            @Synchronized
            private fun initIcons() {
                if (pathBackspace != null) return
                try {
                    val svgBackspace = "M20 6a1 1 0 0 1 1 1v10a1 1 0 0 1 -1 1h-11l-5 -5a1.5 1.5 0 0 1 0 -2l5 -5l11 0 M12 10l4 4m0 -4l-4 4"
                    val svgShift = "M9 20v-8h-3.586a1 1 0 0 1 -.707 -1.707l6.586 -6.586a1 1 0 0 1 1.414 0l6.586 6.586a1 1 0 0 1 -.707 1.707h-3.586v8a1 1 0 0 1 -1 1h-4a1 1 0 0 1 -1 -1"
                    val svgShiftFilled = "M10.586 3l-6.586 6.586a2 2 0 0 0 -.434 2.18l.068 .145a2 2 0 0 0 1.78 1.089h2.586v7a2 2 0 0 0 2 2h4l.15 -.005a2 2 0 0 0 1.85 -1.995l-.001 -7h2.587a2 2 0 0 0 1.414 -3.414l-6.586 -6.586a2 2 0 0 0 -2.828 0z"

                    pathBackspace = PathParser.createPathFromPathData(svgBackspace)
                    pathShift = PathParser.createPathFromPathData(svgShift)
                    pathShiftFilled = PathParser.createPathFromPathData(svgShiftFilled)
                } catch (_: Throwable) {
                    pathBackspace = createFallbackBackspace()
                    pathShift = createFallbackShift()
                    pathShiftFilled = pathShift
                }
            }

            private fun createFallbackBackspace(): Path {
                return Path().apply {
                    moveTo(20f, 6f)
                    quadTo(21f, 6f, 21f, 7f)
                    lineTo(21f, 17f)
                    quadTo(21f, 18f, 20f, 18f)
                    lineTo(9f, 18f)
                    lineTo(4f, 13f)
                    quadTo(2.9f, 12f, 4f, 11f)
                    lineTo(9f, 6f)
                    lineTo(20f, 6f)
                    close()
                    moveTo(12f, 10f)
                    lineTo(16f, 14f)
                    moveTo(16f, 10f)
                    lineTo(12f, 14f)
                }
            }

            private fun createFallbackShift(): Path {
                return Path().apply {
                    moveTo(9f, 20f)
                    lineTo(9f, 12f)
                    lineTo(5.414f, 12f)
                    quadTo(4.4f, 11.6f, 4.707f, 10.293f)
                    lineTo(11.293f, 3.707f)
                    quadTo(12f, 3f, 12.707f, 3.707f)
                    lineTo(19.293f, 10.293f)
                    quadTo(19.6f, 11.6f, 18.586f, 12f)
                    lineTo(15f, 12f)
                    lineTo(15f, 20f)
                    quadTo(15f, 21f, 14f, 21f)
                    lineTo(10f, 21f)
                    quadTo(9f, 21f, 9f, 20f)
                    close()
                }
            }
        }
    }

    companion object {
        private const val KEY_SHIFT = "⇧"
        private const val KEY_DEL = "⌫"
        private const val KEY_SYM = "?123"
        private const val KEY_ABC = "ABC"
        private const val KEY_SPACE = "SPACE"

        private val ROWS_LOWER = arrayOf(
            arrayOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0"),
            arrayOf("q", "w", "e", "r", "t", "y", "u", "i", "o", "p"),
            arrayOf("a", "s", "d", "f", "g", "h", "j", "k", "l", "-"),
            arrayOf(KEY_SHIFT, "z", "x", "c", "v", "b", "n", "m", ".", KEY_DEL),
            arrayOf(KEY_SYM, "@", KEY_SPACE, "_", "DONE")
        )

        private val ROWS_UPPER = arrayOf(
            arrayOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0"),
            arrayOf("Q", "W", "E", "R", "T", "Y", "U", "I", "O", "P"),
            arrayOf("A", "S", "D", "F", "G", "H", "J", "K", "L", "-"),
            arrayOf(KEY_SHIFT, "Z", "X", "C", "V", "B", "N", "M", ".", KEY_DEL),
            arrayOf(KEY_SYM, "@", KEY_SPACE, "_", "DONE")
        )

        private val ROWS_SYMBOLS = arrayOf(
            arrayOf("!", "@", "#", "$", "%", "^", "&", "*", "(", ")"),
            arrayOf("~", "`", "+", "=", "[", "]", "{", "}", "\\", "|"),
            arrayOf(";", ":", "'", "\"", "<", ">", ",", ".", "/", "?"),
            arrayOf(KEY_ABC, "-", "_", "$", "€", "£", "¥", "§", "!", KEY_DEL),
            arrayOf(KEY_ABC, "@", KEY_SPACE, ".", "DONE")
        )

        private val SPECIAL_CHAR_MAP = mapOf(
            "e" to "ę", "E" to "Ę",
            "o" to "ó", "O" to "Ó",
            "s" to "ś", "S" to "Ś",
            "a" to "ą", "A" to "Ą",
            "c" to "ć", "C" to "Ć",
            "l" to "ł", "L" to "Ł",
            "n" to "ń", "N" to "Ń",
            "z" to "ż", "Z" to "Ż",
            "x" to "ź", "X" to "Ź",
            "u" to "ü", "U" to "Ü",
            "i" to "í", "I" to "Í",
            "y" to "ÿ", "Y" to "Ÿ",
            "$" to "€", "!" to "¡", "?" to "¿"
        )
    }
}
