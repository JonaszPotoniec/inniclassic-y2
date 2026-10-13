package com.themoon.y1.core.extensions

import android.content.Context
import android.view.View
import java.util.concurrent.atomic.AtomicInteger

private val nextGeneratedId = AtomicInteger(1)

fun View.visible() {
    visibility = View.VISIBLE
}

fun View.gone() {
    visibility = View.GONE
}

fun View.invisible() {
    visibility = View.INVISIBLE
}

fun View.isVisible(): Boolean = visibility == View.VISIBLE

fun View.dpToPx(dp: Float): Int =
    (dp * resources.displayMetrics.density + 0.5f).toInt()

fun View.dpToPx(dp: Int): Int =
    (dp * resources.displayMetrics.density + 0.5f).toInt()

fun Context.dpToPx(dp: Float): Int =
    (dp * resources.displayMetrics.density + 0.5f).toInt()

fun Context.dpToPx(dp: Int): Int =
    (dp * resources.displayMetrics.density + 0.5f).toInt()

/**
 * Dalvik/KitKat pre-API 17 compatible dynamic View ID generator.
 */
fun generateDynamicViewId(): Int {
    while (true) {
        val result = nextGeneratedId.get()
        var newValue = result + 1
        if (newValue > 0x00FFFFFF) newValue = 1
        if (nextGeneratedId.compareAndSet(result, newValue)) {
            return result
        }
    }
}
