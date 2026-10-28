package com.themoon.y1.migrations

import android.content.Context
import android.content.SharedPreferences

/**
 * Migration 4: the background picker wrote "bg_path" to "y1_settings", but the app reads it from AppPrefs.
 * Moves the value to AppPrefs (when AppPrefs has no value), so a picture that the user chose before shows.
 */
class Migration004MoveMisplacedPrefs : Migration {

    override val version: Int
        get() = VERSION

    override val id: String
        get() = ID

    override val description: String
        get() = "Move bg_path from y1_settings to AppPrefs"

    @Throws(Exception::class)
    override fun migrate(context: MigrationContext) {
        val ctx = context.context ?: return
        val appPrefs = context.settingsPrefs ?: return
        val legacy = ctx.getSharedPreferences(LEGACY_FILE, Context.MODE_PRIVATE)
        moveKeys(legacy, appPrefs) { it in MOVED_KEYS }
    }

    companion object {
        const val VERSION: Int = 4
        const val ID: String = "004_move_misplaced_prefs"
        const val LEGACY_FILE = "y1_settings"
        val MOVED_KEYS = setOf("bg_path")

        /**
         * Moves the keys of [from] that [select] accepts to [to]. A key that [to] already has keeps its value in [to].
         * The keys are removed from [from]. Returns the number of values copied.
         */
        fun moveKeys(from: SharedPreferences, to: SharedPreferences, select: (String) -> Boolean): Int {
            val keys = from.all.filterKeys(select)
            if (keys.isEmpty()) return 0
            val toEditor = to.edit()
            val fromEditor = from.edit()
            var copied = 0
            for ((key, value) in keys) {
                if (!to.contains(key) && putValue(toEditor, key, value)) copied++
                fromEditor.remove(key)
            }
            check(toEditor.commit()) { "Cannot write the moved preferences" }
            fromEditor.commit()
            return copied
        }

        @Suppress("UNCHECKED_CAST")
        internal fun putValue(editor: SharedPreferences.Editor, key: String, value: Any?): Boolean {
            when (value) {
                is String -> editor.putString(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is Float -> editor.putFloat(key, value)
                is Boolean -> editor.putBoolean(key, value)
                is Set<*> -> editor.putStringSet(key, value as Set<String>)
                else -> return false
            }
            return true
        }
    }
}
