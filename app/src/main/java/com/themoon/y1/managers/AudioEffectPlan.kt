package com.themoon.y1.managers

/**
 * The sound settings that decide which audio effects run.
 *
 * @param eqProfile "preset_N" or "custom_<name>" (pref "eq_profile_id").
 * @param eqBandLevels levels of the profile in millibels; empty for a hardware preset.
 */
data class AudioEffectSettings(
    val softwareEq: Boolean,
    val eqProfile: String,
    val eqBandLevels: IntArray,
    val bassBoostStep: Int,
    val virtualizerStep: Int,
    val crossfeedStep: Int,
    val soundCheck: Boolean
)

/**
 * Which audio effects must run. An effect with a neutral setting does not run: each running effect of the
 * platform effect bundle uses CPU time for each buffer, also at zero strength or with a flat curve.
 */
data class AudioEffectPlan(
    val hardwareEq: Boolean,
    val softwareEq: Boolean,
    val bassBoost: Boolean,
    val virtualizer: Boolean,
    val crossfeed: Boolean,
    val loudnessEnhancer: Boolean
) {
    companion object {
        const val HARDWARE_EQ_BANDS = 5
        const val SOFTWARE_EQ_BANDS = 10

        /** Preset 0 is "Off" in the preset list. */
        private const val PRESET_OFF = 0

        fun from(s: AudioEffectSettings): AudioEffectPlan {
            val eqAudible = isEqAudible(s)
            return AudioEffectPlan(
                hardwareEq = eqAudible && !s.softwareEq,
                softwareEq = eqAudible && s.softwareEq,
                bassBoost = s.bassBoostStep > 0,
                virtualizer = s.virtualizerStep > 0,
                crossfeed = s.crossfeedStep > 0,
                loudnessEnhancer = s.soundCheck
            )
        }

        private fun isEqAudible(s: AudioEffectSettings): Boolean {
            val preset = presetIndex(s.eqProfile)
            // The software EQ has no presets: it uses the levels of the "Flat" profile for them.
            if (preset != null && !s.softwareEq) return preset != PRESET_OFF
            return s.eqBandLevels.any { it != 0 }
        }

        /** The preset number of a "preset_N" profile, or null for a custom profile. 0 if N is not a number. */
        fun presetIndex(profile: String): Int? =
            if (profile.startsWith("preset_")) profile.removePrefix("preset_").toIntOrNull()?.takeIf { it >= 0 } ?: PRESET_OFF
            else null

        /** The name of the stored band levels for a profile, or null when a hardware preset gives the levels. */
        fun levelsName(profile: String, softwareEq: Boolean): String? = when {
            presetIndex(profile) == null -> profile.removePrefix("custom_")
            softwareEq -> "Flat"
            else -> null
        }

        fun bandCount(softwareEq: Boolean): Int = if (softwareEq) SOFTWARE_EQ_BANDS else HARDWARE_EQ_BANDS

        /** Highest band level in millibels (+15 dB). The lowest level is -[MAX_BAND_LEVEL]. */
        const val MAX_BAND_LEVEL = 1500

        /** The pref key of one band level of the stored levels [name] (see [levelsName]). */
        fun bandLevelKey(name: String, band: Int): String = "eq_custom_" + name + "_band_" + band

        /** [level] moved by [delta] millibels, in the band range. */
        fun stepBandLevel(level: Int, delta: Int): Int = (level + delta).coerceIn(-MAX_BAND_LEVEL, MAX_BAND_LEVEL)

        /** Strength (0–1000) of the Bass Boost and Virtualizer steps Off, Weak, Normal, Strong. */
        fun strengthForStep(step: Int): Short = when {
            step <= 0 -> 0
            step >= 3 -> 1000
            else -> (step * 333).toShort()
        }
    }
}
