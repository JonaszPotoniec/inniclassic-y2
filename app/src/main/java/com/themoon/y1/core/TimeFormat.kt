package com.themoon.y1.core

import java.util.Locale

/** Track time as on the iPod Classic: m:ss, or h:mm:ss for one hour or more (no leading zero). */
object TimeFormat {
    fun format(ms: Long): String {
        val totalSec = ms.coerceAtLeast(0L) / 1000
        val h = totalSec / 3600
        val m = (totalSec / 60) % 60
        val s = totalSec % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
        else String.format(Locale.US, "%d:%02d", m, s)
    }

    fun format(ms: Int): String = format(ms.toLong())
}
