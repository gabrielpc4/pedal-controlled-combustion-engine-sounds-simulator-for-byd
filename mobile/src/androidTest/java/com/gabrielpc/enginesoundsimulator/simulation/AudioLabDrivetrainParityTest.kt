package com.gabrielpc.enginesoundsimulator.simulation

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gabrielpc.enginesoundsimulator.audio.FmodBankProfiles
import com.gabrielpc.enginesoundsimulator.audio.FmodBankResolver
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Frame-for-frame regression against traces exported by the macOS Audio Lab. */
@RunWith(AndroidJUnit4::class)
class AudioLabDrivetrainParityTest {
    @Test
    fun alfaDriveAndNeutralFramesMatchTheAudioLab() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val profile = FmodBankProfiles.find(PROFILE_ID)
        val resolver = FmodBankResolver(context)
        assumeTrue("Install the Alfa v2 bank pack before parity testing", resolver.isInstalled(profile))
        val physics = resolver.physics(profile)
        val testAssets = InstrumentationRegistry.getInstrumentation().context.assets
        val golden = testAssets.open(GOLDEN_ASSET).bufferedReader().use { JSONObject(it.readText()) }
        assertEquals("byd-audio-lab-drivetrain-golden-v1", golden.getString("schema"))
        assertEquals(PROFILE_ID, golden.getString("profileId"))
        assertEquals(FIXED_STEP_SECONDS, golden.getDouble("fixedStepSeconds"), 0.0)

        compareScenario(physics, golden.getJSONObject("scenarios").getJSONArray("drive"), neutral = false)
        compareScenario(physics, golden.getJSONObject("scenarios").getJSONArray("neutral"), neutral = true)
    }

    private fun compareScenario(physics: AssettoPhysics, rows: JSONArray, neutral: Boolean) {
        val simulation = AssettoDrivetrain(physics)
        repeat(rows.length()) { index ->
            val expected = rows.getJSONObject(index)
            if (!neutral && index == MANUAL_UPSHIFT_FRAME) {
                simulation.requestShift(1)
            }
            val actual = simulation.step(
                throttle = expected.getDouble("throttleInput"),
                brake = expected.getDouble("brakeInput"),
                transmissionPosition = if (neutral) TransmissionPosition.NEUTRAL else TransmissionPosition.DRIVE,
                automaticShifting = !neutral,
                externalSpeedMetersPerSecond = null,
                simulatedUphillGrade = 0.0,
                simulatedRegenStrength = 0.0,
                deltaSeconds = FIXED_STEP_SECONDS,
            )
            val label = "${if (neutral) "neutral" else "drive"} frame=$index"
            assertNear(label, "rpm", expected, actual.rpm, RPM_TOLERANCE)
            assertNear(label, "speedMetersPerSecond", expected, actual.speedMetersPerSecond, SPEED_TOLERANCE)
            assertNear(
                label,
                "drivetrainSpeedRadiansPerSecond",
                expected,
                actual.drivetrainSpeedRadiansPerSecond,
                DRIVETRAIN_SPEED_TOLERANCE,
            )
            assertNear(label, "effectiveThrottle", expected, actual.effectiveThrottle, UNIT_TOLERANCE)
            assertNear(label, "clutch", expected, actual.clutch, UNIT_TOLERANCE)
            assertNear(label, "boost", expected, actual.boost, UNIT_TOLERANCE)
            assertNear(label, "bov", expected, actual.bov, UNIT_TOLERANCE)
            assertNear(label, "bovDecaySeconds", expected, actual.bovDecaySeconds, TIMER_TOLERANCE)
            assertEquals("$label gear", expected.getInt("gear"), actual.gear)
            assertEquals("$label limiter", expected.getBoolean("limiterPulse"), actual.limiterPulse)
            assertEquals("$label backfire", expected.getBoolean("backfireTriggered"), actual.backfireTriggered)
            assertEquals("$label shift started", expected.getBoolean("shiftStarted"), actual.shiftStarted)
            assertEquals("$label shift rejected", expected.getBoolean("shiftRejected"), actual.shiftRejected)
            assertEquals("$label shifting", expected.getBoolean("shifting"), actual.shifting)
            assertEquals("$label direction", expected.getInt("shiftDirection"), actual.shiftDirection)
            assertEquals("$label traction active", expected.getBoolean("tractionLimitActive"), actual.tractionLimitActive)
            assertEquals("$label traction pulse", expected.getBoolean("tractionLimitPulse"), actual.tractionLimitPulse)
        }
        assertFalse("Golden trace must not be empty", rows.length() == 0)
    }

    private fun assertNear(
        label: String,
        field: String,
        expected: JSONObject,
        actual: Double,
        tolerance: Double,
    ) {
        assertEquals("$label $field", expected.getDouble(field), actual, tolerance)
    }

    private companion object {
        const val PROFILE_ID = "alfa-romeo-4c"
        const val GOLDEN_ASSET = "audio_lab_golden_alfa_4c.json"
        const val FIXED_STEP_SECONDS = 0.003
        const val MANUAL_UPSHIFT_FRAME = 430
        const val RPM_TOLERANCE = 0.25
        const val SPEED_TOLERANCE = 0.002
        const val DRIVETRAIN_SPEED_TOLERANCE = 0.01
        const val UNIT_TOLERANCE = 0.002
        const val TIMER_TOLERANCE = 0.0031
    }
}
