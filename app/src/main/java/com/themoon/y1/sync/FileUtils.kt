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
