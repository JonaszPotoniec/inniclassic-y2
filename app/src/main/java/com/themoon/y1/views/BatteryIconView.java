package com.themoon.y1.views;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.view.View;

public class BatteryIconView extends View {
    private int level = 100;
    private boolean isCharging = false;
    private int color = Color.WHITE;
    private boolean showPercentage = false;

    private Paint paintFill, paintStroke, paintTerminal, paintText, paintBolt;
    private RectF rectShell, rectFill, rectTerminal;
    private Rect textBounds = new Rect();
    private Path boltPath = new Path();

    public BatteryIconView(Context context) {
        super(context);
        init();
    }

    public void setShowPercentage(boolean showPercentage) {
        if (this.showPercentage != showPercentage) {
            this.showPercentage = showPercentage;
            invalidate();
        }
    }

    public boolean isShowPercentage() {
        return showPercentage;
    }

    private void init() {
        // 내부 알맹이용 페인트
        paintFill = new Paint(Paint.ANTI_ALIAS_FLAG);

        // 바깥 껍데기(테두리)용 페인트
        paintStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        paintStroke.setStyle(Paint.Style.STROKE);
        paintStroke.setStrokeWidth(3f); // 테두리 두께

        // 🚀 [iPod 스타일] (+)극 단자 전용 - 항상 짙은 색으로 꽉 채워 그립니다
        paintTerminal = new Paint(Paint.ANTI_ALIAS_FLAG);
        paintTerminal.setStyle(Paint.Style.FILL);

        // 중앙 숫자용 페인트
        paintText = new Paint(Paint.ANTI_ALIAS_FLAG);
        paintText.setTextAlign(Paint.Align.LEFT);
        try {
            Typeface tf = com.themoon.y1.ThemeManager.getCustomFontBold();
            if (tf != null) {
                paintText.setTypeface(tf);
            } else {
                paintText.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
            }
        } catch (Exception e) {
            paintText.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        }

        // 🚀 [충전 표시] 충전 중일 때 배터리 안에 그려 넣는 작은 번개 아이콘용 페인트
        paintBolt = new Paint(Paint.ANTI_ALIAS_FLAG);
        paintBolt.setStyle(Paint.Style.FILL);
        paintBolt.setColor(0xFF2E7D32); // 초록 알맹이 위에서도 또렷하게 보이는 짙은 색

        rectShell = new RectF();
        rectFill = new RectF();
        rectTerminal = new RectF();
    }

    public void setBatteryLevel(int level, boolean isCharging) {
        this.level = Math.max(0, Math.min(100, level));
        this.isCharging = isCharging;
        invalidate(); // 값이 바뀌면 즉시 화면을 다시 그립니다!
    }

