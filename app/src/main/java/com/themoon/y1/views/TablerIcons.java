package com.themoon.y1.views;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import androidx.core.graphics.PathParser;

public final class TablerIcons {
    private TablerIcons() {}

    /** Tabler Icons outline/usb.svg (MIT) */
    public static final String[] USB = {
        "M10 19a2 2 0 1 0 4 0a2 2 0 1 0 -4 0",   // bottom circle
        "M12 17v-11.5",                             // vertical stem
        "M7 10v3l5 3",                              // left branch
        "M12 14.5l5 -2v-2.5",                      // right branch
        "M16 10h2v-2h-2l0 2",                      // right square
        "M6 9a1 1 0 1 0 2 0a1 1 0 1 0 -2 0",      // left circle
        "M10 5.5h4l-2 -2.5l-2 2.5"                 // top arrow
    };

    /**
     * Renders a 24x24 stroke-2 Tabler icon to an ARGB_8888 bitmap scaled to sizePx.
     */
    public static Bitmap render(String[] paths, int sizePx, int color) {
        Bitmap bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(color);
        paint.setStyle(Paint.Style.STROKE);
        float scale = sizePx / 24f;
        canvas.scale(scale, scale);
        paint.setStrokeWidth(2.0f);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        for (String svgPath : paths) {
            try {
                Path p = PathParser.createPathFromPathData(svgPath);
                if (p != null) {
                    canvas.drawPath(p, paint);
                }
            } catch (Throwable ignored) {}
        }
        return bmp;
    }
}
