package com.themoon.y1.sync;

import android.util.Log;
import com.themoon.y1.StoragePaths;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Manages the persistent Jellyfin sync ledger (.y1_jellyfin_sync.json).
 * Keeps track of synced items, per-library sync modes ("all", "manual", "off"),
 * manual selections, and orphaned items.
 */
public class SyncLedger {

    private static final String TAG = "SyncLedger";
    private static final String LEDGER_FILENAME = ".y1_jellyfin_sync.json";

    public static class LedgerItem {
        public String jellyfinId;
        public String libraryId;
        public String collectionType;
        public String localPath;
        public long fileSize;
        public String serverDateModified;
        public boolean orphaned;

        public LedgerItem(String jellyfinId, String libraryId, String collectionType,
                          String localPath, long fileSize, String serverDateModified, boolean orphaned) {
            this.jellyfinId = jellyfinId;
            this.libraryId = libraryId;
            this.collectionType = collectionType;
            this.localPath = localPath;
            this.fileSize = fileSize;
            this.serverDateModified = serverDateModified;
            this.orphaned = orphaned;
        }

        public JSONObject toJson() {
            try {
                JSONObject obj = new JSONObject();
                obj.put("libraryId", libraryId);
                obj.put("collectionType", collectionType);
                obj.put("localPath", localPath);
                obj.put("fileSize", fileSize);
                obj.put("serverDateModified", serverDateModified != null ? serverDateModified : "");
                obj.put("orphaned", orphaned);
                return obj;
            } catch (Exception e) {
                return new JSONObject();
            }
        }

        public static LedgerItem fromJson(String id, JSONObject obj) {
            return new LedgerItem(
                    id,
                    obj.optString("libraryId", ""),
                    obj.optString("collectionType", ""),
                    obj.optString("localPath", ""),
                    obj.optLong("fileSize", 0),
                    obj.optString("serverDateModified", ""),
                    obj.optBoolean("orphaned", false)
            );
        }
    }

    private long lastSyncTime = 0;
    private final Map<String, String> librarySyncModes = new HashMap<>(); // libId -> "all"|"manual"|"off"
    private final Map<String, Set<String>> librarySelections = new HashMap<>(); // libId -> set of itemIds
    private final Map<String, LedgerItem> items = new HashMap<>(); // jellyfinId -> LedgerItem

    public synchronized long getLastSyncTime() {
        return lastSyncTime;
    }

    public synchronized void setLastSyncTime(long time) {
        this.lastSyncTime = time;
    }

    public synchronized String getLibrarySyncMode(String libraryId, String defaultMode) {
        String mode = librarySyncModes.get(libraryId);
        return (mode != null && !mode.isEmpty()) ? mode : defaultMode;
    }

    public synchronized void setLibrarySyncMode(String libraryId, String mode) {
        librarySyncModes.put(libraryId, mode);
    }

    public synchronized Set<String> getLibrarySelections(String libraryId) {
        Set<String> set = librarySelections.get(libraryId);
        return set != null ? new HashSet<>(set) : new HashSet<String>();
    }

    public synchronized void setLibrarySelections(String libraryId, Set<String> itemIds) {
        librarySelections.put(libraryId, new HashSet<>(itemIds));
    }

    public synchronized void toggleLibrarySelection(String libraryId, String itemId) {
        Set<String> set = librarySelections.get(libraryId);
        if (set == null) {
            set = new HashSet<>();
            librarySelections.put(libraryId, set);
        }
        if (set.contains(itemId)) {
            set.remove(itemId);
        } else {
            set.add(itemId);
        }
    }

    public synchronized void addLibrarySelections(String libraryId, java.util.Collection<String> itemIds) {
        if (itemIds == null || itemIds.isEmpty()) return;
        Set<String> set = librarySelections.get(libraryId);
        if (set == null) {
            set = new HashSet<>();
            librarySelections.put(libraryId, set);
        }
        set.addAll(itemIds);
    }

    public synchronized void removeLibrarySelections(String libraryId, java.util.Collection<String> itemIds) {
        if (itemIds == null || itemIds.isEmpty()) return;
        Set<String> set = librarySelections.get(libraryId);
        if (set != null) {
            set.removeAll(itemIds);
        }
    }

