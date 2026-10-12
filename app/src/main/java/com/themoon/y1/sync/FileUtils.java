package com.themoon.y1.sync;

import android.os.StatFs;
import java.io.File;

public final class FileUtils {

    private FileUtils() {}

    /**
     * Sanitizes filenames to be safe on FAT32/VFAT/Linux:
     * Strips illegal characters [/ \ : * ? " < > |], trims leading/trailing dots/spaces.
     */
    public static String sanitizeFilename(String name) {
        if (name == null || name.trim().isEmpty()) {
            return "Unknown";
        }
        String sanitized = name.replaceAll("[/\\\\:*?\"<>|]", "_")
                               .replaceAll("^[._\\s]+|[._\\s]+$", "")
                               .trim();
        return sanitized.isEmpty() ? "Unknown" : sanitized;
    }

    /**
     * Checks if the destination volume has at least requiredBytes + headroomBytes available.
     */
    public static boolean hasAvailableSpace(File targetDir, long requiredBytes, long headroomBytes) {
        try {
            if (!targetDir.exists()) {
                targetDir.mkdirs();
            }
            StatFs stat = new StatFs(targetDir.getAbsolutePath());
            long availableBytes = (long) stat.getAvailableBlocks() * (long) stat.getBlockSize();
            return availableBytes >= (requiredBytes + headroomBytes);
        } catch (Exception e) {
            // If StatFs fails, return true so sync isn't unnecessarily blocked on weird mounts
            return true;
        }
    }
}
