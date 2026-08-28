package com.gabrielpc.enginesoundsimulator.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TurboControllerRuntimeTest {
    @Test
    fun gearControllerSelectsTheAuthoredBoostEnvelopeWithoutAllocatingAFrameModel() {
        val bank = bank(
            turboCount = 1,
            stage(TurboControllerInput.RPM, TurboControllerCombinator.ADD, 0.0 to 0.0, 6_000.0 to 2.0),
            stage(
                TurboControllerInput.GEAR,
                TurboControllerCombinator.MULTIPLY,
                1.0 to 0.5,
                2.0 to 1.0,
                3.0 to 0.25,
            ),
        )
        val runtime = bank.newRuntime()

        assertEquals(0.50, runtime.update(6_000.0, 1.0, 1, BLOCK_SECONDS), 1e-9)
        assertEquals(1.00, runtime.update(6_000.0, 1.0, 2, BLOCK_SECONDS), 1e-9)
        assertEquals(0.25, runtime.update(6_000.0, 1.0, 3, BLOCK_SECONDS), 1e-9)
    }

    @Test
    fun rpmAndGasControllersUseAddThenMultiplyAndNormalizeToTheAuthoredMaximum() {
        val runtime = bank(
            turboCount = 1,
            stage(TurboControllerInput.RPM, TurboControllerCombinator.ADD, 0.0 to 0.0, 6_000.0 to 2.0),
            stage(
                TurboControllerInput.THROTTLE,
                TurboControllerCombinator.MULTIPLY,
                0.0 to 0.0,
                1.0 to 1.0,
            ),
        ).newRuntime()

        // RPM LUT = 1.0, gas LUT = 0.5, raw output = 0.5, authored maximum = 2.0.
        assertEquals(0.25, runtime.update(3_000.0, 0.5, 4, BLOCK_SECONDS), 1e-9)
        assertEquals(1.00, runtime.update(6_000.0, 1.0, 4, BLOCK_SECONDS), 1e-9)
    }

    @Test
    fun filterUsesRecoveredFloat32RateDeadbandAndZeroInitialState() {
        val runtime = bank(
            turboCount = 1,
            stage(
                TurboControllerInput.RPM,
                TurboControllerCombinator.ADD,
                0.0 to 0.0,
                1_000.0 to 1.0,
                filter = 0.99,
            ),
        ).newRuntime()

        assertEquals(0.0, runtime.update(0.0, 0.0, 1, 0.003), 1e-9)
        val rate = (1.0f - 0.99f) * 1.333333373f * 333.333343f
        val expected = 0.003f * rate
        assertEquals(expected.toDouble(), runtime.update(1_000.0, 0.0, 1, 0.003), 0.0)

        val deadband = bank(
            turboCount = 1,
            stage(
                TurboControllerInput.RPM,
                TurboControllerCombinator.ADD,
                0.0 to 0.0009,
                1_000.0 to 0.0009,
                filter = 0.0,
            ),
        ).newRuntime()
        assertEquals(0.0, deadband.update(1_000.0, 0.0, 1, 0.003), 0.0)
    }

    @Test
    fun controllerExposesAbsoluteDynamicWastegatePressureInCallerOwnedStorage() {
        val runtime = bank(
            turboCount = 1,
            stage(TurboControllerInput.RPM, TurboControllerCombinator.ADD, 0.0 to 0.0, 6_000.0 to 2.0),
            stage(
                TurboControllerInput.GEAR,
                TurboControllerCombinator.MULTIPLY,
                1.0 to 0.5,
                2.0 to 1.0,
            ),
        ).newRuntime()
        val output = DoubleArray(1)

        runtime.updateInto(6_000.0, 1.0, 1, 0.003, output)
        assertEquals(1.0, output[0], 0.0)
        assertEquals(1.0, runtime.latestAbsoluteOutput(0), 0.0)
        runtime.updateInto(6_000.0, 1.0, 2, 0.003, output)
        assertEquals(2.0, output[0], 0.0)
    }

    @Test
    fun physicalTurboUsesExactThreeMillisecondSpoolPressureAndBovState() {
        val runtime = TurboPhysicsSpec(
            bovPressureThreshold = 0.20,
            units = arrayOf(
                TurboPhysicsUnitSpec(
                    maximumBoost = 2.0,
                    wastegate = 1.5,
                    referenceRpm = 6_000.0,
                    gamma = 2.0,
                    lagUp = 1_000.0,
                    lagDown = 1.0,
                    controllerProgramIndex = -1,
                ),
            ),
            controllerBank = null,
        ).newRuntime()

        // target=(1*3000/6000)^2=.25; dt*lagUp clamps to one.
        assertEquals(0.25, runtime.update(3_000.0, 1.0, 1, 0.003), 1e-12)
        assertEquals(0.50, runtime.latestTotalBoost, 1e-12)
        assertEquals(0.0, runtime.latestBovValue, 0.0)

        // On lift q retains 99.7%; pressure remains above threshold and BOV rises once.
        assertEquals(0.24925, runtime.update(3_000.0, 0.0, 1, 0.003), 1e-12)
        assertEquals(1.0, runtime.latestBovValue, 0.0)
        assertTrue(runtime.bovRisingEdge)
        assertEquals(0.0, runtime.latestBovDecaySeconds, 0.0)
        runtime.update(3_000.0, 0.0, 1, 0.003)
        assertTrue(!runtime.bovRisingEdge)
        assertEquals(0.0, runtime.latestBovDecaySeconds, 0.0)

        repeat(1_000) { runtime.update(3_000.0, 0.0, 1, 0.003) }
        assertEquals(0.0, runtime.latestBovValue, 0.0)
        assertTrue(runtime.latestBovDecaySeconds > 0.0)
    }

    @Test
    fun limiterUsesAssettoCorsasIntegerThreeMillisecondCounterAndLingersAfterReloadStops() {
        val runtime = TurboPhysicsSpec(
            bovPressureThreshold = 10.0,
            units = arrayOf(
                TurboPhysicsUnitSpec(1.0, 1.0, 1_000.0, 1.0, 1_000.0, 1_000.0, -1),
            ),
            controllerBank = null,
        ).newRuntime()
        fun step(reload: Boolean): Double = runtime.update(
            rpm = 1_000.0,
            postAssistPedal = 1.0,
            mappedEngineGas = 1.0,
            limiterReload = reload,
            limiterHz = 30.0,
            gear = 1,
            elapsedSeconds = 0.003,
        )

        assertEquals(1.0, step(false), 0.0)
        // N=trunc(trunc(1000/30)/3)=11; the reload tick consumes the first cut step.
        assertEquals(0.0, step(true), 0.0)
        repeat(10) { assertEquals(0.0, step(false), 0.0) }
        assertEquals(1.0, step(false), 0.0)
    }

    @Test
    fun turboControllerGasReadsPostAssistControlsBeforeThrottleMapAndLimiterCut() {
        val controller = bank(
            turboCount = 1,
            stage(
                TurboControllerInput.THROTTLE,
                TurboControllerCombinator.ADD,
                0.0 to 0.0,
                1.0 to 1.0,
            ),
        )
        val runtime = TurboPhysicsSpec(
            bovPressureThreshold = 10.0,
            units = arrayOf(
                TurboPhysicsUnitSpec(1.0, 1.0, 1_000.0, 1.0, 1_000.0, 0.0, 0),
            ),
            controllerBank = controller,
        ).newRuntime()
        fun step(limiterReload: Boolean): Double = runtime.update(
            rpm = 1_000.0,
            postAssistPedal = 1.0,
            mappedEngineGas = 1.0,
            limiterReload = limiterReload,
            limiterHz = 20.0,
            gear = 1,
            elapsedSeconds = 0.003,
        )

        assertEquals(1.0, step(false), 0.0)
        assertEquals(
            "limiter cuts spool target but must not feed zero into ctrl_turbo GAS",
            1.0,
            step(true),
            0.0,
        )
        assertEquals(0.0, runtime.latestEffectiveThrottle, 0.0)
    }

    @Test
    fun staticZeroWastegateDisablesTheCapButDynamicZeroClosesIt() {
        val staticRuntime = TurboPhysicsSpec(
            bovPressureThreshold = 10.0,
            units = arrayOf(
                TurboPhysicsUnitSpec(2.0, 0.0, 1_000.0, 1.0, 1_000.0, 0.0, -1),
            ),
            controllerBank = null,
        ).newRuntime()

        staticRuntime.update(1_000.0, 1.0, 1, 0.003)
        assertEquals("static WASTEGATE=0 is AC's no-cap sentinel", 2.0,
            staticRuntime.latestTotalBoost, 0.0)

        val zeroController = bank(
            turboCount = 1,
            stage(
                TurboControllerInput.THROTTLE,
                TurboControllerCombinator.ADD,
                0.0 to 0.0,
                1.0 to 0.0,
            ),
        )
        val dynamicRuntime = TurboPhysicsSpec(
            bovPressureThreshold = 10.0,
            units = arrayOf(
                TurboPhysicsUnitSpec(2.0, 1.5, 1_000.0, 1.0, 1_000.0, 0.0, 0),
            ),
            controllerBank = zeroController,
        ).newRuntime()

        dynamicRuntime.update(1_000.0, 1.0, 1, 0.003)
        assertEquals("dynamic zero is an absolute replacement, not the static sentinel", 0.0,
            dynamicRuntime.latestTotalBoost, 0.0)
    }

    @Test
    fun bovRisingEdgeRetainsItsPhysicalBoostWhenValveClosesLaterInSameAudioBlock() {
        val runtime = TurboPhysicsSpec(
            bovPressureThreshold = 0.20,
            units = arrayOf(
                TurboPhysicsUnitSpec(1.0, 1.0, 1_000.0, 1.0, 1_000.0, 200.0, -1),
            ),
            controllerBank = null,
        ).newRuntime()
        runtime.update(1_000.0, 1.0, 1, 0.003)

        runtime.update(1_000.0, 0.0, 1, 0.006)

        assertTrue(runtime.bovRisingEdge)
        assertEquals(0.40, runtime.bovRisingEdgeBoost, 1e-12)
        assertEquals("second 3 ms tick closes the valve", 0.0, runtime.latestBovValue, 0.0)
        assertTrue(runtime.latestBovDecaySeconds > 0.0)
    }

    @Test
    fun partialControllerCoverageDoesNotInventTheMissingTurbosAudioContribution() {
        val runtime = bank(
            turboCount = 2,
            stage(TurboControllerInput.RPM, TurboControllerCombinator.ADD, 0.0 to 1.0, 5_000.0 to 0.0),
        ).newRuntime()

        assertEquals(1.0, runtime.update(5_000.0, 1.0, 3, BLOCK_SECONDS), 0.0)
        assertEquals(0.0, runtime.latestNormalizedOutput, 0.0)
    }

    @Test
    fun rendererAppliesGearControllerToContinuousTurboTrack() {
        val lowGearLevel = renderTurboLevel(gear = 1)
        val fullGearLevel = renderTurboLevel(gear = 2)

        assertTrue("gear 1=$lowGearLevel gear 2=$fullGearLevel", lowGearLevel in 0.45..0.55)
        assertTrue("gear 1=$lowGearLevel gear 2=$fullGearLevel", fullGearLevel > 0.95)
    }

    @Test
    fun rendererUsesRawPedalThroughThrottleMapAndAppliesOnlySupportedGasCuts() {
        val effect = SampleEffectSpec(
            id = "physical_turbo",
            control = SampleEffectControls.turbo,
            assetName = "physical_turbo",
            trigger = SampleEffectTrigger.CONTINUOUS_LOOP,
            turboAudioResponse = TurboAudioResponse.BOOST,
        )
        val profile = EngineSampleProfile(
            id = "physical-input-test",
            displayName = "Physical input test",
            assetDirectory = "test",
            previewAssetName = "",
            outputSampleRate = 48_000,
            minimumRpm = 0.0,
            maximumRpm = 8_000.0,
            idleRpm = 900.0,
            redlineRpm = 7_000.0,
            limiterRpm = 7_200.0,
            upshiftRpm = 7_000.0,
            gearRatios = listOf(3.0, 2.0),
            upshiftDurationSeconds = 0.1,
            downshiftDurationSeconds = 0.1,
            layers = emptyList(),
            effects = listOf(effect),
            turboPhysics = TurboPhysicsSpec(
                bovPressureThreshold = 0.2,
                units = arrayOf(
                    TurboPhysicsUnitSpec(
                        maximumBoost = 1.0,
                        wastegate = 1.0,
                        referenceRpm = 900.0,
                        gamma = 1.0,
                        lagUp = 1_000.0,
                        lagDown = 1_000.0,
                        controllerProgramIndex = -1,
                    ),
                ),
                controllerBank = null,
            ),
            turboPhysicalThrottleCurve = AutomationCurve(
                listOf(CurvePoint(0.0, 0.0), CurvePoint(0.5, 1.0), CurvePoint(1.0, 1.0)),
            ),
            engineGasAssist = EngineGasAssistSpec(
                autoShifterGasCutoffMs = 3.0,
                engineCutoffMs = 3.0,
                autoBlipElectronic = false,
                autoBlipTimesMs = DoubleArray(0),
                autoBlipPedals = DoubleArray(0),
                autoBlipEndTimeMs = 0.0,
            ),
        )
        val pcm = PcmLoopData(
            arrayOf(FloatArray(512) { 0.25f }, FloatArray(512) { 0.25f }),
            48_000,
        )
        val renderer = SampleEngineRenderer.fromDecoded(48_000, mapOf(effect.id to pcm), profile)
        val output = ShortArray(288)
        val enabled = SampleEffectControls.turbo.bit
        val rawHalfPedal = EngineAudioFrame(
            rpm = 900.0,
            throttle = 0.5,
            enabledEffectMask = enabled,
        )

        renderer.render(rawHalfPedal, output, 1.0)
        assertEquals("physical pedal must bypass audio-throttle smoothing", 1.0,
            renderer.diagnostics().turboControllerGain, 0.0)
        renderer.render(rawHalfPedal.copy(shiftSerial = 1, shiftDirection = 1), output, 1.0)
        assertEquals("upshift assist cuts physical engine gas", 0.0,
            renderer.diagnostics().turboControllerGain, 0.0)
        renderer.render(rawHalfPedal.copy(shiftSerial = 2, shiftDirection = -1), output, 1.0)
        assertEquals("no unauthored downshift blip or cut may be invented", 1.0,
            renderer.diagnostics().turboControllerGain, 0.0)
        renderer.render(rawHalfPedal.copy(rpm = 7_300.0, shiftSerial = 2), output, 1.0)
        assertEquals("limiter is the final physical gas cut", 0.0,
            renderer.diagnostics().turboControllerGain, 0.0)
    }

    @Test
    fun autoBlipUsesAuthoredOrderAndSeparatePoint2EndInsteadOfSorting() {
        val spec = EngineGasAssistSpec(
            autoShifterGasCutoffMs = 0.0,
            engineCutoffMs = 0.0,
            autoBlipElectronic = false,
            autoBlipTimesMs = doubleArrayOf(0.0, 20.0, 130.0, 60.0),
            autoBlipPedals = doubleArrayOf(0.0, 0.9, 0.9, 0.0),
            autoBlipEndTimeMs = 60.0,
        )

        assertEquals(0.0, spec.autoBlipPedalAt(0.0), 0.0)
        assertEquals(0.45, spec.autoBlipPedalAt(10.0), 1e-12)
        assertEquals(0.9, spec.autoBlipPedalAt(20.0), 0.0)
        assertEquals("unreachable POINT_1 must not reorder the curve", 0.9,
            spec.autoBlipPedalAt(59.0), 0.0)
        assertEquals("POINT_2 is an exclusive program end", 0.0,
            spec.autoBlipPedalAt(60.0), 0.0)
    }

    @Test
    fun acceptedShiftSerialDrivesDistinctControlsAndEngineGasCutsAtThreeMilliseconds() {
        val spec = EngineGasAssistSpec(
            autoShifterGasCutoffMs = 3.0,
            engineCutoffMs = 6.0,
            autoBlipElectronic = false,
            autoBlipTimesMs = DoubleArray(0),
            autoBlipPedals = DoubleArray(0),
            autoBlipEndTimeMs = 0.0,
        )
        val runtime = spec.newRuntime(
            AutomationCurve(
                listOf(CurvePoint(0.0, 0.0), CurvePoint(0.5, 0.8), CurvePoint(1.0, 1.0)),
            ),
            limiterRpm = 7_000.0,
            limiterHz = 20.0,
        )
        fun step(serial: Long, direction: Int = 0) = runtime.update(
            rawPedal = 0.5,
            rpm = 4_000.0,
            gear = 2,
            shiftSerial = serial,
            shiftDirection = direction,
            elapsedSeconds = 0.003,
            turboPhysics = null,
        )

        step(0)
        assertEquals(0.5, runtime.latestControlsGas, 0.0)
        assertEquals(0.8, runtime.latestEffectiveEngineGas, 0.0)
        step(1, 1)
        assertEquals(0.0, runtime.latestControlsGas, 0.0)
        assertEquals(0.0, runtime.latestEngineGas, 0.0)
        step(1)
        assertEquals("AutoShifter cut ended", 0.5, runtime.latestControlsGas, 0.0)
        assertEquals("separate drivetrain cut remains", 0.0, runtime.latestEngineGas, 0.0)
        step(1)
        assertEquals(0.5, runtime.latestEngineGas, 0.0)
        assertEquals(0.8, runtime.latestEffectiveEngineGas, 0.0)
    }

    @Test
    fun acceptedAutomaticDownshiftStartsAuthoredBlipButNoSerialCannotInventOne() {
        val spec = EngineGasAssistSpec(
            autoShifterGasCutoffMs = 0.0,
            engineCutoffMs = 0.0,
            autoBlipElectronic = false,
            autoBlipTimesMs = doubleArrayOf(0.0, 3.0, 9.0, 12.0),
            autoBlipPedals = doubleArrayOf(0.0, 1.0, 1.0, 0.0),
            autoBlipEndTimeMs = 12.0,
        )
        val runtime = spec.newRuntime(
            AutomationCurve(listOf(CurvePoint(0.0, 0.0), CurvePoint(1.0, 1.0))),
            limiterRpm = 7_000.0,
            limiterHz = 20.0,
        )
        fun step(serial: Long, direction: Int = 0) = runtime.update(
            rawPedal = 0.2,
            rpm = 4_000.0,
            gear = 2,
            shiftSerial = serial,
            shiftDirection = direction,
            elapsedSeconds = 0.003,
            turboPhysics = null,
        )

        step(0, -1)
        assertEquals("first frame establishes the accepted-event baseline", 0.2,
            runtime.latestControlsGas, 0.0)
        step(1, -1)
        assertEquals("accepted request begins at authored t=0", 0.2,
            runtime.latestControlsGas, 0.0)
        step(1)
        assertEquals(1.0, runtime.latestControlsGas, 0.0)
        step(1)
        assertEquals(1.0, runtime.latestControlsGas, 0.0)
    }

    @Test
    fun limiterCutsOnlyEngineGasAndLingersForItsTruncatedCounter() {
        val runtime = EngineGasAssistSpec.NONE.newRuntime(
            AutomationCurve(listOf(CurvePoint(0.0, 0.0), CurvePoint(1.0, 1.0))),
            limiterRpm = 7_000.0,
            limiterHz = 100.0,
        )
        fun step(rpm: Double) = runtime.update(
            rawPedal = 0.7,
            rpm = rpm,
            gear = 2,
            shiftSerial = 0,
            shiftDirection = 0,
            elapsedSeconds = 0.003,
            turboPhysics = null,
        )

        step(7_001.0)
        assertEquals("FMOD controls gas ignores limiter cut", 0.7, runtime.latestControlsGas, 0.0)
        assertEquals(0.0, runtime.latestEffectiveEngineGas, 0.0)
        step(6_900.0)
        assertEquals(0.0, runtime.latestEffectiveEngineGas, 0.0)
        step(6_900.0)
        assertEquals(0.0, runtime.latestEffectiveEngineGas, 0.0)
        step(6_900.0)
        assertEquals(0.7, runtime.latestEffectiveEngineGas, 0.0)
    }

    private fun renderTurboLevel(gear: Int): Double {
        val controller = bank(
            turboCount = 1,
            stage(TurboControllerInput.RPM, TurboControllerCombinator.ADD, 0.0 to 0.0, 6_000.0 to 2.0),
            stage(
                TurboControllerInput.GEAR,
                TurboControllerCombinator.MULTIPLY,
                1.0 to 0.5,
                2.0 to 1.0,
            ),
        )
        val effect = SampleEffectSpec(
            id = "turbo",
            control = SampleEffectControls.turbo,
            assetName = "turbo",
            trigger = SampleEffectTrigger.CONTINUOUS_LOOP,
            turboAudioResponse = TurboAudioResponse.BOOST,
        )
        val profile = EngineSampleProfile(
            id = "turbo-controller-test",
            displayName = "Turbo controller test",
            assetDirectory = "test",
            previewAssetName = "",
            outputSampleRate = 48_000,
            minimumRpm = 0.0,
            maximumRpm = 8_000.0,
            idleRpm = 900.0,
            redlineRpm = 7_000.0,
            limiterRpm = 7_200.0,
            upshiftRpm = 7_000.0,
            gearRatios = listOf(3.0, 2.0),
            upshiftDurationSeconds = 0.1,
            downshiftDurationSeconds = 0.1,
            layers = emptyList(),
            effects = listOf(effect),
            turboControllerBank = controller,
            turboPhysics = TurboPhysicsSpec(
                bovPressureThreshold = 0.1,
                units = arrayOf(
                    TurboPhysicsUnitSpec(
                        maximumBoost = 2.0,
                        wastegate = 2.0,
                        referenceRpm = 6_000.0,
                        gamma = 1.0,
                        lagUp = 1_000.0,
                        lagDown = 1_000.0,
                        controllerProgramIndex = 0,
                    ),
                ),
                controllerBank = controller,
            ),
        )
        val pcm = PcmLoopData(
            channelSamples = arrayOf(FloatArray(512) { 0.25f }, FloatArray(512) { 0.25f }),
            sampleRate = 48_000,
        )
        val renderer = SampleEngineRenderer.fromDecoded(48_000, mapOf("turbo" to pcm), profile)
        val target = EngineAudioFrame(
            rpm = 6_000.0,
            throttle = 1.0,
            enabled = true,
            enabledEffectMask = SampleEffectControls.turbo.bit,
            gear = gear,
        )
        val output = ShortArray(512)
        repeat(100) { renderer.render(target, output, 1.0) }
        return renderer.diagnostics().layerOutputMeters.single().outputLevel
    }

    private fun bank(
        turboCount: Int,
        vararg stages: TurboControllerStageSpec,
    ): TurboControllerBankSpec = TurboControllerBankSpec(
        turboCount = turboCount,
        programs = arrayOf(TurboControllerProgramSpec("ctrl_turbo0.ini", arrayOf(*stages))),
    )

    private fun stage(
        input: TurboControllerInput,
        combinator: TurboControllerCombinator,
        vararg points: Pair<Double, Double>,
        filter: Double = 0.0,
    ): TurboControllerStageSpec = TurboControllerStageSpec(
        input = input,
        combinator = combinator,
        inputPoints = DoubleArray(points.size) { points[it].first },
        outputPoints = DoubleArray(points.size) { points[it].second },
        filter = filter,
        downLimit = 0.0,
        upLimit = 10_000.0,
    )

    private companion object {
        const val BLOCK_SECONDS = 256.0 / 48_000.0
    }
}
