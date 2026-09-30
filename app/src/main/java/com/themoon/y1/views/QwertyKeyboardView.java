package com.themoon.y1.views;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.drawable.GradientDrawable;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.graphics.PathParser;

import com.themoon.y1.ThemeManager;

import java.util.HashMap;
import java.util.Map;

/**
 * Standard QWERTY On-Screen Keyboard for InniClassic (480x360 reference).
 * Supports arrow navigation, linear wheel wrapping, center-click select,
 * center-hold special characters (Polish diacritics ężśó...), Shift, and Symbols modes.
 */
public class QwertyKeyboardView extends LinearLayout {

    public interface OnKeyboardActionListener {
        void onText(String text);
        void onDelete();
        void onClear();
        void onSubmit();
        void onSpecialChar(String specialChar, boolean replaceLast);
    }

    private static final String KEY_SHIFT = "⇧";
    private static final String KEY_DEL = "⌫";
    private static final String KEY_SYM = "?123";
    private static final String KEY_ABC = "ABC";
    private static final String KEY_SPACE = "SPACE";

    // 5-Row QWERTY Layout (Lowercase)
    private static final String[][] ROWS_LOWER = {
            {"1", "2", "3", "4", "5", "6", "7", "8", "9", "0"},
            {"q", "w", "e", "r", "t", "y", "u", "i", "o", "p"},
            {"a", "s", "d", "f", "g", "h", "j", "k", "l", "-"},
            {KEY_SHIFT, "z", "x", "c", "v", "b", "n", "m", ".", KEY_DEL},
            {KEY_SYM, "@", KEY_SPACE, "_", "DONE"}
    };

    // 5-Row QWERTY Layout (Uppercase)
    private static final String[][] ROWS_UPPER = {
            {"1", "2", "3", "4", "5", "6", "7", "8", "9", "0"},
            {"Q", "W", "E", "R", "T", "Y", "U", "I", "O", "P"},
            {"A", "S", "D", "F", "G", "H", "J", "K", "L", "-"},
            {KEY_SHIFT, "Z", "X", "C", "V", "B", "N", "M", ".", KEY_DEL},
            {KEY_SYM, "@", KEY_SPACE, "_", "DONE"}
    };

    // 5-Row Symbols Layout (Covers all 95 printable ASCII Wi-Fi password characters)
    private static final String[][] ROWS_SYMBOLS = {
            {"!", "@", "#", "$", "%", "^", "&", "*", "(", ")"},
            {"~", "`", "+", "=", "[", "]", "{", "}", "\\", "|"},
            {";", ":", "'", "\"", "<", ">", ",", ".", "/", "?"},
            {KEY_ABC, "-", "_", "$", "€", "£", "¥", "§", "!", KEY_DEL},
            {KEY_ABC, "@", KEY_SPACE, ".", "DONE"}
    };

    // Polish diacritics and special characters mappings
    private static final Map<String, String> SPECIAL_CHAR_MAP = new HashMap<String, String>();
    static {
        SPECIAL_CHAR_MAP.put("e", "ę");
        SPECIAL_CHAR_MAP.put("E", "Ę");
        SPECIAL_CHAR_MAP.put("o", "ó");
        SPECIAL_CHAR_MAP.put("O", "Ó");
        SPECIAL_CHAR_MAP.put("s", "ś");
        SPECIAL_CHAR_MAP.put("S", "Ś");
        SPECIAL_CHAR_MAP.put("a", "ą");
        SPECIAL_CHAR_MAP.put("A", "Ą");
        SPECIAL_CHAR_MAP.put("c", "ć");
        SPECIAL_CHAR_MAP.put("C", "Ć");
        SPECIAL_CHAR_MAP.put("l", "ł");
        SPECIAL_CHAR_MAP.put("L", "Ł");
        SPECIAL_CHAR_MAP.put("n", "ń");
        SPECIAL_CHAR_MAP.put("N", "Ń");
        SPECIAL_CHAR_MAP.put("z", "ż");
        SPECIAL_CHAR_MAP.put("Z", "Ż");
        SPECIAL_CHAR_MAP.put("x", "ź");
        SPECIAL_CHAR_MAP.put("X", "Ź");
        SPECIAL_CHAR_MAP.put("u", "ü");
        SPECIAL_CHAR_MAP.put("U", "Ü");
        SPECIAL_CHAR_MAP.put("i", "í");
        SPECIAL_CHAR_MAP.put("I", "Í");
        SPECIAL_CHAR_MAP.put("y", "ÿ");
        SPECIAL_CHAR_MAP.put("Y", "Ÿ");
        SPECIAL_CHAR_MAP.put("$", "€");
        SPECIAL_CHAR_MAP.put("!", "¡");
        SPECIAL_CHAR_MAP.put("?", "¿");
    }

