package com.themoon.y1.games;

import android.content.Context;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.SoundPool;
import android.util.Log;

import com.themoon.y1.StoragePaths;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * JNI Bridge to fliwheel-android native HLE iPod clickwheel emulator.
 */
public class FliwheelBridge {
    private static final String TAG = "FliwheelBridge";
    private static boolean isLibraryLoaded = false;
    private static String lastError = null;

    static {
        try {
            System.loadLibrary("fliwheel_android");
            isLibraryLoaded = true;
            Log.i(TAG, "Loaded libfliwheel_android.so via System.loadLibrary");
            writeDebugLog("Loaded libfliwheel_android.so via System.loadLibrary");
        } catch (Throwable e) {
            Log.d(TAG, "System.loadLibrary not available directly: " + e.getMessage());
            writeDebugLog("System.loadLibrary not found directly: " + e.getMessage());
        }
    }

    public static void writeDebugLog(String message) {
        try {
            File logFile = StoragePaths.primaryFile("y1_game_log.txt");
            FileOutputStream fos = new FileOutputStream(logFile, true);
            String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date());
            fos.write(("[" + timestamp + "] " + message + "\n").getBytes());
            fos.close();
        } catch (Exception ignored) {}
    }

    public static synchronized boolean ensureLibraryLoaded(Context context) {
        if (isLibraryLoaded) {
            return true;
        }

        // 1. Try standard System.loadLibrary again
        try {
            System.loadLibrary("fliwheel_android");
            isLibraryLoaded = true;
            writeDebugLog("ensureLibraryLoaded: System.loadLibrary succeeded");
            return true;
        } catch (Throwable ignored) {}

        // 2. Try /system/lib/libfliwheel_android.so if present
        File sysLib = new File("/system/lib/libfliwheel_android.so");
        if (sysLib.exists()) {
            try {
                System.load(sysLib.getAbsolutePath());
                isLibraryLoaded = true;
                writeDebugLog("ensureLibraryLoaded: loaded from /system/lib/libfliwheel_android.so");
                return true;
            } catch (Throwable t) {
                writeDebugLog("ensureLibraryLoaded: /system/lib load failed: " + t.getMessage());
            }
        }

        // 3. Extract from APK (crucial for system apps in /system/app where Android does not unpack native libs)
        if (context != null) {
            try {
                File destDir = context.getDir("jniLibs", Context.MODE_PRIVATE);
                File destSo = new File(destDir, "libfliwheel_android.so");

                String apkPath = context.getApplicationInfo().sourceDir;
                File apkFile = new File(apkPath);
                writeDebugLog("ensureLibraryLoaded: scanning APK: " + apkPath);

                if (!destSo.exists() || destSo.length() == 0 || destSo.lastModified() < apkFile.lastModified()) {
                    writeDebugLog("Extracting libfliwheel_android.so from APK...");
                    boolean extracted = extractSoFromApk(apkFile, destSo);
                    writeDebugLog("Extraction result: " + extracted + ", size=" + destSo.length());
                }

                if (destSo.exists() && destSo.length() > 0) {
                    destSo.setReadable(true, false);
                    destSo.setExecutable(true, false);
                    try {
                        Runtime.getRuntime().exec("chmod 755 " + destSo.getAbsolutePath()).waitFor();
                    } catch (Exception ignored) {}

                    writeDebugLog("Loading extracted library: " + destSo.getAbsolutePath());
                    System.load(destSo.getAbsolutePath());
                    isLibraryLoaded = true;
                    writeDebugLog("Successfully loaded extracted native library!");
                    return true;
                } else {
                    lastError = "Extracted .so file missing or empty";
                    writeDebugLog(lastError);
                }
            } catch (Throwable t) {
                lastError = "Failed extracting/loading .so: " + t.getMessage();
                writeDebugLog(lastError + "\n" + Log.getStackTraceString(t));
                Log.e(TAG, "Failed to load extracted native library", t);
            }
        } else {
            lastError = "Context is null, cannot extract .so from APK";
            writeDebugLog(lastError);
        }

        return false;
    }

    private static boolean extractSoFromApk(File apkFile, File destFile) {
        ZipFile zip = null;
        try {
            zip = new ZipFile(apkFile);
            ZipEntry entry = zip.getEntry("lib/armeabi-v7a/libfliwheel_android.so");
            if (entry == null) {
                Enumeration<? extends ZipEntry> entries = zip.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry e = entries.nextElement();
                    if (e.getName().endsWith("libfliwheel_android.so")) {
                        entry = e;
                        break;
                    }
                }
            }
            if (entry == null) {
                writeDebugLog("Could not find libfliwheel_android.so entry in APK");
                return false;
            }

            File parent = destFile.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }

            InputStream is = zip.getInputStream(entry);
            FileOutputStream fos = new FileOutputStream(destFile);
            byte[] buf = new byte[32768];
            int len;
            while ((len = is.read(buf)) > 0) {
                fos.write(buf, 0, len);
            }
            fos.flush();
            fos.close();
            is.close();
            return true;
        } catch (Exception e) {
            writeDebugLog("extractSoFromApk exception: " + e.getMessage());
            return false;
        } finally {
            if (zip != null) {
                try { zip.close(); } catch (Exception ignored) {}
            }
        }
    }

    public static String getLastError() {
        return lastError;
    }

    private long nativeHandle = 0;
    private final int[] framePixels = new int[320 * 240];
    private SoundPool soundPool;
    private final Map<String, Integer> soundCache = new HashMap<>();
    private MediaPlayer musicPlayer;
    private Thread audioPollThread;
    private volatile boolean isRunning = false;

    public FliwheelBridge() {
    }

    public synchronized boolean init(Context context, String bundlePath) {
        lastError = null;
        if (nativeHandle != 0) {
            destroy();
        }

        writeDebugLog("FliwheelBridge.init called with bundlePath: " + bundlePath);
        if (!ensureLibraryLoaded(context)) {
            writeDebugLog("Library loading failed, aborting init. lastError=" + lastError);
            return false;
        }

        // Resolve bundle directory: if "Executables" is not a direct child, look for child folder
        File bp = new File(bundlePath);
        if (!new File(bp, "Executables").exists()) {
            File[] subs = bp.listFiles();
            if (subs != null) {
                for (File sub : subs) {
                    if (sub.isDirectory() && new File(sub, "Executables").exists()) {
                        writeDebugLog("Resolved sub-folder containing Executables: " + sub.getAbsolutePath());
                        bundlePath = sub.getAbsolutePath();
                        break;
                    }
                }
            }
        }
        writeDebugLog("Final bundlePath for nativeInit: " + bundlePath + " (exists=" + new File(bundlePath).exists() + ")");

        try {
            nativeHandle = nativeInit(bundlePath);
        } catch (Throwable t) {
            lastError = "nativeInit exception: " + t.getMessage();
            writeDebugLog(lastError + "\n" + Log.getStackTraceString(t));
            Log.e(TAG, "Failed calling nativeInit for: " + bundlePath, t);
            return false;
        }

        writeDebugLog("nativeInit returned handle=" + nativeHandle);
        if (nativeHandle == 0) {
            lastError = "Eapp init returned 0 (check game format / decrypted bin)";
            writeDebugLog(lastError);
            Log.e(TAG, "Failed to initialize native emulator for: " + bundlePath);
            return false;
        }

        try {
            soundPool = new SoundPool(4, AudioManager.STREAM_MUSIC, 0);
        } catch (Exception e) {
            Log.w(TAG, "Could not initialize SoundPool", e);
        }

        isRunning = true;
        startAudioPolling();
        writeDebugLog("Emulator initialized successfully, handle=" + nativeHandle);
        Log.i(TAG, "Emulator initialized successfully, handle=" + nativeHandle);
        return true;
    }

    private void startAudioPolling() {
        audioPollThread = new Thread(new Runnable() {
            @Override
            public void run() {
                while (isRunning && nativeHandle != 0) {
                    try {
                        String audioPath = nativePollAudio(nativeHandle);
                        if (audioPath != null && !audioPath.isEmpty()) {
                            handleAudioEvent(audioPath);
                        } else {
                            Thread.sleep(10);
                        }
                    } catch (InterruptedException e) {
                        break;
                    } catch (Exception e) {
                        Log.e(TAG, "Error in audio poll loop", e);
                    }
                }
            }
        }, "FliwheelAudioPoll");
        audioPollThread.start();
    }

    private void handleAudioEvent(String path) {
        try {
            File file = new File(path);
            if (!file.exists()) return;

            String lower = path.toLowerCase();
            if (lower.endsWith(".wav")) {
                if (soundPool != null) {
                    Integer soundId = soundCache.get(path);
                    if (soundId == null) {
                        soundId = soundPool.load(path, 1);
                        soundCache.put(path, soundId);
                    }
                    soundPool.play(soundId, 1.0f, 1.0f, 1, 0, 1.0f);
                }
            } else if (lower.endsWith(".m4a") || lower.endsWith(".mp3") || lower.endsWith(".aac")) {
                if (musicPlayer != null) {
                    try {
                        musicPlayer.stop();
                        musicPlayer.release();
                    } catch (Exception ignored) {}
                }
                musicPlayer = new MediaPlayer();
                musicPlayer.setDataSource(path);
                musicPlayer.setAudioStreamType(AudioManager.STREAM_MUSIC);
                musicPlayer.prepare();
                musicPlayer.setLooping(true);
                musicPlayer.start();
            }
        } catch (Exception e) {
            Log.w(TAG, "Could not play audio event: " + path, e);
        }
    }

    public synchronized boolean renderFrame(int[] outBuffer) {
        if (nativeHandle == 0 || outBuffer == null || outBuffer.length < 320 * 240) {
            return false;
        }
        return nativeRenderFrame(nativeHandle, outBuffer);
    }

    public synchronized void sendKey(int keyCode, boolean isDown) {
        if (nativeHandle != 0) {
            nativeSendKey(nativeHandle, keyCode, isDown);
        }
    }

    public synchronized void sendWheel(float delta) {
        if (nativeHandle != 0) {
            nativeSendWheel(nativeHandle, delta);
        }
    }

    public synchronized void destroy() {
        isRunning = false;
        if (audioPollThread != null) {
            audioPollThread.interrupt();
            audioPollThread = null;
        }
        if (musicPlayer != null) {
            try {
                if (musicPlayer.isPlaying()) musicPlayer.stop();
                musicPlayer.release();
            } catch (Exception ignored) {}
            musicPlayer = null;
        }
        if (soundPool != null) {
            try {
                soundPool.release();
            } catch (Exception ignored) {}
            soundPool = null;
        }
        soundCache.clear();

        if (nativeHandle != 0) {
            nativeDestroy(nativeHandle);
            nativeHandle = 0;
        }
        Log.i(TAG, "Emulator session destroyed");
    }

    public boolean isRunning() {
        return isRunning && nativeHandle != 0;
    }

    // Native JNI declarations
    private static native long nativeInit(String bundlePath);
    private static native boolean nativeRenderFrame(long handle, int[] outPixels);
    private static native void nativeSendKey(long handle, int keyCode, boolean isDown);
    private static native void nativeSendWheel(long handle, float delta);
    private static native String nativePollAudio(long handle);
    private static native void nativeDestroy(long handle);
}
