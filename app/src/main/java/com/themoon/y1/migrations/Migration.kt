package com.themoon.y1.migrations

/**
 * Represents a single versioned migration step.
 */
interface Migration {
    /**
     * Monotonically increasing version number (e.g. 1, 2, 3...).
     * Migrations are executed in strictly ascending order of version.
     */
    val version: Int

    /**
     * Unique string identifier for logging, tracking, and diagnostics.
     */
    val id: String

    /**
     * Brief human-readable description of what this migration performs.
     */
    val description: String

    /**
     * Executes the migration logic.
     * Must be idempotent where feasible.
     * If an exception is thrown, the migration is treated as failed
     * and will NOT be marked as completed.
     *
     * @param context Migration context providing storage and preferences access.
     * @throws Exception If migration fails.
     */
    @Throws(Exception::class)
    fun migrate(context: MigrationContext)
}
