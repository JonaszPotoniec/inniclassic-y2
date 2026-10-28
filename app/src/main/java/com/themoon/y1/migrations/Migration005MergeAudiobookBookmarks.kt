package com.themoon.y1.migrations

import android.content.Context
import android.content.SharedPreferences

/**
 * Migration 5: audiobook and podcast positions were saved twice, as POS_<path> in "Y1_AUDIOBOOK_BOOKMARKS"
 * and as book_pos_<path> in AppPrefs. AppPrefs is now the only store. Copies the positions and the last
 * audiobook that only the old file has, then clears the old file.
 */
class Migration005MergeAudiobookBookmarks : Migration {

    override val version: Int
        get() = VERSION

    override val id: String
        get() = ID

    override val description: String
        get() = "Move audiobook bookmarks from Y1_AUDIOBOOK_BOOKMARKS to AppPrefs"

    @Throws(Exception::class)
    override fun migrate(context: MigrationContext) {
        val ctx = context.context ?: return
        val appPrefs = context.settingsPrefs ?: return
        mergeBookmarks(ctx.getSharedPreferences(LEGACY_FILE, Context.MODE_PRIVATE), appPrefs)
    }

    companion object {
        const val VERSION: Int = 5
        const val ID: String = "005_merge_audiobook_bookmarks"
        const val LEGACY_FILE = "Y1_AUDIOBOOK_BOOKMARKS"

        /** Copies POS_<path> to book_pos_<path> and LAST_AUDIOBOOK_PATH to last_played_audiobook_path
         *  (only when AppPrefs has no value), then clears [legacy]. CHAP_<path> had no reader. Returns the values copied. */
        fun mergeBookmarks(legacy: SharedPreferences, appPrefs: SharedPreferences): Int {
            val old = legacy.all
            if (old.isEmpty()) return 0
            val editor = appPrefs.edit()
            var copied = 0
            for ((key, value) in old) {
                val target = when {
                    key.startsWith("POS_") && value is Int && value > 0 -> "book_pos_" + key.removePrefix("POS_")
                    // Podcasts also wrote this key; only an audiobook can be the last audiobook.
                    key == "LAST_AUDIOBOOK_PATH" && value is String && value.contains("/Audiobooks", ignoreCase = true) ->
                        "last_played_audiobook_path"
                    else -> null
                } ?: continue
                if (appPrefs.contains(target)) continue
                when (value) {
                    is Int -> editor.putInt(target, value)
                    is String -> editor.putString(target, value)
                }
                copied++
            }
            check(editor.commit()) { "Cannot write the audiobook bookmarks" }
            legacy.edit().clear().commit()
            return copied
        }
    }
}
