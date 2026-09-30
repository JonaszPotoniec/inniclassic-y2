package com.themoon.y1.managers;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Shader;
import android.media.MediaMetadataRetriever;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.LruCache;

import com.themoon.y1.MainActivity;
import com.themoon.y1.StoragePaths;
import com.themoon.y1.models.SongItem;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.BlockingDeque;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * High-performance centralized album art and thumbnail manager for InniClassic on Y2.
 * Provides:
 * 1. Fast in-memory LRU caching of downsampled (256x256) album covers and reflections.
 * 2. Pre-scaled disk caching in /storage/sdcard0/.y1_thumbs_cache/.
 * 3. Bounded, LIFO-priority background thread pool preventing thread storms and CPU/SD thrashing.
 * 4. Shared access for both Cover Flow and the Album List (CategoryListAdapter).
 */
public class AlbumCoverManager {
    private static final String TAG = "AlbumCoverManager";
    public static final int THUMB_SIZE = 256;

    private static volatile AlbumCoverManager sInstance;

    private final LruCache<String, Bitmap> mMemoryCache;
    private final ThreadPoolExecutor mExecutor;
    private final Handler mMainHandler;
    private final Set<String> mPendingKeys = Collections.synchronizedSet(new HashSet<String>());
    private final AtomicBoolean mIsPregenerating = new AtomicBoolean(false);

    public interface CoverCallback {
        void onCoverLoaded(String albumKey, Bitmap cover, Bitmap reflection);
    }

    public static AlbumCoverManager getInstance() {
        if (sInstance == null) {
            synchronized (AlbumCoverManager.class) {
                if (sInstance == null) {
                    sInstance = new AlbumCoverManager();
                }
            }
        }
        return sInstance;
    }

