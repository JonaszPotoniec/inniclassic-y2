package com.themoon.y1.managers;

import android.content.Context;
import android.media.AudioManager;
import android.os.IBinder;
import android.provider.Settings;
import android.util.Log;
import java.lang.reflect.Method;

/**
 * Bypasses Android's Safe Media Volume limit (~80% volume cap on headphones).
 *
 * In standard Android, AudioService caps headphone volume at mSafeMediaVolumeIndex
 * unless the user manually accepts the system dialog. In a custom launcher/player UI,
 * the system volume warning dialog is not shown, preventing the user from exceeding 80%.
 *
 * This class disables the protection via:
 * 1. AudioManager.disableSafeMediaVolume() via reflection.
 * 2. IAudioService binder disableSafeMediaVolume() via ServiceManager reflection.
 * 3. Settings.Global & Settings.System audio_safe_volume_state (SAFE_MEDIA_VOLUME_INACTIVE = 2).
 * 4. Asynchronous root execution on boot (non-blocking).
 * 5. Instant, non-blocking stream volume adjustments.
 */
public class SafeVolumeBypasser {
    private static final String TAG = "SafeVolumeBypasser";
    private static volatile boolean sRootInitialized = false;

    /**
     * Programmatic bypass using reflection on AudioManager, IAudioService, and Settings.
     */
    public static void bypass(Context context, AudioManager audioManager) {
        if (context == null) return;

        // 1. Hidden AudioManager.disableSafeMediaVolume()
        if (audioManager != null) {
            try {
                Method m = AudioManager.class.getMethod("disableSafeMediaVolume");
                m.setAccessible(true);
                m.invoke(audioManager);
            } catch (Throwable t1) {
                try {
                    Method m = AudioManager.class.getMethod("disableSafeMediaVolume", String.class);
                    m.setAccessible(true);
                    m.invoke(audioManager, context.getPackageName());
                } catch (Throwable ignored) {
                }
            }
        }

        // 2. Direct IAudioService binder call via ServiceManager
        try {
            Class<?> smClass = Class.forName("android.os.ServiceManager");
            Method getService = smClass.getMethod("getService", String.class);
            IBinder binder = (IBinder) getService.invoke(null, Context.AUDIO_SERVICE);
            if (binder != null) {
                Class<?> stubClass = Class.forName("android.media.IAudioService$Stub");
                Method asInterface = stubClass.getMethod("asInterface", IBinder.class);
                Object audioService = asInterface.invoke(null, binder);
                if (audioService != null) {
                    try {
                        Method m = audioService.getClass().getMethod("disableSafeMediaVolume", String.class);
                        m.setAccessible(true);
                        m.invoke(audioService, context.getPackageName());
                    } catch (NoSuchMethodException e) {
                        Method m = audioService.getClass().getMethod("disableSafeMediaVolume");
                        m.setAccessible(true);
                        m.invoke(audioService);
                    }
                }
            }
        } catch (Throwable ignored) {
        }

        // 3. Settings.Global and Settings.System (SAFE_MEDIA_VOLUME_INACTIVE = 2, safe_media_volume_enabled = 0)
        try {
            Settings.Global.putInt(context.getContentResolver(), "audio_safe_volume_state", 2);
        } catch (Throwable ignored) {
        }
        try {
            Settings.Global.putInt(context.getContentResolver(), "safe_media_volume_enabled", 0);
        } catch (Throwable ignored) {
        }
        try {
            Settings.System.putInt(context.getContentResolver(), "audio_safe_volume_state", 2);
        } catch (Throwable ignored) {
        }
        try {
            Settings.System.putInt(context.getContentResolver(), "safe_media_volume_enabled", 0);
        } catch (Throwable ignored) {
        }
    }

    /**
     * Executes root commands asynchronously in the background. Never blocks the UI thread.
     */
    public static void bypassWithRoot(final Context context, final AudioManager audioManager) {
        if (sRootInitialized) return;
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    String pkg = context != null ? context.getPackageName() : "com.themoon.y1";
                    String cmd = "settings put global audio_safe_volume_state 2 && " +
                                 "settings put system audio_safe_volume_state 2 && " +
                                 "settings put global safe_media_volume_enabled 0 && " +
                                 "settings put system safe_media_volume_enabled 0 && " +
                                 "pm grant " + pkg + " android.permission.STATUS_BAR_SERVICE 2>/dev/null && " +
                                 "sync";
                    runSu(cmd);
                    bypass(context, audioManager);
                    sRootInitialized = true;
                } catch (Throwable t) {
                    Log.w(TAG, "Root safe volume bypass error: " + t.getMessage());
                }
            }
        }, "safe-vol-root-bypass").start();
    }

    /**
     * Sets stream volume directly and non-blockingly without any UI freeze.
     */
    public static void setStreamVolumeWithBypass(Context context, AudioManager audioManager, int stream, int targetVol, int flags) {
        if (audioManager == null) return;
        audioManager.setStreamVolume(stream, targetVol, flags);
    }

    /**
     * Initializes safe volume bypass on application boot/launch.
     */
    public static void init(final Context context, final AudioManager audioManager) {
        bypass(context, audioManager);
        bypassWithRoot(context, audioManager);
    }

    private static void runSu(String cmd) {
        Process p = null;
        try {
            p = Runtime.getRuntime().exec(new String[] { "su", "-c", cmd });
            final Process proc = p;
            long deadline = System.currentTimeMillis() + 1500;
            while (System.currentTimeMillis() < deadline) {
                try {
                    proc.exitValue();
                    break;
                } catch (IllegalThreadStateException running) {
                    try {
                        Thread.sleep(50);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "su exec failed: " + e.getMessage());
        } finally {
            if (p != null) {
                try {
                    p.destroy();
                } catch (Exception ignored) {
                }
            }
        }
    }
}
