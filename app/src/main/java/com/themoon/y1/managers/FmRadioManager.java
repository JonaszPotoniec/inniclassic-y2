package com.themoon.y1.managers;

import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;
import java.lang.reflect.Method;

public class FmRadioManager {
    private static final String TAG = "Y1FmRadio";
    private static FmRadioManager instance;
    private Context context;
    private AudioManager audioManager;

    public boolean isDeviceOpen = false;
    public boolean isPowerUp = false;
    public float currentFreq = 87.5f; // 기본 주파수
    public boolean isSpeakerOn = false;

    public String lastError = "";

    private Class<?> fmNativeClass;
    private MediaPlayer fmPlayer; // 🚀 [핵심] 소리를 스피커로 빼내 줄 미디어 플레이어
    private android.os.PowerManager.WakeLock radioWakeLock; // 🚀 화면이 꺼져도 라디오가 안 끊기게 하는 WakeLock

    private FmRadioManager(Context context) {
        this.context = context.getApplicationContext();
        this.audioManager = (AudioManager) this.context.getSystemService(Context.AUDIO_SERVICE);

        try {
            fmNativeClass = Class.forName("com.mediatek.FMRadio.FMRadioNative");
        } catch (Throwable t1) {
            String[] jarPaths = {
                    "/system/framework/mediatek-framework.jar",
                    "/system/framework/custom_ext.jar",
                    "/system/framework/com.mediatek.hardware.jar"
            };
            for (String path : jarPaths) {
                if (new java.io.File(path).exists()) {
                    try {
                        dalvik.system.PathClassLoader cl = new dalvik.system.PathClassLoader(path, ClassLoader.getSystemClassLoader());
                        fmNativeClass = Class.forName("com.mediatek.FMRadio.FMRadioNative", true, cl);
                        break;
                    } catch (Throwable t2) {}
                }
            }
            if (fmNativeClass == null) {
                String[] packages = {"com.mediatek.FMRadio", "com.innioasis.fm", "com.innioasis.y1"};
                for (String pkg : packages) {
                    try {
                        android.content.pm.ApplicationInfo info = context.getPackageManager().getApplicationInfo(pkg, 0);
                        dalvik.system.PathClassLoader cl = new dalvik.system.PathClassLoader(info.sourceDir, ClassLoader.getSystemClassLoader());
                        fmNativeClass = Class.forName("com.mediatek.FMRadio.FMRadioNative", true, cl);
                        break;
                    } catch (Throwable t3) {}
                }
            }
        }

        if (fmNativeClass == null) {
            lastError = "FMRadioNative Driver completely missing.";
        } else {
            try { System.loadLibrary("fmjni"); } catch (Throwable t) {
                try { System.load("/system/lib/libfmjni.so"); } catch (Throwable ignore) {}
            }
        }
    }

    public static FmRadioManager getInstance(Context context) {
        if (instance == null) instance = new FmRadioManager(context);
        return instance;
    }

    /** Stream used for FM volume keys / UI (MediaPlayer path → MUSIC). */
    public int getFmStreamType() {
        return AudioManager.STREAM_MUSIC;
    }

    /**
     * Read airplane mode on both pre- and post-API-17 storage locations.
     * Y1 (JB): often {@link Settings.System}; Y2 (KK 4.4): {@link Settings.Global}.
     */
    public boolean isAirplaneModeOn() {
        if (Build.VERSION.SDK_INT >= 17) {
            try {
                if (Settings.Global.getInt(context.getContentResolver(),
                        Settings.Global.AIRPLANE_MODE_ON, 0) != 0)
                    return true;
            } catch (Throwable ignored) {
            }
        }
        try {
            if (Settings.System.getInt(context.getContentResolver(),
                    Settings.System.AIRPLANE_MODE_ON, 0) != 0)
                return true;
        } catch (Throwable ignored) {
        }
        return false;
    }

