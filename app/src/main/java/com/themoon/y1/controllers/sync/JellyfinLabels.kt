package com.themoon.y1.controllers.sync

import java.util.Locale

/** The texts and rules of the Jellyfin lists (pure, for the unit tests). Labels are English keys for localize. */
object JellyfinLabels {

    /** The four kinds of library lists. Other library types use the movie list (one level of items). */
    enum class Kind { TV, MUSIC, BOOKS, MOVIES }

    fun kind(collectionType: String?): Kind = when (collectionType?.lowercase(Locale.US)) {
        "tvshows" -> Kind.TV
        "music" -> Kind.MUSIC
        "books", "audiobooks" -> Kind.BOOKS
        else -> Kind.MOVIES
    }

    /** The sync modes of a library, in the order of their rows. */
    val MODES = listOf("all", "manual", "off")

    /** A music library syncs all by default; movies, TV and books only the selected items; other libraries nothing. */
    fun defaultMode(collectionType: String?): String = when (collectionType?.lowercase(Locale.US)) {
        "music" -> "all"
        "movies", "tvshows", "books", "audiobooks" -> "manual"
        else -> "off"
    }

    fun modeLabel(mode: String): String = when (mode.lowercase(Locale.US)) {
        "all" -> "Sync All"
        "manual" -> "Manual"
        else -> "Off"
    }

    fun authModeLabel(mode: String): String = when (mode.lowercase(Locale.US)) {
        "credentials" -> "Credentials"
        "token" -> "Token"
        else -> "QuickConnect"
    }

    /** Auth Mode goes round: QuickConnect, Credentials, Token. */
    fun nextAuthMode(mode: String): String = when (mode.lowercase(Locale.US)) {
        "quickconnect" -> "credentials"
        "credentials" -> "token"
        else -> "quickconnect"
    }

    /** "E05 - Name"; no number: the name only. */
    fun episode(index: Int, name: String): String = if (index > 0) "E" + twoDigits(index) + " - " + name else name

    /** "05 - Name" (a song or a chapter); no number: the name only. */
    fun track(index: Int, name: String): String = if (index > 0) twoDigits(index) + " - " + name else name

    /** "Name (2010)"; no year: the name only. */
    fun movie(name: String, year: String?): String =
        if (!year.isNullOrEmpty() && !year.contains("Unknown")) "$name ($year)" else name

    private fun twoDigits(n: Int): String = if (n < 10) "0$n" else n.toString()

    fun formatSize(bytes: Long): String {
        if (bytes <= 0) return "0 MB"
        val gb = bytes.toDouble() / (1024.0 * 1024.0 * 1024.0)
        if (gb >= 1.0) return String.format(Locale.US, "%.1f GB", gb)
        return "${bytes / (1024 * 1024)} MB"
    }

    /** "Selected: 3/10 (1.2 GB) | Free: 20.5 GB" ([selected] and [free] are the localized words). */
    fun storageLine(selected: String, free: String, count: Int, total: Int, selectedBytes: Long, freeBytes: Long): String =
        "$selected: $count/$total" + (if (selectedBytes > 0) " (${formatSize(selectedBytes)})" else "") +
            " | $free: ${formatSize(freeBytes)}"
}
