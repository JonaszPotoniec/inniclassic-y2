package com.themoon.y1.managers

import android.content.Context
import android.content.SharedPreferences
import com.themoon.y1.StoragePaths
import com.themoon.y1.io.SafeFiles
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Settings backup: one JSON file with the app preferences, for Settings -> Export / Import Settings.
 *
 * The backup has all keys of the preference files in [FILES] (settings, ratings, favourites, main menu
 * order and visibility, music menu visibility, theme, EQ, FM stations, audiobook and video positions),
 * except secrets and device state ([isExcluded]). Playlists are .m3u8 files in Y1_Playlists, so they are not in it.
 */
object SettingsBackup {
    const val FORMAT = "inniclassic-settings"
    const val VERSION = 1
    const val FILE_NAME = "settings-backup.json"
    const val DIR_NAME = "Inniclassic"

    /** Preference files in the backup. Y1_LASTFM, Y1_RECENTLY_PLAYED and Y1_MIGRATIONS are not in it. */
    val FILES = listOf("AppPrefs", "y1_settings", "Y1_SETTINGS", "Y1_AUDIOBOOK_BOOKMARKS", "Y1_JELLYFIN")

    /** Keys that are not in the backup and that an import does not change. */
    private val EXCLUDED_KEYS = setOf(
        // Last.fm login (AppPrefs)
        "lastfm_username", "lastfm_password",
        // Playback state: it goes with last_playback_*.txt in the app folder, which is not in the backup
        "has_saved_playback_state",
        // Install and one-time state
        "last_lang_version", "last_installed_theme_version", "reboot_to_theme"
    )
    private val EXCLUDED_PREFIXES = listOf("last_played_", "album_art_")
    private val SECRET_WORDS = listOf("password", "token", "session", "secret", "api_key")

    /** Y1_JELLYFIN: only the sync options. Server, login, token, device id and library cache stay out. */
    private val JELLYFIN_KEYS = setOf("download_covers", "remove_deleted", "transcode_video")

    fun isExcluded(file: String, key: String): Boolean {
        if (file == "Y1_JELLYFIN") return key !in JELLYFIN_KEYS
        if (key in EXCLUDED_KEYS) return true
        if (EXCLUDED_PREFIXES.any { key.startsWith(it) }) return true
        // Keys with a file path (ratings, positions) can have any word in the path.
        if (key.contains('/')) return false
        val lower = key.lowercase(java.util.Locale.US)
        return SECRET_WORDS.any { lower.contains(it) }
    }

    /** Makes the backup JSON from the preference files (file name -> preferences). */
    fun toJson(prefsByFile: Map<String, SharedPreferences>, appVersion: String = "", createdMs: Long = System.currentTimeMillis()): JSONObject {
        val root = JSONObject()
        root.put("format", FORMAT)
        root.put("version", VERSION)
        root.put("app_version", appVersion)
        root.put("created", createdMs)
        val files = JSONObject()
        for ((fileName, prefs) in prefsByFile) {
            val entries = JSONObject()
            for ((key, value) in prefs.all.toSortedMap()) {
                if (isExcluded(fileName, key)) continue
                val entry = encode(value) ?: continue
                entries.put(key, entry)
            }
            files.put(fileName, entries)
        }
        root.put("prefs", files)
        return root
    }

    private fun encode(value: Any?): JSONObject? {
        val (type, v) = when (value) {
            is Boolean -> "boolean" to value
            is Int -> "int" to value
            is Long -> "long" to value
            is Float -> "float" to value.toDouble()
            is String -> "string" to value
            is Set<*> -> "string_set" to JSONArray(value.filterIsInstance<String>().sorted())
            else -> return null
        }
        return JSONObject().put("type", type).put("value", v)
    }

    class InvalidBackupException(message: String) : Exception(message)

    /** A checked backup: file name -> key -> value (Boolean, Int, Long, Float, String or Set<String>). */
    class Backup(val prefs: Map<String, Map<String, Any>>, val createdMs: Long)

