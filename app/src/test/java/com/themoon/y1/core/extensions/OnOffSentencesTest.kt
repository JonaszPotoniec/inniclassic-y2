package com.themoon.y1.core.extensions

import com.themoon.y1.LanguageFiles
import org.junit.Assert.assertFalse
import org.junit.Test

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

    @Test
    fun keysHaveNoOnOffInCapitals() {
        for (key in keys) {
            assertFalse("\"$key\" has ON or OFF in capitals", Regex("\\b(ON|OFF)\\b").containsMatchIn(key))
        }
    }

    @Test
    fun everyLanguageFileTranslatesTheSentences() {
        LanguageFiles.assertEachFileTranslates(keys)
    }
}
