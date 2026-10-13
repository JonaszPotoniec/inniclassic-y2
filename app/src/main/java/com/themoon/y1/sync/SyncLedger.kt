package com.themoon.y1.sync

import android.util.Log
import com.themoon.y1.StoragePaths
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.FileReader
import java.io.FileWriter

/**
 * Manages the persistent Jellyfin sync ledger (.y1_jellyfin_sync.json).
 * Keeps track of synced items, per-library sync modes ("all", "manual", "off"),
 * manual selections, and orphaned items.
 */
class SyncLedger {

    class LedgerItem(
        @JvmField var jellyfinId: String,
        @JvmField var libraryId: String?,
        @JvmField var collectionType: String?,
        @JvmField var localPath: String?,
        @JvmField var fileSize: Long,
        @JvmField var serverDateModified: String?,
        @JvmField var orphaned: Boolean
    ) {
        fun toJson(): JSONObject {
            return try {
                JSONObject().apply {
                    put("libraryId", libraryId ?: "")
                    put("collectionType", collectionType ?: "")
                    put("localPath", localPath ?: "")
                    put("fileSize", fileSize)
                    put("serverDateModified", serverDateModified ?: "")
                    put("orphaned", orphaned)
                }
            } catch (_: Exception) {
                JSONObject()
            }
        }

        companion object {
            @JvmStatic
            fun fromJson(id: String, obj: JSONObject): LedgerItem {
                return LedgerItem(
                    id,
                    obj.optString("libraryId", ""),
                    obj.optString("collectionType", ""),
                    obj.optString("localPath", ""),
                    obj.optLong("fileSize", 0),
                    obj.optString("serverDateModified", ""),
                    obj.optBoolean("orphaned", false)
                )
            }
        }
    }

    private var lastSyncTime: Long = 0
    private val librarySyncModes = HashMap<String, String>() // libId -> "all"|"manual"|"off"
    private val librarySelections = HashMap<String, MutableSet<String>>() // libId -> set of itemIds
    private val items = HashMap<String, LedgerItem>() // jellyfinId -> LedgerItem

    @Synchronized
    fun getLastSyncTime(): Long = lastSyncTime

    @Synchronized
    fun setLastSyncTime(time: Long) {
        this.lastSyncTime = time
    }

    @Synchronized
    fun getLibrarySyncMode(libraryId: String, defaultMode: String): String {
        val mode = librarySyncModes[libraryId]
        return if (!mode.isNullOrEmpty()) mode else defaultMode
    }

    @Synchronized
    fun setLibrarySyncMode(libraryId: String, mode: String) {
        librarySyncModes[libraryId] = mode
    }

    @Synchronized
    fun getLibrarySelections(libraryId: String): Set<String> {
        val set = librarySelections[libraryId]
        return if (set != null) HashSet(set) else HashSet()
    }

    @Synchronized
    fun setLibrarySelections(libraryId: String, itemIds: Set<String>) {
        librarySelections[libraryId] = HashSet(itemIds)
    }

    @Synchronized
    fun toggleLibrarySelection(libraryId: String, itemId: String) {
        val set = librarySelections.getOrPut(libraryId) { HashSet() }
        if (set.contains(itemId)) {
            set.remove(itemId)
        } else {
            set.add(itemId)
        }
    }

    @Synchronized
    fun addLibrarySelections(libraryId: String, itemIds: Collection<String>?) {
        if (itemIds.isNullOrEmpty()) return
        val set = librarySelections.getOrPut(libraryId) { HashSet() }
        set.addAll(itemIds)
    }

    @Synchronized
    fun removeLibrarySelections(libraryId: String, itemIds: Collection<String>?) {
        if (itemIds.isNullOrEmpty()) return
        librarySelections[libraryId]?.removeAll(itemIds.toSet())
    }

    @Synchronized
    fun getItem(jellyfinId: String): LedgerItem? = items[jellyfinId]