    /** Reads and checks the backup JSON. Throws [InvalidBackupException] for a file that is not a backup. */
    fun parse(text: String): Backup {
        val root = try {
            JSONObject(text)
        } catch (e: Exception) {
            throw InvalidBackupException("Not JSON")
        }
        if (root.optString("format") != FORMAT) throw InvalidBackupException("Not a settings backup")
        val version = root.optInt("version", -1)
        if (version < 1 || version > VERSION) throw InvalidBackupException("Unknown version $version")
        val files = root.optJSONObject("prefs") ?: throw InvalidBackupException("No prefs")
        val result = LinkedHashMap<String, Map<String, Any>>()
        val names = files.keys()
        while (names.hasNext()) {
            val fileName = names.next()
            if (fileName !in FILES) continue
            val entries = files.optJSONObject(fileName) ?: throw InvalidBackupException("Bad file $fileName")
            val values = LinkedHashMap<String, Any>()
            val keys = entries.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                if (isExcluded(fileName, key)) continue
                val entry = entries.optJSONObject(key) ?: throw InvalidBackupException("Bad entry $key")
                values[key] = decode(key, entry)
            }
            result[fileName] = values
        }
        return Backup(result, root.optLong("created", 0L))
    }

    private fun decode(key: String, entry: JSONObject): Any {
        if (!entry.has("value")) throw InvalidBackupException("No value for $key")
        return try {
            when (entry.optString("type")) {
                "boolean" -> entry.get("value") as? Boolean ?: throw InvalidBackupException("Bad boolean $key")
                "int" -> entry.getInt("value")
                "long" -> entry.getLong("value")
                "float" -> entry.getDouble("value").toFloat()
                "string" -> entry.get("value") as? String ?: throw InvalidBackupException("Bad string $key")
                "string_set" -> {
                    val arr = entry.getJSONArray("value")
                    val set = LinkedHashSet<String>()
                    for (i in 0 until arr.length()) set.add(arr.getString(i))
                    set
                }
                else -> throw InvalidBackupException("Unknown type for $key")
            }
        } catch (e: InvalidBackupException) {
            throw e
        } catch (e: Exception) {
            throw InvalidBackupException("Bad value for $key")
        }
    }

    /**
     * Writes the backup into the preference files. In each file of the backup, the keys that the backup can hold
     * get the backup values; other keys of that kind are removed. Excluded keys (logins, playback state) stay.
     */
    fun apply(backup: Backup, prefsByFile: Map<String, SharedPreferences>) {
        for ((fileName, values) in backup.prefs) {
            val prefs = prefsByFile[fileName] ?: continue
            val editor = prefs.edit()
            for (key in prefs.all.keys) {
                if (!isExcluded(fileName, key) && key !in values) editor.remove(key)
            }
            for ((key, value) in values) {
                @Suppress("UNCHECKED_CAST")
                when (value) {
                    is Boolean -> editor.putBoolean(key, value)
                    is Int -> editor.putInt(key, value)
                    is Long -> editor.putLong(key, value)
                    is Float -> editor.putFloat(key, value)
                    is String -> editor.putString(key, value)
                    is Set<*> -> editor.putStringSet(key, value as Set<String>)
                }
            }
            editor.commit()
        }
    }

    fun openPrefs(context: Context): Map<String, SharedPreferences> =
        FILES.associateWith { context.getSharedPreferences(it, Context.MODE_PRIVATE) }

    /** The microSD card when it is there, else the internal storage. */
    fun exportFile(): File {
        val card = File(StoragePaths.SECONDARY_PATH)
        val root = if (StoragePaths.hasSecondaryStorage() && card.canWrite()) card else StoragePaths.getPrimaryRoot()
        return File(File(root, DIR_NAME), FILE_NAME)
    }

    /** The backup to import: the newest file on the microSD card or on the internal storage. */
    fun findImportFile(): File? =
        listOf(File(StoragePaths.SECONDARY_PATH), StoragePaths.getPrimaryRoot())
            .map { File(File(it, DIR_NAME), FILE_NAME) }
            .filter { it.isFile }
            .maxByOrNull { it.lastModified() }

    fun export(context: Context, appVersion: String): File {
        val file = exportFile()
        if (!SafeFiles.writeStringToFile(file, toJson(openPrefs(context), appVersion).toString(2))) {
            throw java.io.IOException("Cannot write ${file.absolutePath}")
        }
        return file
    }

    fun import(context: Context, file: File) {
        val backup = parse(file.readText(Charsets.UTF_8))
        apply(backup, openPrefs(context))
    }
}
