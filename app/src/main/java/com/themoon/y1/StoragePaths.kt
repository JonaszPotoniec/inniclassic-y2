package com.themoon.y1

import com.themoon.y1.managers.ExternalSdMountMonitor
import java.io.File
import java.util.Locale

/**
 * Resolves Y1/Y2 storage roots while keeping Y1 paths as the default.
 * - Y1 / primary: /storage/sdcard0 - browser defaults, app data, web server
 * - Y2 microSD: /storage/sdcard1 - scanned when readable; never replaces Y1 defaults
 */
object StoragePaths {

    const val PRIMARY_PATH: String = "/storage/sdcard0"
    const val SECONDARY_PATH: String = "/storage/sdcard1"

    @Volatile
    private var cachedPrimary: File? = null

    @Volatile
    private var cachedRoots: List<File>? = null

    @Volatile
    private var cachedSecondaryAvailable: Boolean? = null

    /** Primary writable root - same volume Y1 always used. */
    fun getPrimaryRoot(): File {
        cachedPrimary?.let { return it }
        val primary = firstUsableRoot(PRIMARY_PATH, "/storage/emulated/legacy", "/sdcard")
        val result = primary ?: File(PRIMARY_PATH)
        cachedPrimary = result
        return result
    }

    /**
     * True when /storage/sdcard1 exists. Does not call list() to prevent hanging on wedged mounts.
     */
    fun hasSecondaryStorage(): Boolean {
        cachedSecondaryAvailable?.let { return it }
        var available = false
        try {
            available = if (ExternalSdMountMonitor.isSecondaryReady()) {
                true
            } else {
                File(SECONDARY_PATH).exists()
            }
        } catch (_: Exception) {
        }
        cachedSecondaryAvailable = available
        return available
    }

    /**
     * Primary always; secondary only when hasSecondaryStorage() is true.
     */
    fun getAllRoots(): List<File> {
        cachedRoots?.let { return it }

        val paths = LinkedHashSet<String>()
        paths.add(getPrimaryRoot().absolutePath)
        if (PRIMARY_PATH != getPrimaryRoot().absolutePath) {
            paths.add(PRIMARY_PATH)
        }

        if (hasSecondaryStorage()) {
            paths.add(SECONDARY_PATH)
            val secondaryEnv = System.getenv("SECONDARY_STORAGE")
            if (!secondaryEnv.isNullOrEmpty()) {
                for (part in secondaryEnv.split(":")) {
                    if (part.isNotBlank()) {
                        paths.add(part.trim())
                    }
                }
            }
        }

        val roots = ArrayList<File>()
        for (path in paths) {
            val f = File(path)
            if (!containsSameRoot(roots, f)) {
                roots.add(f)
            }
        }
        if (roots.isEmpty()) {
            roots.add(getPrimaryRoot())
        }

        cachedRoots = roots
        return roots
    }

    /** Force re-probe after SD card mount or unmount. */
    fun invalidate() {
        cachedPrimary = null
        cachedRoots = null
        cachedSecondaryAvailable = null
    }

    /** Folder-browser / mkdir default - always primary. */
    fun getMusicDir(): File = primaryMediaDir("Music")

    fun getAudiobooksDir(): File = primaryMediaDir("Audiobooks")

    fun getVideosDir(): File = primaryMediaDir("Videos")

    /** All Music folders to scan (primary + readable secondary). */
    fun getMusicDirs(): List<File> = mediaDirs("Music")

    fun getAudiobooksDirs(): List<File> = mediaDirs("Audiobooks")

    fun getVideosDirs(): List<File> = mediaDirs("Videos")

    fun getPodcastsDir(): File = File(getPrimaryRoot(), "Podcasts")

    fun getPodcastChannelDir(safeChannel: String): File = File(getPodcastsDir(), safeChannel)

    fun getCoversDir(): File = ensureDir(File(getPrimaryRoot(), "Y1_Covers"))

    fun getThumbsCacheDir(): File = ensureDir(File(getPrimaryRoot(), ".y1_thumbs_cache"))

    fun getThemesDir(): File = ensureDir(File(getPrimaryRoot(), "Y1_Themes"))

    fun getLanguagesDir(): File = ensureDir(File(getPrimaryRoot(), "Y1_Languages"))

    fun getPlaylistsDir(): File = ensureDir(File(getPrimaryRoot(), "Y1_Playlists"))

    fun getEqDir(): File = ensureDir(File(getPrimaryRoot(), "Y1_EQs"))

    fun primaryFile(relativePath: String): File = File(getPrimaryRoot(), relativePath)

    /** Web upload root stays on primary so URL paths are unchanged. */
    fun getWebServerRoot(): File = getPrimaryRoot()

