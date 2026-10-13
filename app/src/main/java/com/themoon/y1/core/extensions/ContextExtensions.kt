package com.themoon.y1.core.extensions

import android.content.Context
import android.os.Vibrator
import android.widget.Toast

fun Context.showToast(message: String, duration: Int = Toast.LENGTH_SHORT) {
    Toast.makeText(this, message, duration).show()
}

fun Context.vibrateFeedback(milliseconds: Long = 25L) {
    try {
        val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        vibrator?.vibrate(milliseconds)
    } catch (_: Exception) {
    }
}
