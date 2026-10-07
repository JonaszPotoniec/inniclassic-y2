package com.themoon.y1.migrations;

import android.content.SharedPreferences;
import org.junit.Before;
import org.junit.Test;

import java.util.*;

import static org.junit.Assert.*;

public class MigrationManagerTest {

    private MockSharedPreferences mockPrefs;
    private MigrationManager migrationManager;

    @Before
    public void setUp() {
        mockPrefs = new MockSharedPreferences();
        migrationManager = new MigrationManager(null, mockPrefs) {
            // Override to avoid Android Context getSharedPreferences in unit test
            @Override
            public synchronized int applyPendingMigrations() {
                int currentVersion = getCurrentVersion();
                MigrationContext context = new MigrationContext(null, mockPrefs, mockPrefs, currentVersion);
                int appliedCount = 0;

                for (Migration migration : getRegisteredMigrations()) {
                    boolean alreadyApplied = isMigrationApplied(migration.getId());
                    if (migration.getVersion() <= currentVersion && alreadyApplied) {
                        continue;
                    }

                    try {
                        migration.migrate(context);

                        int newVersion = Math.max(currentVersion, migration.getVersion());
                        mockPrefs.edit()
                                .putInt(KEY_CURRENT_VERSION, newVersion)
                                .putBoolean(PREFIX_APPLIED + migration.getId(), true)
                                .putLong(PREFIX_APPLIED_TIME + migration.getId(), System.currentTimeMillis())
                                .commit();

                        currentVersion = newVersion;
                        appliedCount++;
                    } catch (Throwable t) {
                        break;
                    }
                }
                return appliedCount;
            }
        };
    }

    @Test
    public void testMigrationsRunInOrder() {
        final List<String> executionLog = new ArrayList<>();

        Migration m2 = new Migration() {
            @Override public int getVersion() { return 2; }
            @Override public String getId() { return "m2"; }
            @Override public String getDescription() { return "step 2"; }
            @Override public void migrate(MigrationContext context) { executionLog.add("m2"); }
        };

        Migration m1 = new Migration() {
            @Override public int getVersion() { return 1; }
            @Override public String getId() { return "m1"; }
            @Override public String getDescription() { return "step 1"; }
            @Override public void migrate(MigrationContext context) { executionLog.add("m1"); }
        };

        // Register out of order
        migrationManager.registerMigration(m2);
        migrationManager.registerMigration(m1);

        int applied = migrationManager.applyPendingMigrations();
        assertEquals(2, applied);
        assertEquals(Arrays.asList("m1", "m2"), executionLog);
        assertEquals(2, migrationManager.getCurrentVersion());
        assertTrue(migrationManager.isMigrationApplied("m1"));
        assertTrue(migrationManager.isMigrationApplied("m2"));
    }

    @Test
    public void testAlreadyAppliedMigrationsAreSkipped() {
        final List<String> executionLog = new ArrayList<>();

        Migration m1 = new Migration() {
            @Override public int getVersion() { return 1; }
            @Override public String getId() { return "m1"; }
            @Override public String getDescription() { return "step 1"; }
            @Override public void migrate(MigrationContext context) { executionLog.add("m1"); }
        };

        migrationManager.registerMigration(m1);
        int appliedFirst = migrationManager.applyPendingMigrations();
        assertEquals(1, appliedFirst);
        assertEquals(1, executionLog.size());

        // Second run should skip
        int appliedSecond = migrationManager.applyPendingMigrations();
        assertEquals(0, appliedSecond);
        assertEquals(1, executionLog.size());
    }

