package com.themoon.y1.navigation

/**
 * The selected row of a list, kept while a deeper screen shows: Back selects the same row again, at the
 * same height in the list, as on the iPod. The text finds the row also when rows were added, hidden or
 * moved while the list was away; the index is the fallback.
 *
 * @param text the row text (null: the row has no text)
 * @param index the row position in the list
 * @param top the distance in pixels from the top of the visible list to the top of the row
 */
data class RowKey(val text: String?, val index: Int, val top: Int = 0) {

    /**
     * The index of this row in a list with the row texts [texts]: the row with the same text (the one
     * nearest to [index] when several rows have that text), else [index] limited to the list.
     * -1 for an empty list.
     */
    fun indexIn(texts: List<String?>): Int {
        if (texts.isEmpty()) return -1
        if (text != null) {
            var best = -1
            for (i in texts.indices) {
                if (texts[i] == text && (best < 0 || Math.abs(i - index) < Math.abs(best - index))) best = i
            }
            if (best >= 0) return best
        }
        return index.coerceIn(0, texts.size - 1)
    }

    /** The [top] to use for a row of [rowHeight] in a list of [listHeight]: the full row stays in view. */
    fun topIn(listHeight: Int, rowHeight: Int): Int =
        top.coerceAtMost(listHeight - rowHeight).coerceAtLeast(0)
}
