package com.themoon.y1.views

import android.content.Context
import android.util.AttributeSet
import android.widget.FrameLayout

/**
 * Custom FrameLayout container for Cover Flow.
 * Enables custom child drawing order without mutating the view hierarchy or calling
 * bringToFront() (which triggers expensive requestLayout() passes during animations).
 *
 * Children are dynamically drawn back-to-front based on their distance from the center
 * (largest abs(translationX) drawn first, center card with translationX=0 drawn last on top).
 * Uses zero-allocation in-place sorting to eliminate Dalvik GC pauses during rapid scrolling.
 */
class CoverFlowLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private var mDrawingOrder: IntArray? = null
    private var mDistances: FloatArray? = null

    init {
        isChildrenDrawingOrderEnabled = true
        clipChildren = false
        clipToPadding = false
    }

    private fun updateDrawingOrder(childCount: Int) {
        if (mDrawingOrder == null || mDrawingOrder?.size != childCount) {
            mDrawingOrder = IntArray(childCount)
            mDistances = FloatArray(childCount)
        }

        val drawingOrder = mDrawingOrder ?: return
        val distances = mDistances ?: return

        for (i in 0 until childCount) {
            drawingOrder[i] = i
            val child = getChildAt(i)
            distances[i] = if (child != null) Math.abs(child.translationX) else 0f
        }

        // In-place primitive insertion sort: descending order of distance (largest drawn first, center last on top)
        for (i in 1 until childCount) {
            val keyIdx = drawingOrder[i]
            val keyDist = distances[keyIdx]
            var j = i - 1
            while (j >= 0 && distances[drawingOrder[j]] < keyDist) {
                drawingOrder[j + 1] = drawingOrder[j]
                j--
            }
            drawingOrder[j + 1] = keyIdx
        }
    }

    override fun getChildDrawingOrder(childCount: Int, i: Int): Int {
        if (i == 0) {
            updateDrawingOrder(childCount)
        }
        val order = mDrawingOrder
        if (order != null && i in order.indices && order[i] < childCount) {
            return order[i]
        }
        return i
    }
}
