package com.themoon.y1.managers

import android.content.SharedPreferences
import android.media.audiofx.AudioEffect
import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import android.media.audiofx.Virtualizer
import com.themoon.y1.MainActivity
import com.themoon.y1.StoragePaths
import com.themoon.y1.views.Notice
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.ArrayList

/**
 * Manages audio hardware effects (Equalizer, BassBoost, Virtualizer, LoudnessEnhancer)
 * and software DSP routing.
 */
class AudioEffectManager private constructor() {

    /** Makes, sets up or releases the effects for the current audio session. Same as [syncAudioEffects]. */
    fun ensureAudioEffectsReady() = syncAudioEffects()

    fun applyAudioEffects() = syncAudioEffects()

    fun applySoundCheck() = syncAudioEffects()

    fun applyEqProfile() = syncAudioEffects()

    /**
     * Reads the sound settings, and runs only the effects that change the sound ([AudioEffectPlan]).
     * An effect with a neutral setting is released, so the effect bundle does no work for it.
     * Call this when a setting changes and when the audio session changes.
     */
    fun syncAudioEffects() {
        val main = MainActivity.instance ?: return
        val player = AudioPlayerManager.getInstance()
        val settings = readSettings(main.prefs)
        val plan = AudioEffectPlan.from(settings)

        main.isSoftwareEqEnabled = settings.softwareEq
        main.isSoundCheckEnabled = settings.soundCheck

        // Software DSP (ExoPlayer): with a neutral setting it only copies the buffer.
        if (plan.softwareEq) {
            for (i in 0 until AudioEffectPlan.SOFTWARE_EQ_BANDS) {
                player.customEqProcessor.setBandLevel(i, settings.eqBandLevels.getOrElse(i) { 0 } / 100.0f)
            }
        }
        player.customEqProcessor.setEnabled(plan.softwareEq)
        player.crossfeedProcessor.setIntensity(if (plan.crossfeed) settings.crossfeedStep else 0)

        val sessionId = player.getAudioSessionId()
        if (sessionId != main.currentAudioSessionId) {
            releaseSessionEffects(main)
            main.currentAudioSessionId = sessionId
        }
        // Session 0 is the global output mix: never attach effects to it.
        if (sessionId <= 0) {
            releaseSessionEffects(main)
            return
        }

        main.equalizer = keepEffect(main.equalizer, plan.hardwareEq, { Equalizer(0, sessionId) }) { eq ->
            val preset = AudioEffectPlan.presetIndex(settings.eqProfile)
            if (preset != null) {
                if (preset >= eq.numberOfPresets) return@keepEffect false
                eq.usePreset(preset.toShort())
            } else {
                val bands = minOf(eq.numberOfBands.toInt(), settings.eqBandLevels.size)
                for (i in 0 until bands) eq.setBandLevel(i.toShort(), settings.eqBandLevels[i].toShort())
            }
            true
        }
        main.bassBoost = keepEffect(main.bassBoost, plan.bassBoost, { BassBoost(0, sessionId) }) { bb ->
            if (bb.strengthSupported) bb.setStrength(AudioEffectPlan.strengthForStep(settings.bassBoostStep))
            true
        }
        main.virtualizer = keepEffect(main.virtualizer, plan.virtualizer, { Virtualizer(0, sessionId) }) { virt ->
            if (virt.strengthSupported) virt.setStrength(AudioEffectPlan.strengthForStep(settings.virtualizerStep))
            true
        }
        main.loudnessEnhancer = keepEffect(main.loudnessEnhancer, plan.loudnessEnhancer, { LoudnessEnhancer(sessionId) }) { le ->
            le.setTargetGain(SOUND_CHECK_GAIN_MB)
            true
        }
    }

    private fun readSettings(prefs: SharedPreferences): AudioEffectSettings {
        val softwareEq = prefs.getBoolean("software_eq_enabled", false)
        val profile = prefs.getString("eq_profile_id", "preset_0") ?: "preset_0"
        val name = AudioEffectPlan.levelsName(profile, softwareEq)
        val levels = if (name == null) IntArray(0) else IntArray(AudioEffectPlan.bandCount(softwareEq)) {
            prefs.getInt(AudioEffectPlan.bandLevelKey(name, it), 0)
        }
        return AudioEffectSettings(
            softwareEq = softwareEq,
            eqProfile = profile,
            eqBandLevels = levels,
            bassBoostStep = prefs.getInt("bass_boost_step", 0),
            virtualizerStep = prefs.getInt("virtualizer_step", 0),
            crossfeedStep = prefs.getInt("crossfeed_step", 0),
            soundCheck = prefs.getBoolean("sound_check_enabled", false)
        )
    }

    /**
     * Returns the effect, set up and enabled, when [needed]; makes it first if [effect] is null.
     * Otherwise (or when [configure] returns false or fails) releases it and returns null.
     */
    private inline fun <T : AudioEffect> keepEffect(effect: T?, needed: Boolean, create: () -> T, configure: (T) -> Boolean): T? {
        if (!needed) {
            release(effect)
            return null
        }
        var e: T? = effect
        try {
            if (e == null) e = create()
            if (!configure(e)) {
                release(e)
                return null
            }
            if (!e.enabled) e.setEnabled(true)
            return e
        } catch (ignored: Exception) {
            release(e)
            return null
        }
    }