    public synchronized LedgerItem getItem(String jellyfinId) {
        return items.get(jellyfinId);
    }

    public synchronized void putItem(LedgerItem item) {
        items.put(item.jellyfinId, item);
    }

    public synchronized void removeItem(String jellyfinId) {
        items.remove(jellyfinId);
    }

    public synchronized Map<String, LedgerItem> getAllItems() {
        return new HashMap<>(items);
    }

    public synchronized List<LedgerItem> getItemsByLibrary(String libraryId) {
        List<LedgerItem> result = new ArrayList<>();
        for (LedgerItem item : items.values()) {
            if (libraryId.equals(item.libraryId)) {
                result.add(item);
            }
        }
        return result;
    }

    public synchronized void load() {
        File file = StoragePaths.primaryFile(LEDGER_FILENAME);
        if (!file.exists()) {
            return;
        }

        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new FileReader(file));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }

            JSONObject root = new JSONObject(sb.toString());
            lastSyncTime = root.optLong("lastSyncTime", 0);

            librarySyncModes.clear();
            JSONObject modesObj = root.optJSONObject("librarySyncModes");
            if (modesObj != null) {
                Iterator<?> keys = modesObj.keys();
                while (keys.hasNext()) {
                    String k = (String) keys.next();
                    librarySyncModes.put(k, modesObj.optString(k));
                }
            }

            librarySelections.clear();
            JSONObject selObj = root.optJSONObject("librarySelections");
            if (selObj != null) {
                Iterator<?> keys = selObj.keys();
                while (keys.hasNext()) {
                    String libId = (String) keys.next();
                    JSONArray arr = selObj.optJSONArray(libId);
                    Set<String> ids = new HashSet<>();
                    if (arr != null) {
                        for (int i = 0; i < arr.length(); i++) {
                            ids.add(arr.getString(i));
                        }
                    }
                    librarySelections.put(libId, ids);
                }
            }

            items.clear();
            JSONObject itemsObj = root.optJSONObject("items");
            if (itemsObj != null) {
                Iterator<?> keys = itemsObj.keys();
                while (keys.hasNext()) {
                    String itemId = (String) keys.next();
                    JSONObject itemJson = itemsObj.optJSONObject(itemId);
                    if (itemJson != null) {
                        items.put(itemId, LedgerItem.fromJson(itemId, itemJson));
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to load sync ledger: " + e.getMessage());
        } finally {
            if (reader != null) {
                try { reader.close(); } catch (Exception ignored) {}
            }
        }
    }

    public synchronized void save() {
        File file = StoragePaths.primaryFile(LEDGER_FILENAME);
        File tempFile = StoragePaths.primaryFile(LEDGER_FILENAME + ".tmp");
        BufferedWriter writer = null;
        try {
            JSONObject root = new JSONObject();
            root.put("version", 2);
            root.put("lastSyncTime", lastSyncTime);

            JSONObject modesObj = new JSONObject();
            for (Map.Entry<String, String> e : librarySyncModes.entrySet()) {
                modesObj.put(e.getKey(), e.getValue());
            }
            root.put("librarySyncModes", modesObj);

            JSONObject selObj = new JSONObject();
            for (Map.Entry<String, Set<String>> e : librarySelections.entrySet()) {
                JSONArray arr = new JSONArray();
                for (String id : e.getValue()) {
                    arr.put(id);
                }
                selObj.put(e.getKey(), arr);
            }
            root.put("librarySelections", selObj);

            JSONObject itemsObj = new JSONObject();
            for (Map.Entry<String, LedgerItem> e : items.entrySet()) {
                itemsObj.put(e.getKey(), e.getValue().toJson());
            }
            root.put("items", itemsObj);

            File parent = tempFile.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }

            writer = new BufferedWriter(new FileWriter(tempFile), 16384);
            writer.write(root.toString(2));
            writer.flush();
            writer.close();
            writer = null;

            if (tempFile.exists() && tempFile.length() > 0) {
                if (file.exists()) {
                    file.delete();
                }
                tempFile.renameTo(file);
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to save sync ledger: " + e.getMessage());
            if (tempFile.exists()) {
                tempFile.delete();
            }
        } finally {
            if (writer != null) {
                try { writer.close(); } catch (Exception ignored) {}
            }
        }
    }
}
