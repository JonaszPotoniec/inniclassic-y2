package com.themoon.y1.core.extensions

import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Sentences about on/off states use sentence case, like Apple text ("Wi-Fi is off"), not "Wi-Fi is OFF".
 * Each language file must translate them.
 */
class OnOffSentencesTest {

    private val keys = listOf(
        "Wi-Fi is off",
        "Bluetooth is off",
        "Turning Wi-Fi on…",
        "Turning Wi-Fi off…",
        "Turn on Wi-Fi first",
        "Off (press Center or Play to turn on)"
    )

    private fun languageFiles(): List<File> {
        val dir = listOf(File("src/main/assets/languages"), File("app/src/main/assets/languages"))
            .first { it.isDirectory }
        val files = dir.listFiles { f -> f.name.endsWith(".json") }!!.sortedBy { it.name }
        assertTrue("no language files in $dir", files.isNotEmpty())
        return files
    }

    @Test
    fun keysHaveNoOnOffInCapitals() {
        for (key in keys) {
            assertFalse("\"$key\" has ON or OFF in capitals", Regex("\\b(ON|OFF)\\b").containsMatchIn(key))
        }
    }

    @Test
    fun everyLanguageFileTranslatesTheSentences() {
        for (file in languageFiles()) {
            val json = JSONObject(file.readText(Charsets.UTF_8))
            for (key in keys) {
                assertTrue("${file.name} has no \"$key\"", json.optString(key).isNotBlank())
            }
        }
    }
}