    @Synchronized
    fun putItem(item: LedgerItem) {
        items[item.jellyfinId] = item
    }

    @Synchronized
    fun removeItem(jellyfinId: String) {
        items.remove(jellyfinId)
    }

    @Synchronized
    fun getAllItems(): Map<String, LedgerItem> = HashMap(items)

    @Synchronized
    fun getItemsByLibrary(libraryId: String): List<LedgerItem> {
        val result = ArrayList<LedgerItem>()
        for (item in items.values) {
            if (libraryId == item.libraryId) {
                result.add(item)
            }
        }
        return result
    }

    @Synchronized
    fun load() {
        val file = StoragePaths.primaryFile(LEDGER_FILENAME)
        if (!file.exists()) return

        var reader: BufferedReader? = null
        try {
            reader = BufferedReader(FileReader(file))
            val sb = StringBuilder()
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                sb.append(line)
            }

            val root = JSONObject(sb.toString())
            lastSyncTime = root.optLong("lastSyncTime", 0)

            librarySyncModes.clear()
            val modesObj = root.optJSONObject("librarySyncModes")
            if (modesObj != null) {
                val keys = modesObj.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    librarySyncModes[k] = modesObj.optString(k)
                }
            }

            librarySelections.clear()
            val selObj = root.optJSONObject("librarySelections")
            if (selObj != null) {
                val keys = selObj.keys()
                while (keys.hasNext()) {
                    val libId = keys.next()
                    val arr = selObj.optJSONArray(libId)
                    val ids = HashSet<String>()
                    if (arr != null) {
                        for (i in 0 until arr.length()) {
                            ids.add(arr.getString(i))
                        }
                    }
                    librarySelections[libId] = ids
                }
            }

            items.clear()
            val itemsObj = root.optJSONObject("items")
            if (itemsObj != null) {
                val keys = itemsObj.keys()
                while (keys.hasNext()) {
                    val itemId = keys.next()
                    val itemJson = itemsObj.optJSONObject(itemId)
                    if (itemJson != null) {
                        items[itemId] = LedgerItem.fromJson(itemId, itemJson)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load sync ledger: ${e.message}")
        } finally {
            try {
                reader?.close()
            } catch (_: Exception) {
            }
        }
    }

    @Synchronized
    fun save() {
        val file = StoragePaths.primaryFile(LEDGER_FILENAME)
        val tempFile = StoragePaths.primaryFile("$LEDGER_FILENAME.tmp")
        var writer: BufferedWriter? = null
        try {
            val root = JSONObject().apply {
                put("version", 2)
                put("lastSyncTime", lastSyncTime)

                val modesObj = JSONObject()
                for ((key, value) in librarySyncModes) {
                    modesObj.put(key, value)
                }
                put("librarySyncModes", modesObj)

                val selObj = JSONObject()
                for ((key, value) in librarySelections) {
                    val arr = JSONArray()
                    for (id in value) {
                        arr.put(id)
                    }
                    selObj.put(key, arr)
                }
                put("librarySelections", selObj)

                val itemsObj = JSONObject()
                for ((key, value) in items) {
                    itemsObj.put(key, value.toJson())
                }
                put("items", itemsObj)
            }

            val parent = tempFile.parentFile
            if (parent != null && !parent.exists()) {
                parent.mkdirs()
            }

            writer = BufferedWriter(FileWriter(tempFile), 16384)
            writer.write(root.toString(2))
            writer.flush()
            writer.close()
            writer = null

            if (tempFile.exists() && tempFile.length() > 0) {
                if (file.exists()) {
                    file.delete()
                }
                tempFile.renameTo(file)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save sync ledger: ${e.message}")
            if (tempFile.exists()) {
                tempFile.delete()
            }
        } finally {
            try {
                writer?.close()
            } catch (_: Exception) {
            }
        }
    }

    companion object {
        private const val TAG = "SyncLedger"
        private const val LEDGER_FILENAME = ".y1_jellyfin_sync.json"
    }
}
