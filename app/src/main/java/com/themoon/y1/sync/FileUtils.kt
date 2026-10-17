package com.themoon.y1.sync

import android.os.StatFs
import java.io.File

object FileUtils {

    /**
     * Sanitizes filenames to be safe on FAT32/VFAT/Linux:
     * Strips illegal characters [/ \ : * ? " < > |], trims leading/trailing dots/spaces.
     */

    fun sanitizeFilename(name: String?): String {
        if (name.isNullOrBlank()) {
            return "Unknown"
        }
        val sanitized = name.replace("[/\\\\:*?\"<>|]".toRegex(), "_")
            .replace("^[._\\s]+|[._\\s]+$".toRegex(), "")
            .trim()
        return if (sanitized.isEmpty()) "Unknown" else sanitized
    }

    val VIDEO_EXTENSIONS: Set<String> = setOf("mp4", "mkv", "avi", "mov", "m4v", "webm", "3gp", "ts", "wmv")
    val AUDIO_EXTENSIONS: Set<String> = setOf("mp3", "flac", "m4a", "aac", "ogg", "opus", "wav", "wma", "ape", "m4b")

    /**
     * Finds an existing file in `dir` matching `baseName` with one of the allowed extensions,
     * regardless of uppercase/lowercase in the extension. Returns the file if found and size > 0.
     */
    fun findExistingMatchingFile(dir: File?, baseName: String, extensions: Set<String>): File? {
        if (dir == null || !dir.exists() || !dir.isDirectory) return null
        val files = dir.listFiles() ?: return null
        for (f in files) {
            if (f.isFile && f.length() > 0) {
                if (f.nameWithoutExtension.equals(baseName, ignoreCase = true)) {
                    val ext = f.extension.lowercase(java.util.Locale.US)
                    if (extensions.contains(ext)) {
                        return f
                    }
                }
            }
        }
        return null
    }

    /**
     * Checks if the destination volume has at least requiredBytes + headroomBytes available.
     */
    fun hasAvailableSpace(targetDir: File, requiredBytes: Long, headroomBytes: Long): Boolean {
        return try {
            if (!targetDir.exists()) {
                targetDir.mkdirs()
            }
            val stat = StatFs(targetDir.absolutePath)
            @Suppress("DEPRECATION")
            val availableBytes = stat.availableBlocks.toLong() * stat.blockSize.toLong()
            availableBytes >= (requiredBytes + headroomBytes)
        } catch (_: Exception) {
            // If StatFs fails, return true so sync is not blocked on unusual mount points
            true
        }
    }
}

