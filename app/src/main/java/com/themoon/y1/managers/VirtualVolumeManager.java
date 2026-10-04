package com.themoon.y1.managers;

import android.media.MediaPlayer;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Central manager and coordinator for iPod 30-step virtual volume and digital software gain attenuation.
 *
 * Provides a unified software master volume pipeline across all media playback subsystems:
 * - Music, Podcasts, Audiobooks (AudioPlayerManager)
 * - Movies & Videos (VideoPlayerManager / LibVLC)
 * - Music Quiz preview playback (MediaPlayer)
 * - FM Radio (FmRadioManager / MediaPlayer)
 * - Any future media players / engines via registration or MasterVolumeObserver
 */
public class VirtualVolumeManager {
    private static VirtualVolumeManager instance;

    public static final int VIRTUAL_VOLUME_STEPS = 30;

    public interface MasterVolumeObserver {
        void onMasterVolumeChanged(float gain);
    }

    private float currentMasterGain = 1.0f;
    private final List<WeakReference<MediaPlayer>> registeredMediaPlayers = new ArrayList<>();
    private final List<WeakReference<MasterVolumeObserver>> registeredObservers = new ArrayList<>();

    private VirtualVolumeManager() {}

    public static synchronized VirtualVolumeManager getInstance() {
        if (instance == null) {
            instance = new VirtualVolumeManager();
        }
        return instance;
    }

    /**
     * Maps 30-step virtual volume (0..30) to digital software attenuation gain (0.0f..1.0f).
     * Steps 1..5 provide fine-grained sub-steps down to -28 dBFS for sensitive headphones at hardware volume 1.
     */
    public static float virtualToSoftwareGain(int virtualVol) {
        if (virtualVol <= 0) return 0.0f;
        switch (virtualVol) {
            case 1: return 0.04f; // -28 dBFS: ultra-whisper quiet for sensitive Porta Pros in silent room
            case 2: return 0.10f; // -20 dBFS: very quiet
            case 3: return 0.20f; // -14 dBFS
            case 4: return 0.35f; // -9.1 dBFS
            case 5: return 0.60f; // -4.4 dBFS
            default: return 1.0f; // 0 dBFS (unattenuated sys vol 1 at step 6+)
        }
    }

    /**
     * Maps 30-step virtual volume to Android hardware/system volume (0..sysMaxVol, typically 15).
     */
    public static int virtualToSystemVolume(int virtualVol, int sysMaxVol) {
        if (virtualVol <= 0) return 0;
        if (virtualVol <= 6) return 1;
        if (sysMaxVol <= 1) return 1;
        int sys = 2 + Math.round((float) (virtualVol - 7) * (sysMaxVol - 2) / 23.0f);
        if (sys > sysMaxVol) sys = sysMaxVol;
        return sys;
    }

    /**
     * Converts Android system volume to closest 30-step virtual volume.
     */
    public static int systemToVirtualVolume(int sysVol, int sysMaxVol, int currentVirtualVol) {
        if (sysVol <= 0) return 0;
        if (sysVol == 1) {
            if (currentVirtualVol >= 1 && currentVirtualVol <= 6) {
                return currentVirtualVol;
            }
            return 6;
        }
        if (sysMaxVol <= 2) return VIRTUAL_VOLUME_STEPS;
        if (sysVol >= sysMaxVol) return VIRTUAL_VOLUME_STEPS;
        float ratio = (float) (sysVol - 2) / (sysMaxVol - 2);
        int v = 7 + Math.round(ratio * 23.0f);
        if (v > VIRTUAL_VOLUME_STEPS) v = VIRTUAL_VOLUME_STEPS;
        return v;
    }

    public synchronized float getMasterGain() {
        return currentMasterGain;
    }

    /**
     * Sets the global digital master volume gain and dispatches it to all playback engines.
     */
    public synchronized void setMasterGain(float gain) {
        this.currentMasterGain = Math.max(0.0f, Math.min(1.0f, gain));
        applyMasterGain();
    }

