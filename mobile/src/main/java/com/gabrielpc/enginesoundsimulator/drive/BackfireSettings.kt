package com.gabrielpc.enginesoundsimulator.drive

import android.content.Context
import com.gabrielpc.enginesoundsimulator.AppPreferenceStores

/** Global, bank-independent backfire policy. Values intentionally apply to every car. */
data class BackfireSettings(
    /** Master switch for every backfire source, including the car's authored FMOD events. */
    val backfireAudioEnabled: Boolean = true,
    /** App-owned trigger timing. When false, the car bank's backfire event is used. */
    val overrideLogicEnabled: Boolean = true,
    /** Replace only the bank's selected source while keeping its trigger path. */
    val soundOnlyOverrideEnabled: Boolean = false,
    val armThrottle: Double = 0.40,
    val releaseThrottle: Double = 0.10,
    val releaseDelaySeconds: Double = 1.50,
    val minimumRpm: Double = 1500.0,
    val maximumRpm: Double = 12000.0,
    val backfireGain: Float = 1.0f,
    /** The four Alfa Romeo bank-derived samples retained for the validated sound profile. */
    val allowedSamples: Set<Int> = AlfaBackfireSources.indices.toSet(),
) {
    fun normalized(): BackfireSettings = copy(
        armThrottle = armThrottle.coerceIn(0.05, 1.0),
        releaseThrottle = releaseThrottle.coerceIn(0.0, 0.9),
        releaseDelaySeconds = releaseDelaySeconds.coerceIn(0.0, 5.0),
        minimumRpm = minimumRpm.coerceIn(0.0, 16000.0),
        maximumRpm = maximumRpm.coerceIn(500.0, 20000.0),
        backfireGain = backfireGain.coerceIn(1.0f, 10.0f),
        allowedSamples = allowedSamples.filter { it in AlfaBackfireSources.indices }.toSet().ifEmpty { setOf(1) },
    )
}

internal object AlfaBackfireSources {
    val names = listOf("backfire_1", "backfire_2", "backfire_3", "backfire_4")
    val indices: IntRange = 1..names.size
}

internal class BackfireSettingsRepository(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        AppPreferenceStores.BACKFIRE_SETTINGS,
        Context.MODE_PRIVATE,
    )

    fun load(): BackfireSettings {
        val defaults = BackfireSettings()
        return BackfireSettings(
            backfireAudioEnabled = preferences.getBoolean("audio_enabled", defaults.backfireAudioEnabled),
            overrideLogicEnabled = preferences.getBoolean("override_logic_enabled", defaults.overrideLogicEnabled),
            soundOnlyOverrideEnabled = preferences.getBoolean("sound_only_override", defaults.soundOnlyOverrideEnabled),
            armThrottle = preferences.getFloat("arm_throttle", defaults.armThrottle.toFloat()).toDouble(),
            releaseThrottle = preferences.getFloat("release_throttle", defaults.releaseThrottle.toFloat()).toDouble(),
            releaseDelaySeconds = preferences.getFloat("release_delay", defaults.releaseDelaySeconds.toFloat()).toDouble(),
            minimumRpm = preferences.getFloat("minimum_rpm", defaults.minimumRpm.toFloat()).toDouble(),
            maximumRpm = preferences.getFloat("maximum_rpm", defaults.maximumRpm.toFloat()).toDouble(),
            backfireGain = preferences.getFloat("backfire_gain", defaults.backfireGain),
            allowedSamples = AlfaBackfireSources.indices.filterTo(linkedSetOf()) {
                preferences.getBoolean("sample_$it", true)
            },
        ).normalized()
    }

    fun save(settings: BackfireSettings) {
        val value = settings.normalized()
        preferences.edit()
            .putBoolean("override_logic_enabled", value.overrideLogicEnabled)
            .putBoolean("sound_only_override", value.soundOnlyOverrideEnabled)
            .putBoolean("audio_enabled", value.backfireAudioEnabled)
            .putFloat("arm_throttle", value.armThrottle.toFloat())
            .putFloat("release_throttle", value.releaseThrottle.toFloat())
            .putFloat("release_delay", value.releaseDelaySeconds.toFloat())
            .putFloat("minimum_rpm", value.minimumRpm.toFloat())
            .putFloat("maximum_rpm", value.maximumRpm.toFloat())
            .putFloat("backfire_gain", value.backfireGain)
            .apply {
                AlfaBackfireSources.indices.forEach { putBoolean("sample_$it", it in value.allowedSamples) }
            }
            .commit()
    }

    fun reset() = preferences.edit().clear().commit()
}