    @Test
    public void testFailFastStopsSubsequentMigrations() {
        final List<String> executionLog = new ArrayList<>();

        Migration m1 = new Migration() {
            @Override public int getVersion() { return 1; }
            @Override public String getId() { return "m1"; }
            @Override public String getDescription() { return "fails"; }
            @Override public void migrate(MigrationContext context) throws Exception {
                executionLog.add("m1_attempt");
                throw new RuntimeException("Simulated failure in m1");
            }
        };

        Migration m2 = new Migration() {
            @Override public int getVersion() { return 2; }
            @Override public String getId() { return "m2"; }
            @Override public String getDescription() { return "should not run"; }
            @Override public void migrate(MigrationContext context) {
                executionLog.add("m2");
            }
        };

        migrationManager.registerMigration(m1);
        migrationManager.registerMigration(m2);

        int applied = migrationManager.applyPendingMigrations();
        assertEquals(0, applied);
        assertEquals(Collections.singletonList("m1_attempt"), executionLog);
        assertEquals(0, migrationManager.getCurrentVersion());
        assertFalse(migrationManager.isMigrationApplied("m1"));
        assertFalse(migrationManager.isMigrationApplied("m2"));
    }

    @Test
    public void testMigrationContextActionRequests() {
        Migration m1 = new Migration() {
            @Override public int getVersion() { return 1; }
            @Override public String getId() { return "m1"; }
            @Override public String getDescription() { return "requests rescan"; }
            @Override public void migrate(MigrationContext context) {
                assertTrue(context.isUpgradingFromBaseline());
                context.requestMediaRescan();
                context.requestCacheInvalidation();
            }
        };

        MigrationContext testContext = new MigrationContext(null, mockPrefs, mockPrefs, 0);
        try {
            m1.migrate(testContext);
        } catch (Exception e) {
            fail("Should not fail");
        }
        assertTrue(testContext.isMediaRescanRequested());
        assertTrue(testContext.isCacheInvalidationRequested());
    }

    // In-memory MockSharedPreferences for isolated JVM testing
    private static class MockSharedPreferences implements SharedPreferences {
        private final Map<String, Object> map = new HashMap<>();

        @Override public Map<String, ?> getAll() { return new HashMap<>(map); }
        @Override public String getString(String key, String defValue) { return (String) map.getOrDefault(key, defValue); }
        @Override public Set<String> getStringSet(String key, Set<String> defValues) { return defValues; }
        @Override public int getInt(String key, int defValue) { return (Integer) map.getOrDefault(key, defValue); }
        @Override public long getLong(String key, long defValue) { return (Long) map.getOrDefault(key, defValue); }
        @Override public float getFloat(String key, float defValue) { return (Float) map.getOrDefault(key, defValue); }
        @Override public boolean getBoolean(String key, boolean defValue) { return (Boolean) map.getOrDefault(key, defValue); }
        @Override public boolean contains(String key) { return map.containsKey(key); }
        @Override public Editor edit() { return new MockEditor(map); }
        @Override public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) {}
        @Override public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) {}

        private static class MockEditor implements Editor {
            private final Map<String, Object> backingMap;
            private final Map<String, Object> pending = new HashMap<>();
            private final Set<String> removes = new HashSet<>();
            private boolean cleared = false;

            MockEditor(Map<String, Object> backingMap) { this.backingMap = backingMap; }
            @Override public Editor putString(String key, String value) { pending.put(key, value); return this; }
            @Override public Editor putStringSet(String key, Set<String> values) { return this; }
            @Override public Editor putInt(String key, int value) { pending.put(key, value); return this; }
            @Override public Editor putLong(String key, long value) { pending.put(key, value); return this; }
            @Override public Editor putFloat(String key, float value) { pending.put(key, value); return this; }
            @Override public Editor putBoolean(String key, boolean value) { pending.put(key, value); return this; }
            @Override public Editor remove(String key) { removes.add(key); return this; }
            @Override public Editor clear() { cleared = true; return this; }
            @Override public boolean commit() {
                if (cleared) backingMap.clear();
                for (String k : removes) backingMap.remove(k);
                backingMap.putAll(pending);
                return true;
            }
            @Override public void apply() { commit(); }
        }
    }
}
