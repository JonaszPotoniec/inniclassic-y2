package com.themoon.y1.migrations;

import com.themoon.y1.StoragePaths;
import java.io.File;

/**
 * Migration 2: Purges OEM factory demo media (e.g. Despacito, Panama, A-Pink demo video)
 * copied to /storage/sdcard0/custom_media by stock ROM preload scripts, and requests
 * a media library rescan so any previously cached demo entries are pruned from the library.
 */
public class Migration002PurgeOemDemoMedia implements Migration {
    public static final int VERSION = 2;
    public static final String ID = "002_purge_oem_demo_media";

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
        return "Purge OEM preloaded demo media (Despacito, Panama, etc.) and prune from library";
    }

    @Override
    public void migrate(MigrationContext context) throws Exception {
        // 1. Attempt to clean up OEM factory demo files in custom_media folders across all roots
        for (File root : StoragePaths.getAllRoots()) {
            File customDir = new File(root, "custom_media");
            if (customDir.exists() && customDir.isDirectory()) {
                deleteOemDemoFiles(customDir);
            }
        }

        // 2. Request an incremental media rescan so customLibrary prunes any tracks outside of Music/
        context.requestMediaRescan();
    }

    private void deleteOemDemoFiles(File dir) {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            String name = f.getName().toLowerCase();
            if (name.contains("despacito") || name.contains("panama") || name.contains("demo") || name.equals(".mediafile")) {
                try {
                    f.delete();
                } catch (Exception ignored) {}
            }
        }
        // If directory is now empty, remove it as well
        File[] remaining = dir.listFiles();
        if (remaining != null && remaining.length == 0) {
            try {
                dir.delete();
            } catch (Exception ignored) {}
        }
    }
}
