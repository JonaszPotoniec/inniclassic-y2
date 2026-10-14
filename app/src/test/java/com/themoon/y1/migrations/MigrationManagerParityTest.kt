package com.themoon.y1.migrations

import android.content.Context
import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Parity and regression test suite for [MigrationManager], [Migration], and [MigrationContext].
 *
 * Verifies that the Kotlin conversion maintains full behavioral parity with the original
 * Java implementation:
 * 1. Ordering: migrations always execute in strictly ascending version order regardless of registration order.
 * 2. Abort on exception: if migration throws an exception, the loop breaks immediately (Java `break` behavior)
 *    and subsequent migrations are skipped.
 * 3. Idempotency: running applyPendingMigrations() twice on an already-applied set applies 0 migrations
 *    and preserves currentVersion.
 * 4. Version tracking: currentVersion in preferences reflects the latest successfully applied migration.
 * 5. Migration record: isMigrationApplied(id) returns true for applied migrations, false for unapplied.
 * 6. Skip already applied: migration with version <= currentVersion and already marked applied is skipped.
 * 7. Registration validation: duplicate version or id throws IllegalArgumentException; null is safely ignored.
 * 8. Durability: synchronous commit() is invoked per migration to guarantee state survives sudden power loss.
 * 9. MigrationContext: startingVersion, isUpgradingFromBaseline, requestMediaRescan, and requestCacheInvalidation.
 * 10. Concrete default migrations: Migration001RestoreDeduplicatedSongs and Migration002PurgeOemDemoMedia.
 */
class MigrationManagerParityTest {

    private lateinit var mockPrefs: TrackableMockSharedPreferences
    private lateinit var migrationManager: MigrationManager

    @Before
    fun setUp() {
        mockPrefs = TrackableMockSharedPreferences()
        migrationManager = MigrationManager(null, mockPrefs)
    }

    // =========================================================================
    // 1. Ordering: Strictly Ascending Version Order
    // =========================================================================

    @Test
    fun ordering_registeredInReverseOrder_executesAscending() {
        val executionLog = mutableListOf<String>()

        val m3 = TestMigration(version = 3, id = "m3") { executionLog.add("m3") }
        val m2 = TestMigration(version = 2, id = "m2") { executionLog.add("m2") }
        val m1 = TestMigration(version = 1, id = "m1") { executionLog.add("m1") }

        migrationManager.registerMigration(m3)
        migrationManager.registerMigration(m2)
        migrationManager.registerMigration(m1)

        val applied = migrationManager.applyPendingMigrations()

        assertEquals(3, applied)
        assertEquals(listOf("m1", "m2", "m3"), executionLog)
        assertEquals(3, migrationManager.currentVersion)
    }

    @Test
    fun ordering_registeredInJumbledOrder_executesAscending() {
        val executionLog = mutableListOf<Int>()

        val versions = listOf(5, 1, 4, 2, 3)
        for (v in versions) {
            migrationManager.registerMigration(TestMigration(version = v, id = "v$v") {
                executionLog.add(v)
            })
        }

        // Verify getRegisteredMigrations() is sorted ascending by version
        val registeredVersions = migrationManager.getRegisteredMigrations().map { it.version }
        assertEquals(listOf(1, 2, 3, 4, 5), registeredVersions)

        val applied = migrationManager.applyPendingMigrations()

        assertEquals(5, applied)
        assertEquals(listOf(1, 2, 3, 4, 5), executionLog)
        assertEquals(5, migrationManager.currentVersion)
    }

    @Test
    fun ordering_sparseVersionsWithGaps_executesAscending() {
        val executionLog = mutableListOf<Int>()

        val versions = listOf(100, 5, 42, 1)
        for (v in versions) {
            migrationManager.registerMigration(TestMigration(version = v, id = "step_$v") {
                executionLog.add(v)
            })
        }

        assertEquals(4, migrationManager.applyPendingMigrations())
        assertEquals(listOf(1, 5, 42, 100), executionLog)
        assertEquals(100, migrationManager.currentVersion)
    }