    /**
     * Force airplane mode OFF before engaging the FM chip.
     * Tries Settings writes then rooted shell fallbacks that differ between Jelly Bean (Y1) and KitKat (Y2).
     */
    public boolean ensureAirplaneModeOff() {
        if (!isAirplaneModeOn())
            return true;

        Log.i(TAG, "Airplane mode is ON — disabling before FM (sdk=" + Build.VERSION.SDK_INT + ")");

        try {
            if (Build.VERSION.SDK_INT >= 17) {
                Settings.Global.putInt(context.getContentResolver(),
                        Settings.Global.AIRPLANE_MODE_ON, 0);
            }
        } catch (Throwable t) {
            Log.w(TAG, "Global.putInt airplane failed: " + t.getMessage());
        }
        try {
            Settings.System.putInt(context.getContentResolver(),
                    Settings.System.AIRPLANE_MODE_ON, 0);
        } catch (Throwable t) {
            Log.w(TAG, "System.putInt airplane failed: " + t.getMessage());
        }

        try {
            Intent intent = new Intent(Intent.ACTION_AIRPLANE_MODE_CHANGED);
            intent.putExtra("state", false);
            context.sendBroadcast(intent);
        } catch (Throwable t) {
            Log.w(TAG, "AIRPLANE_MODE_CHANGED broadcast failed: " + t.getMessage());
        }

        runSuQuiet(
                "settings put global airplane_mode_on 0; "
                        + "settings put system airplane_mode_on 0; "
                        + "setprop persist.radio.airplane_mode_on 0; "
                        + "setprop ril.flightmode 0; "
                        + "am broadcast -a android.intent.action.AIRPLANE_MODE --ez state false");

        try {
            Thread.sleep(400);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }

        boolean stillOn = isAirplaneModeOn();
        if (stillOn) {
            lastError = "Could not disable Airplane Mode (required for FM).";
            Log.w(TAG, lastError);
        }
        return !stillOn;
    }

    private static boolean runSuQuiet(String cmd) {
        String[] suCandidates = new String[] {
                "su",
                "/system/xbin/su",
                "/system/bin/su"
        };
        for (String suBin : suCandidates) {
            Process p = null;
            try {
                p = Runtime.getRuntime().exec(new String[] { suBin, "-c", cmd });
                long deadline = System.currentTimeMillis() + 4000;
                while (System.currentTimeMillis() < deadline) {
                    try {
                        int code = p.exitValue();
                        return (code == 0);
                    } catch (IllegalThreadStateException stillRunning) {
                        Thread.sleep(50);
                    }
                }
                p.destroy();
            } catch (Throwable t) {
                if (p != null)
                    p.destroy();
            }
        }
        return false;
    }

