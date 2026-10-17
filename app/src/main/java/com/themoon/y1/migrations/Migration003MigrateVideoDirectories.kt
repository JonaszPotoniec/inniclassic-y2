package com.themoon.y1.migrations

import android.util.Log
import com.themoon.y1.StoragePaths
import com.themoon.y1.sync.SyncLedger
import java.io.File
import java.util.regex.Pattern

/**
 * Migration 3: Migrates legacy /Videos and /Video storage directories to dedicated
 * /Movies and /Tvshows directories across all mounted storage volumes, preserving
 * companion artwork and updating the Jellyfin SyncLedger local paths.
 */
class Migration003MigrateVideoDirectories : Migration {

    override val version: Int
        get() = VERSION

    override val id: String
        get() = ID

    override val description: String
        get() = "Migrate legacy Videos/Video directories to Movies and Tvshows and update sync ledger"

    @Throws(Exception::class)
    override fun migrate(context: MigrationContext) {
        var anyMoved = false

        for (root in StoragePaths.getAllRoots()) {
            if (migrateVolume(root)) {
                anyMoved = true
            }
        }

        // Update Jellyfin SyncLedger local paths if ledger exists
        updateSyncLedgerPaths()

        if (anyMoved) {
            context.requestMediaRescan()
        }
    }

