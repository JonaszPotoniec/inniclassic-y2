package com.themoon.y1.managers

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Shader
import android.media.MediaMetadataRetriever
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.LruCache
import android.widget.Toast
import com.themoon.y1.MainActivity
import com.themoon.y1.StoragePaths
import com.themoon.y1.controllers.coverflow.CoverFlowGeometry
import com.themoon.y1.models.SongItem
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.Collections
import java.util.HashSet
import java.util.concurrent.BlockingDeque
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * High-performance centralized album art and thumbnail manager for InniClassic on Y2.
 * Provides:
 * 1. Fast in-memory LRU caching of downsampled (256x256) album covers and reflections.
 * 2. Pre-scaled disk caching in /storage/sdcard0/.y1_thumbs_cache/.
 * 3. Bounded, LIFO-priority background thread pool preventing thread storms and CPU/SD thrashing.
 * 4. Shared access for both Cover Flow and the Album List (CategoryListAdapter).
 */
class AlbumCoverManager private constructor() {

    private val memoryCache: LruCache<String, Bitmap>
    private val executor: ThreadPoolExecutor
    private val mainHandler: Handler = Handler(Looper.getMainLooper())
    private val pendingKeys: MutableSet<String> = Collections.synchronizedSet(HashSet<String>())
    private val isPregeneratingFlag: AtomicBoolean = AtomicBoolean(false)

    interface CoverCallback {
        fun onCoverLoaded(albumKey: String, cover: Bitmap?, reflection: Bitmap?)
    }

    init {
        val maxMemory = (Runtime.getRuntime().maxMemory() / 1024).toInt()
        val cacheSizeKb = Math.min(8192, Math.max(2048, maxMemory / 8))

        memoryCache = object : LruCache<String, Bitmap>(cacheSizeKb) {
            override fun sizeOf(key: String, bitmap: Bitmap?): Int {
                if (bitmap == null) return 0
                return bitmap.byteCount / 1024
            }
        }

        val deque: BlockingDeque<Runnable> = object : LinkedBlockingDeque<Runnable>() {
            override fun offer(e: Runnable): Boolean {
                return super.offerFirst(e)
            }
        }

        val threadFactory = object : ThreadFactory {
            private val count = AtomicInteger(1)
            override fun newThread(r: Runnable): Thread {
                val t = Thread(r, "AlbumCoverWorker-" + count.getAndIncrement())
                t.priority = Thread.NORM_PRIORITY - 1
                return t
            }
        }

        executor = ThreadPoolExecutor(
            1, 2,
            30L, TimeUnit.SECONDS,
            deque,
            threadFactory,
            ThreadPoolExecutor.DiscardOldestPolicy()
        )
    }

    fun getDiskThumbFile(albumKey: String): File {
        val dir = StoragePaths.getThumbsCacheDir()
        return File(dir, getHashKey(albumKey) + ".jpg")
    }

    fun getMemoryCover(albumKey: String?): Bitmap? {
        if (albumKey == null) return null
        return memoryCache.get(albumKey)
    }

    fun getMemoryReflection(albumKey: String?): Bitmap? {
        if (albumKey == null) return null
        return memoryCache.get("ref_$albumKey")
    }

    fun getThumbnailDirect(albumKey: String?): Bitmap? {
        if (albumKey == null) return null
        val cached = memoryCache.get(albumKey)
        if (cached != null) return cached

        val diskFile = getDiskThumbFile(albumKey)
        if (diskFile.exists() && diskFile.length() > 0) {
            try {
                val opts = BitmapFactory.Options().apply {
                    inPreferredConfig = Bitmap.Config.RGB_565
                }
                val bmp = BitmapFactory.decodeFile(diskFile.absolutePath, opts)
                if (bmp != null) {
                    memoryCache.put(albumKey, bmp)
                    return bmp
                }
            } catch (ignored: Throwable) {}
        }
        return null
    }

