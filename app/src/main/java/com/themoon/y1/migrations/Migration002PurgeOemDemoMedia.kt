package com.themoon.y1.migrations

import com.themoon.y1.StoragePaths
import java.io.File

/**
 * Migration 2: Purges OEM factory demo media copied to /storage/sdcard0/custom_media
 * by stock ROM preload scripts, and requests a media library rescan.
 */
class Migration002PurgeOemDemoMedia : Migration {

    override val version: Int
        get() = VERSION

    override val id: String
        get() = ID

    override val description: String
        get() = "Purge OEM preloaded demo media (Despacito, Panama, etc.) and prune from library"

    @Throws(Exception::class)
    override fun migrate(context: MigrationContext) {
        for (root in StoragePaths.getAllRoots()) {
            val customDir = File(root, "custom_media")
            if (customDir.exists() && customDir.isDirectory) {
                deleteOemDemoFiles(customDir)
            }
        }
        context.requestMediaRescan()
    }

    private fun deleteOemDemoFiles(dir: File) {
        val files = dir.listFiles() ?: return
        for (f in files) {
            val name = f.name.lowercase()
            if (name.contains("despacito") || name.contains("panama") || name.contains("demo") || name == ".mediafile") {
                try {
                    f.delete()
                } catch (_: Exception) {
                }
            }
        }
        val remaining = dir.listFiles()
        if (remaining != null && remaining.isEmpty()) {
            try {
                dir.delete()
            } catch (_: Exception) {
            }
        }
    }

    companion object {
        const val VERSION: Int = 2
        const val ID: String = "002_purge_oem_demo_media"
    }
}
