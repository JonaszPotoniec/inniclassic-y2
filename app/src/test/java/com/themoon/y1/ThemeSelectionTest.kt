package com.themoon.y1

import android.content.SharedPreferences
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** [ThemeManager.selectTheme] and the current theme after a new load of the theme folder. */
class ThemeSelectionTest {

    @Rule
    @JvmField
    val tempFolder = TemporaryFolder()

    companion object {
        @BeforeClass
        @JvmStatic
        fun setUpClass() {
            // Typeface.DEFAULT is null in android.jar stubs; ThemeData needs a value
            ThemeManagerParityTest.setUpClass()
        }
    }

    @Before
    fun setUp() {
        ThemeManager.availableThemes.clear()
        ThemeManager.setThemeIndex(0)
    }

    @After
    fun tearDown() {
        ThemeManager.availableThemes.clear()
        ThemeManager.setThemeIndex(0)
    }

    private fun writeTheme(root: File, folder: String, name: String, mainMenu: String = "") {
        val dir = File(root, folder).apply { mkdirs() }
        val menu = if (mainMenu.isEmpty()) "" else """, "main_menu": [$mainMenu]"""
        File(dir, "config.json").writeText("""{ "name": "$name"$menu }""")
    }

    @Test
    fun testCurrentThemeIsFoundAgainByNameAfterALoad() {
        val root = tempFolder.newFolder("themes")
        writeTheme(root, "light", "Classic Light")
        writeTheme(root, "dark", "Classic Dark")
        ThemeManager.loadThemesFromStorage(root)
        ThemeManager.setThemeIndex(ThemeManager.findThemeIndexByName("Classic Dark"))

        // A new theme that sorts before "Classic Dark" moves it to a higher index
        writeTheme(root, "aqua", "Aqua")
        ThemeManager.loadThemesFromStorage(root)

        assertEquals("Classic Dark", ThemeManager.getCurrentTheme().name)
    }

    @Test
    fun testSelectThemeSavesTheThemeAndItsWidgets() {
        val root = tempFolder.newFolder("themes")
        writeTheme(root, "light", "Classic Light")
        writeTheme(root, "clock", "Clock Theme", """{ "id": "c", "type": "widget_clock" }""")
        ThemeManager.loadThemesFromStorage(root)
        val prefs = FakePrefs()
        val generation = ThemeManager.themeGeneration

        assertTrue(ThemeManager.selectTheme(prefs, "Clock Theme"))

        assertEquals("Clock Theme", ThemeManager.getCurrentTheme().name)
        assertEquals("Clock Theme", prefs.getString("app_theme_name", null))
        assertEquals("Clock Theme", prefs.getString("current_theme_name", null))
        assertEquals(ThemeManager.getCurrentThemeIndex(), prefs.getInt("app_theme_index", -1))
        assertTrue(prefs.getBoolean("widget_clock", false))
        assertFalse(prefs.getBoolean("widget_battery", true))
        assertEquals(generation + 1, ThemeManager.themeGeneration)
    }

    @Test
    fun testSelectThemeIsFalseForTheCurrentThemeAndForAMissingTheme() {
        val root = tempFolder.newFolder("themes")
        writeTheme(root, "light", "Classic Light")
        writeTheme(root, "dark", "Classic Dark")
        ThemeManager.loadThemesFromStorage(root)
        val prefs = FakePrefs()
        assertTrue(ThemeManager.selectTheme(prefs, "Classic Dark"))
        val generation = ThemeManager.themeGeneration

        assertFalse(ThemeManager.selectTheme(prefs, "Classic Dark"))
        assertFalse(ThemeManager.selectTheme(prefs, "Not Installed"))
        assertEquals("Classic Dark", ThemeManager.getCurrentTheme().name)
        assertEquals(generation, ThemeManager.themeGeneration)
    }

    /** In-memory SharedPreferences (android.jar has only stubs). */
    private class FakePrefs : SharedPreferences {
        val values = HashMap<String, Any?>()

        override fun getAll(): MutableMap<String, *> = HashMap(values)
        override fun getString(key: String, defValue: String?): String? = values[key] as? String ?: defValue
        override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? = defValues
        override fun getInt(key: String, defValue: Int): Int = values[key] as? Int ?: defValue
        override fun getLong(key: String, defValue: Long): Long = values[key] as? Long ?: defValue
        override fun getFloat(key: String, defValue: Float): Float = values[key] as? Float ?: defValue
        override fun getBoolean(key: String, defValue: Boolean): Boolean = values[key] as? Boolean ?: defValue
        override fun contains(key: String): Boolean = values.containsKey(key)
        override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}

        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            val pending = HashMap<String, Any?>()
            override fun putString(key: String, value: String?) = apply { pending[key] = value }
            override fun putStringSet(key: String, values: MutableSet<String>?) = apply { pending[key] = values }
            override fun putInt(key: String, value: Int) = apply { pending[key] = value }
            override fun putLong(key: String, value: Long) = apply { pending[key] = value }
            override fun putFloat(key: String, value: Float) = apply { pending[key] = value }
            override fun putBoolean(key: String, value: Boolean) = apply { pending[key] = value }
            override fun remove(key: String) = apply { pending[key] = null }
            override fun clear() = apply { values.clear() }
            override fun commit(): Boolean {
                apply()
                return true
            }
            override fun apply() {
                for ((k, v) in pending) if (v == null) values.remove(k) else values[k] = v
                pending.clear()
            }
        }
    }
}
