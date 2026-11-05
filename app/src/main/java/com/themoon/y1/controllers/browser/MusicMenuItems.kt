package com.themoon.y1.controllers.browser

import com.themoon.y1.Prefs
import android.content.SharedPreferences

/** The Music menu items and their visibility (Settings → Music Menu Items, `hide_music_btn_<id>` in AppPrefs). */
object MusicMenuItems {

    /** [label] is the English text (localized when shown); [icon] is a Material Icons character. */
    class Item(val id: String, val label: String, val icon: String)

    /** In menu order. The Music menu shows them, and Settings → Music Menu Items lists them. */
    val ITEMS = listOf(
        Item("coverflow", "Cover Flow", "\uE3B6"),
        Item("playlists", "Playlists", "\uE05F"),
        Item("track_artists", "Artists", "\uE7FD"),
        Item("artists", "Album Artists", "\uE7FD"),
        Item("albums", "Albums", "\uE019"),
        Item("compilations", "Compilations", "\uE019"),
        Item("songs", "Songs", "\uE03D"),
        Item("shuffle_songs", "Shuffle Songs", "\uE043"),
        Item("genres", "Genres", "\uE030"),
        Item("composers", "Composers", "\uE7FD"),
        Item("years", "Years", "\uE8B6"),
        Item("recently_added", "Recently Added", "\uE8B6"),
        Item("favorites", "My Favorites", "\uE8B6"),
        Item("folders", "Folders", "\uE8B6"),
        Item("search", "Search", "\uE8B6")
    )

    /**
     * Items that stay hidden until the user shows them. Album Artists: like the iPod, the Music menu has
     * Artists and Compilations.
     */
    private val HIDDEN_BY_DEFAULT = setOf("artists")

    fun isHiddenByDefault(id: String): Boolean = id in HIDDEN_BY_DEFAULT

    fun isHidden(prefs: SharedPreferences, id: String): Boolean =
        prefs.getBoolean(Prefs.MUSIC_MENU_HIDDEN.key(id), isHiddenByDefault(id))
}
