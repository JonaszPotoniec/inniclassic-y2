package com.themoon.y1.migrations

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.themoon.y1.AppPrefs

/**
 * Universal migration engine that manages versioned migrations across app updates.
 *
 * State is persisted in a dedicated SharedPreferences file "Y1_MIGRATIONS" to prevent
 * user preference resets or cache clearing from accidentally re-running one-way migrations.
 */
open class MigrationManager {

    private val context: Context?
    private val prefs: SharedPreferences
    private val registeredMigrations: MutableList<Migration> = ArrayList()
    var migrationContext: MigrationContext? = null
        protected set

    internal constructor(context: Context) {
        this.context = context
        this.prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        registerDefaultMigrations()
    }

    // Constructor for testing or custom preferences injection
    internal constructor(context: Context?, prefs: SharedPreferences) {
        this.context = context
        this.prefs = prefs
    }

    private fun registerDefaultMigrations() {
        registerMigration(Migration001RestoreDeduplicatedSongs())
        registerMigration(Migration002PurgeOemDemoMedia())
        registerMigration(Migration003MigrateVideoDirectories())
        registerMigration(Migration004MoveMisplacedPrefs())
        registerMigration(Migration005MergeAudiobookBookmarks())
    }

    /**
     * Registers a migration. Migrations must have unique versions and unique IDs.
     */
    @Synchronized
    fun registerMigration(migration: Migration?) {
        if (migration == null) return
        for (existing in registeredMigrations) {
            if (existing.version == migration.version) {
                throw IllegalArgumentException(
                    "Duplicate migration version: " + migration.version +
                        " between " + existing.id + " and " + migration.id
                )
            }
            if (existing.id == migration.id) {
                throw IllegalArgumentException("Duplicate migration id: " + migration.id)
            }
        }
        registeredMigrations.add(migration)
        registeredMigrations.sortBy { it.version }
    }

    @Synchronized
    fun getRegisteredMigrations(): List<Migration> = ArrayList(registeredMigrations)

    val currentVersion: Int
        get() = prefs.getInt(KEY_CURRENT_VERSION, 0)

    fun isMigrationApplied(id: String): Boolean =
        prefs.getBoolean(PREFIX_APPLIED + id, false)

    /**
     * Executes any registered migrations that have not yet been applied.
     * Migrations run strictly in ascending version order.
     */
    @Synchronized
    open fun applyPendingMigrations(): Int {
        var currentVer = currentVersion
        val settingsPrefs = context?.let { AppPrefs.get(it) } ?: prefs
        val ctx = MigrationContext(context, prefs, settingsPrefs, currentVer)
        migrationContext = ctx

        var appliedCount = 0
        for (migration in registeredMigrations) {
            val alreadyApplied = isMigrationApplied(migration.id)
            if (migration.version <= currentVer && alreadyApplied) {
                continue
            }

            Log.i(TAG, "Applying migration v${migration.version} [${migration.id}]: ${migration.description}")
            try {
                migration.migrate(ctx)

                val newVersion = maxOf(currentVer, migration.version)
                val committed = prefs.edit()
                    .putInt(KEY_CURRENT_VERSION, newVersion)
                    .putBoolean(PREFIX_APPLIED + migration.id, true)
                    .putLong(PREFIX_APPLIED_TIME + migration.id, System.currentTimeMillis())
                    .commit()

                if (!committed) {
                    Log.w(TAG, "Failed to commit migration record to SharedPreferences for: ${migration.id}")
                }

                currentVer = newVersion
                appliedCount++
                Log.i(TAG, "Migration v${migration.version} [${migration.id}] applied successfully.")
            } catch (t: Throwable) {
                Log.e(TAG, "Migration v${migration.version} [${migration.id}] failed: ${t.message}", t)
                break
            }
        }

        return appliedCount
    }

    companion object {
        private const val TAG = "MigrationManager"
        const val PREFS_NAME: String = "Y1_MIGRATIONS"
        const val KEY_CURRENT_VERSION: String = "current_version"
        const val PREFIX_APPLIED: String = "applied_"
        const val PREFIX_APPLIED_TIME: String = "applied_time_"

        @Volatile
        private var instance: MigrationManager? = null
        fun getInstance(context: Context): MigrationManager {
            val existing = instance
            if (existing != null) return existing
            return synchronized(MigrationManager::class.java) {
                instance ?: MigrationManager(context.applicationContext).also { instance = it }
            }
        }
    }
}
