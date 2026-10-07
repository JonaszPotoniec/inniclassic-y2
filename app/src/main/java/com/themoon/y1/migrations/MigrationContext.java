package com.themoon.y1.migrations;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Context passed to migrations providing access to app resources, preferences,
 * and migration control flags.
 */
public class MigrationContext {
    private final Context context;
    private final SharedPreferences migrationPrefs;
    private final SharedPreferences settingsPrefs;
    private final int startingVersion;
    private boolean mediaRescanRequested;
    private boolean cacheInvalidationRequested;

    public MigrationContext(Context context, SharedPreferences migrationPrefs, SharedPreferences settingsPrefs, int startingVersion) {
        this.context = context;
        this.migrationPrefs = migrationPrefs;
        this.settingsPrefs = settingsPrefs;
        this.startingVersion = startingVersion;
    }

    public Context getContext() {
        return context;
    }

    public SharedPreferences getMigrationPrefs() {
        return migrationPrefs;
    }

    public SharedPreferences getSettingsPrefs() {
        return settingsPrefs;
    }

    /**
     * Returns true if the device is migrating from baseline version 0 (initial install or first update with migration support).
     */
    public boolean isUpgradingFromBaseline() {
        return startingVersion == 0;
    }

    public int getStartingVersion() {
        return startingVersion;
    }

    public boolean isMediaRescanRequested() {
        return mediaRescanRequested;
    }

    /**
     * Requests that MainActivity schedule an incremental media library rescan once the scanner is ready.
     * Use this when a migration alters audio library filtering or cache contents.
     */
    public void requestMediaRescan() {
        this.mediaRescanRequested = true;
    }

    public boolean isCacheInvalidationRequested() {
        return cacheInvalidationRequested;
    }

    /**
     * Requests that MainActivity discard existing library cache files before proceeding.
     */
    public void requestCacheInvalidation() {
        this.cacheInvalidationRequested = true;
    }
}