    private boolean isShiftActive = false;
    private boolean isSymbolsActive = false;
    private int currentRow = 1; // Default focus on 'q'
    private int currentCol = 0;
    private int rememberedCol10 = 0; // Remembers exact 10-column alignment across wide keys
    private String submitActionLabel = "Connect";

    private KeyView[][] keyViews;
    private LinearLayout[] rowLayouts;
    private OnKeyboardActionListener actionListener;

    public QwertyKeyboardView(Context context) {
        super(context);
        init();
    }

    public QwertyKeyboardView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public void setOnKeyboardActionListener(OnKeyboardActionListener listener) {
        this.actionListener = listener;
    }

    public void setSubmitActionLabel(String label) {
        this.submitActionLabel = label;
        updateKeyLabels();
    }

    public void resetState() {
        isShiftActive = false;
        isSymbolsActive = false;
        currentRow = 1;
        currentCol = 0;
        rememberedCol10 = 0;
        updateKeyLabels();
        updateKeyFocus();
    }

    private void init() {
        setOrientation(VERTICAL);
        setGravity(Gravity.CENTER_HORIZONTAL);

        float density = getResources().getDisplayMetrics().density;
        int rowHeight = (int) (34 * density);
        int rowMargin = (int) (2 * density);

        keyViews = new KeyView[5][];
        rowLayouts = new LinearLayout[5];

        for (int r = 0; r < 5; r++) {
            LinearLayout rowLayout = new LinearLayout(getContext());
            rowLayout.setOrientation(HORIZONTAL);
            rowLayout.setGravity(Gravity.CENTER);
            LayoutParams rowLp = new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, rowHeight);
            rowLp.setMargins(0, rowMargin, 0, rowMargin);
            rowLayout.setLayoutParams(rowLp);
            rowLayouts[r] = rowLayout;

            int colCount = ROWS_LOWER[r].length;
            keyViews[r] = new KeyView[colCount];

            for (int c = 0; c < colCount; c++) {
                final int rowIdx = r;
                final int colIdx = c;
                KeyView keyView = new KeyView(getContext());
                keyView.setGravity(Gravity.CENTER);
                keyView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
                try {
                    keyView.setTypeface(ThemeManager.getCustomFontBold());
                } catch (Exception e) {
                    keyView.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
                }
                keyView.setIncludeFontPadding(false);

                // Set key layout weight
                float weight = 1.0f;
                if (r == 4) {
                    if (c == 0) weight = 2.0f; // ?123
                    else if (c == 1) weight = 1.4f; // @
                    else if (c == 2) weight = 4.2f; // SPACE
                    else if (c == 3) weight = 1.4f; // _
                    else if (c == 4) weight = 2.5f; // DONE
                } else if (r == 3) {
                    if (c == 0) weight = 1.35f; // Shift
                    else if (c == colCount - 1) weight = 1.35f; // Del
                }

                LayoutParams keyLp = new LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, weight);
                int marginH = (int) (1.5f * density);
                keyLp.setMargins(marginH, 0, marginH, 0);
                keyView.setLayoutParams(keyLp);

                // Touch support
                keyView.setOnClickListener(new OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        currentRow = rowIdx;
                        currentCol = colIdx;
                        if (rowIdx == 4) {
                            rememberedCol10 = row4ToCol10(colIdx);
                        } else {
                            rememberedCol10 = colIdx;
                        }
                        updateKeyFocus();
                        performCenterClick();
                    }
                });

                keyViews[r][c] = keyView;
                rowLayout.addView(keyView);
            }

            addView(rowLayout);
        }

        updateKeyLabels();
        updateKeyFocus();
    }

    private String[][] getCurrentKeyMatrix() {
        if (isSymbolsActive) {
            return ROWS_SYMBOLS;
        } else if (isShiftActive) {
            return ROWS_UPPER;
        } else {
            return ROWS_LOWER;
        }
    }

    public void updateKeyLabels() {
        String[][] matrix = getCurrentKeyMatrix();
        for (int r = 0; r < matrix.length; r++) {
            for (int c = 0; c < matrix[r].length; c++) {
                String key = matrix[r][c];
                KeyView tv = keyViews[r][c];
                if (tv != null) {
                    if (r == 4 && c == 4) {
                        tv.setIconType(KeyView.ICON_NONE);
                        tv.setText(submitActionLabel);
                    } else if (key.equals(KEY_DEL)) {
                        tv.setText("");
                        tv.setIconType(KeyView.ICON_BACKSPACE);
                    } else if (key.equals(KEY_SHIFT)) {
                        tv.setText("");
                        tv.setIconType(isShiftActive ? KeyView.ICON_SHIFT_FILLED : KeyView.ICON_SHIFT);
                    } else {
                        tv.setIconType(KeyView.ICON_NONE);
                        tv.setText(key);
                    }
                }
            }
        }
    }

    public void updateKeyFocus() {
        float density = getResources().getDisplayMetrics().density;
        int cornerRadius = (int) (4 * density);
        int strokeWidthNormal = Math.max(1, (int) (1.0f * density));
        int strokeWidthFocused = Math.max(2, (int) (2.0f * density));

        int focusedBg = 0xFF007AFF;    // High-contrast vibrant iPod blue
        int focusedDoneBg = 0xFF34C759;// High-contrast vivid iOS green for Connect/Search
        int normalCharBg = 0xFF323236; // Solid charcoal (high contrast against dark backdrop)
        int normalSpecialBg = 0xFF242426; // Solid darker matte for function keys
        int normalDoneBg = 0xFF1B5E20; // Solid dark green for unfocused submit
        int shiftActiveBg = 0xFF0A84FF;// Solid vibrant blue for active shift

        int normalTextColor = 0xFFFFFFFF;
        int specialTextColor = 0xFFE5E5EA;

        String[][] matrix = getCurrentKeyMatrix();
        for (int r = 0; r < keyViews.length; r++) {
            for (int c = 0; c < keyViews[r].length; c++) {
                KeyView tv = keyViews[r][c];
                if (tv == null) continue;

                boolean isFocused = (r == currentRow && c == currentCol);
                boolean isDoneKey = (r == 4 && c == 4);
                String key = (r < matrix.length && c < matrix[r].length) ? matrix[r][c] : "";
                boolean isShiftKey = key.equals(KEY_SHIFT);
                boolean isDelKey = key.equals(KEY_DEL);
                boolean isSpecialKey = isShiftKey || isDelKey
                        || key.equals(KEY_SYM) || key.equals(KEY_ABC) || key.equals(KEY_SPACE)
                        || key.equals("@") || key.equals("_");

                GradientDrawable bg = new GradientDrawable();
                bg.setCornerRadius(cornerRadius);

                if (isFocused) {
                    if (isDoneKey) {
                        bg.setColor(focusedDoneBg);
                    } else {
                        bg.setColor(focusedBg);
                    }
                    tv.setTextColor(Color.WHITE);
                    bg.setStroke(strokeWidthFocused, Color.WHITE);
                } else {
                    if (isDoneKey) {
                        bg.setColor(normalDoneBg);
                        tv.setTextColor(Color.WHITE);
                        bg.setStroke(strokeWidthNormal, 0x884CAF50);
                    } else if (isShiftKey && isShiftActive) {
                        bg.setColor(shiftActiveBg);
                        tv.setTextColor(Color.WHITE);
                        bg.setStroke(strokeWidthNormal, Color.WHITE);
                    } else if (isSpecialKey) {
                        bg.setColor(normalSpecialBg);
                        tv.setTextColor(specialTextColor);
                        bg.setStroke(strokeWidthNormal, 0x33FFFFFF);
                    } else {
                        bg.setColor(normalCharBg);
                        tv.setTextColor(normalTextColor);
                        bg.setStroke(strokeWidthNormal, 0x44FFFFFF);
                    }
                }

                // Adjust text size dynamically so longer words (like "Connect" or "SPACE") don't clip
                String text = tv.getText().toString();
                if (text.length() > 3) {
                    tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
                } else {
                    tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
                }

                tv.setBackground(bg);
            }
        }
    }

    private int col10ToRow4(int col10) {
        if (col10 <= 1) return 0;      // 0, 1 -> SYM (?123)
        if (col10 == 2) return 1;      // 2    -> @
        if (col10 <= 6) return 2;      // 3, 4, 5, 6 -> SPACE (covering keys 4, 5, 6, 7)
        if (col10 == 7) return 3;      // 7    -> _ (covering key 8)
        return 4;                      // 8, 9 -> DONE (covering keys 9, 0)
    }

    private int row4ToCol10(int col4) {
        switch (col4) {
            case 0: return 0;          // SYM -> 1 / SHIFT
            case 1: return 2;          // @   -> 3 / x
            case 2: return 4;          // SPACE -> 5 / v (center column alignment)
            case 3: return 7;          // _   -> 8 / m
            case 4: default: return 9; // DONE -> 0 / DEL
        }
    }

    /**
     * Move 2D cursor focus across rows and columns.
     * Default wrapAcrossRows = false (for button navigation).
     */
    public void moveFocus(int dx, int dy) {
        moveFocus(dx, dy, false);
    }

    /**
     * Move 2D cursor focus across rows and columns.
     * @param dx Horizontal delta (-1 = left, +1 = right)
     * @param dy Vertical delta (-1 = up, +1 = down)
     * @param wrapAcrossRows When true (scroll wheel), crossing row ends moves to next/prev row.
     *                       When false (hardware buttons), crossing row ends wraps within same row.
     */
    public void moveFocus(int dx, int dy, boolean wrapAcrossRows) {
        String[][] matrix = getCurrentKeyMatrix();
        int rowCount = matrix.length;

        if (dy != 0) {
            int prevRow = currentRow;
            currentRow = (currentRow + dy + rowCount) % rowCount;

            int prevCols = matrix[prevRow].length;
            int newCols = matrix[currentRow].length;

            if (prevCols != newCols) {
                if (prevCols == 10 && newCols == 5) {
                    // Entering Row 4 (5 keys) from a 10-key row: remember exact 10-column position
                    rememberedCol10 = currentCol;
                    currentCol = col10ToRow4(currentCol);
                } else if (prevCols == 5 && newCols == 10) {
                    // Exiting Row 4 (5 keys) to a 10-key row: restore original column if focus matches
                    if (col10ToRow4(rememberedCol10) == currentCol) {
                        currentCol = rememberedCol10;
                    } else {
                        currentCol = row4ToCol10(currentCol);
                        rememberedCol10 = currentCol;
                    }
                } else {
                    currentCol = Math.min(currentCol, newCols - 1);
                    if (newCols == 10) {
                        rememberedCol10 = currentCol;
                    }
                }
            } else {
                currentCol = Math.min(currentCol, newCols - 1);
                if (newCols == 10) {
                    rememberedCol10 = currentCol;
                }
            }
        }

        if (dx != 0) {
            int colsInCurrentRow = matrix[currentRow].length;
            if (wrapAcrossRows) {
                // Wheel navigation: linear continuous wrapping across rows
                if (dx > 0) {
                    currentCol++;
                    if (currentCol >= colsInCurrentRow) {
                        currentRow = (currentRow + 1) % rowCount;
                        currentCol = 0;
                    }
                } else {
                    currentCol--;
                    if (currentCol < 0) {
                        currentRow = (currentRow - 1 + rowCount) % rowCount;
                        currentCol = matrix[currentRow].length - 1;
                    }
                }
            } else {
                // Button navigation: wrap within current row only
                currentCol = (currentCol + dx + colsInCurrentRow) % colsInCurrentRow;
            }

            // Keep rememberedCol10 synchronized after horizontal movement
            if (matrix[currentRow].length == 10) {
                rememberedCol10 = currentCol;
            } else {
                rememberedCol10 = row4ToCol10(currentCol);
            }
        }

        updateKeyFocus();
    }

    /**
     * Handle Center button short-click: type regular char or trigger button action.
     */
    public void performCenterClick() {
        String[][] matrix = getCurrentKeyMatrix();
        if (currentRow < 0 || currentRow >= matrix.length) return;
        if (currentCol < 0 || currentCol >= matrix[currentRow].length) return;

        String key = matrix[currentRow][currentCol];

        if (key.equals(KEY_SHIFT)) {
            isShiftActive = !isShiftActive;
            updateKeyLabels();
            updateKeyFocus();
            return;
        }

        if (key.equals(KEY_SYM)) {
            isSymbolsActive = true;
            isShiftActive = false;
            updateKeyLabels();
            updateKeyFocus();
            return;
        }

        if (key.equals(KEY_ABC)) {
            isSymbolsActive = false;
            updateKeyLabels();
            updateKeyFocus();
            return;
        }

        if (actionListener == null) return;

        if (key.equals(KEY_DEL)) {
            actionListener.onDelete();
            return;
        }

        if (currentRow == 4 && currentCol == 4) {
            actionListener.onSubmit();
            return;
        }

        if (key.equals(KEY_SPACE)) {
            actionListener.onText(" ");
            return;
        }

        actionListener.onText(key);

        // Turn off shift after typing a letter if shift was active
        if (isShiftActive && !isSymbolsActive) {
            isShiftActive = false;
            updateKeyLabels();
            updateKeyFocus();
        }
    }

    /**
     * Handle Center button long-press: insert special accented character (ężśó...) or action.
     * @param currentInputText The current text in the password/search input field.
     */
    public void performCenterLongPress(String currentInputText) {
        String[][] matrix = getCurrentKeyMatrix();
        if (currentRow < 0 || currentRow >= matrix.length) return;
        if (currentCol < 0 || currentCol >= matrix[currentRow].length) return;

        String key = matrix[currentRow][currentCol];

        if (actionListener == null) return;

        if (key.equals(KEY_DEL)) {
            actionListener.onClear();
            return;
        }

        if (key.equals(KEY_SHIFT) || key.equals(KEY_SYM) || key.equals(KEY_ABC)) {
            performCenterClick();
            return;
        }

        // Special character handling for Polish 'z' -> 'ż' / 'ź' alternation
        if (key.equalsIgnoreCase("z")) {
            boolean isUpper = isShiftActive || key.equals("Z");
            String ż = isUpper ? "Ż" : "ż";
            String ź = isUpper ? "Ź" : "ź";

            if (currentInputText != null && (currentInputText.endsWith(ż) || currentInputText.endsWith("z") || currentInputText.endsWith("Z"))) {
                // If it already ends with ż, toggle to ź
                if (currentInputText.endsWith(ż)) {
                    actionListener.onSpecialChar(ź, true);
                } else {
                    actionListener.onSpecialChar(ż, true);
                }
            } else if (currentInputText != null && currentInputText.endsWith(ź)) {
                actionListener.onSpecialChar(ż, true);
            } else {
                actionListener.onSpecialChar(ż, false);
            }
            return;
        }

        // Special character mapping for e, o, s, a, c, l, n, x, u, etc.
        String special = SPECIAL_CHAR_MAP.get(key);
        if (special != null) {
            // If the input already ends with the base character, replace it with the accented variant
            boolean replaceLast = currentInputText != null && currentInputText.endsWith(key);
            actionListener.onSpecialChar(special, replaceLast);
        } else {
            // Fallback: regular click action
            performCenterClick();
        }
    }

    /**
     * Specialized KeyView for QWERTY keyboard.
     * Renders standard Tabler icons (backspace, arrow-big-up) directly via native Canvas Path
     * with anti-aliasing and dynamic color binding, avoiding font glyph missing issues on Android 4.4.
     */
    public static class KeyView extends TextView {
        public static final int ICON_NONE = 0;
        public static final int ICON_SHIFT = 1;
        public static final int ICON_SHIFT_FILLED = 2;
        public static final int ICON_BACKSPACE = 3;

        private static Path pathBackspace;
        private static Path pathShift;
        private static Path pathShiftFilled;

        private int iconType = ICON_NONE;
        private final Paint iconPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

        public KeyView(Context context) {
            super(context);
            initIcons();
        }

        private static synchronized void initIcons() {
            if (pathBackspace != null) return;
            try {
                // Official Tabler Icons SVG path definitions (tabler.io)
                String svgBackspace = "M20 6a1 1 0 0 1 1 1v10a1 1 0 0 1 -1 1h-11l-5 -5a1.5 1.5 0 0 1 0 -2l5 -5l11 0 M12 10l4 4m0 -4l-4 4";
                String svgShift = "M9 20v-8h-3.586a1 1 0 0 1 -.707 -1.707l6.586 -6.586a1 1 0 0 1 1.414 0l6.586 6.586a1 1 0 0 1 -.707 1.707h-3.586v8a1 1 0 0 1 -1 1h-4a1 1 0 0 1 -1 -1";
                String svgShiftFilled = "M10.586 3l-6.586 6.586a2 2 0 0 0 -.434 2.18l.068 .145a2 2 0 0 0 1.78 1.089h2.586v7a2 2 0 0 0 2 2h4l.15 -.005a2 2 0 0 0 1.85 -1.995l-.001 -7h2.587a2 2 0 0 0 1.414 -3.414l-6.586 -6.586a2 2 0 0 0 -2.828 0z";

                pathBackspace = PathParser.createPathFromPathData(svgBackspace);
                pathShift = PathParser.createPathFromPathData(svgShift);
                pathShiftFilled = PathParser.createPathFromPathData(svgShiftFilled);
            } catch (Throwable t) {
                // Fallback manual paths if PathParser is ever unavailable
                pathBackspace = createFallbackBackspace();
                pathShift = createFallbackShift();
                pathShiftFilled = pathShift;
            }
        }

        private static Path createFallbackBackspace() {
            Path p = new Path();
            p.moveTo(20f, 6f);
            p.quadTo(21f, 6f, 21f, 7f);
            p.lineTo(21f, 17f);
            p.quadTo(21f, 18f, 20f, 18f);
            p.lineTo(9f, 18f);
            p.lineTo(4f, 13f);
            p.quadTo(2.9f, 12f, 4f, 11f);
            p.lineTo(9f, 6f);
            p.lineTo(20f, 6f);
            p.close();
            p.moveTo(12f, 10f);
            p.lineTo(16f, 14f);
            p.moveTo(16f, 10f);
            p.lineTo(12f, 14f);
            return p;
        }

        private static Path createFallbackShift() {
            Path p = new Path();
            p.moveTo(9f, 20f);
            p.lineTo(9f, 12f);
            p.lineTo(5.414f, 12f);
            p.quadTo(4.4f, 11.6f, 4.707f, 10.293f);
            p.lineTo(11.293f, 3.707f);
            p.quadTo(12f, 3f, 12.707f, 3.707f);
            p.lineTo(19.293f, 10.293f);
            p.quadTo(19.6f, 11.6f, 18.586f, 12f);
            p.lineTo(15f, 12f);
            p.lineTo(15f, 20f);
            p.quadTo(15f, 21f, 14f, 21f);
            p.lineTo(10f, 21f);
            p.quadTo(9f, 21f, 9f, 20f);
            p.close();
            return p;
        }

        public void setIconType(int type) {
            if (this.iconType != type) {
                this.iconType = type;
                invalidate();
            }
        }

        public int getIconType() {
            return iconType;
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            if (iconType == ICON_NONE) return;

            int w = getWidth();
            int h = getHeight();
            if (w <= 0 || h <= 0) return;

            float density = getResources().getDisplayMetrics().density;
            // Target icon height around 18-20dp, scaled to 24x24 Tabler viewport
            float targetSize = Math.min(Math.min(w * 0.55f, h * 0.58f), 20f * density);
            float scale = targetSize / 24f;

            iconPaint.setColor(getCurrentTextColor());

            canvas.save();
            canvas.translate(w / 2f, h / 2f);
            canvas.scale(scale, scale);
            canvas.translate(-12f, -12f);

            // Ensure crisp minimum stroke width on low-DPI displays
            float strokeInPixels = Math.max(1.8f * density, 2.0f * scale);
            float strokeInDesign = strokeInPixels / scale;

            if (iconType == ICON_BACKSPACE) {
                iconPaint.setStyle(Paint.Style.STROKE);
                iconPaint.setStrokeWidth(strokeInDesign);
                iconPaint.setStrokeCap(Paint.Cap.ROUND);
                iconPaint.setStrokeJoin(Paint.Join.ROUND);
                if (pathBackspace != null) {
                    canvas.drawPath(pathBackspace, iconPaint);
                }
            } else if (iconType == ICON_SHIFT) {
                iconPaint.setStyle(Paint.Style.STROKE);
                iconPaint.setStrokeWidth(strokeInDesign);
                iconPaint.setStrokeCap(Paint.Cap.ROUND);
                iconPaint.setStrokeJoin(Paint.Join.ROUND);
                if (pathShift != null) {
                    canvas.drawPath(pathShift, iconPaint);
                }
            } else if (iconType == ICON_SHIFT_FILLED) {
                iconPaint.setStyle(Paint.Style.FILL);
                if (pathShiftFilled != null) {
                    canvas.drawPath(pathShiftFilled, iconPaint);
                } else if (pathShift != null) {
                    iconPaint.setStyle(Paint.Style.FILL_AND_STROKE);
                    iconPaint.setStrokeWidth(strokeInDesign);
                    iconPaint.setStrokeCap(Paint.Cap.ROUND);
                    iconPaint.setStrokeJoin(Paint.Join.ROUND);
                    canvas.drawPath(pathShift, iconPaint);
                }
            }

            canvas.restore();
        }
    }
}
