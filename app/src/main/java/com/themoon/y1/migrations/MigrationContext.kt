package com.themoon.y1.migrations

import android.content.Context
import android.content.SharedPreferences

/**
 * Context passed to migrations providing access to app resources, preferences,
 * and migration control flags.
 */
class MigrationContext(
    val context: Context?,
    val migrationPrefs: SharedPreferences?,
    val settingsPrefs: SharedPreferences?,
    val startingVersion: Int
) {
    var isMediaRescanRequested: Boolean = false
        private set

    var isCacheInvalidationRequested: Boolean = false
        private set

    /**
     * Returns true if the device is migrating from baseline version 0.
     */
    val isUpgradingFromBaseline: Boolean
        get() = startingVersion == 0

    /**
     * Requests that MainActivity schedule an incremental media library rescan once the scanner is ready.
     */
    fun requestMediaRescan() {
        isMediaRescanRequested = true
    }

    /**
     * Requests that MainActivity discard existing library cache files before proceeding.
     */
    fun requestCacheInvalidation() {
        isCacheInvalidationRequested = true
    }
}
