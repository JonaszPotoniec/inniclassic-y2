package com.themoon.y1

import android.content.Context
import android.util.Log

/**
 * A feature that the user hides in Settings -> Main Menu Items also stops its background work.
 * The check reads the preference at the time of the work, so no restart is necessary.
 *
 * Background work that stops while the feature is hidden:
 * - Videos: video folder scan and video thumbnails, Jellyfin movie and TV sync. Unhide = one new scan.
 * - Audiobooks: audiobook folder scan, Jellyfin book sync. The next library scan finds new books.
 * - Web Server: a hide stops a running server (it holds a wake lock, a Wi-Fi lock and keeps the screen on).
 */
object FeatureVisibility {
    const val VIDEOS = "OPEN_VIDEOS"
    const val AUDIOBOOKS = "OPEN_AUDIOBOOKS"
    const val WEB_SERVER = "OPEN_WEBSERVER"

    private const val TAG = "FeatureVisibility"

    /** True when every main menu button of the current theme that opens [action] is hidden. */
    fun isHidden(context: Context?, action: String): Boolean {
        if (context == null) return false
        return try {
            val prefs = context.getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
            val ids = ThemeManager.getCurrentTheme().menuElements
                .filter { it.type == "button" && it.action.equals(action, ignoreCase = true) }
                .map { it.id }
            isHidden(ids) { prefs.getBoolean("hide_btn_$it", false) }
        } catch (e: Exception) {
            false
        }
    }

    /** A feature without a menu button stays as it is: hiding is a user choice in the menu settings. */
    internal fun isHidden(buttonIds: List<String>, isButtonHidden: (String) -> Boolean): Boolean =
        buttonIds.isNotEmpty() && buttonIds.all(isButtonHidden)

    /** The feature that a Jellyfin library type belongs to, or null for music and other libraries. */
    internal fun featureForLibrary(collectionType: String?): String? = when (collectionType?.lowercase(java.util.Locale.US)) {
        "movies", "tvshows" -> VIDEOS
        "books", "audiobooks" -> AUDIOBOOKS
        else -> null
    }

    /** True when the sync skips a Jellyfin library because its feature is hidden. */
    fun skipsJellyfinLibrary(context: Context?, collectionType: String?): Boolean {
        val feature = featureForLibrary(collectionType) ?: return false
        return isHidden(context, feature)
    }

    /** Settings -> Main Menu Items calls this after it hides or shows a button. */
    fun onMenuButtonToggled(activity: android.app.Activity, action: String?, hidden: Boolean) {
        val main = activity as? MainActivity ?: return
        when {
            action.equals(VIDEOS, ignoreCase = true) && !hidden && !isHidden(main, VIDEOS) -> {
                Thread({
                    try {
                        main.scanVideos()
                    } catch (e: Exception) {
                        Log.e(TAG, "Video scan failed", e)
                    }
                }, "y1-video-scan").start()
            }
            action.equals(WEB_SERVER, ignoreCase = true) && hidden && isHidden(main, WEB_SERVER) -> {
                if (main.webServerController.isServerRunning) main.webServerController.toggleServer()
            }
        }
    }
}
