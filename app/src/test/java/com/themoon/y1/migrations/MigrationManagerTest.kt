package com.themoon.y1.migrations

import android.content.SharedPreferences
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*

class MigrationManagerTest {

    private lateinit var mockPrefs: MockSharedPreferences
    private lateinit var migrationManager: MigrationManager

    @Before
    fun setUp() {
        mockPrefs = MockSharedPreferences()
        migrationManager = MigrationManager(null, mockPrefs)
    }

    @Test
    fun testMigrationsRunInOrder() {
        val executionLog = mutableListOf<String>()

        val m2 = object : Migration {
            override val version = 2
            override val id = "m2"
            override val description = "step 2"
            override fun migrate(context: MigrationContext) { executionLog.add("m2") }
        }
        val m1 = object : Migration {
            override val version = 1
            override val id = "m1"
            override val description = "step 1"
            override fun migrate(context: MigrationContext) { executionLog.add("m1") }
        }

        migrationManager.registerMigration(m2)
        migrationManager.registerMigration(m1)

        assertEquals(2, migrationManager.applyPendingMigrations())
        assertEquals(listOf("m1", "m2"), executionLog)
        assertEquals(2, migrationManager.currentVersion)
        assertTrue(migrationManager.isMigrationApplied("m1"))
        assertTrue(migrationManager.isMigrationApplied("m2"))
    }

    @Test
    fun testAlreadyAppliedMigrationsAreSkipped() {
        val executionLog = mutableListOf<String>()

        val m1 = object : Migration {
            override val version = 1
            override val id = "m1"
            override val description = "step 1"
            override fun migrate(context: MigrationContext) { executionLog.add("m1") }
        }

        migrationManager.registerMigration(m1)
        assertEquals(1, migrationManager.applyPendingMigrations())
        assertEquals(1, executionLog.size)

        assertEquals(0, migrationManager.applyPendingMigrations())
        assertEquals(1, executionLog.size)
    }

    @Test
    fun testFailFastStopsSubsequentMigrations() {
        val executionLog = mutableListOf<String>()

        val m1 = object : Migration {
            override val version = 1
            override val id = "m1"
            override val description = "fails"
            override fun migrate(context: MigrationContext) {
                executionLog.add("m1_attempt")
                throw RuntimeException("Simulated failure in m1")
            }
        }
        val m2 = object : Migration {
            override val version = 2
            override val id = "m2"
            override val description = "should not run"
            override fun migrate(context: MigrationContext) { executionLog.add("m2") }
        }

        migrationManager.registerMigration(m1)
        migrationManager.registerMigration(m2)

        assertEquals(0, migrationManager.applyPendingMigrations())
        assertEquals(listOf("m1_attempt"), executionLog)
        assertEquals(0, migrationManager.currentVersion)
        assertFalse(migrationManager.isMigrationApplied("m1"))
        assertFalse(migrationManager.isMigrationApplied("m2"))
    }

    @Test
    fun testMigrationContextActionRequests() {
        val m1 = object : Migration {
            override val version = 1
            override val id = "m1"
            override val description = "requests rescan"
            override fun migrate(context: MigrationContext) {
                assertTrue(context.isUpgradingFromBaseline)
                context.requestMediaRescan()
                context.requestCacheInvalidation()
            }
        }

        val testContext = MigrationContext(null, mockPrefs, mockPrefs, 0)
        m1.migrate(testContext)
        assertTrue(testContext.isMediaRescanRequested)
        assertTrue(testContext.isCacheInvalidationRequested)
    }

    @Test
    fun testMigration001RestoreDeduplicatedSongsRegistersAndRequestsRescan() {
        val m1 = Migration001RestoreDeduplicatedSongs()
        assertEquals(1, m1.version)
        assertEquals("001_restore_deduplicated_songs", m1.id)

        val context = MigrationContext(null, mockPrefs, mockPrefs, 0)
        assertFalse(context.isMediaRescanRequested)
        m1.migrate(context)
        assertTrue(context.isMediaRescanRequested)
    }

    @Test
    fun testMigration002PurgeOemDemoMediaRegistersAndRequestsRescan() {
        val m2 = Migration002PurgeOemDemoMedia()
        assertEquals(2, m2.version)
        assertEquals("002_purge_oem_demo_media", m2.id)

        val context = MigrationContext(null, mockPrefs, mockPrefs, 0)
        assertFalse(context.isMediaRescanRequested)
        m2.migrate(context)
        assertTrue(context.isMediaRescanRequested)
    }

    // In-memory SharedPreferences for isolated JVM testing
    private class MockSharedPreferences : SharedPreferences {
        private val map = mutableMapOf<String, Any?>()

        override fun getAll(): Map<String, *> = HashMap(map)
        override fun getString(key: String, defValue: String?) = map.getOrDefault(key, defValue) as? String ?: defValue
        override fun getStringSet(key: String, defValues: Set<String>?) = defValues
        override fun getInt(key: String, defValue: Int) = (map.getOrDefault(key, defValue) as? Int) ?: defValue
        override fun getLong(key: String, defValue: Long) = (map.getOrDefault(key, defValue) as? Long) ?: defValue
        override fun getFloat(key: String, defValue: Float) = (map.getOrDefault(key, defValue) as? Float) ?: defValue
        override fun getBoolean(key: String, defValue: Boolean) = (map.getOrDefault(key, defValue) as? Boolean) ?: defValue
        override fun contains(key: String) = map.containsKey(key)
        override fun edit(): SharedPreferences.Editor = MockEditor(map)
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {}
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {}

        private class MockEditor(private val backingMap: MutableMap<String, Any?>) : SharedPreferences.Editor {
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
                if (cleared) backingMap.clear()
                removes.forEach { backingMap.remove(it) }
                backingMap.putAll(pending)
                return true
            }
            override fun apply() { commit() }
        }
    }
}
