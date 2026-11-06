package com.themoon.y1.migrations

import com.themoon.y1.Prefs
import com.themoon.y1.controllers.menu.MenuOrder

/**
 * Migration 008: the main menu has a new row *Play On* (after *Wi-Fi*). A saved order (Settings → Menus → Main
 * Menu Items) puts an item that it does not have at the end: put Play On after Wi-Fi in it once.
 */
class Migration008PlayOnMenuItem : Migration {

    override val version: Int
        get() = VERSION

    override val id: String
        get() = ID

    override val description: String
        get() = "Put Play On after Wi-Fi in the saved main menu order"

    @Throws(Exception::class)
    override fun migrate(context: MigrationContext) {
        val prefs = context.settingsPrefs ?: return
        // An order still in the old keys (one for each theme) becomes the one order here: the current theme's first
        val theme = prefs.getString(Prefs.THEME_NAME.key, null) ?: Prefs.CURRENT_THEME_NAME.get(prefs)
        val order = prefs.getString(MenuOrder.KEY, null) ?: MenuOrder.fromThemeOrders(prefs.all, theme) ?: return
        val updated = withPlayOn(order)
        if (updated != order || !prefs.contains(MenuOrder.KEY)) MenuOrder.save(prefs, updated)
    }

    companion object {
        const val VERSION: Int = 8
        const val ID: String = "008_play_on_menu_item"
        const val ITEM = "btn_play_on"

        /** [order] with [ITEM] after btn_wifi (or at the end without btn_wifi); the same order if it has [ITEM]. */
        fun withPlayOn(order: String): String {
            val ids = order.split(",").filter { it.isNotEmpty() }.toMutableList()
            if (ids.isEmpty() || ITEM in ids) return order
            val wifi = ids.indexOf("btn_wifi")
            if (wifi >= 0) ids.add(wifi + 1, ITEM) else ids.add(ITEM)
            return ids.joinToString(",")
        }
    }
}