    private fun release(effect: AudioEffect?) {
        try { effect?.release() } catch (ignored: Exception) {}
    }

    private fun releaseSessionEffects(main: MainActivity) {
        release(main.equalizer)
        release(main.bassBoost)
        release(main.virtualizer)
        release(main.loudnessEnhancer)
        main.equalizer = null
        main.bassBoost = null
        main.virtualizer = null
        main.loudnessEnhancer = null
    }

    /**
     * Saves one band level of the custom profile [name] in the prefs (the levels that [syncAudioEffects] reads)
     * and in the profile file, then applies it.
     */
    fun setCustomBandLevel(name: String, band: Int, level: Int) {
        val main = MainActivity.instance ?: return
        main.prefs.edit().putInt(AudioEffectPlan.bandLevelKey(name, band), level).apply()
        exportEqProfileToFile(name)
        syncAudioEffects()
    }

    /** Save Current Configuration: writes the levels of the custom profile [name] (in the prefs) to its file. */
    fun saveCustomEqProfile(name: String) = exportEqProfileToFile(name)

    /** Sets all band levels of the custom profile [name] to 0 (flat), in the prefs and in the profile file. */
    fun resetCustomEqProfile(name: String) {
        val main = MainActivity.instance ?: return
        val bands = AudioEffectPlan.bandCount(main.prefs.getBoolean("software_eq_enabled", false))
        val editor = main.prefs.edit()
        for (i in 0 until bands) {
            editor.putInt(AudioEffectPlan.bandLevelKey(name, i), 0)
        }
        editor.commit()
        exportEqProfileToFile(name)
    }

    fun deleteCustomEqProfile(name: String) {
        val main = MainActivity.instance ?: return
        try {
            val file = File(StoragePaths.getEqDir(), "$name.json")
            if (file.exists()) {
                file.delete()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        val listStr = main.prefs.getString("custom_eq_list", "") ?: ""
        val items = listStr.split(",")
        val sb = StringBuilder()
        for (item in items) {
            if (item != name && item.trim().isNotEmpty()) {
                if (sb.isNotEmpty()) sb.append(",")
                sb.append(item)
            }
        }

        val editor = main.prefs.edit()
        editor.putString("custom_eq_list", sb.toString())

        for (i in 0 until 32) {
            editor.remove(AudioEffectPlan.bandLevelKey(name, i))
        }
        editor.commit()

        Notice.show(main, "Profile Deleted Completely.")
    }

    /**
     * Writes the levels of the custom profile [name] from the prefs to its file in the EQ folder.
     * [loadAndSyncExternalEqProfiles] reads the files back into the prefs, so each change must also go to the file.
     */
    fun exportEqProfileToFile(name: String) {
        val main = MainActivity.instance ?: return
        try {
            val folder = StoragePaths.getEqDir()
            if (!folder.exists()) folder.mkdirs()
            val file = File(folder, "$name.json")

            val json = JSONObject()
            json.put("profile_name", name)

            val bandsArray = JSONArray()
            val bands = AudioEffectPlan.bandCount(main.prefs.getBoolean("software_eq_enabled", false))
            for (i in 0 until bands) {
                bandsArray.put(main.prefs.getInt(AudioEffectPlan.bandLevelKey(name, i), 0))
            }
            json.put("bands", bandsArray)

            val fos = FileOutputStream(file)
            fos.write(json.toString(2).toByteArray(Charsets.UTF_8))
            fos.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun loadAndSyncExternalEqProfiles() {
        val main = MainActivity.instance ?: return
        try {
            val folder = StoragePaths.getEqDir()
            if (!folder.exists()) {
                folder.mkdirs()
                return
            }
            val files = folder.listFiles()
            val validCustomList = ArrayList<String>()

            if (files != null) {
                val editor = main.prefs.edit()
                for (f in files) {
                    if (f.isFile && f.name.lowercase().endsWith(".json")) {
                        try {
                            val fis = FileInputStream(f)
                            val data = ByteArray(f.length().toInt())
                            fis.read(data)
                            fis.close()

                            val json = JSONObject(String(data, Charsets.UTF_8))
                            val name = json.optString("profile_name", f.name.replace(".json", ""))
                            val bandsArray = json.optJSONArray("bands")

                            if (bandsArray != null) {
                                for (i in 0 until bandsArray.length()) {
                                    editor.putInt(AudioEffectPlan.bandLevelKey(name, i), bandsArray.getInt(i))
                                }
                                if (!validCustomList.contains(name)) {
                                    validCustomList.add(name)
                                }
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                }

                val sb = StringBuilder()
                for (i in validCustomList.indices) {
                    if (i > 0) sb.append(",")
                    sb.append(validCustomList[i])
                }
                editor.putString("custom_eq_list", sb.toString())
                editor.commit()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    companion object {
        /** Sound Check gain of the LoudnessEnhancer, in millibels. */
        private const val SOUND_CHECK_GAIN_MB = 500

        @Volatile
        private var instance: AudioEffectManager? = null
        @Synchronized
        fun getInstance(): AudioEffectManager {
            var inst = instance
            if (inst == null) {
                inst = AudioEffectManager()
                instance = inst
            }
            return inst
        }
    }
}
