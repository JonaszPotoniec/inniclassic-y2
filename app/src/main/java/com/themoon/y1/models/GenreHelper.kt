package com.themoon.y1.models

import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Utility for parsing and matching multi-genre tags in audio files.
 *
 * Supports common tag separators:
 * - Semicolons (";")
 * - Forward slashes ("/")
 * - Commas (",")
 * - Double-backslashes ("\\")
 * - Null bytes ("\0") and newlines
 *
 * Preserves compound genres like "Singer/Songwriter" without splitting them.
 */
object GenreHelper {
    private const val SINGER_SONGWRITER_PLACEHOLDER = "__SINGER_SONGWRITER__"
    private val SINGER_SONGWRITER_REGEX = Regex("(?i)\\bSinger\\s*[/,]\\s*Songwriter\\b")
    private val DELIMITER_REGEX = Regex("[;,\\u0000\\r\\n]+|\\\\+|/")

    private val UNKNOWN_GENRE_LIST = listOf("Unknown Genre")
    private val parseCache = ConcurrentHashMap<String, List<String>>()

    /**
     * Splits a raw genre string into individual distinct genre names.
     * Trims whitespace and preserves case of first occurrence, removing case-insensitive duplicates.
     * Caches results so each distinct raw string is parsed only once.
     */
    fun parseGenres(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return UNKNOWN_GENRE_LIST
        val cached = parseCache[raw]
        if (cached != null) return cached

        val parsed = parseInternal(raw)
        parseCache[raw] = parsed
        return parsed
    }

    private fun parseInternal(raw: String): List<String> {
        val trimmed = raw.trim()
        if (trimmed.equals("Unknown Genre", ignoreCase = true)) {
            return UNKNOWN_GENRE_LIST
        }

        // Protect "Singer/Songwriter" (including "Singer, Songwriter") before splitting
        val hasSingerSongwriter = SINGER_SONGWRITER_REGEX.containsMatchIn(trimmed)
        val prepared = if (hasSingerSongwriter) {
            SINGER_SONGWRITER_REGEX.replace(trimmed, SINGER_SONGWRITER_PLACEHOLDER)
        } else {
            trimmed
        }

        val tokens = prepared.split(DELIMITER_REGEX)
        val result = LinkedHashSet<String>()
        val seenLower = HashSet<String>()

        for (token in tokens) {
            var item = token.trim()
            if (item.isEmpty()) continue
            if (item.contains(SINGER_SONGWRITER_PLACEHOLDER)) {
                item = item.replace(SINGER_SONGWRITER_PLACEHOLDER, "Singer/Songwriter")
            }
            if (seenLower.add(item.lowercase(Locale.US))) {
                result.add(item)
            }
        }

        return if (result.isEmpty()) listOf(trimmed) else result.toList()
    }

    /**
     * Checks if [rawGenres] contains [targetGenre] (case-insensitive).
     */
    fun matches(rawGenres: String?, targetGenre: String?): Boolean {
        if (rawGenres.isNullOrBlank() || targetGenre.isNullOrBlank()) return false
        val target = targetGenre.trim()
        return parseGenres(rawGenres).any { it.equals(target, ignoreCase = true) }
    }

    /**
     * Clears the genre parsing cache (primarily for tests).
     */
    internal fun clearCache() {
        parseCache.clear()
    }
}