    companion object {
        const val VERSION: Int = 3
        const val ID: String = "003_migrate_video_directories"
        private const val TAG = "Migration003"

        private val tvPattern = Pattern.compile("(?i)(?:s|season\\s*)(\\d{1,2})\\s*(?:e|ep|episode\\s*)(\\d{1,3})|(\\d{1,2})x(\\d{2,3})")

        fun migrateVolume(root: File): Boolean {
            val legacyDirs = listOf(File(root, "Videos"), File(root, "Video"))
            val targetMoviesDir = File(root, "Movies")
            val targetTvShowsDir = File(root, "Tvshows")
            var anyMoved = false

            for (legacyDir in legacyDirs) {
                if (!legacyDir.exists() || !legacyDir.isDirectory) continue

                val items = legacyDir.listFiles() ?: continue
                for (item in items) {
                    val name = item.name
                    if (name.equals("Jellyfin", ignoreCase = true) && item.isDirectory) {
                        migrateJellyfinDir(item, targetMoviesDir, targetTvShowsDir)
                        anyMoved = true
                    } else if (name.equals("TV Shows", ignoreCase = true) || name.equals("TV Series", ignoreCase = true) || name.equals("Series", ignoreCase = true)) {
                        if (item.isDirectory) {
                            moveFolderContents(item, targetTvShowsDir)
                        } else {
                            safeMove(item, File(targetTvShowsDir, item.name))
                        }
                        anyMoved = true
                    } else if (name.equals("Movies", ignoreCase = true)) {
                        if (item.isDirectory) {
                            moveFolderContents(item, targetMoviesDir)
                        } else {
                            safeMove(item, File(targetMoviesDir, item.name))
                        }
                        anyMoved = true
                    } else {
                        // Classify file or subfolder
                        val isTv = isTvItem(item)
                        val destDir = if (isTv) targetTvShowsDir else targetMoviesDir
                        safeMove(item, File(destDir, item.name))
                        anyMoved = true
                    }
                }

                // Delete legacy directory if now empty
                deleteIfEmpty(legacyDir)
            }
            return anyMoved
        }

        private fun migrateJellyfinDir(jellyfinLegacyDir: File, targetMovies: File, targetTvShows: File) {
            val jMovies = File(targetMovies, "Jellyfin")
            val jTv = File(targetTvShows, "Jellyfin")
            if (!jMovies.exists()) jMovies.mkdirs()
            if (!jTv.exists()) jTv.mkdirs()

            val children = jellyfinLegacyDir.listFiles() ?: return
            for (child in children) {
                if (child.isDirectory) {
                    // If it contains TV episode files or season folders, move to Tvshows/Jellyfin
                    val isTv = isTvItem(child)
                    val dest = File(if (isTv) jTv else jMovies, child.name)
                    safeMove(child, dest)
                } else {
                    val isTv = isTvItem(child)
                    val dest = File(if (isTv) jTv else jMovies, child.name)
                    safeMove(child, dest)
                }
            }
            deleteIfEmpty(jellyfinLegacyDir)
        }

        private fun isTvItem(file: File): Boolean {
            if (!file.isDirectory) {
                return tvPattern.matcher(file.name).find()
            }
            val subfiles = file.listFiles() ?: return false
            for (f in subfiles) {
                if (f.isDirectory) {
                    val nameLower = f.name.lowercase()
                    if (nameLower.startsWith("season") || nameLower.startsWith("series") || nameLower.startsWith("s") && nameLower.length in 2..4) {
                        return true
                    }
                    if (isTvItem(f)) return true
                } else if (tvPattern.matcher(f.name).find()) {
                    return true
                }
            }
            return false
        }

        private fun moveFolderContents(srcDir: File, destParent: File) {
            if (!destParent.exists()) destParent.mkdirs()
            val files = srcDir.listFiles() ?: return
            for (f in files) {
                val dest = File(destParent, f.name)
                safeMove(f, dest)
            }
            deleteIfEmpty(srcDir)
        }

        private fun safeMove(src: File, dest: File) {
            if (!src.exists()) return
            val parent = dest.parentFile
            if (parent != null && !parent.exists()) {
                parent.mkdirs()
            }
            if (dest.exists() && dest.isDirectory && src.isDirectory) {
                // Merge directory contents
                val children = src.listFiles()
                if (children != null) {
                    for (child in children) {
                        safeMove(child, File(dest, child.name))
                    }
                }
                deleteIfEmpty(src)
                return
            }
            if (dest.exists()) {
                dest.delete()
            }
            val success = src.renameTo(dest)
            if (!success) {
                try {
                    if (src.isDirectory) {
                        src.copyRecursively(dest, overwrite = true)
                        src.deleteRecursively()
                    } else {
                        src.copyTo(dest, overwrite = true)
                        src.delete()
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to copy/move ${src.absolutePath} to ${dest.absolutePath}: ${e.message}")
                }
            }
        }

        private fun deleteIfEmpty(dir: File) {
            val remaining = dir.listFiles()
            if (remaining != null && remaining.isEmpty()) {
                dir.delete()
            }
        }

        fun updateSyncLedgerPaths() {
            try {
                for (root in StoragePaths.getAllRoots()) {
                    val ledgerFile = File(root, ".y1_jellyfin_sync.json")
                    if (!ledgerFile.exists()) continue

                    val ledger = SyncLedger(ledgerFile)
                    ledger.load(ledgerFile)
                    var modified = false
                    for (item in ledger.getAllItems().values) {
                        val path = item.localPath ?: continue
                        if (path.contains("/Videos/") || path.contains("/Video/")) {
                            val isTv = "tvshows".equals(item.collectionType, ignoreCase = true)
                            val newPath = if (isTv) {
                                path.replace("/Videos/Jellyfin/", "/Tvshows/Jellyfin/")
                                    .replace("/Video/Jellyfin/", "/Tvshows/Jellyfin/")
                                    .replace("/Videos/", "/Tvshows/")
                                    .replace("/Video/", "/Tvshows/")
                            } else {
                                path.replace("/Videos/Jellyfin/", "/Movies/Jellyfin/")
                                    .replace("/Video/Jellyfin/", "/Movies/Jellyfin/")
                                    .replace("/Videos/", "/Movies/")
                                    .replace("/Video/", "/Movies/")
                            }
                            var finalPath = newPath
                            val newFile = File(newPath)
                            if (!newFile.exists()) {
                                val actual = com.themoon.y1.sync.FileUtils.findExistingMatchingFile(
                                    newFile.parentFile,
                                    newFile.nameWithoutExtension,
                                    com.themoon.y1.sync.FileUtils.VIDEO_EXTENSIONS
                                )
                                if (actual != null) {
                                    finalPath = actual.absolutePath
                                    item.fileSize = actual.length()
                                }
                            } else {
                                item.fileSize = newFile.length()
                            }

                            if (finalPath != path) {
                                item.localPath = finalPath
                                item.orphaned = false
                                modified = true
                            }
                        }
                    }
                    if (modified) {
                        ledger.save(ledgerFile)
                        Log.i(TAG, "Updated Jellyfin sync ledger paths to Movies/Tvshows in ${ledgerFile.absolutePath}")
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error updating sync ledger paths: ${e.message}")
            }
        }
    }
}