    fun isStorageVolumeName(name: String?): Boolean {
        if (name == null) return false
        val n = name.lowercase(Locale.US)
        return n == "sdcard0" || n == "sdcard1" || n == "emulated" ||
            n == "legacy" || n == "media_rw"
    }

    fun isAnyStorageRoot(absolutePath: String?): Boolean {
        if (absolutePath == null) return false
        if (absolutePath == PRIMARY_PATH || absolutePath == SECONDARY_PATH) return true
        for (root in getAllRoots()) {
            if (absolutePath == root.absolutePath) return true
        }
        return false
    }

    fun isVideosRoot(absolutePath: String?): Boolean {
        if (absolutePath == null) return false
        if (absolutePath == "$PRIMARY_PATH/Videos" || absolutePath == "$SECONDARY_PATH/Videos") return true
        for (root in getAllRoots()) {
            if (absolutePath == File(root, "Videos").absolutePath) return true
        }
        return false
    }

    fun isAudiobooksRoot(absolutePath: String?): Boolean {
        if (absolutePath == null) return false
        if (absolutePath == "$PRIMARY_PATH/Audiobooks" || absolutePath == "$SECONDARY_PATH/Audiobooks") return true
        for (dir in getAudiobooksDirs()) {
            if (absolutePath == dir.absolutePath) return true
        }
        return false
    }

    fun isUnderAudiobooks(absolutePath: String?): Boolean {
        if (absolutePath == null) return false
        if (absolutePath.startsWith("$PRIMARY_PATH/Audiobooks") ||
            absolutePath.startsWith("$SECONDARY_PATH/Audiobooks")
        ) return true
        for (dir in getAudiobooksDirs()) {
            val root = dir.absolutePath
            if (absolutePath == root || absolutePath.startsWith("$root/")) return true
        }
        return false
    }

    fun isUnderAudiobooks(file: File?): Boolean =
        file != null && isUnderAudiobooks(file.absolutePath)

    fun isUnderMusic(absolutePath: String?): Boolean {
        if (absolutePath == null) return false
        if (absolutePath.startsWith("$PRIMARY_PATH/Music") ||
            absolutePath.startsWith("$SECONDARY_PATH/Music")
        ) return true
        for (dir in getMusicDirs()) {
            val root = dir.absolutePath
            if (absolutePath == root || absolutePath.startsWith("$root/")) return true
        }
        return false
    }

    fun isUnderMusic(file: File?): Boolean =
        file != null && isUnderMusic(file.absolutePath)

    /** Strip known volume prefixes so the browser path stays short. */
    fun toDisplayPath(absolutePath: String?): String {
        if (absolutePath == null) return ""
        val path = absolutePath
        for (prefix in arrayOf(
            getPrimaryRoot().absolutePath, PRIMARY_PATH, SECONDARY_PATH,
            "/storage/emulated/legacy"
        )) {
            if (path == prefix) return "/"
            if (path.startsWith("$prefix/")) return path.substring(prefix.length)
        }
        for (root in getAllRoots()) {
            val prefix = root.absolutePath
            if (path == prefix) return "/"
            if (path.startsWith("$prefix/")) return path.substring(prefix.length)
        }
        return path
    }

    private fun primaryMediaDir(name: String): File {
        val primary = File(getPrimaryRoot(), name)
        if (!primary.exists()) primary.mkdirs()
        return primary
    }

    private fun mediaDirs(name: String): List<File> {
        val dirs = ArrayList<File>()
        addIfMissing(dirs, File(getPrimaryRoot(), name))
        addIfMissing(dirs, File(PRIMARY_PATH, name))
        if (hasSecondaryStorage()) {
            addIfMissing(dirs, File(SECONDARY_PATH, name))
        }
        return dirs
    }

    private fun addIfMissing(dirs: MutableList<File>, dir: File) {
        if (!containsSameRoot(dirs, dir)) {
            dirs.add(dir)
        }
    }

    private fun firstUsableRoot(vararg candidates: String): File? {
        for (path in candidates) {
            val f = File(path)
            if (isUsableRoot(f)) return f
        }
        return null
    }

    private fun isUsableRoot(f: File?): Boolean {
        if (f == null) return false
        return try {
            f.exists()
        } catch (_: Exception) {
            false
        }
    }

    private fun containsSameRoot(roots: List<File>, candidate: File): Boolean {
        val path = candidate.absolutePath
        for (existing in roots) {
            if (existing.absolutePath == path) return true
        }
        return false
    }

    private fun ensureDir(dir: File): File {
        try {
            if (!dir.exists()) dir.mkdirs()
        } catch (_: Exception) {
        }
        return dir
    }
}