    // =========================================================================
    // 2. Abort on Exception: Fail-fast Loop Break
    // =========================================================================

    @Test
    fun abortOnException_firstMigrationThrows_haltsImmediatelyAndNoneApplied() {
        val executionLog = mutableListOf<String>()

        val m1 = TestMigration(version = 1, id = "m1") {
            executionLog.add("m1_attempt")
            throw RuntimeException("Database error in m1")
        }
        val m2 = TestMigration(version = 2, id = "m2") {
            executionLog.add("m2_attempt")
        }

        migrationManager.registerMigration(m1)
        migrationManager.registerMigration(m2)

        val applied = migrationManager.applyPendingMigrations()

        assertEquals(0, applied)
        assertEquals(listOf("m1_attempt"), executionLog)
        assertEquals(0, migrationManager.currentVersion)
        assertFalse(migrationManager.isMigrationApplied("m1"))
        assertFalse(migrationManager.isMigrationApplied("m2"))
        assertFalse(mockPrefs.contains(MigrationManager.PREFIX_APPLIED + "m1"))
        assertFalse(mockPrefs.contains(MigrationManager.PREFIX_APPLIED_TIME + "m1"))
    }

    @Test
    fun abortOnException_middleMigrationThrows_priorSucceedsSubsequentSkipped() {
        val executionLog = mutableListOf<String>()

        val m1 = TestMigration(version = 1, id = "m1") { executionLog.add("m1") }
        val m2 = TestMigration(version = 2, id = "m2") {
            executionLog.add("m2_fail")
            throw IllegalStateException("Failed to migrate settings table")
        }
        val m3 = TestMigration(version = 3, id = "m3") { executionLog.add("m3") }

        migrationManager.registerMigration(m1)
        migrationManager.registerMigration(m2)
        migrationManager.registerMigration(m3)

        val applied = migrationManager.applyPendingMigrations()

        assertEquals(1, applied)
        assertEquals(listOf("m1", "m2_fail"), executionLog)
        assertEquals(1, migrationManager.currentVersion)
        assertTrue(migrationManager.isMigrationApplied("m1"))
        assertFalse(migrationManager.isMigrationApplied("m2"))
        assertFalse(migrationManager.isMigrationApplied("m3"))
    }

    @Test
    fun abortOnException_catchesAnyThrowableWithoutCrashingCaller() {
        val executionLog = mutableListOf<String>()

        val m1 = TestMigration(version = 1, id = "m1") {
            executionLog.add("m1_throw_error")
            // Throw an Error (non-Exception Throwable) to verify catch (Throwable t) parity
            throw AssertionError("Severe assertion error in migration")
        }
        val m2 = TestMigration(version = 2, id = "m2") { executionLog.add("m2") }

        migrationManager.registerMigration(m1)
        migrationManager.registerMigration(m2)

        val applied = migrationManager.applyPendingMigrations()

        assertEquals(0, applied)
        assertEquals(listOf("m1_throw_error"), executionLog)
        assertEquals(0, migrationManager.currentVersion)
        assertFalse(migrationManager.isMigrationApplied("m1"))
        assertFalse(migrationManager.isMigrationApplied("m2"))
    }