    private AlbumCoverManager() {
        mMainHandler = new Handler(Looper.getMainLooper());

        // Allocate up to 8MB for album art RAM cache (fits ~50 complete 256x256 covers + reflections)
        final int maxMemory = (int) (Runtime.getRuntime().maxMemory() / 1024);
        final int cacheSizeKb = Math.min(8192, Math.max(2048, maxMemory / 8));

        mMemoryCache = new LruCache<String, Bitmap>(cacheSizeKb) {
            @Override
            protected int sizeOf(String key, Bitmap bitmap) {
                if (bitmap == null) return 0;
                return bitmap.getByteCount() / 1024;
            }
        };

        // LIFO bounded executor: single background worker with LIFO queue for maximum responsiveness
        BlockingDeque<Runnable> deque = new LinkedBlockingDeque<Runnable>() {
            @Override
            public boolean offer(Runnable e) {
                // Insert at front of deque (LIFO) so latest scrolled album loads first
                return super.offerFirst(e);
            }
        };

        final ThreadFactory threadFactory = new ThreadFactory() {
            private final AtomicInteger mCount = new AtomicInteger(1);
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "AlbumCoverWorker-" + mCount.getAndIncrement());
                t.setPriority(Thread.NORM_PRIORITY - 1);
                return t;
            }
        };

        mExecutor = new ThreadPoolExecutor(
                1, 2,
                30L, TimeUnit.SECONDS,
                deque,
                threadFactory,
                new ThreadPoolExecutor.DiscardOldestPolicy()
        );
    }

    /**
     * Build unique deterministic album key.
     */
    public static String getAlbumKey(File file, String album) {
        String parent = (file != null && file.getParentFile() != null)
                ? file.getParentFile().getAbsolutePath() : "";
        String safeAlbum = (album != null) ? album : "Unknown Album";
        return parent + " - " + safeAlbum;
    }

    /**
     * Compute MD5 hash for filename safety across FAT32/exFAT and multi-byte charsets.
     */
    public static String getHashKey(String input) {
        if (input == null) input = "";
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] bytes = md.digest(input.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : bytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Throwable t) {
            return String.valueOf(input.hashCode());
        }
    }

    public File getDiskThumbFile(String albumKey) {
        File dir = StoragePaths.getThumbsCacheDir();
        return new File(dir, getHashKey(albumKey) + ".jpg");
    }

    /**
     * Immediate synchronous memory lookup for cover bitmap.
     */
    public Bitmap getMemoryCover(String albumKey) {
        if (albumKey == null) return null;
        return mMemoryCache.get(albumKey);
    }

    /**
     * Immediate synchronous memory lookup for reflection bitmap.
     */
    public Bitmap getMemoryReflection(String albumKey) {
        if (albumKey == null) return null;
        return mMemoryCache.get("ref_" + albumKey);
    }

    /**
     * Fast direct thumbnail lookup: checks RAM first, then disk thumbnail file synchronously.
     * Ideal for list rows (CategoryListAdapter) without launching heavy threads.
     */
    public Bitmap getThumbnailDirect(String albumKey) {
        if (albumKey == null) return null;
        Bitmap cached = mMemoryCache.get(albumKey);
        if (cached != null) return cached;

        File diskFile = getDiskThumbFile(albumKey);
        if (diskFile.exists() && diskFile.length() > 0) {
            try {
                BitmapFactory.Options opts = new BitmapFactory.Options();
                opts.inPreferredConfig = Bitmap.Config.RGB_565;
                Bitmap bmp = BitmapFactory.decodeFile(diskFile.getAbsolutePath(), opts);
                if (bmp != null) {
                    mMemoryCache.put(albumKey, bmp);
                    return bmp;
                }
            } catch (Throwable ignored) {}
        }
        return null;
    }

    /**
     * Asynchronously loads or generates cover and reflection for the given album.
     */
    public void loadCoverAsync(final SongItem song, final CoverCallback callback) {
        if (song == null || song.file == null) return;
        final String albumKey = getAlbumKey(song.file, song.album);

        // 1. RAM Cache check (instant 0ms)
        final Bitmap memBmp = getMemoryCover(albumKey);
        final Bitmap memRef = getMemoryReflection(albumKey);
        if (memBmp != null) {
            if (callback != null) {
                callback.onCoverLoaded(albumKey, memBmp, memRef);
            }
            return;
        }

        mPendingKeys.add(albumKey);

        mExecutor.execute(new Runnable() {
            @Override
            public void run() {
                Bitmap bmp = null;
                File diskThumb = getDiskThumbFile(albumKey);

                // 2. Fast disk thumbnail check (~1-2ms)
                if (diskThumb.exists() && diskThumb.length() > 0) {
                    try {
                        BitmapFactory.Options opts = new BitmapFactory.Options();
                        opts.inPreferredConfig = Bitmap.Config.RGB_565;
                        bmp = BitmapFactory.decodeFile(diskThumb.getAbsolutePath(), opts);
                    } catch (Throwable t) {
                        bmp = null;
                    }
                }

                // 3. Extraction from source files if thumbnail is missing
                if (bmp == null) {
                    bmp = extractAndSaveThumbnail(song, diskThumb);
                }

                Bitmap ref = null;
                if (bmp != null) {
                    ref = generateReflection(bmp);
                    mMemoryCache.put(albumKey, bmp);
                    if (ref != null) {
                        mMemoryCache.put("ref_" + albumKey, ref);
                    }
                }

                mPendingKeys.remove(albumKey);

                final Bitmap finalBmp = bmp;
                final Bitmap finalRef = ref;
                mMainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (callback != null) {
                            callback.onCoverLoaded(albumKey, finalBmp, finalRef);
                        }
                    }
                });
            }
        });
    }

    /**
     * Extracts album art from file metadata or folder cover, downscales to 256x256,
     * and persists to the disk thumbnail cache.
     */
    private Bitmap extractAndSaveThumbnail(SongItem item, File targetThumbFile) {
        if (item == null || item.file == null) return null;
        String trackPath = item.file.getAbsolutePath();
        Bitmap rawBmp = null;

        // 1. SharedPreferences pre-cached path check
        try {
            if (MainActivity.instance != null && MainActivity.instance.prefs != null) {
                String cachedArtPath = MainActivity.instance.prefs.getString("album_art_" + trackPath, null);
                if (cachedArtPath != null && new File(cachedArtPath).exists()) {
                    rawBmp = decodeSampledBitmapFromFile(cachedArtPath, THUMB_SIZE, THUMB_SIZE);
                }
            }
        } catch (Throwable ignored) {}

        // 2. Y1_Covers directory check
        if (rawBmp == null) {
            try {
                String songName = item.file.getName();
                int dot = songName.lastIndexOf(".");
                if (dot > 0) songName = songName.substring(0, dot);
                File fallbackFile = new File(StoragePaths.getCoversDir(), songName + ".jpg");
                if (fallbackFile.exists()) {
                    rawBmp = decodeSampledBitmapFromFile(fallbackFile.getAbsolutePath(), THUMB_SIZE, THUMB_SIZE);
                }
            } catch (Throwable ignored) {}
        }

        // 3. Same folder cover.jpg / folder.jpg check
        if (rawBmp == null) {
            try {
                if (MainActivity.instance != null) {
                    File folderCover = MainActivity.instance.findFolderCover(item.file.getParentFile());
                    if (folderCover != null && folderCover.exists()) {
                        rawBmp = decodeSampledBitmapFromFile(folderCover.getAbsolutePath(), THUMB_SIZE, THUMB_SIZE);
                    }
                }
            } catch (Throwable ignored) {}
        }

        // 4. Embedded audio tags extraction (Opus, FLAC, MMR)
        if (rawBmp == null) {
            byte[] embeddedArt = null;
            try {
                String lower = trackPath.toLowerCase();
                if (lower.endsWith(".opus")) {
                    Object[] opusTags = AudioPlayerManager.getInstance().extractOpusMetadata(item.file);
                    if (opusTags != null && opusTags.length > 5 && opusTags[5] != null) {
                        embeddedArt = (byte[]) opusTags[5];
                    }
                } else if (lower.endsWith(".flac")) {
                    Object[] flacTags = AudioPlayerManager.getInstance().extractFlacMetadata(item.file);
                    if (flacTags != null && flacTags.length > 5 && flacTags[5] != null) {
                        embeddedArt = (byte[]) flacTags[5];
                    }
                } else {
                    MediaMetadataRetriever mmr = new MediaMetadataRetriever();
                    FileInputStream fis = new FileInputStream(item.file);
                    mmr.setDataSource(fis.getFD());
                    embeddedArt = mmr.getEmbeddedPicture();
                    fis.close();
                    mmr.release();
                }
            } catch (Throwable ignored) {}

            if (embeddedArt != null && embeddedArt.length > 0) {
                rawBmp = decodeSampledBitmapFromByteArray(embeddedArt, THUMB_SIZE, THUMB_SIZE);
            }
        }

        if (rawBmp == null) return null;

        // Ensure exactly THUMB_SIZE x THUMB_SIZE
        Bitmap finalThumb = rawBmp;
        if (rawBmp.getWidth() != THUMB_SIZE || rawBmp.getHeight() != THUMB_SIZE) {
            try {
                finalThumb = Bitmap.createScaledBitmap(rawBmp, THUMB_SIZE, THUMB_SIZE, true);
                if (finalThumb != rawBmp) {
                    rawBmp.recycle();
                }
            } catch (Throwable t) {
                finalThumb = rawBmp;
            }
        }

        // Save downscaled JPEG to disk cache
        if (finalThumb != null && targetThumbFile != null) {
            FileOutputStream fos = null;
            try {
                File parent = targetThumbFile.getParentFile();
                if (parent != null && !parent.exists()) {
                    parent.mkdirs();
                }
                fos = new FileOutputStream(targetThumbFile);
                finalThumb.compress(Bitmap.CompressFormat.JPEG, 85, fos);
                fos.flush();
            } catch (Throwable t) {
                Log.w(TAG, "Failed writing thumb cache for " + trackPath + ": " + t.getMessage());
            } finally {
                if (fos != null) {
                    try { fos.close(); } catch (Throwable ignored) {}
                }
            }
        }

        return finalThumb;
    }

    /**
     * Efficiently decodes a sampled Bitmap from file matching reqWidth and reqHeight.
     */
    public static Bitmap decodeSampledBitmapFromFile(String path, int reqWidth, int reqHeight) {
        try {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(path, options);

            options.inSampleSize = calculateInSampleSize(options, reqWidth, reqHeight);
            options.inJustDecodeBounds = false;
            options.inPreferredConfig = Bitmap.Config.RGB_565;
            return BitmapFactory.decodeFile(path, options);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Efficiently decodes a sampled Bitmap from byte array matching reqWidth and reqHeight.
     */
    public static Bitmap decodeSampledBitmapFromByteArray(byte[] data, int reqWidth, int reqHeight) {
        try {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, options);

            options.inSampleSize = calculateInSampleSize(options, reqWidth, reqHeight);
            options.inJustDecodeBounds = false;
            options.inPreferredConfig = Bitmap.Config.RGB_565;
            return BitmapFactory.decodeByteArray(data, 0, data.length, options);
        } catch (Throwable t) {
            return null;
        }
    }

    public static int calculateInSampleSize(BitmapFactory.Options options, int reqWidth, int reqHeight) {
        final int height = options.outHeight;
        final int width = options.outWidth;
        int inSampleSize = 1;

        if (height > reqHeight || width > reqWidth) {
            final int halfHeight = height / 2;
            final int halfWidth = width / 2;
            while ((halfHeight / inSampleSize) >= reqHeight && (halfWidth / inSampleSize) >= reqWidth) {
                inSampleSize *= 2;
            }
        }
        return Math.max(1, inSampleSize);
    }

    /**
     * Generates a 25% height gradient reflection bitmap.
     * With 256x256 source, this takes under 0.2ms and produces a tiny 256x64 bitmap.
     */
    public static Bitmap generateReflection(Bitmap src) {
        if (src == null) return null;
        try {
            int w = src.getWidth();
            int h = src.getHeight();
            int reqH = h / 4;
            if (reqH <= 0) return null;

            Matrix matrix = new Matrix();
            matrix.preScale(1, -1);

            Bitmap flipped = Bitmap.createBitmap(src, 0, h - reqH, w, reqH, matrix, false);
            Bitmap reflection = Bitmap.createBitmap(w, reqH, Bitmap.Config.ARGB_8888);

            Canvas canvas = new Canvas(reflection);
            canvas.drawBitmap(flipped, 0, 0, null);
            flipped.recycle();

            Paint paint = new Paint();
            LinearGradient shader = new LinearGradient(
                    0, 0, 0, reqH,
                    0x44FFFFFF, 0x00FFFFFF,
                    Shader.TileMode.CLAMP);
            paint.setShader(shader);
            paint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_IN));
            canvas.drawRect(0, 0, w, reqH, paint);

            return reflection;
        } catch (Throwable t) {
            return null;
        }
    }

    public boolean isPregenerating() {
        return mIsPregenerating.get();
    }

    /**
     * Clear all disk cached thumbnails from /storage/sdcard0/.y1_thumbs_cache/.
     */
    public void clearDiskCache() {
        try {
            File dir = StoragePaths.getThumbsCacheDir();
            if (dir.exists()) {
                File[] files = dir.listFiles();
                if (files != null) {
                    for (File f : files) {
                        f.delete();
                    }
                }
            }
        } catch (Throwable ignored) {}
    }

    /**
     * Clears in-memory RAM cache and optionally disk thumbnail files.
     */
    public void clearCache(boolean memoryOnly) {
        mMemoryCache.evictAll();
        if (!memoryOnly) {
            clearDiskCache();
        }
    }

    /**
     * Low-priority background task to pre-generate missing thumbnails for library albums.
     */
    public void pregenerateThumbnails(final List<SongItem> uniqueAlbums) {
        pregenerateThumbnails(uniqueAlbums, false);
    }

    /**
     * Pre-generates thumbnails for all unique library albums with detailed progress logging.
     * @param uniqueAlbums List of unique albums to process.
     * @param force If true, existing disk thumbnails are regenerated and overwritten.
     */
    public void pregenerateThumbnails(final List<SongItem> uniqueAlbums, final boolean force) {
        if (uniqueAlbums == null || uniqueAlbums.isEmpty()) return;
        if (!mIsPregenerating.compareAndSet(false, true)) {
            Log.d(TAG, "pregenerateThumbnails: already running, skipping request.");
            return;
        }

        final List<SongItem> copy;
        synchronized (uniqueAlbums) {
            copy = new java.util.ArrayList<SongItem>(uniqueAlbums);
        }

        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    long startTime = System.currentTimeMillis();
                    final int total = copy.size();
                    int generated = 0;
                    int skipped = 0;
                    Log.i(TAG, "Starting album thumbnail pregeneration for " + total + " albums (force=" + force + ")...");

                    // Gentle pause to allow UI / app boot to settle
                    try {
                        Thread.sleep(800);
                    } catch (InterruptedException ignored) {}

                    for (int i = 0; i < total; i++) {
                        SongItem item = copy.get(i);
                        if (item == null || item.file == null) continue;
                        String albumKey = getAlbumKey(item.file, item.album);
                        File diskThumb = getDiskThumbFile(albumKey);

                        if (force || !diskThumb.exists() || diskThumb.length() == 0) {
                            Bitmap bmp = extractAndSaveThumbnail(item, diskThumb);
                            if (bmp != null) {
                                generated++;
                            }
                            try {
                                Thread.sleep(20); // Yield CPU gently so audio playback and UI remain 100% fluid
                            } catch (InterruptedException ignored) {}
                        } else {
                            skipped++;
                        }

                        if ((i + 1) % 50 == 0 || i == total - 1) {
                            Log.i(TAG, "Thumbnail pregeneration progress: " + (i + 1) + "/" + total
                                    + " (" + ((i + 1) * 100 / total) + "%) - generated=" + generated + ", skipped=" + skipped);
                        }
                    }

                    long elapsed = System.currentTimeMillis() - startTime;
                    final int finalGenerated = generated;
                    final int finalSkipped = skipped;
                    Log.i(TAG, "Thumbnail pregeneration complete in " + (elapsed / 1000f) + "s! Generated: "
                            + finalGenerated + ", Skipped: " + finalSkipped);

                    if (finalGenerated > 0 && MainActivity.instance != null) {
                        MainActivity.instance.runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                if (MainActivity.instance != null) {
                                    android.widget.Toast.makeText(MainActivity.instance,
                                            "Cover cache updated (" + finalGenerated + " thumbnails ready)",
                                            android.widget.Toast.LENGTH_SHORT).show();
                                }
                            }
                        });
                    }
                } catch (Throwable t) {
                    Log.e(TAG, "Error during thumbnail pregeneration: " + t.getMessage(), t);
                } finally {
                    mIsPregenerating.set(false);
                }
            }
        }, "AlbumCoverPregen").start();
    }
}
