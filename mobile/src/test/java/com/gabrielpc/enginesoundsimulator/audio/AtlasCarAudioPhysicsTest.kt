package com.gabrielpc.enginesoundsimulator.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AtlasCarAudioPhysicsTest {
    @Test
    fun catalogPhysicsParserPropagatesEveryAudioControlAndRejectsSchemaDrift() {
        val values = AtlasRuntimeJson.parse(physicsJson().toByteArray()).objectValues("physics")
        val physics = ExternalCarCatalogParser.parseAtlasAudioPhysics(values, "catalog.cars[0]")
        assertEquals(1, physics.turbos.size)
        assertEquals(0.987, physics.turbos.single().lagUp, 0.0)
        assertEquals(2.0, physics.turboBoostDivisor, 0.0)
        assertEquals(0.3, physics.backfire.maximumGas, 0.0)
        assertEquals(40.0, physics.limiterFrequencyHz, 0.0)

        val drifted = AtlasRuntimeJson.parse(
            physicsJson().replace("\"limiterFrequencyHz\":40.0", "\"limiterFrequencyHz\":40.0,\"unknown\":1")
                .toByteArray(),
        ).objectValues("physics")
        assertThrows(IllegalArgumentException::class.java) {
            ExternalCarCatalogParser.parseAtlasAudioPhysics(drifted, "catalog.cars[0]")
        }
    }

    @Test
    fun donorTurboUsesAllStagesWastegateNormalizationAndBovState() {
        val physics = testPhysics(
            turbos = listOf(
                AtlasTurboStage(0, 1.0, 0.0, 2.0, 1.0, 1_000.0, 1.0, 0.20),
                AtlasTurboStage(1, 2.0, 0.0, 1.0, 1.0, 2_000.0, 2.0, 0.20),
            ),
            boostDivisor = 3.0,
        )
        val turbo = AtlasTurboDynamics(physics)
        repeat(100) { turbo.update(0.01, rpm = 2_000.0, effectiveThrottle = 1.0) }
        assertTrue(turbo.boost > 0.45)
        assertTrue(turbo.boost <= 2.0 / 3.0)

        turbo.update(0.01, rpm = 2_000.0, effectiveThrottle = 0.0)
        assertEquals(1.0, turbo.bov, 0.0)
        assertEquals(0.0, turbo.bovDecay, 0.0)
        assertTrue(turbo.consumeDumpPulse())
        assertFalse(turbo.consumeDumpPulse())
    }

    @Test
    fun effectControlsUsePresentationWheelSpeedAndQualifiedDelayedBackfire() {
        val physics = testPhysics(turbos = emptyList(), boostDivisor = 0.0)
        val controls = AtlasEffectControlModel(physics, drivenWheelRadiusMeters = 0.5)
        var frame = EngineAudioFrame(
            rpm = 5_000.0,
            throttle = 0.8,
            presentationSpeedMetersPerSecond = 10.0,
            throttleLiftEffectsEnabled = true,
        )
        repeat(14) { controls.update(frame, 0.08) }
        assertEquals(20.0, controls.drivetrainSpeedRadiansPerSecond, 0.0)
        assertFalse(controls.isTriggered(AtlasRuntimeTrigger.THROTTLE_LIFT))

        frame = frame.copy(throttle = 0.0)
        controls.update(frame, 0.08)
        assertFalse(controls.isTriggered(AtlasRuntimeTrigger.THROTTLE_LIFT))
        controls.update(frame, 0.08)
        assertFalse(controls.isTriggered(AtlasRuntimeTrigger.THROTTLE_LIFT))
        controls.update(frame, 0.08)
        assertTrue(controls.isTriggered(AtlasRuntimeTrigger.THROTTLE_LIFT))
        assertEquals(0.01, controls.parameter("throttle", AtlasRuntimeTrigger.THROTTLE_LIFT), 0.0)
    }

    @Test
    fun limiterTractionShiftAndStartTriggersAreEdgeDriven() {
        val controls = AtlasEffectControlModel(testPhysics(emptyList(), 0.0), 0.5)
        var frame = EngineAudioFrame(engineStarting = true)
        controls.update(frame, 0.01)
        assertTrue(controls.isTriggered(AtlasRuntimeTrigger.ENGINE_EVENT_START))
        assertTrue(controls.isTriggered(AtlasRuntimeTrigger.ENGINE_START))

        frame = frame.copy(
            engineStarting = false,
            limiterActive = true,
            tractionLimitActive = true,
            shiftSerial = 1L,
            shiftDirection = 1,
            shiftRejectedSerial = 1L,
        )
        controls.update(frame, 0.01)
        assertTrue(controls.isTriggered(AtlasRuntimeTrigger.LIMITER_PULSE))
        assertTrue(controls.isTriggered(AtlasRuntimeTrigger.LIMITER_LOOP))
        assertTrue(controls.isTriggered(AtlasRuntimeTrigger.TRACTION_PULSE))
        assertTrue(controls.isTriggered(AtlasRuntimeTrigger.TRACTION_LIMIT))
        assertTrue(controls.isTriggered(AtlasRuntimeTrigger.SHIFT_UP))
        assertTrue(controls.isTriggered(AtlasRuntimeTrigger.SHIFT_REJECTED))
        assertEquals(0.0, controls.parameter("decay", AtlasRuntimeTrigger.LIMITER_LOOP), 0.0)
        assertEquals(0.0, controls.parameter("decay", AtlasRuntimeTrigger.TRACTION_LIMIT), 0.0)

        controls.update(frame, 0.01)
        assertFalse(controls.isTriggered(AtlasRuntimeTrigger.LIMITER_PULSE))
        assertFalse(controls.isTriggered(AtlasRuntimeTrigger.TRACTION_PULSE))
        assertFalse(controls.isTriggered(AtlasRuntimeTrigger.SHIFT_UP))
        assertFalse(controls.isTriggered(AtlasRuntimeTrigger.ENGINE_EVENT_START))

        controls.update(frame, 0.01, selectedEngineEventActivationStarted = true)
        assertTrue(controls.isTriggered(AtlasRuntimeTrigger.ENGINE_EVENT_START))

        controls.update(frame.copy(limiterActive = false, tractionLimitActive = false), 0.01)
        assertEquals(0.01, controls.parameter("decay", AtlasRuntimeTrigger.LIMITER_LOOP), 0.0)
        assertEquals(0.01, controls.parameter("decay", AtlasRuntimeTrigger.TRACTION_LIMIT), 0.0)

        // Lifecycle contracts use monotonically increasing generations; stale telemetry cannot
        // replay a shift merely because a counter was reset by a disconnected producer.
        controls.update(frame.copy(shiftSerial = 0L, shiftRejectedSerial = 0L), 0.01)
        assertFalse(controls.isTriggered(AtlasRuntimeTrigger.SHIFT_UP))
        assertFalse(controls.isTriggered(AtlasRuntimeTrigger.SHIFT_REJECTED))
    }

    @Test
    fun nativeGearTriggersIgnoreAndConsumeStartupShiftSerials() {
        val controls = AtlasEffectControlModel(testPhysics(emptyList(), 0.0), 0.5)
        controls.update(EngineAudioFrame(engineStarting = true), 0.01)

        controls.update(
            EngineAudioFrame(
                engineStarting = true,
                shiftSerial = 1L,
                shiftDirection = 1,
            ),
            0.01,
        )
        assertFalse(controls.isTriggered(AtlasRuntimeTrigger.SHIFT_UP))
        assertFalse(controls.isTriggered(AtlasRuntimeTrigger.SHIFT_DOWN))

        controls.update(
            EngineAudioFrame(
                engineStarting = false,
                shiftSerial = 1L,
                shiftDirection = 1,
            ),
            0.01,
        )
        assertFalse(controls.isTriggered(AtlasRuntimeTrigger.SHIFT_UP))
        assertFalse(controls.isTriggered(AtlasRuntimeTrigger.SHIFT_DOWN))

        controls.update(
            EngineAudioFrame(
                engineStarting = false,
                shiftSerial = 2L,
                shiftDirection = -1,
            ),
            0.01,
        )
        assertTrue(controls.isTriggered(AtlasRuntimeTrigger.SHIFT_DOWN))
        assertFalse(controls.isTriggered(AtlasRuntimeTrigger.SHIFT_UP))
    }

    @Test
    fun engineEventParametersUseTheSameLoadCoastBothThrottleAsTheEngineBed() {
        val controls = AtlasEffectControlModel(testPhysics(emptyList(), 0.0), 0.5)
        val frame = EngineAudioFrame(throttle = 0.37)

        controls.update(
            frame,
            dt = 0.01,
            effectiveProgramThrottle = effectiveAtlasProgramThrottle(AtlasEngineProgram.LOAD_ONLY, frame.throttle),
        )
        assertEquals(1.0, controls.parameter("throttle", AtlasRuntimeTrigger.ENGINE_EVENT_START), 0.0)

        controls.update(
            frame,
            dt = 0.01,
            effectiveProgramThrottle = effectiveAtlasProgramThrottle(AtlasEngineProgram.COAST_ONLY, frame.throttle),
        )
        assertEquals(0.0, controls.parameter("throttle", AtlasRuntimeTrigger.ENGINE_EVENT_START), 0.0)

        controls.update(
            frame,
            dt = 0.01,
            effectiveProgramThrottle = effectiveAtlasProgramThrottle(AtlasEngineProgram.FULL, frame.throttle),
        )
        assertEquals(0.37, controls.parameter("throttle", AtlasRuntimeTrigger.ENGINE_EVENT_START), 0.0)
        assertEquals(0.01, controls.parameter("throttle", AtlasRuntimeTrigger.THROTTLE_LIFT), 0.0)
    }

    @Test
    fun engineTransientLoadAndCoastTrimsFollowTheExplicitAuthoredProgramRole() {
        assertEquals(
            0.5,
            atlasEngineContributorProgramGain(
                role = AtlasEngineProgramRole.COAST,
                loadGain = 3.0,
                coastGain = 0.5,
            ),
            0.0,
        )
        assertEquals(
            3.0,
            atlasEngineContributorProgramGain(
                role = AtlasEngineProgramRole.LOAD,
                loadGain = 3.0,
                coastGain = 0.5,
            ),
            0.0,
        )
        assertEquals(
            1.0,
            atlasEngineContributorProgramGain(
                role = AtlasEngineProgramRole.UNAFFECTED,
                loadGain = 3.0,
                coastGain = 0.5,
            ),
            0.0,
        )
        assertEquals(
            0.0,
            atlasEngineContributorProgramGain(
                role = AtlasEngineProgramRole.UNAFFECTED,
                loadGain = 3.0,
                coastGain = 0.5,
                unaffectedGain = 0.0,
            ),
            0.0,
        )
    }

    private fun testPhysics(
        turbos: List<AtlasTurboStage>,
        boostDivisor: Double,
    ): AtlasCarAudioPhysics = AtlasCarAudioPhysics(
        turbos = turbos,
        turboBoostDivisor = boostDivisor,
        backfire = AtlasBackfirePhysics(
            maximumGas = 0.3,
            minimumRpm = 3_500.0,
            maximumRpm = 8_000.0,
            triggerGas = 0.6,
            minimumIntentThrottle = 0.4,
            minimumIntentSeconds = 1.0,
        ),
        limiterFrequencyHz = 40.0,
    )

    private fun physicsJson(): String = """
        {
          "minimumRpm":0.0,
          "maximumRpm":8000.0,
          "idleRpm":900.0,
          "redlineRpm":7500.0,
          "limiterRpm":7800.0,
          "upshiftRpm":7400.0,
          "gearRatios":[3.0,2.0],
          "upshiftDurationSeconds":0.02,
          "downshiftDurationSeconds":0.04,
          "soundFinalDriveRatio":4.1,
          "soundDrivenWheelRadiusMeters":0.33,
          "turbos":[{"index":0,"lagUp":0.987,"lagDown":0.9993,"maximumBoost":2.0,"wastegate":1.5,"referenceRpm":3500.0,"gamma":2.0,"bovPressureThreshold":0.5}],
          "turboBoostNormalization":{"kind":"TOTAL_PHYSICAL_BOOST_DIVIDED_BY_SUM_MAX_BOOST","divisor":2.0,"minimum":0.0,"maximum":1.0},
          "backfire":{"maximumGas":0.3,"minimumRpm":3500.0,"maximumRpm":8000.0,"triggerGas":0.6,"minimumIntentThrottle":0.4,"minimumIntentSeconds":1.0},
          "limiterFrequencyHz":40.0,
          "drivetrainSpeedControl":{"parameterName":"drivetrain_speed","unit":"drivenWheelRadiansPerSecond","formula":"signedPresentationSpeedMetersPerSecond / soundDrivenWheelRadiusMeters","signed":true}
        }
    """.trimIndent()
}