    @Test
    fun abortOnException_retryAfterFixingFailure_resumesFromFailedMigration() {
        val executionLog = mutableListOf<String>()
        val failM2 = AtomicBoolean(true)

        val m1 = TestMigration(version = 1, id = "m1") { executionLog.add("m1") }
        val m2 = TestMigration(version = 2, id = "m2") {
            if (failM2.get()) {
                executionLog.add("m2_fail")
                throw RuntimeException("Temporary I/O error")
            }
            executionLog.add("m2_ok")
        }
        val m3 = TestMigration(version = 3, id = "m3") { executionLog.add("m3") }

        migrationManager.registerMigration(m1)
        migrationManager.registerMigration(m2)
        migrationManager.registerMigration(m3)

        // First attempt: m2 fails
        val appliedFirst = migrationManager.applyPendingMigrations()
        assertEquals(1, appliedFirst)
        assertEquals(listOf("m1", "m2_fail"), executionLog)
        assertEquals(1, migrationManager.currentVersion)
        assertTrue(migrationManager.isMigrationApplied("m1"))
        assertFalse(migrationManager.isMigrationApplied("m2"))
        assertFalse(migrationManager.isMigrationApplied("m3"))

        // Fix the root cause and retry
        failM2.set(false)
        val appliedSecond = migrationManager.applyPendingMigrations()

        assertEquals(2, appliedSecond)
        assertEquals(listOf("m1", "m2_fail", "m2_ok", "m3"), executionLog)
        assertEquals(3, migrationManager.currentVersion)
        assertTrue(migrationManager.isMigrationApplied("m1"))
        assertTrue(migrationManager.isMigrationApplied("m2"))
        assertTrue(migrationManager.isMigrationApplied("m3"))
    }

    // =========================================================================
    // 3. Idempotency: Multiple Invocations
    // =========================================================================

    @Test
    fun idempotency_secondRunOnAlreadyAppliedSet_appliesZeroAndPreservesVersion() {
        val executionCounter = AtomicInteger(0)

        val m1 = TestMigration(version = 1, id = "m1") { executionCounter.incrementAndGet() }
        val m2 = TestMigration(version = 2, id = "m2") { executionCounter.incrementAndGet() }

        migrationManager.registerMigration(m1)
        migrationManager.registerMigration(m2)

        val firstRunCount = migrationManager.applyPendingMigrations()
        assertEquals(2, firstRunCount)
        assertEquals(2, executionCounter.get())
        assertEquals(2, migrationManager.currentVersion)

        // Second run
        val secondRunCount = migrationManager.applyPendingMigrations()
        assertEquals(0, secondRunCount)
        assertEquals(2, executionCounter.get())
        assertEquals(2, migrationManager.currentVersion)

        // Third run
        val thirdRunCount = migrationManager.applyPendingMigrations()
        assertEquals(0, thirdRunCount)
        assertEquals(2, executionCounter.get())
        assertEquals(2, migrationManager.currentVersion)
    }

    @Test
    fun idempotency_timestampsArePreservedAndNotOverwrittenOnSecondRun() {
        val m1 = TestMigration(version = 1, id = "m1") {}
        migrationManager.registerMigration(m1)

        assertEquals(1, migrationManager.applyPendingMigrations())
        val timestampBefore = mockPrefs.getLong(MigrationManager.PREFIX_APPLIED_TIME + "m1", -1L)
        assertTrue("Timestamp should be set (>0)", timestampBefore > 0L)

        // Run again
        assertEquals(0, migrationManager.applyPendingMigrations())
        val timestampAfter = mockPrefs.getLong(MigrationManager.PREFIX_APPLIED_TIME + "m1", -1L)
        assertEquals("Timestamp must not be altered on subsequent runs", timestampBefore, timestampAfter)
    }

    @Test
    fun idempotency_emptyMigrationsSet_returnsZeroAndLeavesVersionZero() {
        val applied = migrationManager.applyPendingMigrations()
        assertEquals(0, applied)
        assertEquals(0, migrationManager.currentVersion)
    }

    // =========================================================================
    // 4. Version Tracking: Current Version in Preferences
    // =========================================================================

    @Test
    fun versionTracking_advancesToLatestSuccessfullyAppliedVersion() {
        val m1 = TestMigration(version = 1, id = "m1") {}
        val m4 = TestMigration(version = 4, id = "m4") {}
        val m9 = TestMigration(version = 9, id = "m9") {}

        migrationManager.registerMigration(m1)
        migrationManager.registerMigration(m4)
        migrationManager.registerMigration(m9)

        assertEquals(3, migrationManager.applyPendingMigrations())
        assertEquals(9, migrationManager.currentVersion)
        assertEquals(9, mockPrefs.getInt(MigrationManager.KEY_CURRENT_VERSION, 0))
    }