    fun loadCoverAsync(song: SongItem?, callback: CoverCallback?) {
        if (song?.file == null) return
        val albumKey = getAlbumKey(song.file, song.album)

        val memBmp = getMemoryCover(albumKey)
        val memRef = getMemoryReflection(albumKey)
        if (memBmp != null) {
            callback?.onCoverLoaded(albumKey, memBmp, memRef)
            return
        }

        pendingKeys.add(albumKey)

        executor.execute {
            var bmp: Bitmap? = null
            val diskThumb = getDiskThumbFile(albumKey)

            if (diskThumb.exists() && diskThumb.length() > 0) {
                try {
                    val opts = BitmapFactory.Options().apply {
                        inPreferredConfig = Bitmap.Config.RGB_565
                    }
                    bmp = BitmapFactory.decodeFile(diskThumb.absolutePath, opts)
                } catch (t: Throwable) {
                    bmp = null
                }
            }

            if (bmp == null) {
                bmp = extractAndSaveThumbnail(song, diskThumb)
            }

            var ref: Bitmap? = null
            if (bmp != null) {
                ref = generateReflection(bmp)
                memoryCache.put(albumKey, bmp)
                if (ref != null) {
                    memoryCache.put("ref_$albumKey", ref)
                }
            }

            pendingKeys.remove(albumKey)

            val finalBmp = bmp
            val finalRef = ref
            mainHandler.post {
                callback?.onCoverLoaded(albumKey, finalBmp, finalRef)
            }
        }
    }

    private fun extractAndSaveThumbnail(item: SongItem?, targetThumbFile: File?): Bitmap? {
        if (item?.file == null) return null
        val trackPath = item.file.absolutePath
        var rawBmp: Bitmap? = null

        try {
            val act = MainActivity.instance
            if (act != null) {
                val cachedArtPath = act.prefs.getString("album_art_$trackPath", null)
                if (cachedArtPath != null && File(cachedArtPath).exists()) {
                    rawBmp = decodeSampledBitmapFromFile(cachedArtPath, THUMB_SIZE, THUMB_SIZE)
                }
            }
        } catch (ignored: Throwable) {}

        if (rawBmp == null) {
            try {
                var songName = item.file.name
                val dot = songName.lastIndexOf(".")
                if (dot > 0) songName = songName.substring(0, dot)
                val fallbackFile = File(StoragePaths.getCoversDir(), "$songName.jpg")
                if (fallbackFile.exists()) {
                    rawBmp = decodeSampledBitmapFromFile(fallbackFile.absolutePath, THUMB_SIZE, THUMB_SIZE)
                }
            } catch (ignored: Throwable) {}
        }

        if (rawBmp == null) {
            try {
                val act = MainActivity.instance
                if (act != null) {
                    val folderCover = act.findFolderCover(item.file.parentFile)
                    if (folderCover != null && folderCover.exists()) {
                        rawBmp = decodeSampledBitmapFromFile(folderCover.absolutePath, THUMB_SIZE, THUMB_SIZE)
                    }
                }
            } catch (ignored: Throwable) {}
        }

        if (rawBmp == null) {
            var embeddedArt: ByteArray? = null
            try {
                val lower = trackPath.lowercase()
                if (lower.endsWith(".opus")) {
                    val opusTags = AudioPlayerManager.getInstance().extractOpusMetadata(item.file)
                    if (opusTags != null && opusTags.size > 5 && opusTags[5] != null) {
                        embeddedArt = opusTags[5] as? ByteArray
                    }
                } else if (lower.endsWith(".flac")) {
                    val flacTags = AudioPlayerManager.getInstance().extractFlacMetadata(item.file)
                    if (flacTags != null && flacTags.size > 5 && flacTags[5] != null) {
                        embeddedArt = flacTags[5] as? ByteArray
                    }
                } else {
                    val mmr = MediaMetadataRetriever()
                    val fis = FileInputStream(item.file)
                    mmr.setDataSource(fis.fd)
                    embeddedArt = mmr.embeddedPicture
                    fis.close()
                    mmr.release()
                }
            } catch (ignored: Throwable) {}

            if (embeddedArt != null && embeddedArt.isNotEmpty()) {
                rawBmp = decodeSampledBitmapFromByteArray(embeddedArt, THUMB_SIZE, THUMB_SIZE)
            }
        }

        if (rawBmp == null) return null

        var finalThumb = rawBmp
        if (rawBmp.width != THUMB_SIZE || rawBmp.height != THUMB_SIZE) {
            try {
                finalThumb = Bitmap.createScaledBitmap(rawBmp, THUMB_SIZE, THUMB_SIZE, true)
                if (finalThumb !== rawBmp) {
                    rawBmp.recycle()
                }
            } catch (t: Throwable) {
                finalThumb = rawBmp
            }
        }

        if (finalThumb != null && targetThumbFile != null) {
            var fos: FileOutputStream? = null
            try {
                val parent = targetThumbFile.parentFile
                if (parent != null && !parent.exists()) {
                    parent.mkdirs()
                }
                fos = FileOutputStream(targetThumbFile)
                finalThumb.compress(Bitmap.CompressFormat.JPEG, 85, fos)
                fos.flush()
            } catch (t: Throwable) {
                Log.w(TAG, "Failed writing thumb cache for " + trackPath + ": " + t.message)
            } finally {
                try { fos?.close() } catch (ignored: Throwable) {}
            }
        }

        return finalThumb
    }

