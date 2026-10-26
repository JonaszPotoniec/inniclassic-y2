package com.themoon.y1.controllers.menu

import android.content.SharedPreferences
import com.themoon.y1.ThemeManager

/**
 * Settings → Main Menu Items order. One order for all themes: the user's order must not change when the theme
 * changes (for example with the Auto theme at sunrise). Ids that a theme does not have are ignored; items that
 * are not in the order go after the ordered items, in the theme's own order.
 */
object MenuOrder {
    const val KEY = "custom_menu_order"
    /** Old keys: one order for each theme name. */
    const val THEME_KEY_PREFIX = "custom_menu_order_"

    fun load(prefs: SharedPreferences): String {
        prefs.getString(KEY, null)?.let { return it }
        val old = fromThemeOrders(prefs.all, ThemeManager.getCurrentTheme().name) ?: return ""
        save(prefs, old)
        return old
    }

    fun save(prefs: SharedPreferences, order: String) {
        val editor = prefs.edit().putString(KEY, order)
        for (key in prefs.all.keys) {
            if (key.startsWith(THEME_KEY_PREFIX)) editor.remove(key)
        }
        editor.commit()
    }

    /** The order to keep from the old per-theme keys: the current theme's, else the first one that is not empty. */
    fun fromThemeOrders(all: Map<String, *>, currentTheme: String): String? {
        val current = all[THEME_KEY_PREFIX + currentTheme] as? String
        if (!current.isNullOrEmpty()) return current
        return all.entries
            .filter { it.key.startsWith(THEME_KEY_PREFIX) }
            .sortedBy { it.key }
            .mapNotNull { (it.value as? String)?.takeIf { v -> v.isNotEmpty() } }
            .firstOrNull()
    }

    fun <T> sort(items: MutableList<T>, order: String, id: (T) -> String, themeIndex: (T) -> Int) {
        val ids = order.split(",").filter { it.isNotEmpty() }
        items.sortWith { e1, e2 ->
            val idx1 = ids.indexOf(id(e1))
            val idx2 = ids.indexOf(id(e2))
            if (idx1 != -1 && idx2 != -1) idx1 - idx2
            else if (idx1 != -1) -1
            else if (idx2 != -1) 1
            else themeIndex(e1) - themeIndex(e2)
        }
    }

    fun sort(buttons: MutableList<ThemeManager.MenuElement>, prefs: SharedPreferences) =
        sort(buttons, load(prefs), { it.id }, { it.focusIndex })
}
