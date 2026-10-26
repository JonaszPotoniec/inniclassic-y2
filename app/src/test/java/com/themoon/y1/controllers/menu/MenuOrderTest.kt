package com.themoon.y1.controllers.menu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MenuOrderTest {
    private data class Item(val id: String, val themeIndex: Int)

    private fun sorted(order: String, vararg items: Item): List<String> {
        val list = items.toMutableList()
        MenuOrder.sort(list, order, { it.id }, { it.themeIndex })
        return list.map { it.id }
    }

    @Test
    fun noOrderKeepsTheThemeOrder() {
        assertEquals(listOf("a", "b", "c"), sorted("", Item("c", 2), Item("a", 0), Item("b", 1)))
    }

    @Test
    fun orderedItemsComeFirstAndOthersFollowInThemeOrder() {
        assertEquals(listOf("c", "a", "b", "d"), sorted("c,a,", Item("a", 0), Item("b", 1), Item("c", 2), Item("d", 3)))
    }

    @Test
    fun idsThatTheThemeDoesNotHaveAreIgnored() {
        assertEquals(listOf("b", "a"), sorted("x,b,y,a", Item("a", 0), Item("b", 1)))
    }

    @Test
    fun migrationKeepsTheCurrentThemeOrder() {
        val all = mapOf(
            "custom_menu_order_Classic Dark" to "btn_now,btn_music",
            "custom_menu_order_Classic Light" to "btn_music,btn_now",
            "shuffle" to true
        )
        assertEquals("btn_music,btn_now", MenuOrder.fromThemeOrders(all, "Classic Light"))
    }

    @Test
    fun migrationUsesAnotherThemeOrderWhenTheCurrentThemeHasNone() {
        val all = mapOf("custom_menu_order_Classic Dark" to "btn_now,btn_radio", "custom_menu_order_Old" to "")
        assertEquals("btn_now,btn_radio", MenuOrder.fromThemeOrders(all, "Classic Light"))
    }

    @Test
    fun noOldOrderGivesNull() {
        assertNull(MenuOrder.fromThemeOrders(mapOf("custom_menu_order_Classic Dark" to ""), "Classic Light"))
    }
}
