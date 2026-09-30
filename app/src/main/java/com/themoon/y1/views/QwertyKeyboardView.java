package com.themoon.y1.views;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

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
    private String submitActionLabel = "Connect";

    private TextView[][] keyViews;
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
        updateKeyLabels();
        updateKeyFocus();
    }

    private void init() {
        setOrientation(VERTICAL);
        setGravity(Gravity.CENTER_HORIZONTAL);

        float density = getResources().getDisplayMetrics().density;
        int rowHeight = (int) (34 * density);
        int rowMargin = (int) (2 * density);

        keyViews = new TextView[5][];
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
            keyViews[r] = new TextView[colCount];

            for (int c = 0; c < colCount; c++) {
                final int rowIdx = r;
                final int colIdx = c;
                TextView keyView = new TextView(getContext());
                keyView.setGravity(Gravity.CENTER);
                keyView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
                keyView.setTypeface(ThemeManager.getCustomFontBold());
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
                TextView tv = keyViews[r][c];
                if (tv != null) {
                    if (r == 4 && c == 4) {
                        tv.setText(submitActionLabel);
                    } else if (key.equals(KEY_SHIFT)) {
                        tv.setText(isShiftActive ? "▲" : KEY_SHIFT);
                    } else {
                        tv.setText(key);
                    }
                }
            }
        }
    }

    public void updateKeyFocus() {
        float density = getResources().getDisplayMetrics().density;
        int cornerRadius = (int) (5 * density);
        int focusedBg = ThemeManager.getListButtonFocusedBg();
        int focusedTextColor = ThemeManager.getListButtonFocusedTextColor();
        int normalTextColor = Color.WHITE;
        int normalBgColor = 0x33444444;
        int specialKeyBgColor = 0x55222222;

        for (int r = 0; r < keyViews.length; r++) {
            for (int c = 0; c < keyViews[r].length; c++) {
                TextView tv = keyViews[r][c];
                if (tv == null) continue;

                boolean isFocused = (r == currentRow && c == currentCol);
                GradientDrawable bg = new GradientDrawable();
                bg.setCornerRadius(cornerRadius);

                if (isFocused) {
                    bg.setColor(focusedBg);
                    tv.setTextColor(focusedTextColor);
                    bg.setStroke((int) (1.5f * density), Color.WHITE);
                } else {
                    String text = tv.getText().toString();
                    if (text.equals(KEY_SHIFT) || text.equals("▲") || text.equals(KEY_DEL)
                            || text.equals(KEY_SYM) || text.equals(KEY_ABC)
                            || (r == 4 && c == 4)) {
                        bg.setColor(specialKeyBgColor);
                    } else {
                        bg.setColor(normalBgColor);
                    }

                    if (text.equals("▲")) {
                        bg.setColor(0x77007ACC); // Highlight active Shift
                    }

                    tv.setTextColor(normalTextColor);
                    bg.setStroke((int) (0.5f * density), 0x44FFFFFF);
                }

                tv.setBackground(bg);
            }
        }
    }

    /**
     * Move 2D cursor focus across rows and columns.
     * Horizontal wraps rows linearly; vertical snaps to closest column.
     */
    public void moveFocus(int dx, int dy) {
        String[][] matrix = getCurrentKeyMatrix();
        int rowCount = matrix.length;

        if (dy != 0) {
            int prevRow = currentRow;
            currentRow = (currentRow + dy + rowCount) % rowCount;

            // Map column position proportionally when crossing row boundaries
            int prevCols = matrix[prevRow].length;
            int newCols = matrix[currentRow].length;

            if (prevCols != newCols) {
                if (prevRow == 4 && currentRow == 3) {
                    // Moving UP from Row 4 (5 keys) to Row 3 (10 keys)
                    if (currentCol == 0) currentCol = 0;       // SYM -> SHIFT
                    else if (currentCol == 1) currentCol = 2;  // @ -> x
                    else if (currentCol == 2) currentCol = 5;  // SPACE -> b
                    else if (currentCol == 3) currentCol = 7;  // _ -> m
                    else if (currentCol == 4) currentCol = 9;  // DONE -> DEL
                } else if (prevRow == 3 && currentRow == 4) {
                    // Moving DOWN from Row 3 (10 keys) to Row 4 (5 keys)
                    if (currentCol <= 1) currentCol = 0;       // SHIFT, z -> SYM
                    else if (currentCol <= 3) currentCol = 1;  // x, c -> @
                    else if (currentCol <= 6) currentCol = 2;  // v, b, n -> SPACE
                    else if (currentCol <= 7) currentCol = 3;  // m -> _
                    else currentCol = 4;                       // ., DEL -> DONE
                } else {
                    currentCol = Math.min(currentCol, newCols - 1);
                }
            } else {
                currentCol = Math.min(currentCol, newCols - 1);
            }
        }

        if (dx != 0) {
            int colsInCurrentRow = matrix[currentRow].length;
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
}
