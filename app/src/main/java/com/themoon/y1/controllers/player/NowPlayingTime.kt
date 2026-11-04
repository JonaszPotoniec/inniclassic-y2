package com.themoon.y1.controllers.player

/** Right-hand time on Now Playing: remaining time (the default, as on the iPod) or total time. */
object NowPlayingTime {
    /** AppPrefs key: true shows the total time instead of the remaining time. */
    const val PREF_SHOW_TOTAL_TIME = "show_total_time"

    /** Total time ("2:42"), or remaining time with a minus sign ("-2:20"). */
    fun rightText(positionMs: Int, durationMs: Int, showTotal: Boolean, format: (Int) -> String): String =
        if (showTotal) format(durationMs) else "-" + format(Math.max(0, durationMs - positionMs))
}

/** Spectrum in place of the album art on Now Playing (Settings → Spectrum). */
object NowPlayingSpectrum {
    /** AppPrefs key: true shows the spectrum. Default false (album art). */
    const val PREF = "show_spectrum"

    /**
     * The audio capture runs only while the spectrum is really visible, as the capture thread uses battery.
     * [onScreen]: the app shows and the screen is not off or blanked.
     */
    fun shouldCapture(spectrumOn: Boolean, nowPlayingShown: Boolean, lyricsShowing: Boolean, onScreen: Boolean): Boolean =
        spectrumOn && nowPlayingShown && !lyricsShowing && onScreen
}
