package com.themoon.y1

import org.json.JSONObject
import org.junit.Assert.assertTrue
import java.io.File

/** Reads the language files (the .json files in assets/languages) in unit tests. */
object LanguageFiles {

    /** Each language file, by file name (for example "english.json"), in name order. */
    fun all(): Map<String, JSONObject> {
        val dir = listOf(File("src/main/assets/languages"), File("app/src/main/assets/languages"))
            .first { it.isDirectory }
        val files = dir.listFiles { f -> f.name.endsWith(".json") }!!.sortedBy { it.name }
        assertTrue("no language files in $dir", files.isNotEmpty())
        return files.associate { it.name to JSONObject(it.readText(Charsets.UTF_8)) }
    }

    /** Fails when a language file has no text for one of the [keys]. */
    fun assertEachFileTranslates(keys: List<String>) {
        for ((name, json) in all()) {
            for (key in keys) {
                assertTrue("$name has no \"$key\"", json.optString(key).isNotBlank())
            }
        }
    }
}