    @Test
    fun versionTracking_startingFromPreExistingVersion_advancesFromThere() {
        mockPrefs.edit()
            .putInt(MigrationManager.KEY_CURRENT_VERSION, 5)
            .putBoolean(MigrationManager.PREFIX_APPLIED + "m3", true)
            .putBoolean(MigrationManager.PREFIX_APPLIED + "m5", true)
            .commit()

        val executionLog = mutableListOf<String>()
        val m3 = TestMigration(version = 3, id = "m3") { executionLog.add("m3") }
        val m5 = TestMigration(version = 5, id = "m5") { executionLog.add("m5") }
        val m7 = TestMigration(version = 7, id = "m7") { executionLog.add("m7") }
        val m8 = TestMigration(version = 8, id = "m8") { executionLog.add("m8") }

        migrationManager.registerMigration(m3)
        migrationManager.registerMigration(m5)
        migrationManager.registerMigration(m7)
        migrationManager.registerMigration(m8)

        val applied = migrationManager.applyPendingMigrations()

        assertEquals(2, applied)
        assertEquals(listOf("m7", "m8"), executionLog)
        assertEquals(8, migrationManager.currentVersion)
        assertEquals(8, mockPrefs.getInt(MigrationManager.KEY_CURRENT_VERSION, 0))
    }

    @Test
    fun versionTracking_backportedMigrationDoesNotRegressCurrentVersion() {
        // Device is already at version 10
        mockPrefs.edit()
            .putInt(MigrationManager.KEY_CURRENT_VERSION, 10)
            .putBoolean(MigrationManager.PREFIX_APPLIED + "m10", true)
            .commit()

        val executionLog = mutableListOf<String>()
        // Backported migration v4 was never applied
        val m4 = TestMigration(version = 4, id = "m4") { executionLog.add("m4") }

        migrationManager.registerMigration(m4)
        val applied = migrationManager.applyPendingMigrations()

        assertEquals(1, applied)
        assertEquals(listOf("m4"), executionLog)
        assertTrue(migrationManager.isMigrationApplied("m4"))
        // maxOf(currentVer, migration.version) guarantees version stays at 10 and does not downgrade to 4
        assertEquals(10, migrationManager.currentVersion)
        assertEquals(10, mockPrefs.getInt(MigrationManager.KEY_CURRENT_VERSION, 0))
    }

    // =========================================================================
    // 5. Migration Record: isMigrationApplied(id) and Timestamp Persistence
    // =========================================================================

    @Test
    fun migrationRecord_accuratelyReflectsAppliedStatus() {
        val m1 = TestMigration(version = 1, id = "patch_audio") {}
        val m2 = TestMigration(version = 2, id = "patch_video") {}

        migrationManager.registerMigration(m1)
        migrationManager.registerMigration(m2)

        assertFalse(migrationManager.isMigrationApplied("patch_audio"))
        assertFalse(migrationManager.isMigrationApplied("patch_video"))
        assertFalse(migrationManager.isMigrationApplied("unknown_patch"))

        val beforeTime = System.currentTimeMillis()
        migrationManager.applyPendingMigrations()
        val afterTime = System.currentTimeMillis()

        assertTrue(migrationManager.isMigrationApplied("patch_audio"))
        assertTrue(migrationManager.isMigrationApplied("patch_video"))
        assertFalse(migrationManager.isMigrationApplied("unknown_patch"))

        val time1 = mockPrefs.getLong(MigrationManager.PREFIX_APPLIED_TIME + "patch_audio", -1L)
        val time2 = mockPrefs.getLong(MigrationManager.PREFIX_APPLIED_TIME + "patch_video", -1L)

        assertTrue(time1 in beforeTime..afterTime)
        assertTrue(time2 in beforeTime..afterTime)
    }

    // =========================================================================
    // 6. Skip Already Applied Migrations
    // =========================================================================

