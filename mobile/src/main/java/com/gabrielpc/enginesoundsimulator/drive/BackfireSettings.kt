package com.gabrielpc.enginesoundsimulator.drive

import android.content.Context
import com.gabrielpc.enginesoundsimulator.AppPreferenceStores

/** Global, bank-independent backfire policy. Values intentionally apply to every car. */
data class BackfireSettings(
    val enabled: Boolean = true,
    val armThrottle: Double = 0.40,
    val releaseThrottle: Double = 0.10,
    val releaseDelaySeconds: Double = 1.50,
    val minimumRpm: Double = 1500.0,
    val maximumRpm: Double = 12000.0,
    val allowedSamples: Set<Int> = (1..4).toSet(),
) {
    fun normalized(): BackfireSettings = copy(
        armThrottle = armThrottle.coerceIn(0.05, 1.0),
        releaseThrottle = releaseThrottle.coerceIn(0.0, 0.9),
        releaseDelaySeconds = releaseDelaySeconds.coerceIn(0.0, 5.0),
        minimumRpm = minimumRpm.coerceIn(0.0, 16000.0),
        maximumRpm = maximumRpm.coerceIn(500.0, 20000.0),
        allowedSamples = allowedSamples.filter { it in 1..4 }.toSet().ifEmpty { setOf(1) },
    )
}

internal class BackfireSettingsRepository(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        AppPreferenceStores.BACKFIRE_SETTINGS,
        Context.MODE_PRIVATE,
    )

    fun load(): BackfireSettings {
        val defaults = BackfireSettings()
        return BackfireSettings(
            enabled = preferences.getBoolean("enabled", defaults.enabled),
            armThrottle = preferences.getFloat("arm_throttle", defaults.armThrottle.toFloat()).toDouble(),
            releaseThrottle = preferences.getFloat("release_throttle", defaults.releaseThrottle.toFloat()).toDouble(),
            releaseDelaySeconds = preferences.getFloat("release_delay", defaults.releaseDelaySeconds.toFloat()).toDouble(),
            minimumRpm = preferences.getFloat("minimum_rpm", defaults.minimumRpm.toFloat()).toDouble(),
            maximumRpm = preferences.getFloat("maximum_rpm", defaults.maximumRpm.toFloat()).toDouble(),
            allowedSamples = (1..4).filterTo(linkedSetOf()) { preferences.getBoolean("sample_$it", true) },
        ).normalized()
    }

    fun save(settings: BackfireSettings) {
        val value = settings.normalized()
        preferences.edit()
            .putBoolean("enabled", value.enabled)
            .putFloat("arm_throttle", value.armThrottle.toFloat())
            .putFloat("release_throttle", value.releaseThrottle.toFloat())
            .putFloat("release_delay", value.releaseDelaySeconds.toFloat())
            .putFloat("minimum_rpm", value.minimumRpm.toFloat())
            .putFloat("maximum_rpm", value.maximumRpm.toFloat())
            .apply {
                (1..4).forEach { putBoolean("sample_$it", it in value.allowedSamples) }
            }
            .commit()
    }

    fun reset() = preferences.edit().clear().commit()
}
