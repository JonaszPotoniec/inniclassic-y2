package com.themoon.y1.migrations;

/**
 * Migration 1: Restores tracks previously dropped by the aggressive cross-album
 * audio quality deduplication filter.
 * <p>
 * Requests an incremental media library rescan so that all previously pruned tracks
 * are indexed and saved to .y1_library_cache.json, while already cached tracks are
 * preserved and fast-forwarded without re-parsing metadata.
 */
public class Migration001RestoreDeduplicatedSongs implements Migration {
    public static final int VERSION = 1;
    public static final String ID = "001_restore_deduplicated_songs";

    @Override
    public int getVersion() {
        return VERSION;
    }

    @Override
    public String getId() {
        return ID;
    }

    @Override
    public String getDescription() {
        return "Restore missing tracks across albums by requesting incremental media rescan";
    }

    @Override
    public void migrate(MigrationContext context) throws Exception {
        // Request an incremental media scan so that any files previously skipped/dropped
        // by filterDuplicateSongs are discovered, parsed, and merged into the library cache.
        context.requestMediaRescan();
    }
}
