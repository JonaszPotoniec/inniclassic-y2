package com.themoon.y1.controllers.browser

import android.content.SharedPreferences

/** The visibility of the Music menu items (Settings → Music Menu Items, `hide_music_btn_<id>` in AppPrefs). */
object MusicMenuItems {

    /**
     * Items that stay hidden until the user shows them. Album Artists: like the iPod, the Music menu has
     * Artists and Compilations.
     */
    private val HIDDEN_BY_DEFAULT = setOf("artists")

    fun isHiddenByDefault(id: String): Boolean = id in HIDDEN_BY_DEFAULT

    fun isHidden(prefs: SharedPreferences, id: String): Boolean =
        prefs.getBoolean("hide_music_btn_$id", isHiddenByDefault(id))
}
