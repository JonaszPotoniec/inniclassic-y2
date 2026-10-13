package com.themoon.y1.managers

import android.content.SharedPreferences
import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import android.media.audiofx.Virtualizer
import android.os.Build
import android.widget.Toast
import com.themoon.y1.MainActivity
import com.themoon.y1.StoragePaths
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

    fun ensureAudioEffectsReady() {
        val main = MainActivity.instance ?: return
        try {
            val sessionId = AudioPlayerManager.getInstance().getAudioSessionId()
            if (main.equalizer == null || main.currentAudioSessionId != sessionId) {
                main.equalizer?.release()
                main.equalizer = Equalizer(0, sessionId).apply { enabled = true }
            }
            if (main.bassBoost == null || main.currentAudioSessionId != sessionId) {
                main.bassBoost?.release()
                main.bassBoost = BassBoost(0, sessionId).apply { enabled = true }
            }
            if (main.virtualizer == null || main.currentAudioSessionId != sessionId) {
                main.virtualizer?.release()
                main.virtualizer = Virtualizer(0, sessionId).apply { enabled = true }
            }
            if (Build.VERSION.SDK_INT >= 19 && (main.loudnessEnhancer == null || main.currentAudioSessionId != sessionId)) {
                main.loudnessEnhancer?.release()
                main.loudnessEnhancer = LoudnessEnhancer(sessionId)
            }
            main.currentAudioSessionId = sessionId
        } catch (ignored: Exception) {}
    }

    fun applyAudioEffects() {
        val main = MainActivity.instance ?: return
        ensureAudioEffectsReady()

        AudioPlayerManager.getInstance().crossfeedProcessor.setIntensity(main.currentCrossfeedStep)

        val bb = main.bassBoost ?: return
        val virt = main.virtualizer ?: return
        try {
            var bassStrength = (main.currentBassBoostStep * 333).toShort()
            if (main.currentBassBoostStep == 3) bassStrength = 1000
            if (bb.strengthSupported) bb.setStrength(bassStrength)

            var virtStrength = (main.currentVirtualizerStep * 333).toShort()
            if (main.currentVirtualizerStep == 3) virtStrength = 1000
            if (virt.strengthSupported) virt.setStrength(virtStrength)
        } catch (ignored: Exception) {}

        applySoundCheck()
    }

    fun applySoundCheck() {
        val main = MainActivity.instance
        if (Build.VERSION.SDK_INT < 19 || main == null || main.loudnessEnhancer == null) return
        try {
            if (main.isSoundCheckEnabled) {
                main.loudnessEnhancer?.setTargetGain(500)
                main.loudnessEnhancer?.enabled = true
            } else {
                main.loudnessEnhancer?.enabled = false
            }
        } catch (ignored: Exception) {}
    }

    fun applyEqProfile() {
        val main = MainActivity.instance ?: return
        ensureAudioEffectsReady()

        try {
            if (main.isSoftwareEqEnabled) {
                main.equalizer?.enabled = false
                AudioPlayerManager.getInstance().customEqProcessor.setEnabled(true)

                var name = main.currentEqProfile.replace("custom_", "")
                if (main.currentEqProfile.startsWith("preset_")) name = "Flat"

                for (i in 0 until 10) {
                    val level = main.prefs.getInt("eq_custom_" + name + "_band_" + i, 0)
                    main.customBandLevels[i] = level
                    AudioPlayerManager.getInstance().customEqProcessor.setBandLevel(i, level / 100.0f)
                }
                main.prefs.edit().putString("eq_profile_id", main.currentEqProfile).commit()
            } else {
                AudioPlayerManager.getInstance().customEqProcessor.setEnabled(false)
                val eq = main.equalizer ?: return
                eq.enabled = true

                if (main.currentEqProfile.startsWith("preset_")) {
                    val pIdx = main.currentEqProfile.replace("preset_", "").toInt()
                    if (pIdx < eq.numberOfPresets) {
                        eq.usePreset(pIdx.toShort())
                        main.currentEqPresetIndex = pIdx
                        main.prefs.edit().putInt("eq_preset", main.currentEqPresetIndex)
                            .putString("eq_profile_id", main.currentEqProfile).commit()
                    }
                } else {
                    val name = main.currentEqProfile.replace("custom_", "")
                    val bands = eq.numberOfBands
                    for (i in 0 until bands) {
                        val level = main.prefs.getInt("eq_custom_" + name + "_band_" + i, 0)
                        main.customBandLevels[i] = level
                        eq.setBandLevel(i.toShort(), level.toShort())
                    }
                    main.prefs.edit().putString("eq_profile_id", main.currentEqProfile).commit()
                }
            }
        } catch (ignored: Exception) {}
    }

    fun saveCustomEqProfile(name: String) {
        val main = MainActivity.instance ?: return
        val eq = main.equalizer ?: return
        val bands = eq.numberOfBands
        val editor = main.prefs.edit()
        for (i in 0 until bands) {
            editor.putInt("eq_custom_" + name + "_band_" + i, main.customBandLevels[i])
        }
        editor.commit()
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
            editor.remove("eq_custom_" + name + "_band_" + i)
        }
        editor.commit()

        Toast.makeText(main, "Profile Deleted Completely.", Toast.LENGTH_SHORT).show()
    }

    fun exportEqProfileToFile(name: String) {
        val main = MainActivity.instance ?: return
        try {
            val folder = StoragePaths.getEqDir()
            if (!folder.exists()) folder.mkdirs()
            val file = File(folder, "$name.json")

            val json = JSONObject()
            json.put("profile_name", name)

            val bandsArray = JSONArray()
            val bands = main.equalizer?.numberOfBands?.toInt() ?: 5
            for (i in 0 until bands) {
                bandsArray.put(main.prefs.getInt("eq_custom_" + name + "_band_" + i, 0))
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
                                    editor.putInt("eq_custom_" + name + "_band_" + i, bandsArray.getInt(i))
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
        @Volatile
        private var instance: AudioEffectManager? = null

        @JvmStatic
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