    public void setColor(int color) {
        this.color = color;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        int w = getWidth();
        int h = getHeight();
        if (w == 0 || h == 0) return;

        float terminalWidth = w * 0.08f; // 우측 볼록 튀어나온 단자 길이
        float shellWidth = w - terminalWidth;
        float pillRadius = h * 0.12f; // 🚀 [iPod 스타일] 완전히 둥근 알약(pill) 모양

        // 🚀 1. 상태별 색상 자동 전환 (충전 중: 초록 / 20% 이하: 빨강 / 평소: 초록 그라데이션)
        int currentColor = color;
        if (isCharging) currentColor = Color.parseColor("#4CAF50");
        else if (level <= 20) currentColor = Color.parseColor("#F44336");

        // 🚀 [iPod 스타일] 테두리/단자는 상태와 무관하게 항상 짙은 회색/검정 (반질반질한 아이팟 배터리 느낌)
        paintStroke.setColor(0xFF3C3C43);
        paintTerminal.setColor(0xFF3C3C43);
        paintFill.setColor(currentColor);

        if (level <= 20 && !isCharging) {
            paintFill.setShader(null);
        } else {
            // 🚀 위(밝은 연두) -> 아래(진한 초록) 광택 그라데이션
            String topColor = isCharging ? "#8FE070" : "#D0E9B7";
            String bottomColor = isCharging ? "#4CAF50" : "#7CB342";
            paintFill.setShader(new LinearGradient(0, 0, 0, h,
                    Color.parseColor(topColor), Color.parseColor(bottomColor), Shader.TileMode.CLAMP));
        }

        // 🚀 2. 배터리 바깥 껍데기 그리기
        rectShell.set(2f, 2f, shellWidth - 2f, h - 2f);
        canvas.drawRoundRect(rectShell, pillRadius, pillRadius, paintStroke);

        // 🚀 3. 우측 (+)극 단자 그리기
        float terminalHeight = h * 0.4f;
        rectTerminal.set(shellWidth, (h - terminalHeight) / 2f, w - 2f, (h + terminalHeight) / 2f);
        canvas.drawRoundRect(rectTerminal, 2f, 2f, paintTerminal);

        // 🚀 4. 남은 용량만큼 안쪽 알맹이 차오르게 하기!
        float padX = 4f;
        float padY = 3.5f;
        float maxFillWidth = shellWidth - (padX * 2f); // 100%일 때의 가로 길이
        float currentFillWidth = maxFillWidth * (level / 100f); // 현재 퍼센트만큼 길이 자르기

        // 잔량이 0보다 클 때만 알맹이를 그립니다.
        if (currentFillWidth > 0) {
            rectFill.set(padX, padY, padX + currentFillWidth, h - padY);
            canvas.drawRoundRect(rectFill, Math.max(0f, pillRadius - 1f), Math.max(0f, pillRadius - 1f), paintFill);
        }

        // 🚀 5. 퍼센트 표시 모드 vs 기본 충전 번개 아이콘 표시
        if (showPercentage) {
            try {
                Typeface tf = com.themoon.y1.ThemeManager.getCustomFontBold();
                if (tf != null) paintText.setTypeface(tf);
            } catch (Exception ignored) {}

            String text = String.valueOf(level);
            float baseTextSize = isCharging ? (h * 0.50f) : (h * 0.58f);
            paintText.setTextSize(baseTextSize);

            float maxContentWidth = shellWidth - (padX * 2f);

            if (isCharging) {
                float boltW = h * 0.28f;
                float boltH = h * 0.52f;
                float gap = 2f;

                paintText.getTextBounds(text, 0, text.length(), textBounds);
                float availableForText = maxContentWidth - boltW - gap;
                if (textBounds.width() > availableForText && textBounds.width() > 0) {
                    paintText.setTextSize(paintText.getTextSize() * (availableForText / textBounds.width()));
                    paintText.getTextBounds(text, 0, text.length(), textBounds);
                }

                float totalWidth = boltW + gap + textBounds.width();
                float startX = rectShell.centerX() - (totalWidth / 2f);
                float boltLeft = startX;
                float boltTop = rectShell.centerY() - (boltH / 2f);

                buildBoltPath(boltLeft, boltTop, boltW, boltH);

                float textX = startX + boltW + gap - textBounds.left;
                float textY = rectShell.centerY() - textBounds.exactCenterY();

                // 1. 미충전(빈 배경) 영역에 맞춰 테마 텍스트 색상으로 렌더링
                int emptyColor = color;
                if (color == Color.WHITE || Color.red(color) > 200) {
                    emptyColor = 0xFF1C1C1E;
                }
                paintText.setColor(emptyColor);
                paintBolt.setColor(emptyColor);
                canvas.drawPath(boltPath, paintBolt);
                canvas.drawText(text, textX, textY, paintText);

                // 2. 충전된 알맹이(fill) 영역에 맞춰 클리핑 렌더링
                if (currentFillWidth > 0) {
                    int filledColor = 0xFF1C1C1E;
                    paintText.setColor(filledColor);
                    paintBolt.setColor(filledColor);
                    canvas.save();
                    canvas.clipRect(padX, 0, padX + currentFillWidth, h);
                    canvas.drawPath(boltPath, paintBolt);
                    canvas.drawText(text, textX, textY, paintText);
                    canvas.restore();
                }
            } else {
                float textWidth = paintText.measureText(text);
                if (textWidth > maxContentWidth && textWidth > 0) {
                    paintText.setTextSize(paintText.getTextSize() * (maxContentWidth / textWidth));
                }

                paintText.getTextBounds(text, 0, text.length(), textBounds);
                float textX = rectShell.centerX() - textBounds.exactCenterX();
                float textY = rectShell.centerY() - textBounds.exactCenterY();

                // 1. 미충전(빈 배경) 영역에 맞춰 테마 텍스트 색상으로 기본 텍스트 렌더링
                int emptyTextColor = color;
                if (color == Color.WHITE || Color.red(color) > 200) {
                    emptyTextColor = 0xFF1C1C1E;
                }
                paintText.setColor(emptyTextColor);
                canvas.drawText(text, textX, textY, paintText);

                // 2. 충전된 알맹이(fill) 영역에 맞춰 대비되는 색상으로 클리핑 렌더링
                if (currentFillWidth > 0) {
                    int filledTextColor = (level <= 20) ? Color.WHITE : 0xFF1C1C1E;
                    paintText.setColor(filledTextColor);
                    canvas.save();
                    canvas.clipRect(padX, 0, padX + currentFillWidth, h);
                    canvas.drawText(text, textX, textY, paintText);
                    canvas.restore();
                }
            }
        } else if (isCharging) {
            float boltW = shellWidth * 0.34f;
            float boltH = h * 0.72f;
            float boltLeft = (shellWidth - boltW) / 2f;
            float boltTop = (h - boltH) / 2f;

            buildBoltPath(boltLeft, boltTop, boltW, boltH);

            paintBolt.setColor(0xFF2E7D32);
            canvas.drawPath(boltPath, paintBolt);
        }
    }

    private void buildBoltPath(float boltLeft, float boltTop, float boltW, float boltH) {
        boltPath.reset();
        boltPath.moveTo(boltLeft + boltW * 0.62f, boltTop);
        boltPath.lineTo(boltLeft + boltW * 0.10f, boltTop + boltH * 0.58f);
        boltPath.lineTo(boltLeft + boltW * 0.42f, boltTop + boltH * 0.58f);
        boltPath.lineTo(boltLeft + boltW * 0.38f, boltTop + boltH);
        boltPath.lineTo(boltLeft + boltW * 0.90f, boltTop + boltH * 0.42f);
        boltPath.lineTo(boltLeft + boltW * 0.58f, boltTop + boltH * 0.42f);
        boltPath.close();
    }
}