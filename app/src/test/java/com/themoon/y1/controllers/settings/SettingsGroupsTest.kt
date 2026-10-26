package com.themoon.y1.controllers.settings

import com.themoon.y1.controllers.settings.SettingsGroups.Entry
import com.themoon.y1.controllers.settings.SettingsGroups.GROUPS
import com.themoon.y1.controllers.settings.SettingsGroups.TOP
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SettingsGroupsTest {

    private val dir = "src/main/java/com/themoon/y1/controllers/settings"

    private fun source(name: String): String? =
        listOf(File("$dir/$name"), File("app/$dir/$name")).firstOrNull { it.exists() }?.readText()

    /** The body of the function [name] of [code]: from its declaration to the next function. */
    private fun body(code: String, name: String): String {
        val start = code.indexOf("fun $name(")
        assertTrue("fun $name not found", start >= 0)
        val end = code.indexOf("\n    fun ", start + 1).let { a ->
            val b = code.indexOf("\n    private fun ", start + 1)
            listOf(a, b).filter { it > 0 }.minOrNull() ?: code.length
        }
        return code.substring(start, end)
    }

    /** The labels of the rows that buildSettingsUI makes (with the Status Bar rows, and the backup rows if this build has them). */
    private fun rowLabels(): List<String> {
        val controller = source("SettingsController.kt") ?: error("SettingsController.kt not found")
        val body = body(controller, "buildSettingsUI") + body(controller, "addStatusBarRows") +
            body(controller, "addClockRow") + (source("SettingsBackupUi.kt") ?: "")
        val re = Regex("""createSettingRow\(\s*localize\("([^"]+)"\)""")
        return re.findAll(body).map { it.groupValues[1] }.toList()
    }

    /** Each row that this build makes is in one group; the groups can also list rows of other branches. */
    @Test
    fun everyRowIsInExactlyOneGroup() {
        val labels = rowLabels()
        assertTrue("found only ${labels.size} rows", labels.size >= 30)
        for (label in labels) {
            val groups = GROUPS.filter { label in it.rows }.map { it.title }
            assertEquals("'$label' must be in exactly one group, is in $groups", 1, groups.size)
        }
    }

    @Test
    fun noRowIsTwiceInTheModel() {
        val all = GROUPS.flatMap { it.rows }
        assertEquals(all.size, all.toSet().size)
        assertEquals(GROUPS.size, GROUPS.map { it.title }.toSet().size)
    }

    @Test
    fun topLevelHasAtMostElevenShortEntries() {
        assertTrue(GROUPS.size in 6..11)
        assertEquals(
            listOf(
                "Playback", "Display", "Status Bar", "Appearance", "Menus", "Controls", "Connectivity", "Library",
                "Date & Time", "General", "Developer"
            ),
            GROUPS.map { it.title }
        )
        for (g in GROUPS) {
            assertTrue("${g.title}: label too long", g.title.length <= 12)
            assertTrue("${g.title}: ${g.rows.size} rows", g.rows.size <= 10)
            if (g.direct) assertEquals("${g.title}: a direct group has one row", 1, g.rows.size)
        }
        for ((old, new) in SettingsGroups.RENAMES) {
            assertTrue("rename of a row that is in no group: $old", SettingsGroups.groupOf(old) != TOP)
            assertTrue("$new is not shorter than $old", new.length < old.length)
        }
    }

    @Test
    fun groupOrderIsMostUsedFirst() {
        assertEquals(listOf("Shuffle Mode", "Repeat Mode", "Equalizer & Audio Effects"), GROUPS[0].rows.take(3))
        assertEquals("About", GROUPS.first { it.title == "General" }.rows.first())
        assertEquals("Power Off", GROUPS.first { it.title == "General" }.rows.last())
        assertEquals(listOf("Wireless ADB"), GROUPS.last().rows)
    }

    @Test
    fun dateAndTimeIsARowOfTheTopLevelBeforeGeneral() {
        val labels = rowLabels()
        val (_, top) = SettingsGroups.page(labels, TOP)
        val dateTime = top.indexOf(Entry.Row("Date & Time"))
        val general = top.indexOf(Entry.GroupRow(GROUPS.indexOfFirst { it.title == "General" }))
        assertTrue("Date & Time at $dateTime, General at $general", dateTime in 0 until general)
    }

    @Test
    fun statusBarHasTheClockAndTheBatteryRows() {
        val statusBar = GROUPS.first { it.title == "Status Bar" }
        assertEquals(listOf("Time in Title", "Battery", "Battery Percentage"), statusBar.rows)
        assertEquals("Clock", SettingsGroups.RENAMES["Time in Title"])
        assertTrue(rowLabels().containsAll(statusBar.rows))
    }

    @Test
    fun controlsHasTheVibrationRowsAndNoVibrationScreen() {
        val controls = GROUPS.first { it.title == "Controls" }
        assertEquals(
            listOf(
                "Button Sound", "Click Volume", "Button Vibration", "Vibration Strength", "Wheel Loop Scroll",
                "Screen-Off Control", "Double-Click to Favorite", "Keyboard Layout"
            ),
            controls.rows
        )
        assertTrue(rowLabels().containsAll(listOf("Button Vibration", "Vibration Strength")))
        val controller = source("SettingsController.kt") ?: error("SettingsController.kt not found")
        assertTrue("Vibration Strength is not grey while Button Vibration is off",
            "StatusBarSettings.setRowEnabled(btnVibrationStrength, isVibrationEnabled)" in body(controller, "buildSettingsUI"))
        assertTrue("the Vibration sub-screen is back", "buildVibrationSettingsUI" !in controller)
        assertTrue("Vibration" !in GROUPS.flatMap { it.rows })
    }

    @Test
    fun libraryHasNoAutoFetchRow() {
        assertEquals(TOP, SettingsGroups.groupOf("Auto Fetch Album Art"))
        assertTrue("Auto Fetch Album Art" !in rowLabels())
    }

    @Test
    fun topLevelShowsOnlyGroupsWithRowsThenLooseRows() {
        val (shown, entries) = SettingsGroups.page(listOf("Power Off", "New Row", "Shuffle Mode", "Theme"), TOP)
        assertEquals(TOP, shown)
        val playback = GROUPS.indexOfFirst { it.title == "Playback" }
        val appearance = GROUPS.indexOfFirst { it.title == "Appearance" }
        val general = GROUPS.indexOfFirst { it.title == "General" }
        assertEquals(
            listOf(Entry.GroupRow(playback), Entry.GroupRow(appearance), Entry.GroupRow(general), Entry.Row("New Row")),
            entries
        )
    }

    @Test
    fun groupPageUsesTheModelOrder() {
        val playback = GROUPS.indexOfFirst { it.title == "Playback" }
        val present = listOf("Volume Limit", "Theme", "Shuffle Mode", "Playback Speed", "Repeat Mode")
        val (shown, entries) = SettingsGroups.page(present, playback)
        assertEquals(playback, shown)
        assertEquals(
            listOf("Shuffle Mode", "Repeat Mode", "Volume Limit", "Playback Speed").map { Entry.Row(it) },
            entries
        )
    }

    @Test
    fun openGroupWithNoRowsInThisBuildShowsTheTopLevel() {
        val developer = GROUPS.indexOfFirst { it.title == "Developer" }
        val (shown, entries) = SettingsGroups.page(listOf("Theme"), developer)
        assertEquals(TOP, shown)
        assertEquals(listOf(Entry.GroupRow(GROUPS.indexOfFirst { it.title == "Appearance" })), entries)
    }

    @Test
    fun everyBuildRowIsReachable() {
        val labels = rowLabels()
        val (_, top) = SettingsGroups.page(labels, TOP)
        val reachable = top.flatMap { e ->
            when (e) {
                is Entry.Row -> listOf(e.label)
                // a direct group shows its row on the top level
                is Entry.GroupRow -> SettingsGroups.page(labels, e.group).second.map { (it as Entry.Row).label }
            }
        }
        assertEquals(labels.toSet(), reachable.toSet())
        assertEquals(labels.size, reachable.size)
    }
    @Test
    fun subScreenTitleIsTheRowThatOpenedIt() {
        val t = SettingsGroups::subScreenTitles
        // Appearance → Background, then Back
        val bg = t(emptyList(), 0, 1, "Background", "Settings")
        assertEquals(listOf("Background"), bg)
        assertEquals(emptyList<String>(), t(bg, 1, 0, null, "Settings"))
        // General → Date & Time → Year, Back to Date & Time, Time Format rebuilds Date & Time at the same depth
        val dt = t(emptyList(), 0, 1, "Date & Time", "Settings")
        val year = t(dt, 1, 2, "Year", "Settings")
        assertEquals(listOf("Date & Time", "Year"), year)
        val back = t(year, 2, 1, null, "Settings")
        assertEquals(listOf("Date & Time"), back)
        assertEquals(listOf("Date & Time"), t(back, 1, 1, null, "Settings"))
        // Theme list from the main menu: no row opened it
        assertEquals(listOf("Settings"), t(emptyList(), 0, 1, null, "Settings"))
        // A new sub-screen replaces an old title at its depth
        assertEquals(listOf("Language"), t(listOf("Theme"), 0, 1, "Language", "Settings"))
    }

    @Test
    fun everySubScreenSetsTheDepthBeforeItRemovesTheRows() {
        // onDepthChanged reads the focused row (the opener), so it must run before removeAllViews()
        val controller = source("SettingsController.kt") ?: error("SettingsController.kt not found")
        val builders = Regex("""fun (build\w+UI)\([^)]*\) \{""").findAll(controller).map { it.groupValues[1] to it.range.last }.toList()
        assertTrue(builders.size >= 10)
        for ((name, start) in builders) {
            val depth = controller.indexOf("currentSettingsDepth = ", start)
            val remove = controller.indexOf("containerSettingsItems.removeAllViews()", start)
            assertTrue("$name: no depth or no removeAllViews", depth > 0 && remove > 0)
            assertTrue("$name sets the depth after removeAllViews()", depth < remove)
        }
    }
}
