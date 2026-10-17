package com.themoon.y1.core.extensions

import android.content.Context
import com.themoon.y1.managers.LanguageManager

/**
 * Global localization helper backed by LanguageManager.
 */
fun t(key: String, context: Context? = null): String {
    val manager = LanguageManager.getInstance(context)
    return manager.t(key)
}
