package com.gabrielpc.enginesoundsimulator.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AtlasAudioSessionEffectsTest {
    @Test
    fun finitePcmParticipantAndPersistentControlStateSurvivePerspectiveAndModeSwitches() {
        val controls = controls()
        val session = session(controls)
        val created = mutableMapOf<EngineSoundPerspective, FakeParticipant>()
        val shared = FakeSharedEffects()
        val owner = AtlasAudioSessionEffects.createForTest(
            state = session,
            controls = controls,
            effectMeterTrackIds = listOf(CABIN_METER, EXTERIOR_METER),
            sharedEffects = shared,
            participantFactory = AtlasSessionEffectsParticipantFactory { perspective, activation ->
                FakeParticipant(perspective, activation.rendererId).also { created[perspective] = it }
            },
        )
        val cabinView = owner.selectPerspective(EngineSoundPerspective.CABIN)
        val cabin = requireNotNull(created[EngineSoundPerspective.CABIN])
        cabin.finiteTailActive = true
        render(cabinView, PrimaryEngineLayerSource.LOAD)
        val engineIntGeneration = session.activationFor(ENGINE_INT)?.generation

        render(cabinView, PrimaryEngineLayerSource.COAST)
        assertEquals(engineIntGeneration, session.activationFor(ENGINE_INT)?.generation)
        assertEquals(2, cabin.renderCount)
        assertEquals(2, shared.updateCount)

        val exteriorView = owner.selectPerspective(EngineSoundPerspective.EXTERIOR)
        cabinView.close()
        assertFalse(cabin.closed)
        val effectLeft = render(exteriorView, PrimaryEngineLayerSource.FMOD_MIX)
        val exterior = requireNotNull(created[EngineSoundPerspective.EXTERIOR])
        assertEquals(3, cabin.renderCount)
        assertEquals(1, exterior.renderCount)
        assertEquals(2, cabin.selectedRenderCount)
        assertEquals(1, cabin.inactiveRenderCount)
        assertEquals(1, exterior.selectedRenderCount)
        assertTrue(effectLeft.all { it == FakeParticipant.TAIL_SAMPLE })
        assertFalse(session.acceptsSemanticUpdates(ENGINE_INT, cabin.rendererId))
        assertTrue(session.acceptsSemanticUpdates(ENGINE_EXT, exterior.rendererId))
        assertThrows(IllegalStateException::class.java) {
            render(cabinView, PrimaryEngineLayerSource.LOAD)
        }

        val cabinAgain = owner.selectPerspective(EngineSoundPerspective.CABIN)
        assertSame(cabin, created[EngineSoundPerspective.CABIN])
        assertEquals(2L, session.activationFor(ENGINE_INT)?.generation)
        assertThrows(IllegalStateException::class.java) {
            render(cabinView, PrimaryEngineLayerSource.LOAD)
        }
        render(cabinAgain, PrimaryEngineLayerSource.LOAD)
        assertEquals(4, cabin.renderCount)
        assertEquals(2, exterior.renderCount)
        assertEquals(3, cabin.selectedRenderCount)
        assertEquals(1, exterior.inactiveRenderCount)
        cabinAgain.close()
        assertThrows(IllegalStateException::class.java) {
            render(cabinAgain, PrimaryEngineLayerSource.LOAD)
        }

        owner.close()
        assertTrue(cabin.closed)
        assertTrue(exterior.closed)
    }

    @Test
    fun metersFromRetainedParticipantsAreMergedIntoOneStableSessionLayout() {
        val controls = controls()
        val session = session(controls)
        val created = mutableMapOf<EngineSoundPerspective, FakeParticipant>()
        val shared = FakeSharedEffects()
        val owner = AtlasAudioSessionEffects.createForTest(
            state = session,
            controls = controls,
            effectMeterTrackIds = listOf(CABIN_METER, EXTERIOR_METER),
            sharedEffects = shared,
            participantFactory = AtlasSessionEffectsParticipantFactory { perspective, activation ->
                FakeParticipant(perspective, activation.rendererId).also { created[perspective] = it }
            },
        )
        owner.selectPerspective(EngineSoundPerspective.CABIN)
        val exteriorView = owner.selectPerspective(EngineSoundPerspective.EXTERIOR)
        created.getValue(EngineSoundPerspective.CABIN).meterLevel = 0.4
        created.getValue(EngineSoundPerspective.EXTERIOR).meterLevel = 0.7
        val destination = DoubleArray(exteriorView.meterTrackIds.size)

        exteriorView.writeMeters(EngineAudioFrame(), destination, 0)

        assertEquals(0.4, destination[exteriorView.meterTrackIds.indexOf(CABIN_METER)], 0.0)
        assertEquals(0.7, destination[exteriorView.meterTrackIds.indexOf(EXTERIOR_METER)], 0.0)
        assertEquals(0.2, destination[destination.size - 3], 0.0)
        assertEquals(0.3, destination[destination.size - 2], 0.0)
        assertEquals(0.4, destination[destination.size - 1], 0.0)
    }

    @Test
    fun retainedPerspectiveParticipantsNeverMixContinuousLoopsTogether() {
        val controls = controls()
        val session = session(controls)
        val created = mutableMapOf<EngineSoundPerspective, FakeParticipant>()
        val owner = AtlasAudioSessionEffects.createForTest(
            state = session,
            controls = controls,
            effectMeterTrackIds = listOf(CABIN_METER, EXTERIOR_METER),
            sharedEffects = FakeSharedEffects(),
            participantFactory = AtlasSessionEffectsParticipantFactory { perspective, activation ->
                FakeParticipant(perspective, activation.rendererId).also { participant ->
                    participant.continuousLoopActive = true
                    created[perspective] = participant
                }
            },
        )
        val cabinView = owner.selectPerspective(EngineSoundPerspective.CABIN)
        assertTrue(render(cabinView, PrimaryEngineLayerSource.FMOD_MIX).all {
            it == FakeParticipant.CONTINUOUS_SAMPLE
        })

        val exteriorView = owner.selectPerspective(EngineSoundPerspective.EXTERIOR)
        assertTrue(render(exteriorView, PrimaryEngineLayerSource.FMOD_MIX).all {
            it == FakeParticipant.CONTINUOUS_SAMPLE
        })

        val cabinAgain = owner.selectPerspective(EngineSoundPerspective.CABIN)
        assertTrue(render(cabinAgain, PrimaryEngineLayerSource.FMOD_MIX).all {
            it == FakeParticipant.CONTINUOUS_SAMPLE
        })
        assertEquals(2, created.getValue(EngineSoundPerspective.CABIN).selectedRenderCount)
        assertEquals(1, created.getValue(EngineSoundPerspective.CABIN).inactiveRenderCount)
        assertEquals(1, created.getValue(EngineSoundPerspective.EXTERIOR).selectedRenderCount)
        assertEquals(1, created.getValue(EngineSoundPerspective.EXTERIOR).inactiveRenderCount)

        owner.close()
    }

    private fun render(
        view: AtlasAudioSessionEffects.View,
        source: PrimaryEngineLayerSource,
    ): DoubleArray {
        val engineLeft = DoubleArray(FRAMES)
        val engineRight = DoubleArray(FRAMES)
        val effectLeft = DoubleArray(FRAMES)
        val effectRight = DoubleArray(FRAMES)
        view.updateAndRender(
            target = EngineAudioFrame(primaryLayerSource = source),
            blockSeconds = FRAMES / 48_000.0,
            effectiveProgramThrottle = if (source == PrimaryEngineLayerSource.COAST) 0.0 else 1.0,
            frameCount = FRAMES,
            engineEventLeft = engineLeft,
            engineEventRight = engineRight,
            effectEventLeft = effectLeft,
            effectEventRight = effectRight,
            anySolo = false,
            loadProgramGain = 1.0,
            coastProgramGain = 1.0,
            loadProgramGainIgnoringSolo = 1.0,
            coastProgramGainIgnoringSolo = 1.0,
        )

        return effectLeft
    }

    private fun controls(): AtlasEffectControlModel = AtlasEffectControlModel(
        AtlasCarAudioPhysics(
            turbos = emptyList(),
            turboBoostDivisor = 0.0,
            backfire = AtlasBackfirePhysics(
                maximumGas = 0.3,
                minimumRpm = 3_500.0,
                maximumRpm = 8_000.0,
                triggerGas = 0.6,
                minimumIntentThrottle = 0.4,
                minimumIntentSeconds = 1.0,
            ),
            limiterFrequencyHz = 40.0,
        ),
        drivenWheelRadiusMeters = 0.5,
    )

    private fun session(controls: AtlasEffectControlModel): AtlasAudioSessionState = AtlasAudioSessionState(
        atlasFamilyId = "test-family",
        eventContracts = listOf(
            AtlasAudioSessionEventContract(
                ENGINE_INT,
                AtlasEventInstanceOwner.SELECTED_PERSPECTIVE_ENGINE,
                setOf(EngineSoundPerspective.CABIN),
            ),
            AtlasAudioSessionEventContract(
                ENGINE_EXT,
                AtlasEventInstanceOwner.SELECTED_PERSPECTIVE_ENGINE,
                setOf(EngineSoundPerspective.EXTERIOR),
            ),
        ),
        effectControls = controls,
    )

    private class FakeParticipant(
        override val perspective: EngineSoundPerspective,
        override val rendererId: Long,
    ) : AtlasSessionEffectsParticipant {
        override val meterTrackIds = listOf(
            if (perspective == EngineSoundPerspective.CABIN) CABIN_METER else EXTERIOR_METER,
        )
        var finiteTailActive = false
        var continuousLoopActive = false
        var renderCount = 0
        var selectedRenderCount = 0
        var inactiveRenderCount = 0
        var meterLevel = 0.0
        var closed = false

        override fun render(
            target: EngineAudioFrame,
            selectedPerspectiveActive: Boolean,
            frameCount: Int,
            engineEventLeft: DoubleArray,
            engineEventRight: DoubleArray,
            effectEventLeft: DoubleArray,
            effectEventRight: DoubleArray,
            anySolo: Boolean,
            loadProgramGain: Double,
            coastProgramGain: Double,
            loadProgramGainIgnoringSolo: Double,
            coastProgramGainIgnoringSolo: Double,
        ) {
            renderCount += 1
            if (selectedPerspectiveActive) {
                selectedRenderCount += 1
            } else {
                inactiveRenderCount += 1
            }
            if (continuousLoopActive && selectedPerspectiveActive) {
                repeat(frameCount) { frame ->
                    effectEventLeft[frame] += CONTINUOUS_SAMPLE
                    effectEventRight[frame] += CONTINUOUS_SAMPLE
                }
            }
            if (!finiteTailActive) return
            repeat(frameCount) { frame ->
                effectEventLeft[frame] += TAIL_SAMPLE
                effectEventRight[frame] += TAIL_SAMPLE
            }
        }

        override fun writeMeters(target: EngineAudioFrame, destination: DoubleArray, offset: Int) {
            destination[offset] = meterLevel
        }

        override fun close() {
            closed = true
        }

        companion object {
            const val TAIL_SAMPLE = 0.25
            const val CONTINUOUS_SAMPLE = 0.125
        }
    }

    private class FakeSharedEffects : AtlasSessionSharedEffects {
        var updateCount = 0

        override fun update(target: EngineAudioFrame, controls: AtlasEffectControlModel) {
            updateCount += 1
        }

        override fun mixFrame(target: EngineAudioFrame, destination: DoubleArray, anySolo: Boolean) = Unit

        override fun writeMeters(target: EngineAudioFrame, destination: DoubleArray, offset: Int) {
            destination[offset] = 0.2
            destination[offset + 1] = 0.3
            destination[offset + 2] = 0.4
        }
    }

    private companion object {
        const val FRAMES = 4
        const val ENGINE_INT = "event:/cars/test/engine_int"
        const val ENGINE_EXT = "event:/cars/test/engine_ext"
        const val CABIN_METER = "atlas_effect_engine_int_engine_event_start"
        const val EXTERIOR_METER = "atlas_effect_engine_ext_engine_event_start"
    }
}