    /**
     * Unblocks FM hardware access:
     * 1. Forces daemonsu to start if not already running (for SELinux on KK 4.4).
     * 2. Force-stops background stock FM apps (com.mediatek.FMRadio, com.innioasis.fm, etc.) so they release /dev/fm.
     * 3. Changes permissions on /dev/fm and /dev/FM50AF to 0666.
     * 4. Ensures airplane mode radio blocks are cleared.
     */
    public void prepareFmHardware() {
        try {
            android.app.ActivityManager am = (android.app.ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            if (am != null) {
                am.killBackgroundProcesses("com.mediatek.FMRadio");
                am.killBackgroundProcesses("com.innioasis.fm");
                am.killBackgroundProcesses("com.android.fmradio");
            }
        } catch (Throwable ignored) {}

        try {
            if (new java.io.File("/system/xbin/daemonsu").exists()) {
                Runtime.getRuntime().exec(new String[] { "/system/xbin/daemonsu", "--auto-daemon" });
            }
        } catch (Throwable ignored) {}

        String unblockCmd =
                "am force-stop com.mediatek.FMRadio 2>/dev/null; "
                + "am force-stop com.innioasis.fm 2>/dev/null; "
                + "am force-stop com.android.fmradio 2>/dev/null; "
                + "kill $(pidof com.mediatek.FMRadio) 2>/dev/null; "
                + "kill $(pidof com.innioasis.fm) 2>/dev/null; "
                + "pkill -9 -f FMRadio 2>/dev/null; "
                + "killall -9 com.mediatek.FMRadio 2>/dev/null; "
                + "[ ! -c /dev/fm ] && mknod /dev/fm c 193 0 2>/dev/null; "
                + "chmod 666 /dev/fm 2>/dev/null; "
                + "chmod 666 /dev/FM50AF 2>/dev/null; "
                + "chown system:media /dev/fm 2>/dev/null; "
                + "setprop persist.radio.airplane_mode_on 0 2>/dev/null; "
                + "setprop ril.flightmode 0 2>/dev/null";

        runSuQuiet(unblockCmd);
    }

    private Method getNativeMethod(String name, Class<?>... parameterTypes) throws NoSuchMethodException {
        Class<?> clazz = fmNativeClass;
        while (clazz != null) {
            try {
                Method m = clazz.getDeclaredMethod(name, parameterTypes);
                m.setAccessible(true);
                return m;
            } catch (NoSuchMethodException e) { clazz = clazz.getSuperclass(); }
        }
        throw new NoSuchMethodException(name);
    }

    // 🚀 [신규 핵심 기술] 칩셋의 소리를 안드로이드 스피커/이어폰으로 연결해 주는 함수!
    private void startFmAudio() {
        try {
            if (fmPlayer != null) {
                fmPlayer.release();
            }
            fmPlayer = new MediaPlayer();
            fmPlayer.setWakeMode(context, android.os.PowerManager.PARTIAL_WAKE_LOCK);

            try {
                android.os.PowerManager pm = (android.os.PowerManager) context.getSystemService(Context.POWER_SERVICE);
                if (pm != null) {
                    if (radioWakeLock == null) {
                        radioWakeLock = pm.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "Y1:FmRadioLock");
                    }
                    if (!radioWakeLock.isHeld()) {
                        radioWakeLock.acquire();
                    }
                }
            } catch (Exception e) {}

            // 💡 미디어텍 전용 숨겨진 FM 라디오 오디오 스트림 주소
            fmPlayer.setDataSource("MEDIATEK://MEDIAPLAYER_PLAYERTYPE_FM");

            // 💡 숨겨진 STREAM_FM(보통 10번) 채널을 찾아서 볼륨을 물리립니다.
            int streamFm = 10;
            try { streamFm = (Integer) AudioManager.class.getDeclaredField("STREAM_FM").get(null); } catch (Exception e) {}

            fmPlayer.setAudioStreamType(streamFm);
            fmPlayer.prepare();
            fmPlayer.start();

            // 🚀 [버그 수리 2] 시스템이 멋대로 스피커로 소리를 빼버리는 것을 막고, 현재 UI에 설정된 출력(isSpeakerOn) 상태로 물리적 강제 고정!
            setSpeaker(isSpeakerOn);

        } catch (Throwable t) {
            lastError = "Audio Routing Failed: " + t.getMessage();
        }
    }

    // 🚀 [신규 기술] 라디오 소리 끄기
    private void stopFmAudio() {
        try {
            if (radioWakeLock != null && radioWakeLock.isHeld()) {
                radioWakeLock.release();
            }
        } catch (Exception e) {}

        if (fmPlayer != null) {
            try {
                if (fmPlayer.isPlaying()) fmPlayer.stop();
                fmPlayer.release();
            } catch (Throwable t) {}
            fmPlayer = null;
        }
    }