    fun isPregenerating(): Boolean {
        return isPregeneratingFlag.get()
    }

    fun clearDiskCache() {
        try {
            val dir = StoragePaths.getThumbsCacheDir()
            if (dir.exists()) {
                val files = dir.listFiles()
                if (files != null) {
                    for (f in files) {
                        f.delete()
                    }
                }
            }
        } catch (ignored: Throwable) {}
    }

    fun clearCache(memoryOnly: Boolean) {
        memoryCache.evictAll()
        if (!memoryOnly) {
            clearDiskCache()
        }
    }

    fun pregenerateThumbnails(uniqueAlbums: List<SongItem>?) {
        pregenerateThumbnails(uniqueAlbums, false)
    }

    fun pregenerateThumbnails(uniqueAlbums: List<SongItem>?, force: Boolean) {
        if (uniqueAlbums.isNullOrEmpty()) return
        if (!isPregeneratingFlag.compareAndSet(false, true)) {
            Log.d(TAG, "pregenerateThumbnails: already running, skipping request.")
            return
        }

        val copy: List<SongItem>
        synchronized(uniqueAlbums) {
            copy = ArrayList(uniqueAlbums)
        }

        Thread({
            try {
                val startTime = System.currentTimeMillis()
                val total = copy.size
                var generated = 0
                var skipped = 0
                Log.i(TAG, "Starting album thumbnail pregeneration for $total albums (force=$force)...")

                try {
                    Thread.sleep(800)
                } catch (ignored: InterruptedException) {}

                for (i in 0 until total) {
                    val item = copy[i]
                    if (item.file == null) continue
                    val albumKey = getAlbumKey(item.file, item.album)
                    val diskThumb = getDiskThumbFile(albumKey)

                    if (force || !diskThumb.exists() || diskThumb.length() == 0L) {
                        val bmp = extractAndSaveThumbnail(item, diskThumb)
                        if (bmp != null) {
                            generated++
                        }
                        try {
                            Thread.sleep(20)
                        } catch (ignored: InterruptedException) {}
                    } else {
                        skipped++
                    }

                    if ((i + 1) % 50 == 0 || i == total - 1) {
                        Log.i(
                            TAG, "Thumbnail pregeneration progress: " + (i + 1) + "/" + total
                                    + " (" + ((i + 1) * 100 / total) + "%) - generated=" + generated + ", skipped=" + skipped
                        )
                    }
                }

                val elapsed = System.currentTimeMillis() - startTime
                val finalGenerated = generated
                val finalSkipped = skipped
                Log.i(
                    TAG, "Thumbnail pregeneration complete in " + (elapsed / 1000f) + "s! Generated: "
                            + finalGenerated + ", Skipped: " + finalSkipped
                )

                if (finalGenerated > 0) {
                    MainActivity.instance?.let { act ->
                        act.runOnUiThread {
                            Toast.makeText(
                                act,
                                "Cover cache updated ($finalGenerated thumbnails ready)",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Error during thumbnail pregeneration: " + t.message, t)
            } finally {
                isPregeneratingFlag.set(false)
            }
        }, "AlbumCoverPregen").start()
    }

    companion object {
        private const val TAG = "AlbumCoverManager"
        const val THUMB_SIZE: Int = 256

        @Volatile
        private var sInstance: AlbumCoverManager? = null
        fun getInstance(): AlbumCoverManager {
            var inst = sInstance
            if (inst == null) {
                synchronized(AlbumCoverManager::class.java) {
                    inst = sInstance
                    if (inst == null) {
                        inst = AlbumCoverManager()
                        sInstance = inst
                    }
                }
            }
            return inst!!
        }
        fun getAlbumKey(file: File?, album: String?): String {
            val parent = if (file?.parentFile != null) file.parentFile.absolutePath else ""
            val safeAlbum = album ?: "Unknown Album"
            return "$parent - $safeAlbum"
        }
        fun getHashKey(input: String?): String {
            val s = input ?: ""
            return try {
                val md = MessageDigest.getInstance("MD5")
                val bytes = md.digest(s.toByteArray(Charsets.UTF_8))
                val sb = StringBuilder()
                for (b in bytes) {
                    sb.append(String.format("%02x", b))
                }
                sb.toString()
            } catch (t: Throwable) {
                s.hashCode().toString()
            }
        }
        fun decodeSampledBitmapFromFile(path: String, reqWidth: Int, reqHeight: Int): Bitmap? {
            return try {
                val options = BitmapFactory.Options().apply {
                    inJustDecodeBounds = true
                }
                BitmapFactory.decodeFile(path, options)

                options.inSampleSize = calculateInSampleSize(options, reqWidth, reqHeight)
                options.inJustDecodeBounds = false
                options.inPreferredConfig = Bitmap.Config.RGB_565
                BitmapFactory.decodeFile(path, options)
            } catch (t: Throwable) {
                null
            }
        }
        fun decodeSampledBitmapFromByteArray(data: ByteArray, reqWidth: Int, reqHeight: Int): Bitmap? {
            return try {
                val options = BitmapFactory.Options().apply {
                    inJustDecodeBounds = true
                }
                BitmapFactory.decodeByteArray(data, 0, data.size, options)

                options.inSampleSize = calculateInSampleSize(options, reqWidth, reqHeight)
                options.inJustDecodeBounds = false
                options.inPreferredConfig = Bitmap.Config.RGB_565
                BitmapFactory.decodeByteArray(data, 0, data.size, options)
            } catch (t: Throwable) {
                null
            }
        }
        fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
            val height = options.outHeight
            val width = options.outWidth
            var inSampleSize = 1

            if (height > reqHeight || width > reqWidth) {
                val halfHeight = height / 2
                val halfWidth = width / 2
                while ((halfHeight / inSampleSize) >= reqHeight && (halfWidth / inSampleSize) >= reqWidth) {
                    inSampleSize *= 2
                }
            }
            return Math.max(1, inSampleSize)
        }
        fun generateReflection(src: Bitmap?): Bitmap? {
            if (src == null) return null
            return try {
                val w = src.width
                val h = src.height
                // The iPod reflection: as tall as 78/192 of the cover, from 28 % opacity at the top to 0.
                val reqH = h * CoverFlowGeometry.REFLECTION_HEIGHT / CoverFlowGeometry.COVER_SIZE
                if (reqH <= 0) return null

                val matrix = Matrix().apply {
                    preScale(1f, -1f)
                }

                val flipped = Bitmap.createBitmap(src, 0, h - reqH, w, reqH, matrix, false)
                val reflection = Bitmap.createBitmap(w, reqH, Bitmap.Config.ARGB_8888)

                val canvas = Canvas(reflection)
                canvas.drawBitmap(flipped, 0f, 0f, null)
                flipped.recycle()

                val paint = Paint().apply {
                    shader = LinearGradient(
                        0f, 0f, 0f, reqH.toFloat(),
                        (CoverFlowGeometry.REFLECTION_TOP_ALPHA shl 24) or 0xFFFFFF, 0x00FFFFFF,
                        Shader.TileMode.CLAMP
                    )
                    xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
                }
                canvas.drawRect(0f, 0f, w.toFloat(), reqH.toFloat(), paint)

                reflection
            } catch (t: Throwable) {
                null
            }
        }
    }
}
