package com.themoon.y1.controllers.player

/**
 * Lyrics text checks. Now Playing shows the lyrics view only for lyrics with at least one line of visible text.
 * Before, an empty ID3 lyrics frame (USLT with only a NUL, as some taggers write) gave a blank lyrics view: Kotlin's
 * trim() keeps a NUL. Lines with only a zero-width space or a BOM did the same.
 */
object LyricsText {
    /** True when [text] has a character that shows: not a space, a control character (NUL) or a format character (zero-width space, BOM). */
    fun isVisible(text: String?): Boolean {
        if (text == null) return false
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            val type = Character.getType(cp)
            if (!Character.isWhitespace(cp) && !Character.isSpaceChar(cp) &&
                type != Character.CONTROL.toInt() && type != Character.FORMAT.toInt()
            ) return true
            i += Character.charCount(cp)
        }
        return false
    }

    /** Plain lyrics from a tag without NUL characters and outer spaces, or null when no line has visible text. */
    fun plain(text: String?): String? {
        if (text == null) return null
        val clean = text.replace("\u0000", "").trim()
        return if (isVisible(clean)) clean else null
    }
}