    // 1. 하드웨어 전원 켜기
    public boolean powerUp(float freq) {
        if (fmNativeClass == null) {
            if (lastError.isEmpty()) lastError = "Driver class is null.";
            return false;
        }
        try {
            // 0. Airplane mode blocks FM on both Y1 (JB) and Y2 (KK) — clear it first.
            if (!ensureAirplaneModeOff()) {
                Log.w(TAG, "Continuing FM powerUp despite airplane-mode clear uncertainty");
            }

            // 1. Unblock hardware & force-stop background stock apps holding /dev/fm
            prepareFmHardware();

            Thread.sleep(200);

            // 2. Reset any previous stale connection
            try {
                Method closeDev = getNativeMethod("closedev");
                closeDev.invoke(null);
            } catch (Throwable ignore) {}

            isDeviceOpen = false;

            // 3. Open the FM device node with retry
            Method openDev = getNativeMethod("opendev");
            for (int attempt = 0; attempt < 3; attempt++) {
                try {
                    isDeviceOpen = (Boolean) openDev.invoke(null);
                } catch (Throwable t) {
                    Log.w(TAG, "opendev exception attempt " + attempt + ": " + t.getMessage());
                }
                if (isDeviceOpen) break;

                // Close, wait, and re-run unblock
                try {
                    Method closeDev = getNativeMethod("closedev");
                    closeDev.invoke(null);
                } catch (Throwable ignore) {}

                prepareFmHardware();
                Thread.sleep(250);
            }

            if (!isDeviceOpen) {
                java.io.File fmDev = new java.io.File("/dev/fm");
                lastError = "Failed to open /dev/fm (exists=" + fmDev.exists() 
                        + ", r=" + fmDev.canRead() + ", w=" + fmDev.canWrite() + ")";
                Log.e(TAG, lastError);
                return false;
            }

            Method powerUp = getNativeMethod("powerup", float.class);
            isPowerUp = (Boolean) powerUp.invoke(null, freq);

            if (isPowerUp) {
                currentFreq = freq;
                setMute(false);
                startFmAudio(); // 🚀 전원이 켜지면 소리 통로를 연결합니다!
            } else {
                try {
                    Method switchAntenna = getNativeMethod("switchAntenna", int.class);
                    switchAntenna.invoke(null, 1);

                    isPowerUp = (Boolean) powerUp.invoke(null, freq);
                    if (isPowerUp) {
                        currentFreq = freq;
                        setMute(false);
                        startFmAudio(); // 🚀 안테나 우회 성공 시에도 소리 통로 연결!
                    } else {
                        lastError = "Power up rejected by Hardware.";
                    }
                } catch (Throwable ex) {
                    lastError = "Earphones required and Antenna bypass failed.";
                }
            }
            return isPowerUp;
        } catch (Throwable t) {
            lastError = "Exception: " + t.getClass().getSimpleName() + " - " + t.getMessage();
        }
        return false;
    }
    // 2. 하드웨어 전원 끄기
    public void powerDown() {
        if (fmNativeClass == null || !isPowerUp) return;
        try {
            stopFmAudio(); // 🚀 전원 끌 때 소리 통로도 같이 뽑아줍니다!
            setMute(true);
            Method powerDown = getNativeMethod("powerdown", int.class);
            powerDown.invoke(null, 0);

            Method closeDev = getNativeMethod("closedev");
            closeDev.invoke(null);

            isPowerUp = false;
            isDeviceOpen = false;
        } catch (Throwable e) { }
    }

    // 3. 주파수 수동 맞추기 (Manual Tuning)
    public boolean tune(float freq) {
        if (fmNativeClass == null || !isPowerUp) return false;
        try {
            Method tuneMethod = getNativeMethod("tune", float.class);
            boolean success = (Boolean) tuneMethod.invoke(null, freq);
            if (success) currentFreq = freq;
            return success;
        } catch (Throwable e) { return false; }
    }

    // 🚀 4. 자동 스캔 엔진 (Auto Scan)
    public float[] autoScan() {
        if (fmNativeClass == null || !isPowerUp) {
            lastError = "Turn on the radio first.";
            return null;
        }
        try {
            Method autoScanMethod = getNativeMethod("autoscan");
            short[] result = (short[]) autoScanMethod.invoke(null);

            if (result != null && result.length > 0) {
                float[] freqs = new float[result.length];
                for (int i = 0; i < result.length; i++) {
                    freqs[i] = result[i] / 10.0f; // 미디어텍은 875를 87.5로 반환하므로 소수점 변환
                }
                return freqs;
            }
        } catch (Throwable t) {
            lastError = "AutoScan failed: " + t.getMessage();
        }
        return null;
    }

    // 5. 음소거 제어
    public void setMute(boolean mute) {
        if (fmNativeClass == null) return;
        try {
            Method setMuteMethod = getNativeMethod("setmute", boolean.class);
            setMuteMethod.invoke(null, mute);
        } catch (Throwable e) {}
    }

    // 6. 스피커 출력 강제 변경
    public void setSpeaker(boolean useSpeaker) {
        try {
            Method setForceUse = Class.forName("android.media.AudioSystem").getDeclaredMethod("setForceUse", int.class, int.class);
            setForceUse.setAccessible(true);
            setForceUse.invoke(null, 5, useSpeaker ? 1 : 0);
            isSpeakerOn = useSpeaker;
        } catch (Throwable e) {}
    }
}