package com.themoon.y1.managers;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.themoon.y1.StoragePaths;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.InputStreamReader;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Y2 MicroSD watchdog: detects when {@code /storage/sdcard1} is not usable and
 * attempts to remount via {@code vdc}/{@code fuse_sdcard1} (requires root, which
 * JJ/InniClassic already uses elsewhere on these devices).
 * <p>
 * Safe on Y1: if the secondary path does not exist, the monitor is a no-op.
 */
public final class ExternalSdMountMonitor {
    private static final String TAG = "Y1SdMount";
    private static final String PRIMARY = StoragePaths.PRIMARY_PATH;
    private static final String SECONDARY = StoragePaths.SECONDARY_PATH;
    private static final long POLL_MS = 12_000L;
    private static final long MOUNT_COOLDOWN_MS = 30_000L;
    private static final int CMD_TIMEOUT_MS = 8_000;

    public interface Listener {
        /** Called on a background thread after a successful remount. */
        void onSecondaryStorageReady();
    }

    private final Context appContext;
    private android.os.HandlerThread bgThread;
    private Handler bgHandler;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean mountInFlight = new AtomicBoolean(false);
    private final AtomicBoolean suspended = new AtomicBoolean(false);
    private Listener listener;
    private BroadcastReceiver mediaReceiver;
    private BroadcastReceiver usbReceiver;
    private long lastMountAttemptMs;
    private boolean lastPriReady = false;
    private boolean lastSecReady = false;

