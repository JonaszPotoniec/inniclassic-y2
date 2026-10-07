package com.themoon.y1.migrations;

/**
 * Represents a single versioned migration step.
 */
public interface Migration {
    /**
     * Monotonically increasing version number (e.g. 1, 2, 3...).
     * Migrations are executed in strictly ascending order of version.
     */
    int getVersion();

    /**
     * Unique string identifier for logging, tracking, and diagnostics.
     */
    String getId();

    /**
     * Brief human-readable description of what this migration performs.
     */
    String getDescription();

    /**
     * Executes the migration logic.
     * Must be idempotent where feasible.
     * If an exception is thrown, the migration is treated as failed
     * and will NOT be marked as completed.
     *
     * @param context Migration context providing storage and preferences access.
     * @throws Exception If migration fails.
     */
    void migrate(MigrationContext context) throws Exception;
}
