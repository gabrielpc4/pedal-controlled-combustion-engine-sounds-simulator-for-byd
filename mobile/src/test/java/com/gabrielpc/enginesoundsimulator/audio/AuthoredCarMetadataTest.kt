package com.gabrielpc.enginesoundsimulator.audio

import com.gabrielpc.enginesoundsimulator.catalog.AlternateGearOptionMetadata
import com.gabrielpc.enginesoundsimulator.catalog.AlternateGearSetMetadata
import com.gabrielpc.enginesoundsimulator.catalog.CarEngineMetadata
import com.gabrielpc.enginesoundsimulator.catalog.CarGearboxMetadata
import com.gabrielpc.enginesoundsimulator.catalog.CurvePointV1
import com.gabrielpc.enginesoundsimulator.catalog.HybridConfigMetadata
import com.gabrielpc.enginesoundsimulator.catalog.HybridControllerFileMetadata
import com.gabrielpc.enginesoundsimulator.catalog.OfficialCarQuirks
import com.gabrielpc.enginesoundsimulator.catalog.TurboControllerFileMetadata
import com.gabrielpc.enginesoundsimulator.catalog.TurboControllerMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthoredCarMetadataTest {
    @Test
    fun `default gearbox stays exact while alternate pools remain unselected metadata`() {
        val gearbox = gearbox(
            forwardRatios = listOf(5.091, 3.2, 2.1, 1.4),
            finalDrive = 4.437,
            alternateGearSets = listOf(
                AlternateGearSetMetadata(
                    file = "data/1st.rto",
                    sha256 = "1".repeat(64),
                    options = listOf(
                        AlternateGearOptionMetadata("short", 5.4),
                        AlternateGearOptionMetadata("long", 4.8),
                    ),
                ),
                AlternateGearSetMetadata(
                    file = "data/final.rto",
                    sha256 = "2".repeat(64),
                    options = listOf(AlternateGearOptionMetadata("race", 4.9)),
                ),
            ),
        )

        val metadata = authoredCarMetadata("test_car", engine(), gearbox, null)

        assertEquals(listOf(5.091, 3.2, 2.1, 1.4), metadata.defaultForwardRatios)
        assertEquals(4.437, metadata.defaultFinalDrive!!, 0.0)
        assertEquals(2, metadata.alternateGearSets.size)
        assertEquals(3, metadata.alternateOptionCount)
        assertEquals("data/1st.rto,data/final.rto", metadata.alternateSourceFiles)
        assertTrue(metadata.alternateGearDiagnostic.contains("data/1st.rto@${"1".repeat(64)}{short=5.4,long=4.8}"))
        assertEquals(5.4, metadata.alternateGearSets[0].options[0].ratio, 0.0)
        assertNull(metadata.hybrid)
    }

    @Test
    fun `hybrid and all wheel drive provenance cannot replace Seal physics`() {
        val hybrid = HybridConfigMetadata(
            file = "data/ers.ini",
            sha256 = "3".repeat(64),
            maximumEnergyKjPerLap = 4_000.0,
            dischargeTimeMs = 33.0,
            hasButtonOverride = true,
            defaultController = 0.7,
            heatTorquePercent = 80.0,
            hasFrontMotors = true,
            frontDischargeTimeMs = 40.0,
            controllerFiles = listOf(HybridControllerFileMetadata("ctrl_ers_0.ini", "4".repeat(64))),
        )
        val metadata = authoredCarMetadata(
            "hybrid_test",
            engine(hybrid = hybrid),
            gearbox(traction = "AWD"),
            null,
        )

        assertEquals(4_000.0, metadata.hybrid!!.maximumEnergyKjPerLap, 0.0)
        assertEquals("ctrl_ers_0.ini", metadata.hybrid.controllerFiles.single().first)
        assertTrue(metadata.hybridDiagnostic.contains(":energy_kj=4000.0:discharge_ms=33.0"))
        assertEquals(
            setOf(OfficialCarQuirks.ALL_WHEEL_DRIVE, OfficialCarQuirks.HYBRID),
            metadata.quirkPolicies.map { it.id }.toSet(),
        )
        assertTrue(metadata.quirkPolicies.all { it.executionSite == QuirkExecutionSite.EXCLUDED_SEAL_PHYSICS })
    }

    @Test
    fun `BMW compatibility DSP and silent Tatuus BOV remain compiler capture decisions`() {
        val gearbox = gearbox()
        val bmw = authoredCarMetadata("bmw_m3_e30_gra", engine(), gearbox, null)
        val tatuus = authoredCarMetadata("tatuusfa1", engine(), gearbox, null)

        assertEquals(
            QuirkExecutionSite.COMPILER_CAPTURE,
            bmw.quirkPolicies.single { it.id == OfficialCarQuirks.BMW_M3_E30_GRA_ADDITIONAL_DSP }.executionSite,
        )
        assertEquals(
            QuirkExecutionSite.COMPILER_CAPTURE,
            tatuus.quirkPolicies.single { it.id == OfficialCarQuirks.AUTHORED_BOV_LANE_SILENT }.executionSite,
        )
    }

    @Test
    fun `partial turbo controller coverage stays metadata only while complete coverage executes`() {
        val partialEngine = engine(turboCount = 2, turboControllers = turboControllers(1))
        val completeEngine = engine(turboCount = 2, turboControllers = turboControllers(2))
        val gearbox = gearbox()

        val partial = authoredCarMetadata(
            "partial_turbo",
            partialEngine,
            gearbox,
            partialEngine.toTurboControllerBank(),
        )
        val complete = authoredCarMetadata(
            "complete_turbo",
            completeEngine,
            gearbox,
            completeEngine.toTurboControllerBank(),
        )

        assertEquals(
            QuirkExecutionSite.METADATA_ONLY,
            partial.quirkPolicies.single { it.id == OfficialCarQuirks.GEAR_DEPENDENT_TURBO }.executionSite,
        )
        assertEquals(
            QuirkExecutionSite.RUNTIME_AUDIO,
            complete.quirkPolicies.single { it.id == OfficialCarQuirks.GEAR_DEPENDENT_TURBO }.executionSite,
        )
    }

    private fun engine(
        hybrid: HybridConfigMetadata? = null,
        turboCount: Int = 0,
        turboControllers: List<TurboControllerFileMetadata> = emptyList(),
    ) = CarEngineMetadata(
        idleRpm = 900.0,
        redlineRpm = 7_000.0,
        limiterRpm = 7_200.0,
        limiterHz = 20.0,
        tachometerMaximumRpm = 8_000.0,
        turboCount = turboCount,
        hybrid = hybrid != null,
        hybridConfig = hybrid,
        turboControllers = turboControllers,
    )

    private fun turboControllers(count: Int) = List(count) { index ->
        TurboControllerFileMetadata(
            file = "ctrl_turbo$index.ini",
            sha256 = index.toString().repeat(64),
            controllers = listOf(
                TurboControllerMetadata(
                    section = "CONTROLLER_0",
                    input = "RPMS",
                    combinator = "ADD",
                    lut = listOf(CurvePointV1(0.0, 0.0), CurvePointV1(7_000.0, 1.0)),
                    filter = 0.0,
                    upLimit = 1.0,
                    downLimit = 0.0,
                ),
            ),
        )
    }

    private fun gearbox(
        traction: String = "RWD",
        forwardRatios: List<Double> = listOf(3.2, 2.1),
        finalDrive: Double = 4.0,
        alternateGearSets: List<AlternateGearSetMetadata> = emptyList(),
    ) = CarGearboxMetadata(
        traction = traction,
        forwardRatios = forwardRatios,
        reverseRatio = 3.0,
        finalDrive = finalDrive,
        upshiftRpm = 7_000.0,
        downshiftLandingRpmByGear = mapOf(2 to 4_593.75),
        upshiftTimeMs = 100.0,
        downshiftTimeMs = 150.0,
        alternateGearSets = alternateGearSets,
    )
}