    /**
     * Applies the current master gain to all known playback engines, registered MediaPlayers,
     * and custom observers.
     */
    public synchronized void applyMasterGain() {
        // 1. AudioPlayerManager (Music, Podcasts, Audiobooks)
        try {
            AudioPlayerManager apm = AudioPlayerManager.getInstance();
            if (apm != null) {
                apm.setMasterVolume(currentMasterGain);
            }
        } catch (Throwable ignored) {}

        // 2. VideoPlayerManager (Movies / Videos)
        try {
            VideoPlayerManager vpm = VideoPlayerManager.getInstance();
            if (vpm != null) {
                vpm.setMasterVolume(currentMasterGain);
            }
        } catch (Throwable ignored) {}

        // 3. FmRadioManager (Radio)
        try {
            FmRadioManager fm = FmRadioManager.getInstance();
            if (fm != null) {
                fm.setMasterVolume(currentMasterGain);
            }
        } catch (Throwable ignored) {}

        // 4. Registered MediaPlayers (e.g. Music Quiz preview player, FM Radio player)
        Iterator<WeakReference<MediaPlayer>> playerIt = registeredMediaPlayers.iterator();
        while (playerIt.hasNext()) {
            MediaPlayer mp = playerIt.next().get();
            if (mp == null) {
                playerIt.remove();
            } else {
                try {
                    mp.setVolume(currentMasterGain, currentMasterGain);
                } catch (Throwable ignored) {}
            }
        }

        // 5. Registered observers
        Iterator<WeakReference<MasterVolumeObserver>> observerIt = registeredObservers.iterator();
        while (observerIt.hasNext()) {
            MasterVolumeObserver obs = observerIt.next().get();
            if (obs == null) {
                observerIt.remove();
            } else {
                try {
                    obs.onMasterVolumeChanged(currentMasterGain);
                } catch (Throwable ignored) {}
            }
        }
    }

    /**
     * Registers a MediaPlayer and immediately applies the current master gain to it.
     */
    public synchronized void applyTo(MediaPlayer player) {
        if (player == null) return;
        registerMediaPlayer(player);
        try {
            player.setVolume(currentMasterGain, currentMasterGain);
        } catch (Throwable ignored) {}
    }

    public synchronized void registerMediaPlayer(MediaPlayer player) {
        if (player == null) return;
        Iterator<WeakReference<MediaPlayer>> it = registeredMediaPlayers.iterator();
        while (it.hasNext()) {
            MediaPlayer p = it.next().get();
            if (p == null || p == player) {
                it.remove();
            }
        }
        registeredMediaPlayers.add(new WeakReference<>(player));
        try {
            player.setVolume(currentMasterGain, currentMasterGain);
        } catch (Throwable ignored) {}
    }

    public synchronized void unregisterMediaPlayer(MediaPlayer player) {
        if (player == null) return;
        Iterator<WeakReference<MediaPlayer>> it = registeredMediaPlayers.iterator();
        while (it.hasNext()) {
            MediaPlayer p = it.next().get();
            if (p == null || p == player) {
                it.remove();
            }
        }
    }

    public synchronized void registerObserver(MasterVolumeObserver observer) {
        if (observer == null) return;
        Iterator<WeakReference<MasterVolumeObserver>> it = registeredObservers.iterator();
        while (it.hasNext()) {
            MasterVolumeObserver obs = it.next().get();
            if (obs == null || obs == observer) {
                it.remove();
            }
        }
        registeredObservers.add(new WeakReference<>(observer));
        try {
            observer.onMasterVolumeChanged(currentMasterGain);
        } catch (Throwable ignored) {}
    }

    public synchronized void unregisterObserver(MasterVolumeObserver observer) {
        if (observer == null) return;
        Iterator<WeakReference<MasterVolumeObserver>> it = registeredObservers.iterator();
        while (it.hasNext()) {
            MasterVolumeObserver obs = it.next().get();
            if (obs == null || obs == observer) {
                it.remove();
            }
        }
    }
}
