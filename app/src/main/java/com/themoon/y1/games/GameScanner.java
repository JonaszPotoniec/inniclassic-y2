package com.themoon.y1.games;

import android.util.Log;

import com.themoon.y1.StoragePaths;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class GameScanner {
    private static final String TAG = "GameScanner";

    public interface ScanProgressCallback {
        void onProgress(String status);
    }

    public static List<GameItem> scanGames() {
        return scanGames(null);
    }

    public static List<GameItem> scanGames(ScanProgressCallback callback) {
        List<GameItem> games = new ArrayList<>();
        List<File> gamesDirs = getGamesDirs();

        // 1. Deduplicate roots (avoid duplicate mount paths like /sdcard and /storage/sdcard0)
        Set<String> processedRoots = new HashSet<>();
        List<File> uniqueRoots = new ArrayList<>();
        for (File root : gamesDirs) {
            if (root == null || !root.exists() || !root.isDirectory()) continue;
            try {
                String canon = root.getCanonicalPath();
                if (processedRoots.add(canon)) {
                    uniqueRoots.add(root);
                }
            } catch (Exception e) {
                String abs = root.getAbsolutePath();
                if (processedRoots.add(abs)) {
                    uniqueRoots.add(root);
                }
            }
        }

        Set<String> seenCanonicalPaths = new HashSet<>();
        Set<String> seenTitles = new HashSet<>();

        for (File root : uniqueRoots) {
            File[] files = root.listFiles();
            if (files == null) continue;

            // Pass 1: Parse already-extracted / standard game directories first
            for (File file : files) {
                if (file.isDirectory()) {
                    GameItem item = parseGameDirectory(file);
                    if (item != null) {
                        addIfUnique(games, item, seenCanonicalPaths, seenTitles);
                    }
                }
            }

            // Pass 2: Inspect .ipg archives
            for (File file : files) {
                if (file.isFile() && file.getName().toLowerCase(Locale.US).endsWith(".ipg")) {
                    String baseName = getBaseName(file.getName());
                    File targetDir = new File(root, baseName);

                    GameItem existingItem = null;
                    if (targetDir.exists() && targetDir.isDirectory()) {
                        existingItem = parseGameDirectory(targetDir);
                    }

                    // If not extracted yet, or previously corrupted/incomplete, extract now
                    if (existingItem == null) {
                        if (callback != null) {
                            callback.onProgress(String.format("Extracting %s...", baseName));
                        }
                        extractIpg(file, targetDir);
                        if (targetDir.exists() && targetDir.isDirectory()) {
                            existingItem = parseGameDirectory(targetDir);
                        }
                    }

                    if (existingItem != null) {
                        addIfUnique(games, existingItem, seenCanonicalPaths, seenTitles);
                    }
                }
            }
        }

        Collections.sort(games, new Comparator<GameItem>() {
            @Override
            public int compare(GameItem a, GameItem b) {
                return a.title.compareToIgnoreCase(b.title);
            }
        });

        return games;
    }

    private static void addIfUnique(List<GameItem> games, GameItem item,
                                    Set<String> seenPaths, Set<String> seenTitles) {
        if (item == null || item.getBundleDir() == null) return;

        String canonicalPath;
        try {
            canonicalPath = item.getBundleDir().getCanonicalPath();
        } catch (Exception e) {
            canonicalPath = item.getBundleDir().getAbsolutePath();
        }

        String titleKey = item.getTitle() != null ? item.getTitle().trim().toLowerCase(Locale.US) : "";

        if (seenPaths.contains(canonicalPath) || (!titleKey.isEmpty() && seenTitles.contains(titleKey))) {
            Log.d(TAG, "Skipping duplicate game: " + item.getTitle() + " at " + canonicalPath);
            return;
        }

        seenPaths.add(canonicalPath);
        if (!titleKey.isEmpty()) {
            seenTitles.add(titleKey);
        }
        games.add(item);
        Log.i(TAG, "Indexed game: " + item.getTitle() + " (" + canonicalPath + ")");
    }

    public static List<File> getGamesDirs() {
        return StoragePaths.getGamesDirs();
    }

    private static GameItem parseGameDirectory(File dir) {
        if (dir == null || !dir.isDirectory()) return null;

        File exeDir = new File(dir, "Executables");
        if (!exeDir.exists() || !exeDir.isDirectory()) {
            // Check if nested (some ipg packs have GameName/GameName/Executables)
            File[] subs = dir.listFiles();
            if (subs != null) {
                for (File sub : subs) {
                    if (sub.isDirectory()) {
                        File subExe = new File(sub, "Executables");
                        if (subExe.exists() && subExe.isDirectory()) {
                            return parseGameDirectory(sub);
                        }
                    }
                }
            }
            return null;
        }

        File[] bins = exeDir.listFiles();
        boolean hasBin = false;
        if (bins != null) {
            for (File b : bins) {
                if (b.isFile() && b.getName().toLowerCase(Locale.US).endsWith(".bin")) {
                    hasBin = true;
                    break;
                }
            }
        }
        if (!hasBin) return null;

        String title = dir.getName();
        File iconFile = findIcon(dir);
        if (iconFile == null && dir.getParentFile() != null) {
            iconFile = findIcon(dir.getParentFile());
        }

        return new GameItem(title, dir, iconFile, "1.0");
    }

    private static File findIcon(File dir) {
        if (dir == null) return null;

        File artwork = new File(dir, "iTunesArtwork");
        if (artwork.exists()) return artwork;

        File png = new File(dir, "icon.png");
        if (png.exists()) return png;

        File jpg = new File(dir, "cover.jpg");
        if (jpg.exists()) return jpg;

        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.getName().toLowerCase(Locale.US).endsWith(".raw.lcd5")) {
                    return f;
                }
            }
        }
        return null;
    }

    private static String getBaseName(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static void extractIpg(File zipFile, File targetDir) {
        ZipInputStream zis = null;
        try {
            targetDir.mkdirs();
            zis = new ZipInputStream(new FileInputStream(zipFile));
            ZipEntry entry;
            byte[] buffer = new byte[32768];
            while ((entry = zis.getNextEntry()) != null) {
                File out = new File(targetDir, entry.getName());
                if (entry.isDirectory()) {
                    out.mkdirs();
                } else {
                    File parent = out.getParentFile();
                    if (parent != null && !parent.exists()) parent.mkdirs();
                    FileOutputStream fos = new FileOutputStream(out);
                    try {
                        int len;
                        while ((len = zis.read(buffer)) > 0) {
                            fos.write(buffer, 0, len);
                        }
                    } finally {
                        fos.close();
                    }
                }
                zis.closeEntry();
            }
            Log.i(TAG, "Extracted " + zipFile.getName() + " to " + targetDir.getAbsolutePath());
        } catch (Exception e) {
            Log.e(TAG, "Failed to extract IPG: " + zipFile.getName(), e);
        } finally {
            if (zis != null) {
                try {
                    zis.close();
                } catch (Exception ignored) {}
            }
        }
    }
}