    @Test
    fun skipAlreadyApplied_versionLessOrEqualAndFlagged_isSkipped() {
        mockPrefs.edit()
            .putInt(MigrationManager.KEY_CURRENT_VERSION, 2)
            .putBoolean(MigrationManager.PREFIX_APPLIED + "m1", true)
            .putBoolean(MigrationManager.PREFIX_APPLIED + "m2", true)
            .commit()

        val executionLog = mutableListOf<String>()
        val m1 = TestMigration(version = 1, id = "m1") { executionLog.add("m1") }
        val m2 = TestMigration(version = 2, id = "m2") { executionLog.add("m2") }
        val m3 = TestMigration(version = 3, id = "m3") { executionLog.add("m3") }

        migrationManager.registerMigration(m1)
        migrationManager.registerMigration(m2)
        migrationManager.registerMigration(m3)

        val applied = migrationManager.applyPendingMigrations()

        assertEquals(1, applied)
        assertEquals(listOf("m3"), executionLog)
        assertEquals(3, migrationManager.currentVersion)
    }

    @Test
    fun skipAlreadyApplied_versionLessOrEqualButNotFlagged_isExecuted() {
        // Version in prefs is 2, but m1 was never flagged as applied
        mockPrefs.edit()
            .putInt(MigrationManager.KEY_CURRENT_VERSION, 2)
            .putBoolean(MigrationManager.PREFIX_APPLIED + "m2", true)
            .commit()

        val executionLog = mutableListOf<String>()
        val m1 = TestMigration(version = 1, id = "m1") { executionLog.add("m1") }
        val m2 = TestMigration(version = 2, id = "m2") { executionLog.add("m2") }

        migrationManager.registerMigration(m1)
        migrationManager.registerMigration(m2)

        val applied = migrationManager.applyPendingMigrations()

        assertEquals(1, applied)
        assertEquals(listOf("m1"), executionLog)
        assertTrue(migrationManager.isMigrationApplied("m1"))
        assertEquals(2, migrationManager.currentVersion)
    }

    @Test
    fun skipAlreadyApplied_versionGreaterThanCurrentVersion_executesEvenIfFlagged() {
        // If prefs somehow have applied_m5 = true, but currentVersion is only 2:
        // condition (migration.version <= currentVer && alreadyApplied) is false (5 <= 2 is false)
        // so m5 executes as required by Java logic
        mockPrefs.edit()
            .putInt(MigrationManager.KEY_CURRENT_VERSION, 2)
            .putBoolean(MigrationManager.PREFIX_APPLIED + "m5", true)
            .commit()

        val executionLog = mutableListOf<String>()
        val m5 = TestMigration(version = 5, id = "m5") { executionLog.add("m5") }

        migrationManager.registerMigration(m5)
        val applied = migrationManager.applyPendingMigrations()

        assertEquals(1, applied)
        assertEquals(listOf("m5"), executionLog)
        assertEquals(5, migrationManager.currentVersion)
    }

    // =========================================================================
    // 7. Registration Validation and Invariants
    // =========================================================================

