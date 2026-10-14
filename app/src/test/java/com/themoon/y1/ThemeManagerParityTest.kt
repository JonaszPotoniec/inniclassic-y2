package com.themoon.y1

import android.graphics.Color
import android.graphics.Typeface
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Parity and regression test suite for [ThemeManager].
 *
 * Verifies that the Kotlin conversion maintains full behavioral parity with the original
 * Java implementation:
 * 1. [ThemeManager.setThemeIndex]: Index bounds clamping, negative index fallback, and large index fallback.
 * 2. [ThemeManager.findThemeIndexByName]: Lookup by exact name, legacy theme rename normalization
 *    ("iPod Classic" -> "Classic Light", "iPod Classic Dark" -> "Classic Dark"), and safe fallback for unknown/null names.
 * 3. [ThemeManager.isClassicTheme] and [ThemeManager.isClassicLightTheme]: Proper classification of classic vs custom themes.
 * 4. Color & Dimension accessors: Verification of valid non-zero text sizes, color integers, and non-negative radii.
 * 5. Theme blacklist filtering: Verification that obsolete themes in OBSOLETE_THEMES are ignored during storage scans.
 */
class ThemeManagerParityTest {

    @Rule
    @JvmField
    val tempFolder = TemporaryFolder()

    companion object {
        @BeforeClass
        @JvmStatic
        fun setUpClass() {
            // Under JVM unit tests with returnDefaultValues = true, static final object references
            // in android.jar (such as Typeface.DEFAULT and Typeface.DEFAULT_BOLD) default to null.
            // Initialize them reflectively using Unsafe so ThemeManager's class initializer and font
            // methods function identically to on-device runtime.
            try {
                val unsafeClass = Class.forName("sun.misc.Unsafe")
                val unsafeField = unsafeClass.getDeclaredField("theUnsafe")
                unsafeField.isAccessible = true
                val unsafe = unsafeField.get(null)

                val ctor = Typeface::class.java.getDeclaredConstructor()
                ctor.isAccessible = true
                val stubTypeface = ctor.newInstance()

                val defaultField = Typeface::class.java.getDeclaredField("DEFAULT")
                val defaultBase = unsafeClass.getMethod("staticFieldBase", java.lang.reflect.Field::class.java)
                    .invoke(unsafe, defaultField)
                val defaultOffset = unsafeClass.getMethod("staticFieldOffset", java.lang.reflect.Field::class.java)
                    .invoke(unsafe, defaultField) as Long
                unsafeClass.getMethod("putObject", Any::class.java, Long::class.javaPrimitiveType, Any::class.java)
                    .invoke(unsafe, defaultBase, defaultOffset, stubTypeface)

                val boldField = Typeface::class.java.getDeclaredField("DEFAULT_BOLD")
                val boldBase = unsafeClass.getMethod("staticFieldBase", java.lang.reflect.Field::class.java)
                    .invoke(unsafe, boldField)
                val boldOffset = unsafeClass.getMethod("staticFieldOffset", java.lang.reflect.Field::class.java)
                    .invoke(unsafe, boldField) as Long
                unsafeClass.getMethod("putObject", Any::class.java, Long::class.javaPrimitiveType, Any::class.java)
                    .invoke(unsafe, boldBase, boldOffset, stubTypeface)
            } catch (e: Throwable) {
                System.err.println("Note: Typeface initialization via reflection: $e")
            }
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

    private fun createStubTheme(
        name: String,
        textPrimary: Int = 0xFFFFFFFF.toInt(),
        textSecondary: Int = 0xFF888888.toInt(),
        statusBarBg: Int = 0xFF111111.toInt(),
        bgOverlay: Int = 0x88000000.toInt(),
        btnNormal: Int = 0x15FFFFFF,
        btnFocused: Int = 0xDDFFFFFF.toInt(),
        btnFocusedText: Int = 0xFF000000.toInt(),
        radius: Int = 15
    ): ThemeManager.ThemeData {
        return ThemeManager.ThemeData(
            folderPath = "/dummy/$name",
            name = name,
            bgImage = "",
            customFont = Typeface.DEFAULT,
            textPrimary = textPrimary,
            textSecondary = textSecondary,
            bgOverlay = bgOverlay,
            statusBarBg = statusBarBg,
            btnNormal = btnNormal,
            btnFocused = btnFocused,
            btnFocusedText = btnFocusedText,
            buttonRadius = radius
        )
    }

    // =========================================================================
    // 1. setThemeIndex(index)
    // =========================================================================

    @Test
    fun testSetThemeIndex_validIndices() {
        ThemeManager.availableThemes.add(createStubTheme("Classic Light"))
        ThemeManager.availableThemes.add(createStubTheme("Classic Dark"))
        ThemeManager.availableThemes.add(createStubTheme("Custom Theme"))

        ThemeManager.setThemeIndex(0)
        assertEquals(0, ThemeManager.getCurrentThemeIndex())
        assertEquals("Classic Light", ThemeManager.getCurrentTheme().name)

        ThemeManager.setThemeIndex(1)
        assertEquals(1, ThemeManager.getCurrentThemeIndex())
        assertEquals("Classic Dark", ThemeManager.getCurrentTheme().name)

        ThemeManager.setThemeIndex(2)
        assertEquals(2, ThemeManager.getCurrentThemeIndex())
        assertEquals("Custom Theme", ThemeManager.getCurrentTheme().name)
    }

    @Test
    fun testSetThemeIndex_negativeIndexFallback() {
        ThemeManager.availableThemes.add(createStubTheme("Classic Light"))
        ThemeManager.availableThemes.add(createStubTheme("Classic Dark"))

        // Set to a valid non-zero index first
        ThemeManager.setThemeIndex(1)
        assertEquals(1, ThemeManager.getCurrentThemeIndex())

        // Negative indices must fall back to 0 without throwing
        ThemeManager.setThemeIndex(-1)
        assertEquals(0, ThemeManager.getCurrentThemeIndex())

        ThemeManager.setThemeIndex(1)
        ThemeManager.setThemeIndex(-100)
        assertEquals(0, ThemeManager.getCurrentThemeIndex())

        ThemeManager.setThemeIndex(1)
        ThemeManager.setThemeIndex(Int.MIN_VALUE)
        assertEquals(0, ThemeManager.getCurrentThemeIndex())
    }

    @Test
    fun testSetThemeIndex_largeIndexFallback() {
        ThemeManager.availableThemes.add(createStubTheme("Classic Light"))
        ThemeManager.availableThemes.add(createStubTheme("Classic Dark"))

        // Available size is 2 (valid indices: 0 and 1)
        ThemeManager.setThemeIndex(1)
        assertEquals(1, ThemeManager.getCurrentThemeIndex())

        // Index equal to size should fall back to 0
        ThemeManager.setThemeIndex(2)
        assertEquals(0, ThemeManager.getCurrentThemeIndex())

        // Index greater than size should fall back to 0
        ThemeManager.setThemeIndex(1)
        ThemeManager.setThemeIndex(5)
        assertEquals(0, ThemeManager.getCurrentThemeIndex())

        ThemeManager.setThemeIndex(1)
        ThemeManager.setThemeIndex(Int.MAX_VALUE)
        assertEquals(0, ThemeManager.getCurrentThemeIndex())
    }

    @Test
    fun testSetThemeIndex_emptyAvailableThemesSafe() {
        ThemeManager.availableThemes.clear()

        // With no themes in list, any index should safely default to 0
        ThemeManager.setThemeIndex(0)
        assertEquals(0, ThemeManager.getCurrentThemeIndex())

        ThemeManager.setThemeIndex(-1)
        assertEquals(0, ThemeManager.getCurrentThemeIndex())

        ThemeManager.setThemeIndex(99)
        assertEquals(0, ThemeManager.getCurrentThemeIndex())

        // getCurrentTheme() should return DEFAULT_FALLBACK_THEME ("Classic Light")
        assertNotNull(ThemeManager.getCurrentTheme())
        assertEquals("Classic Light", ThemeManager.getCurrentTheme().name)
    }

    // =========================================================================
    // 2. findThemeIndexByName(name)
    // =========================================================================

    @Test
    fun testFindThemeIndexByName_findsValidIndex() {
        ThemeManager.availableThemes.add(createStubTheme("Classic Light"))
        ThemeManager.availableThemes.add(createStubTheme("Classic Dark"))
        ThemeManager.availableThemes.add(createStubTheme("Modern Minimal"))

        assertEquals(0, ThemeManager.findThemeIndexByName("Classic Light"))
        assertEquals(1, ThemeManager.findThemeIndexByName("Classic Dark"))
        assertEquals(2, ThemeManager.findThemeIndexByName("Modern Minimal"))
    }

    @Test
    fun testFindThemeIndexByName_legacyIpodClassicNormalization() {
        // In legacy releases, the theme was named "iPod Classic". It was renamed to "Classic Light".
        // findThemeIndexByName("iPod Classic") MUST normalize to "Classic Light".
        ThemeManager.availableThemes.add(createStubTheme("Classic Light"))
        ThemeManager.availableThemes.add(createStubTheme("Classic Dark"))

        val index = ThemeManager.findThemeIndexByName("iPod Classic")
        assertEquals(
            "Legacy name 'iPod Classic' must resolve to 'Classic Light' at index 0",
            0,
            index
        )
        assertEquals("Classic Light", ThemeManager.availableThemes[index].name)
    }

    @Test
    fun testFindThemeIndexByName_legacyIpodClassicDarkNormalization() {
        // Legacy releases used "iPod Classic Dark" and "iPod Classic (Dark)".
        // Both MUST normalize to "Classic Dark".
        ThemeManager.availableThemes.add(createStubTheme("Classic Light"))
        ThemeManager.availableThemes.add(createStubTheme("Classic Dark"))

        assertEquals(1, ThemeManager.findThemeIndexByName("iPod Classic Dark"))
        assertEquals(1, ThemeManager.findThemeIndexByName("iPod Classic (Dark)"))
    }

    @Test
    fun testFindThemeIndexByName_unknownThemeNameReturnsFallbackWithoutCrash() {
        ThemeManager.availableThemes.add(createStubTheme("Classic Light"))
        ThemeManager.availableThemes.add(createStubTheme("Classic Dark"))

        val unknownIndex = ThemeManager.findThemeIndexByName("Unknown Theme 404")
        assertTrue("Unknown theme should return -1 or fallback", unknownIndex == -1 || unknownIndex == 0)

        val emptyIndex = ThemeManager.findThemeIndexByName("")
        assertTrue("Empty theme name should return -1 or fallback", emptyIndex == -1 || emptyIndex == 0)
    }

    @Test
    fun testFindThemeIndexByName_nullSafe() {
        ThemeManager.availableThemes.add(createStubTheme("Classic Light"))

        val result = ThemeManager.findThemeIndexByName(null)
        assertEquals(-1, result)
    }

    // =========================================================================
    // 3. isClassicTheme() and isClassicLightTheme()
    // =========================================================================

    @Test
    fun testIsClassicTheme_classicLight() {
        ThemeManager.availableThemes.add(createStubTheme("Classic Light"))
        ThemeManager.setThemeIndex(0)

        assertTrue("Classic Light must be recognized as classic theme", ThemeManager.isClassicTheme())
        assertTrue("Classic Light must be recognized as classic light theme", ThemeManager.isClassicLightTheme())
    }

    @Test
    fun testIsClassicTheme_classicDark() {
        ThemeManager.availableThemes.add(createStubTheme("Classic Dark"))
        ThemeManager.setThemeIndex(0)

        assertTrue("Classic Dark must be recognized as classic theme", ThemeManager.isClassicTheme())
        assertFalse("Classic Dark is NOT a classic light theme", ThemeManager.isClassicLightTheme())
    }

    @Test
    fun testIsClassicTheme_legacyNames() {
        // Direct support for legacy theme objects if loaded with old names
        ThemeManager.availableThemes.add(createStubTheme("iPod Classic"))
        ThemeManager.availableThemes.add(createStubTheme("iPod Classic Dark"))
        ThemeManager.availableThemes.add(createStubTheme("iPod Classic (Dark)"))

        ThemeManager.setThemeIndex(0) // iPod Classic
        assertTrue(ThemeManager.isClassicTheme())
        assertTrue(ThemeManager.isClassicLightTheme())

        ThemeManager.setThemeIndex(1) // iPod Classic Dark
        assertTrue(ThemeManager.isClassicTheme())
        assertFalse(ThemeManager.isClassicLightTheme())

        ThemeManager.setThemeIndex(2) // iPod Classic (Dark)
        assertTrue(ThemeManager.isClassicTheme())
        assertFalse(ThemeManager.isClassicLightTheme())
    }

    @Test
    fun testIsClassicTheme_customNonClassicTheme() {
        ThemeManager.availableThemes.add(createStubTheme("Neon Cyberpunk"))
        ThemeManager.setThemeIndex(0)

        assertFalse("Custom themes should not be classic themes", ThemeManager.isClassicTheme())
        assertFalse("Custom themes should not be classic light themes", ThemeManager.isClassicLightTheme())
    }

    @Test
    fun testIsClassicTheme_fallbackThemeWhenEmpty() {
        ThemeManager.availableThemes.clear()
        // When availableThemes is empty, getCurrentTheme() returns DEFAULT_FALLBACK_THEME ("Classic Light")
        assertTrue(ThemeManager.isClassicTheme())
        assertTrue(ThemeManager.isClassicLightTheme())
    }

    // =========================================================================
    // 4. Color and Dimension Accessors
    // =========================================================================

    @Test
    fun testGetListTextSize_reasonableSpValues() {
        ThemeManager.availableThemes.add(createStubTheme("Classic Light"))
        ThemeManager.availableThemes.add(createStubTheme("Custom Theme"))

        // Classic theme uses 21sp
        ThemeManager.setThemeIndex(0)
        val classicSize = ThemeManager.getListTextSize()
        assertTrue("Classic list text size should be positive and reasonable", classicSize in 10f..40f)
        assertEquals(21f, classicSize, 0.001f)

        // Non-classic theme uses 23sp
        ThemeManager.setThemeIndex(1)
        val nonClassicSize = ThemeManager.getListTextSize()
        assertTrue("Non-classic list text size should be positive and reasonable", nonClassicSize in 10f..40f)
        assertEquals(23f, nonClassicSize, 0.001f)
    }

    @Test
    fun testColorAccessors_returnsValidColorIntegers() {
        val theme = createStubTheme(
            name = "Test Palette",
            textPrimary = 0xFF112233.toInt(),
            textSecondary = 0xFF445566.toInt(),
            statusBarBg = 0xFF778899.toInt(),
            bgOverlay = 0x88AABBCC.toInt(),
            btnNormal = 0x22334455,
            btnFocused = 0xFF556677.toInt(),
            btnFocusedText = 0xFFFFFFFF.toInt(),
            radius = 12
        )
        ThemeManager.availableThemes.add(theme)
        ThemeManager.setThemeIndex(0)

        assertEquals(0xFF112233.toInt(), ThemeManager.getTextColorPrimary())
        assertEquals(0xFF445566.toInt(), ThemeManager.getTextColorSecondary())
        assertEquals(0xFF778899.toInt(), ThemeManager.getStatusBarBackgroundColor())
        assertEquals(0x88AABBCC.toInt(), ThemeManager.getOverlayBackgroundColor())
        assertEquals(0x22334455, ThemeManager.getListButtonNormalBg())
        assertEquals(0xFF556677.toInt(), ThemeManager.getListButtonFocusedBg())
        assertEquals(0xFFFFFFFF.toInt(), ThemeManager.getListButtonFocusedTextColor())
    }

    @Test
    fun testGetStatusBarBackgroundColor_emptyThemesFallback() {
        ThemeManager.availableThemes.clear()
        // Line 153: if (availableThemes.isEmpty()) return 0x66000000
        assertEquals(0x66000000, ThemeManager.getStatusBarBackgroundColor())
    }

    @Test
    fun testGetButtonRadius_nonNegative() {
        // Fallback default theme has radius 0
        ThemeManager.availableThemes.clear()
        val defaultRadius = ThemeManager.getButtonRadius()
        assertTrue("Default button radius must be non-negative", defaultRadius >= 0)
        assertTrue("Button radius as float must be non-negative", defaultRadius.toFloat() >= 0f)

        // Custom theme with explicit radius
        ThemeManager.availableThemes.add(createStubTheme("Rounded Theme", radius = 20))
        ThemeManager.setThemeIndex(0)
        val customRadius = ThemeManager.getButtonRadius()
        assertEquals(20, customRadius)
        assertTrue("Custom button radius must be non-negative", customRadius >= 0)
        assertTrue("Custom button radius as float must be non-negative", customRadius.toFloat() >= 0f)
    }

    // =========================================================================
    // 5. Theme Blacklist Filtering (OBSOLETE_THEMES)
    // =========================================================================

    private fun writeThemeConfig(folder: File, jsonContent: String) {
        if (!folder.exists()) folder.mkdirs()
        val configFile = File(folder, "config.json")
        FileOutputStream(configFile).use { fos ->
            fos.write(jsonContent.toByteArray(Charsets.UTF_8))
        }
    }

    @Test
    fun testThemeBlacklist_obsoleteThemesAreFilteredOut() {
        val rootDir = tempFolder.newFolder("themes_blacklist_test")

        val obsoleteThemeNames = listOf(
            "Clean Light",
            "Cyberpunk Neon",
            "cyberpunk_neon_list",
            "Forest Green",
            "forest_green_list",
            "macOS26",
            "Royal Gold",
            "royal_gold_list",
            "Windows 8 Metro",
            "Dark (Default)",
            "default"
        )

        // Create folders with config.json for all obsolete themes
        for (obsName in obsoleteThemeNames) {
            val sub = File(rootDir, obsName.replace(" ", "_"))
            writeThemeConfig(
                sub,
                """
                {
                    "name": "$obsName",
                    "textPrimary": "#FFFFFF",
                    "textSecondary": "#888888"
                }
                """.trimIndent()
            )
        }

        // Add valid themes: one standard, one legacy renamed, one custom
        val validSub1 = File(rootDir, "classic_dark")
        writeThemeConfig(
            validSub1,
            """
            {
                "name": "Classic Dark",
                "textPrimary": "#EEEEEE"
            }
            """.trimIndent()
        )

        val validSub2 = File(rootDir, "legacy_classic")
        writeThemeConfig(
            validSub2,
            """
            {
                "name": "iPod Classic",
                "textPrimary": "#111111"
            }
            """.trimIndent()
        )

        val validSub3 = File(rootDir, "custom_modern")
        writeThemeConfig(
            validSub3,
            """
            {
                "name": "Modern Glass",
                "textPrimary": "#222222"
            }
            """.trimIndent()
        )

        ThemeManager.loadThemesFromStorage(rootDir)

        val loadedNames = ThemeManager.availableThemes.map { it.name }

        // None of the obsolete themes should be loaded
        for (obsName in obsoleteThemeNames) {
            assertFalse(
                "Obsolete theme '$obsName' must not be loaded",
                loadedNames.contains(obsName)
            )
        }

        // Valid themes must be loaded
        assertTrue("Classic Dark must be present", loadedNames.contains("Classic Dark"))
        assertTrue("Legacy 'iPod Classic' must be normalized to 'Classic Light'", loadedNames.contains("Classic Light"))
        assertTrue("Modern Glass must be present", loadedNames.contains("Modern Glass"))

        // Verification of sorting: Classic Light must be first
        assertEquals("Classic Light must be sorted first", "Classic Light", ThemeManager.availableThemes[0].name)
    }

    @Test
    fun testThemeBlacklist_obsoleteFolderNamesFilteredEvenWithDifferentThemeName() {
        val rootDir = tempFolder.newFolder("themes_folder_blacklist_test")

        // Subfolder named "default" is in OBSOLETE_THEMES
        val defaultFolder = File(rootDir, "default")
        writeThemeConfig(
            defaultFolder,
            """
            {
                "name": "Deceptive Name",
                "textPrimary": "#FFFFFF"
            }
            """.trimIndent()
        )

        // Subfolder named "Cyberpunk Neon" is in OBSOLETE_THEMES
        val cyberpunkFolder = File(rootDir, "Cyberpunk Neon")
        writeThemeConfig(
            cyberpunkFolder,
            """
            {
                "name": "Another Deceptive Name",
                "textPrimary": "#FFFFFF"
            }
            """.trimIndent()
        )

        // Valid theme folder
        val validFolder = File(rootDir, "valid_theme")
        writeThemeConfig(
            validFolder,
            """
            {
                "name": "Classic Dark",
                "textPrimary": "#FFFFFF"
            }
            """.trimIndent()
        )

        ThemeManager.loadThemesFromStorage(rootDir)

        val loadedNames = ThemeManager.availableThemes.map { it.name }
        assertFalse(loadedNames.contains("Deceptive Name"))
        assertFalse(loadedNames.contains("Another Deceptive Name"))
        assertTrue(loadedNames.contains("Classic Dark"))
    }

    @Test
    fun testThemeBlacklist_allObsoleteFallsBackToDefaultTheme() {
        val rootDir = tempFolder.newFolder("themes_only_obsolete_test")

        val folder = File(rootDir, "macOS26")
        writeThemeConfig(
            folder,
            """
            {
                "name": "macOS26"
            }
            """.trimIndent()
        )

        ThemeManager.loadThemesFromStorage(rootDir)

        // If no valid themes remain after filtering, ThemeManager falls back to DEFAULT_FALLBACK_THEME
        assertEquals(1, ThemeManager.availableThemes.size)
        assertEquals("Classic Light", ThemeManager.availableThemes[0].name)
    }

    @Test
    fun testThemeZipExtractionAndLoading() {
        val rootDir = tempFolder.newFolder("themes_zip_test")
        val zipFile = File(rootDir, "Classic_Dark_Pack.zip")

        // Create a zip with a theme folder and config.json inside
        ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
            val entry = ZipEntry("config.json")
            zos.putNextEntry(entry)
            val jsonContent = """
            {
                "name": "Classic Dark",
                "textPrimary": "#DEDEDE",
                "textSecondary": "#777777"
            }
            """.trimIndent()
            zos.write(jsonContent.toByteArray(Charsets.UTF_8))
            zos.closeEntry()
        }

        assertTrue("Zip file should exist before scan", zipFile.exists())

        ThemeManager.loadThemesFromStorage(rootDir)

        // The zip must be extracted and deleted
        assertFalse("Zip file should be deleted after extraction", zipFile.exists())

        val darkIndex = ThemeManager.findThemeIndexByName("Classic Dark")
        assertTrue("Theme extracted from zip should be loaded", darkIndex >= 0)
    }
}
