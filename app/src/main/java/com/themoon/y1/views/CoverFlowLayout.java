package com.themoon.y1.views;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.widget.FrameLayout;

/**
 * Custom FrameLayout container for Cover Flow.
 * Enables custom child drawing order without mutating the view hierarchy or calling
 * bringToFront() (which triggers expensive requestLayout() passes during animations).
 *
 * Children are dynamically drawn back-to-front based on their distance from the center
 * (largest abs(translationX) drawn first, center card with translationX=0 drawn last on top).
 * Uses zero-allocation in-place sorting to eliminate Dalvik GC pauses during rapid scrolling.
 */
public class CoverFlowLayout extends FrameLayout {

    private int[] mDrawingOrder;
    private float[] mDistances;

    public CoverFlowLayout(Context context) {
        super(context);
        init();
    }

    public CoverFlowLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public CoverFlowLayout(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        init();
    }

    private void init() {
        setChildrenDrawingOrderEnabled(true);
        setClipChildren(false);
        setClipToPadding(false);
    }

    private void updateDrawingOrder(int childCount) {
        if (mDrawingOrder == null || mDrawingOrder.length != childCount) {
            mDrawingOrder = new int[childCount];
            mDistances = new float[childCount];
        }

        for (int i = 0; i < childCount; i++) {
            mDrawingOrder[i] = i;
            View child = getChildAt(i);
            mDistances[i] = (child != null) ? Math.abs(child.getTranslationX()) : 0f;
        }

        // In-place primitive insertion sort: descending order of distance (largest drawn first, center last on top)
        // 9 elements -> completes in < 25 CPU instructions with 0 heap allocations
        for (int i = 1; i < childCount; i++) {
            int keyIdx = mDrawingOrder[i];
            float keyDist = mDistances[keyIdx];
            int j = i - 1;
            while (j >= 0 && mDistances[mDrawingOrder[j]] < keyDist) {
                mDrawingOrder[j + 1] = mDrawingOrder[j];
                j--;
            }
            mDrawingOrder[j + 1] = keyIdx;
        }
    }

    @Override
    protected int getChildDrawingOrder(int childCount, int i) {
        if (i == 0) {
            updateDrawingOrder(childCount);
        }
        if (mDrawingOrder != null && i >= 0 && i < mDrawingOrder.length && mDrawingOrder[i] < childCount) {
            return mDrawingOrder[i];
        }
        return i;
    }
}