    @Test
    fun registration_duplicateVersion_throwsIllegalArgumentException() {
        val m1 = TestMigration(version = 1, id = "first_m1") {}
        val m2 = TestMigration(version = 1, id = "second_m1") {}

        migrationManager.registerMigration(m1)
        try {
            migrationManager.registerMigration(m2)
            fail("Expected IllegalArgumentException for duplicate version")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("Duplicate migration version: 1") == true)
            assertTrue(e.message?.contains("first_m1") == true)
            assertTrue(e.message?.contains("second_m1") == true)
        }
    }

    @Test
    fun registration_duplicateId_throwsIllegalArgumentException() {
        val m1 = TestMigration(version = 1, id = "duplicate_id") {}
        val m2 = TestMigration(version = 2, id = "duplicate_id") {}

        migrationManager.registerMigration(m1)
        try {
            migrationManager.registerMigration(m2)
            fail("Expected IllegalArgumentException for duplicate id")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("Duplicate migration id: duplicate_id") == true)
        }
    }

    @Test
    fun registration_nullMigration_ignoredSafelyWithoutThrowing() {
        migrationManager.registerMigration(null)
        assertTrue(migrationManager.getRegisteredMigrations().isEmpty())
    }

    @Test
    fun registration_getRegisteredMigrationsReturnsDefensiveCopy() {
        val m1 = TestMigration(version = 1, id = "m1") {}
        migrationManager.registerMigration(m1)

        val list = migrationManager.getRegisteredMigrations()
        assertEquals(1, list.size)

        // Mutating the returned list does not affect MigrationManager internal list
        try {
            (list as MutableList<Migration>).clear()
        } catch (_: UnsupportedOperationException) {
            // Immutable list is also safe
        }
        assertEquals(1, migrationManager.getRegisteredMigrations().size)
    }

    // =========================================================================
    // 8. MigrationContext & Action Request Flags
    // =========================================================================

    @Test
    fun migrationContext_baselineDetectionLogic() {
        val baselineContext = MigrationContext(null, mockPrefs, mockPrefs, 0)
        assertTrue("Version 0 is considered baseline upgrade", baselineContext.isUpgradingFromBaseline)

        val nonBaselineContext1 = MigrationContext(null, mockPrefs, mockPrefs, 1)
        assertFalse("Version 1 is not baseline upgrade", nonBaselineContext1.isUpgradingFromBaseline)

        val nonBaselineContext5 = MigrationContext(null, mockPrefs, mockPrefs, 5)
        assertFalse("Version 5 is not baseline upgrade", nonBaselineContext5.isUpgradingFromBaseline)
    }

    @Test
    fun migrationContext_actionRequestFlagsDefaultFalseAndToggleToTrue() {
        val ctx = MigrationContext(null, mockPrefs, mockPrefs, 0)

        assertFalse(ctx.isMediaRescanRequested)
        assertFalse(ctx.isCacheInvalidationRequested)

        ctx.requestMediaRescan()
        assertTrue(ctx.isMediaRescanRequested)
        assertFalse(ctx.isCacheInvalidationRequested)

        ctx.requestCacheInvalidation()
        assertTrue(ctx.isMediaRescanRequested)
        assertTrue(ctx.isCacheInvalidationRequested)

        // Multiple calls idempotent
        ctx.requestMediaRescan()
        ctx.requestCacheInvalidation()
        assertTrue(ctx.isMediaRescanRequested)
        assertTrue(ctx.isCacheInvalidationRequested)
    }

    @Test
    fun migrationContext_accessibleViaMigrationManagerAfterExecution() {
        assertNull(migrationManager.migrationContext)

        val m1 = TestMigration(version = 1, id = "m1") { context ->
            context.requestMediaRescan()
            context.requestCacheInvalidation()
        }

        migrationManager.registerMigration(m1)
        migrationManager.applyPendingMigrations()

        val ctx = migrationManager.migrationContext
        assertNotNull(ctx)
        assertEquals(0, ctx!!.startingVersion)
        assertTrue(ctx.isUpgradingFromBaseline)
        assertTrue(ctx.isMediaRescanRequested)
        assertTrue(ctx.isCacheInvalidationRequested)
    }

    // =========================================================================
    // 9. Durability and Synchronous Commits
    // =========================================================================

    @Test
    fun durability_commitsSynchronouslyPerAppliedMigration() {
        val m1 = TestMigration(version = 1, id = "m1") {}
        val m2 = TestMigration(version = 2, id = "m2") {}

        migrationManager.registerMigration(m1)
        migrationManager.registerMigration(m2)

        assertEquals(0, mockPrefs.commitCount)
        assertEquals(2, migrationManager.applyPendingMigrations())
        assertEquals(2, mockPrefs.commitCount)
        assertEquals(0, mockPrefs.applyCount) // Verifies synchronous commit(), NOT async apply()
    }

    @Test
    fun durability_commitFailureIsHandledGracefullyWithoutCrashing() {
        mockPrefs.commitReturnValue = false

        val m1 = TestMigration(version = 1, id = "m1") {}
        val m2 = TestMigration(version = 2, id = "m2") {}

        migrationManager.registerMigration(m1)
        migrationManager.registerMigration(m2)

        // Should still apply and return 2 even if commit returns false
        val applied = migrationManager.applyPendingMigrations()
        assertEquals(2, applied)
        assertEquals(2, migrationManager.currentVersion)
    }

    // =========================================================================
    // 10. Concrete Built-in Migrations Parity
    // =========================================================================

    @Test
    fun concreteMigration_001RestoreDeduplicatedSongs_parityContracts() {
        val m1 = Migration001RestoreDeduplicatedSongs()
        assertEquals(1, m1.version)
        assertEquals("001_restore_deduplicated_songs", m1.id)
        assertTrue(m1.description.isNotEmpty())

        val ctx = MigrationContext(null, mockPrefs, mockPrefs, 0)
        assertFalse(ctx.isMediaRescanRequested)
        m1.migrate(ctx)
        assertTrue(ctx.isMediaRescanRequested)
        assertFalse(ctx.isCacheInvalidationRequested)
    }

    @Test
    fun concreteMigration_002PurgeOemDemoMedia_parityContracts() {
        val m2 = Migration002PurgeOemDemoMedia()
        assertEquals(2, m2.version)
        assertEquals("002_purge_oem_demo_media", m2.id)
        assertTrue(m2.description.isNotEmpty())

        val ctx = MigrationContext(null, mockPrefs, mockPrefs, 1)
        assertFalse(ctx.isMediaRescanRequested)
        m2.migrate(ctx)
        assertTrue(ctx.isMediaRescanRequested)
        assertFalse(ctx.isCacheInvalidationRequested)
    }

    // =========================================================================
    // Test Helpers & Mock Classes
    // =========================================================================

    private class TestMigration(
        override val version: Int,
        override val id: String,
        override val description: String = "Test migration $id (v$version)",
        private val block: (MigrationContext) -> Unit
    ) : Migration {
        override fun migrate(context: MigrationContext) {
            block(context)
        }
    }

    private class TrackableMockSharedPreferences : SharedPreferences {
        private val map = mutableMapOf<String, Any?>()
        var commitCount = 0
            private set
        var applyCount = 0
            private set
        var commitReturnValue = true

        override fun getAll(): Map<String, *> = HashMap(map)
        override fun getString(key: String, defValue: String?) = (map[key] as? String) ?: defValue
        override fun getStringSet(key: String, defValues: Set<String>?) = defValues
        override fun getInt(key: String, defValue: Int) = (map[key] as? Int) ?: defValue
        override fun getLong(key: String, defValue: Long) = (map[key] as? Long) ?: defValue
        override fun getFloat(key: String, defValue: Float) = (map[key] as? Float) ?: defValue
        override fun getBoolean(key: String, defValue: Boolean) = (map[key] as? Boolean) ?: defValue
        override fun contains(key: String) = map.containsKey(key)
        override fun edit(): SharedPreferences.Editor = MockEditor(this, map)
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {}
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {}

        private class MockEditor(
            private val owner: TrackableMockSharedPreferences,
            private val backingMap: MutableMap<String, Any?>
        ) : SharedPreferences.Editor {
            private val pending = mutableMapOf<String, Any?>()
            private val removes = mutableSetOf<String>()
            private var cleared = false

            override fun putString(key: String, value: String?) = apply { pending[key] = value }
            override fun putStringSet(key: String, values: Set<String>?) = this
            override fun putInt(key: String, value: Int) = apply { pending[key] = value }
            override fun putLong(key: String, value: Long) = apply { pending[key] = value }
            override fun putFloat(key: String, value: Float) = apply { pending[key] = value }
            override fun putBoolean(key: String, value: Boolean) = apply { pending[key] = value }
            override fun remove(key: String) = apply { removes.add(key) }
            override fun clear() = apply { cleared = true }

            override fun commit(): Boolean {
                owner.commitCount++
                if (cleared) backingMap.clear()
                removes.forEach { backingMap.remove(it) }
                backingMap.putAll(pending)
                return owner.commitReturnValue
            }

            override fun apply() {
                owner.applyCount++
                commit()
            }
        }
    }
}
