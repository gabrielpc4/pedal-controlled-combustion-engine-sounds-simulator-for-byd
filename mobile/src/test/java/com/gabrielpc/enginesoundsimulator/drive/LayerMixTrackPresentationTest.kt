package com.gabrielpc.enginesoundsimulator.drive

import com.gabrielpc.enginesoundsimulator.audio.LayerMixControl
import com.gabrielpc.enginesoundsimulator.audio.SILENT_CATALOG_PROFILE
import com.gabrielpc.enginesoundsimulator.audio.SampleLayerRole
import com.gabrielpc.enginesoundsimulator.audio.SampleLayerSpec
import org.junit.Assert.assertTrue
import org.junit.Test

class LayerMixTrackPresentationTest {
    @Test
    fun coastTracksExposeTheSamePerTrackVolumeControlAsOtherContinuousLayers() {
        val profile = SILENT_CATALOG_PROFILE.copy(
            layers = listOf(
                SampleLayerSpec(
                    id = "coast_mid",
                    assetName = "coast_mid",
                    role = SampleLayerRole.COAST,
                    startRpm = 0.0,
                    endRpm = 8_000.0,
                    autopitchRootRpm = 4_000.0,
                ),
            ),
        )

        val track = buildLayerMixTracks(
            profile = profile,
            controls = mapOf("coast_mid" to LayerMixControl.DEFAULT),
            outputLevels = emptyMap(),
        ).single()

        assertTrue(track.showVolumeSlider)
    }
}
