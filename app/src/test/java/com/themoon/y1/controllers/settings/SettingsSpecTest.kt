package com.themoon.y1.controllers.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SettingsSpecTest {

    private fun source(name: String): String {
        val dir = "src/main/java/com/themoon/y1/controllers/settings"
        return listOf(File("$dir/$name"), File("app/$dir/$name")).first { it.exists() }.readText()
    }

    /** The groups of the spec in SettingsController ([SettingsController.groups]) and their row labels, in order. */
    private fun specGroups(): List<Pair<String, List<String>>> {
        val code = source("SettingsController.kt")
        val start = code.indexOf("private val groups: List<SettingsGroup>")
        assertTrue("the groups spec is not found", start >= 0)
        val end = code.indexOf("\n    ) } }", start)
        val re = Regex("""SettingsGroup\("([^"]+)"|\b(?:toggle|cycle|choice|action|SettingRow)\("([^"]+)"""")
        val out = ArrayList<Pair<String, MutableList<String>>>()
        for (m in re.findAll(code.substring(start, end))) {
            val group = m.groupValues[1]
            if (group.isNotEmpty()) out += group to ArrayList() else out.last().second += m.groupValues[2]
        }
        return out
    }

    /** The groups, the rows, their order and labels as before the spec (SettingsGroups.GROUPS with RENAMES). */
    @Test
    fun theGroupsAndTheirRowsAreAsBefore() {
        assertEquals(
            listOf(
                "Playback" to listOf("Shuffle", "Repeat", "EQ", "Sound Check", "Volume Limit", "Playback Speed"),
                "Display" to listOf("Brightness", "Backlight"),
                "Now Playing" to listOf("Spectrum", "Total Time", "Lyrics Keep Screen On", "Cover Tilt"),
                "Status Bar" to listOf("Clock", "Battery Icon", "Battery Percentage"),
                "Appearance" to listOf("Theme", "Background", "Toggle Style", "List Edges", "Menu Cover Motion", "Fill Video Screen"),
                "Menus" to listOf("Main Menu Items", "Music Menu Items", "Group Albums"),
                "Controls" to listOf(
                    "Button Sound", "Click Volume", "Button Vibration", "Vibration Strength", "Wheel Loop Scroll",
                    "Screen-Off Control", "Double-Click to Favorite"
                ),
                "Connectivity" to listOf("Bluetooth", "Wi-Fi", "Web Server", "Ask on USB Connect"),
                "Battery" to listOf("Battery"),
                "Library" to listOf("Clear Album Art & Info", "Jellyfin Sync", "Sync Scrobbles to Last.fm", "Last.fm Account"),
                "Date & Time" to listOf("Date & Time"),
                "General" to listOf(
                    "About", "Language", "System Update", "Legal", "Export Settings", "Import Settings", "Reset Settings", "Power"
                ),
            ),
            specGroups()
        )
    }

    @Test
    fun theTopLevelHasAtMostThirteenShortGroupsAndEachRowIsInOneGroup() {
        val groups = specGroups()
        // 13 with Battery (2026-10-31): the top level as Settings of iOS and macOS
        assertTrue(groups.size in 6..13)
        for ((title, rows) in groups) {
            assertTrue("$title: label too long", title.length <= 12)
            assertTrue("$title: ${rows.size} rows", rows.size in 1..10)
        }
        val all = groups.flatMap { it.second }
        assertEquals(all.size, all.toSet().size)
        assertTrue("Auto Fetch Album Art" !in all)
        assertTrue("the Vibration sub-screen is back", "Vibration" !in all)
    }
}
