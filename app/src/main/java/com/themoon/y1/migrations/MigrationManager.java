package com.themoon.y1.migrations;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Universal migration engine that manages versioned migrations across app updates.
 * <p>
 * State is persisted in a dedicated SharedPreferences file {@code "Y1_MIGRATIONS"} to prevent
 * user preference resets or cache clearing from accidentally re-running one-way migrations.
 */
public class MigrationManager {
    private static final String TAG = "MigrationManager";
    public static final String PREFS_NAME = "Y1_MIGRATIONS";
    public static final String KEY_CURRENT_VERSION = "current_version";
    public static final String PREFIX_APPLIED = "applied_";
    public static final String PREFIX_APPLIED_TIME = "applied_time_";

    private static volatile MigrationManager instance;

    private final Context context;
    private final SharedPreferences prefs;
    private final List<Migration> registeredMigrations = new ArrayList<>();
    private MigrationContext migrationContext;

    public static MigrationManager getInstance(Context context) {
        if (instance == null) {
            synchronized (MigrationManager.class) {
                if (instance == null) {
                    instance = new MigrationManager(context.getApplicationContext());
                }
            }
        }
        return instance;
    }

    MigrationManager(Context context) {
        this.context = context;
        this.prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        registerDefaultMigrations();
    }

    // Constructor for testing or custom preferences injection
    MigrationManager(Context context, SharedPreferences prefs) {
        this.context = context;
        this.prefs = prefs;
    }

    private void registerDefaultMigrations() {
        // Individual migration classes will be registered here.
    }

    /**
     * Registers a migration. Migrations must have unique versions and unique IDs.
     */
    public synchronized void registerMigration(Migration migration) {
        if (migration == null) return;
        for (Migration existing : registeredMigrations) {
            if (existing.getVersion() == migration.getVersion()) {
                throw new IllegalArgumentException("Duplicate migration version: " + migration.getVersion() +
                        " between " + existing.getId() + " and " + migration.getId());
            }
            if (existing.getId().equals(migration.getId())) {
                throw new IllegalArgumentException("Duplicate migration id: " + migration.getId());
            }
        }
        registeredMigrations.add(migration);
        Collections.sort(registeredMigrations, new Comparator<Migration>() {
            @Override
            public int compare(Migration m1, Migration m2) {
                return (m1.getVersion() < m2.getVersion()) ? -1 : ((m1.getVersion() == m2.getVersion()) ? 0 : 1);
            }
        });
    }

    public synchronized List<Migration> getRegisteredMigrations() {
        return new ArrayList<>(registeredMigrations);
    }

    public int getCurrentVersion() {
        return prefs.getInt(KEY_CURRENT_VERSION, 0);
    }

    public boolean isMigrationApplied(String id) {
        return prefs.getBoolean(PREFIX_APPLIED + id, false);
    }

    public MigrationContext getMigrationContext() {
        return migrationContext;
    }

    /**
     * Executes any registered migrations that have not yet been applied.
     * Migrations run strictly in ascending version order.
     * <p>
     * Guarantees:
     * <ul>
     *   <li>Fail-fast: If any migration fails, execution immediately halts so newer migrations
     *       do not execute on an inconsistent state.</li>
     *   <li>Durability: Each migration is marked as applied and committed to disk via {@code .commit()}
     *       immediately after successful execution.</li>
     * </ul>
     *
     * @return Number of migrations successfully applied during this execution.
     */
    public synchronized int applyPendingMigrations() {
        int currentVersion = getCurrentVersion();
        SharedPreferences settingsPrefs = context.getSharedPreferences("Y1_SETTINGS", Context.MODE_PRIVATE);
        migrationContext = new MigrationContext(context, prefs, settingsPrefs, currentVersion);

        int appliedCount = 0;
        for (Migration migration : registeredMigrations) {
            boolean alreadyApplied = isMigrationApplied(migration.getId());
            if (migration.getVersion() <= currentVersion && alreadyApplied) {
                continue;
            }

            Log.i(TAG, "Applying migration v" + migration.getVersion() + " [" + migration.getId() + "]: " + migration.getDescription());
            try {
                migration.migrate(migrationContext);

                // Commit synchronously so state is durable even if device loses power shortly after
                int newVersion = Math.max(currentVersion, migration.getVersion());
                boolean committed = prefs.edit()
                        .putInt(KEY_CURRENT_VERSION, newVersion)
                        .putBoolean(PREFIX_APPLIED + migration.getId(), true)
                        .putLong(PREFIX_APPLIED_TIME + migration.getId(), System.currentTimeMillis())
                        .commit();

                if (!committed) {
                    Log.w(TAG, "Failed to commit migration record to SharedPreferences for: " + migration.getId());
                }

                currentVersion = newVersion;
                appliedCount++;
                Log.i(TAG, "Migration v" + migration.getVersion() + " [" + migration.getId() + "] applied successfully.");
            } catch (Throwable t) {
                Log.e(TAG, "Migration v" + migration.getVersion() + " [" + migration.getId() + "] failed: " + t.getMessage(), t);
                // Fail-fast: Stop execution on error
                break;
            }
        }

        return appliedCount;
    }
}
