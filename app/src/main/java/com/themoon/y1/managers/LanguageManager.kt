package com.themoon.y1.managers

import android.content.Context
import com.themoon.y1.MainActivity
import com.themoon.y1.StoragePaths
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.util.ArrayList
import java.util.HashMap

/**
 * Manages translation dictionaries loaded from custom JSON language packs or assets.
 */
class LanguageManager private constructor(context: Context) {

    private val context: Context = context.applicationContext
    private val dictionary: HashMap<String, String> = HashMap()
    var availableLangFiles: MutableList<File> = ArrayList()
    var currentLangFileName: String = "English (Default)"

    init {
        loadAvailableLanguages()
    }

    /**
     * Scans storage and bundled assets for language pack files.
     */
    fun loadAvailableLanguages() {
        availableLangFiles.clear()
        val langDir = StoragePaths.getLanguagesDir()
        if (!langDir.exists()) {
            langDir.mkdirs()
        }

        val files = langDir.listFiles()
        if (files != null) {
            for (f in files) {
                if (f.name.lowercase().endsWith(".json")) {
                    availableLangFiles.add(f)
                }
            }
        }

        try {
            val assetLangs = context.assets.list("languages")
            if (assetLangs != null) {
                for (name in assetLangs) {
                    if (name.lowercase().endsWith(".json")) {
                        var alreadyAdded = false
                        for (existing in availableLangFiles) {
                            if (existing.name.equals(name, ignoreCase = true)) {
                                alreadyAdded = true
                                break
                            }
                        }
                        if (!alreadyAdded) {
                            availableLangFiles.add(File(langDir, name))
                        }
                    }
                }
            }
        } catch (ignored: Exception) {}
    }

    /**
     * Loads and activates translation dictionary from given language pack JSON file.
     */
    fun applyLanguage(fileName: String?) {
        dictionary.clear()
        currentLangFileName = fileName ?: "English (Default)"

        if ("English (Default)".equals(currentLangFileName, ignoreCase = true)) {
            return
        }

        var isStream: InputStream? = null
        try {
            val f = File(StoragePaths.getLanguagesDir(), currentLangFileName)
            if (f.exists() && f.length() > 0) {
                isStream = FileInputStream(f)
            } else {
                isStream = context.assets.open("languages/$currentLangFileName")
            }

            if (isStream != null) {
                val baos = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                var count: Int
                while (isStream.read(buffer).also { count = it } != -1) {
                    baos.write(buffer, 0, count)
                }
                isStream.close()

                val jsonStr = baos.toString("UTF-8")
                val json = JSONObject(jsonStr)

                val keys = json.keys()
                while (keys.hasNext()) {
                    val originalText = keys.next()
                    val translatedText = json.getString(originalText)
                    dictionary[originalText] = translatedText
                }
            }
        } catch (e: Exception) {
            try {
                if (currentLangFileName != currentLangFileName.lowercase()) {
                    val fallbackIs = context.assets.open("languages/" + currentLangFileName.lowercase())
                    val baos = ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    var count: Int
                    while (fallbackIs.read(buffer).also { count = it } != -1) {
                        baos.write(buffer, 0, count)
                    }
                    fallbackIs.close()
                    val jsonStr = baos.toString("UTF-8")
                    val json = JSONObject(jsonStr)
                    val keys = json.keys()
                    while (keys.hasNext()) {
                        val originalText = keys.next()
                        dictionary[originalText] = json.getString(originalText)
                    }
                }
            } catch (ignored: Exception) {}
        }
    }

    /**
     * Translates input string using current dictionary.
     */
    fun t(originalText: String?): String {
        if (originalText == null) return ""
        if (dictionary.isEmpty()) return originalText

        val translated = dictionary[originalText]
        if (translated != null) {
            return translated
        }

        val trimmed = originalText.trim()
        val trimmedTranslated = dictionary[trimmed]
        if (trimmedTranslated != null) {
            return originalText.replace(trimmed, trimmedTranslated)
        }

        return originalText
    }

    companion object {
        @Volatile
        private var instance: LanguageManager? = null
        @Synchronized
        fun getInstance(context: Context?): LanguageManager {
            var inst = instance
            if (inst == null) {
                val ctx = context?.applicationContext ?: MainActivity.instance?.applicationContext
                checkNotNull(ctx) { "Context must not be null when initializing LanguageManager" }
                inst = LanguageManager(ctx)
                instance = inst
            }
            return inst!!
        }
    }
}
