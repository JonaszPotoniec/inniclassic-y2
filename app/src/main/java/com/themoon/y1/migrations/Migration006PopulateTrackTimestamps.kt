package com.themoon.y1.migrations

/**
 * Migration 6: Populates file modification timestamps and file sizes in the media library
 * cache (.y1_library_cache.json) to enable fast incremental scanning that accurately
 * detects updated track metadata.
 */
class Migration006PopulateTrackTimestamps : Migration {

    override val version: Int
        get() = VERSION

    override val id: String
        get() = ID

    override val description: String
        get() = "Populate file modification timestamps and sizes for incremental media change detection"

    @Throws(Exception::class)
    override fun migrate(context: MigrationContext) {
        context.requestMediaRescan()
    }

    companion object {
        const val VERSION: Int = 6
        const val ID: String = "006_populate_track_timestamps"
    }
}
