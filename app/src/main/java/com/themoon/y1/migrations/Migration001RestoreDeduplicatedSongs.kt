package com.themoon.y1.migrations

/**
 * Migration 1: Restores tracks previously dropped by the aggressive cross-album
 * audio quality deduplication filter.
 *
 * Requests an incremental media library rescan so that all previously pruned tracks
 * are indexed and saved to .y1_library_cache.json, while already cached tracks are
 * preserved and fast-forwarded without re-parsing metadata.
 */
class Migration001RestoreDeduplicatedSongs : Migration {

    override val version: Int
        get() = VERSION

    override val id: String
        get() = ID

    override val description: String
        get() = "Restore missing tracks across albums by requesting incremental media rescan"

    @Throws(Exception::class)
    override fun migrate(context: MigrationContext) {
        context.requestMediaRescan()
    }

    companion object {
        const val VERSION: Int = 1
        const val ID: String = "001_restore_deduplicated_songs"
    }
}
