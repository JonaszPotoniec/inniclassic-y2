package com.themoon.y1.controllers.player

/**
 * Lyrics in the original script, romanised (pinyin, romaji), or both. The romanised lines come from a sidecar
 * `<track>.romanised.lrc` with the same timestamps as the lyrics (or, for plain lyrics, the same lines in the
 * same order). Songs without the sidecar show their lyrics as they are, with each value.
 */
object LyricsScript {
    /** AppPrefs key: [ORIGINAL], [ROMANISED] or [BOTH]. */
    const val PREF = "lyrics_script"
    const val ORIGINAL = 0
    const val ROMANISED = 1
    const val BOTH = 2
    const val SUFFIX = ".romanised.lrc"

    /** English label of a value (the app translates it). The stored value of [BOTH] stays 2. */
    fun label(mode: Int): String = when (mode) {
        ROMANISED -> "Romanised"
        BOTH -> "Original + Romanised"
        else -> "Original"
    }

    fun normalize(mode: Int): Int = if (mode == ROMANISED || mode == BOTH) mode else ORIGINAL

    /** The sidecar path for an audio file path ("…/01 Song.mp3" → "…/01 Song.romanised.lrc"), or null. */
    fun sidecarPath(audioPath: String): String? {
        val dot = audioPath.lastIndexOf('.')
        if (dot <= audioPath.lastIndexOf('/')) return null
        return audioPath.substring(0, dot) + SUFFIX
    }

    /**
     * The text of one lyrics line, and where the romanised part starts in it (-1 = no romanised part).
     * [BOTH] puts the romanised line under the original line; a line that is the same in both (an English line)
     * shows once. A romanised line with no visible text (spaces, a NUL, a zero-width space) is no romanised line:
     * the original line shows, so [ROMANISED] never gives a blank lyrics view.
     */
    fun line(original: String, romanised: String?, mode: Int): Pair<String, Int> {
        val r = romanised?.trim()?.takeIf { LyricsText.isVisible(it) }
        return when {
            r == null || r == original.trim() -> Pair(original, -1)
            mode == ROMANISED -> Pair(r, -1)
            mode == BOTH -> Pair(original + "\n" + r, original.length + 1)
            else -> Pair(original, -1)
        }
    }

    /**
     * Plain lyrics (no timestamps): pairs the lines in order. Returns null (show the original text) when the
     * romanised text has another number of lines, or for [ORIGINAL]. The original text has no blank lines at its
     * start and end; blank romanised lines there are removed only when the romanised text has more lines than the
     * original. Thus a blank first or last romanised line stays paired, and that line shows the original.
     */
    fun plainLines(original: String, romanised: String?, mode: Int): List<Pair<String, Int>>? {
        if (romanised == null || normalize(mode) == ORIGINAL) return null
        val o = original.split("\n")
        val all = romanised.split("\n")
        var from = 0
        var to = all.size
        while (to - from > o.size && !LyricsText.isVisible(all[to - 1])) to--
        while (to - from > o.size && !LyricsText.isVisible(all[from])) from++
        if (to - from != o.size) return null
        val r = all.subList(from, to)
        return o.indices.map { line(o[it], r[it], mode) }
    }
}