    /** While true (USB storage shared with PC) the watchdog never remounts/starts FUSE. */
    public void setSuspended(boolean s) {
        suspended.set(s);
        if (!s) {
            // Re-baseline so the remount after UMS is reported as "newly mounted" exactly once.
            lastSecReady = false;
            if (bgHandler != null) {
                bgHandler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        checkAndMaybeMount("ums_resume");
                    }
                }, 1500);
            }
        }
    }

    private final Runnable pollTask = new Runnable() {
        @Override
        public void run() {
            if (!running.get())
                return;
            checkAndMaybeMount("poll");
            if (bgHandler != null && running.get()) {
                bgHandler.postDelayed(this, POLL_MS);
            }
        }
    };

    public ExternalSdMountMonitor(Context context) {
        this.appContext = context.getApplicationContext();
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public void start() {
        if (!running.compareAndSet(false, true))
            return;

        lastPriReady = isPrimaryReady();
        lastSecReady = isSecondaryReady();

        if (bgThread == null || !bgThread.isAlive()) {
            bgThread = new android.os.HandlerThread("y1-sd-monitor-bg");
            bgThread.start();
            bgHandler = new Handler(bgThread.getLooper());
        }

        registerMediaReceiver();
        registerUsbReceiver();
        if (bgHandler != null) {
            bgHandler.postDelayed(pollTask, POLL_MS);
        }
    }

    public void stop() {
        running.set(false);
        if (bgHandler != null) {
            bgHandler.removeCallbacks(pollTask);
            bgHandler = null;
        }
        if (bgThread != null) {
            bgThread.quit();
            bgThread = null;
        }
        unregisterMediaReceiver();
        unregisterUsbReceiver();
    }

    /** True when primary storage is usable and readable. */
    public static boolean isPrimaryReady() {
        try {
            File pri = StoragePaths.getPrimaryRoot();
            return pri != null && pri.exists() && pri.isDirectory() && pri.canRead();
        } catch (Exception e) {
            return false;
        }
    }

    /** True when apps can list the secondary volume (/storage/sdcard1). */
    public static boolean isSecondaryReady() {
        if (!hasSecondarySlot())
            return false;
        try {
            File sec = new File(SECONDARY);
            if (!sec.exists() || !sec.isDirectory() || !sec.canRead())
                return false;
            String[] list = sec.list();
            return list != null;
        } catch (Exception e) {
            return false;
        }
    }

    /** True only if a physical secondary SD card device exists in hardware. */
    public static boolean hasSecondarySlot() {
        try {
            return new File("/sys/block/mmcblk1").exists() || new File("/dev/block/mmcblk1").exists();
        } catch (Exception e) {
            return false;
        }
    }

    private void checkAndMaybeMount(String reason) {
        if (suspended.get())
            return;
        boolean priReady = isPrimaryReady();
        boolean secReady = isSecondaryReady();

        boolean newlyMounted = (priReady && !lastPriReady) || (secReady && !lastSecReady);

        lastPriReady = priReady;
        lastSecReady = secReady;

        if (newlyMounted) {
            Log.i(TAG, "Storage volume newly mounted (pri=" + priReady + ", sec=" + secReady + ", reason=" + reason + ")");
            StoragePaths.invalidate();
            notifyReady();
        }

        // Only secondary slot can ever be remounted; never touch primary storage!
        boolean secNeeded = hasSecondarySlot() && !secReady;
        if (!secNeeded) {
            return;
        }

        boolean isBroadcastEvent = reason.startsWith("broadcast:") || reason.startsWith("usb_disconnect:");
        long now = System.currentTimeMillis();
        if (!isBroadcastEvent && (now - lastMountAttemptMs < MOUNT_COOLDOWN_MS))
            return;
        if (!mountInFlight.compareAndSet(false, true))
            return;
        lastMountAttemptMs = now;

        try {
            Log.i(TAG, "Secondary storage not ready (" + reason + ") — attempting mount");
            attemptRemount();
            boolean afterSec = isSecondaryReady();
            if (afterSec && !secReady) {
                Log.i(TAG, "Secondary remount succeeded");
                StoragePaths.invalidate();
                lastSecReady = afterSec;
                notifyReady();
            }
        } finally {
            mountInFlight.set(false);
        }
    }

    /**
     * Attempts to mount secondary storage without touching primary storage.
     */
    private boolean attemptRemount() {
        if (hasSecondarySlot() && !isSecondaryReady()) {
            runSuTimed("vdc volume mount sdcard1 2>/dev/null; start fuse_sdcard1 2>/dev/null");
        }
        return isSecondaryReady();
    }

    private void notifyReady() {
        final Listener l = listener;
        if (l == null)
            return;
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    l.onSecondaryStorageReady();
                } catch (Exception e) {
                    Log.w(TAG, "listener failed", e);
                }
            }
        }, "y1-sd-ready").start();
    }

    private void registerMediaReceiver() {
        if (mediaReceiver != null)
            return;
        mediaReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (intent == null || intent.getAction() == null)
                    return;
                String action = intent.getAction();
                if (Intent.ACTION_MEDIA_MOUNTED.equals(action)
                        || Intent.ACTION_MEDIA_UNMOUNTED.equals(action)
                        || Intent.ACTION_MEDIA_REMOVED.equals(action)
                        || Intent.ACTION_MEDIA_BAD_REMOVAL.equals(action)
                        || Intent.ACTION_MEDIA_EJECT.equals(action)
                        || Intent.ACTION_MEDIA_NOFS.equals(action)
                        || Intent.ACTION_MEDIA_UNMOUNTABLE.equals(action)
                        || "android.intent.action.MEDIA_CHECKING".equals(action)) {
                    StoragePaths.invalidate();
                    new Thread(new Runnable() {
                        @Override
                        public void run() {
                            checkAndMaybeMount("broadcast:" + action);
                        }
                    }, "y1-sd-bcast").start();
                }
            }
        };
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_MEDIA_MOUNTED);
        filter.addAction(Intent.ACTION_MEDIA_UNMOUNTED);
        filter.addAction(Intent.ACTION_MEDIA_REMOVED);
        filter.addAction(Intent.ACTION_MEDIA_BAD_REMOVAL);
        filter.addAction(Intent.ACTION_MEDIA_EJECT);
        filter.addAction(Intent.ACTION_MEDIA_NOFS);
        filter.addAction(Intent.ACTION_MEDIA_UNMOUNTABLE);
        filter.addAction("android.intent.action.MEDIA_CHECKING");
        filter.addDataScheme("file");
        try {
            appContext.registerReceiver(mediaReceiver, filter);
        } catch (Exception e) {
            Log.w(TAG, "registerMediaReceiver failed", e);
        }
    }

    private void unregisterMediaReceiver() {
        if (mediaReceiver == null)
            return;
        try {
            appContext.unregisterReceiver(mediaReceiver);
        } catch (Exception ignored) {
        }
        mediaReceiver = null;
    }

    private void registerUsbReceiver() {
        if (usbReceiver != null)
            return;
        usbReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (intent == null || intent.getAction() == null)
                    return;
                String action = intent.getAction();
                boolean trigger = false;
                if ("android.hardware.usb.action.USB_STATE".equals(action)) {
                    boolean connected = intent.getBooleanExtra("connected", false);
                    if (!connected) {
                        trigger = true;
                    }
                } else if ("android.intent.action.UMS_DISCONNECTED".equals(action)) {
                    trigger = true;
                }

                if (trigger) {
                    Log.i(TAG, "USB disconnected event (" + action + ") — scheduling self-healing check in 500ms");
                    if (bgHandler != null) {
                        bgHandler.postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                checkAndMaybeMount("usb_disconnect:" + action);
                            }
                        }, 500);
                    }
                }
            }
        };
        IntentFilter filter = new IntentFilter();
        filter.addAction("android.hardware.usb.action.USB_STATE");
        filter.addAction("android.intent.action.UMS_DISCONNECTED");
        try {
            appContext.registerReceiver(usbReceiver, filter);
        } catch (Exception e) {
            Log.w(TAG, "registerUsbReceiver failed", e);
        }
    }

    private void unregisterUsbReceiver() {
        if (usbReceiver == null)
            return;
        try {
            appContext.unregisterReceiver(usbReceiver);
        } catch (Exception ignored) {
        }
        usbReceiver = null;
    }

    private static boolean mountsContain(String path) {
        BufferedReader br = null;
        try {
            br = new BufferedReader(new FileReader("/proc/mounts"));
            String line;
            while ((line = br.readLine()) != null) {
                String[] parts = line.split(" ");
                if (parts.length > 1 && parts[1].equals(path))
                    return true;
            }
        } catch (Exception ignored) {
        } finally {
            try {
                if (br != null)
                    br.close();
            } catch (Exception ignored) {
            }
        }
        return false;
    }

    private static String readProp(String key) {
        Process p = null;
        BufferedReader br = null;
        try {
            p = Runtime.getRuntime().exec(new String[] { "getprop", key });
            br = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line = br.readLine();
            p.waitFor();
            return line != null ? line.trim() : "";
        } catch (Exception e) {
            return "";
        } finally {
            try {
                if (br != null)
                    br.close();
            } catch (Exception ignored) {
            }
            if (p != null)
                p.destroy();
        }
    }

    public static String runSuTimed(String cmd) {
        Process p = null;
        final StringBuilder out = new StringBuilder();
        try {
            p = Runtime.getRuntime().exec(new String[] { "su", "-c", cmd });
            final Process proc = p;
            Thread reader = new Thread(new Runnable() {
                @Override
                public void run() {
                    BufferedReader br = null;
                    try {
                        br = new BufferedReader(new InputStreamReader(proc.getInputStream()));
                        String line;
                        while ((line = br.readLine()) != null) {
                            if (out.length() > 0)
                                out.append('\n');
                            out.append(line);
                        }
                    } catch (Exception ignored) {
                    } finally {
                        try {
                            if (br != null)
                                br.close();
                        } catch (Exception ignored) {
                        }
                    }
                }
            }, "y1-sd-su-out");
            reader.start();

            long deadline = System.currentTimeMillis() + CMD_TIMEOUT_MS;
            while (System.currentTimeMillis() < deadline) {
                try {
                    proc.exitValue();
                    break;
                } catch (IllegalThreadStateException stillRunning) {
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
            try {
                proc.exitValue();
            } catch (IllegalThreadStateException stillRunning) {
                proc.destroy();
                Log.w(TAG, "su command timed out");
            }
            try {
                reader.join(500);
            } catch (InterruptedException ignored) {
            }
        } catch (Exception e) {
            Log.w(TAG, "su failed: " + e.getMessage());
        } finally {
            if (p != null)
                p.destroy();
        }
        return out.toString();
    }
}
