package com.gabrielpc.enginesoundsimulator.audio

import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.nio.ByteBuffer
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Transient host-loopback diagnostic. It drives one event at a time through the actual Android
 * device output so a simultaneous Windows Stereo Mix capture can measure the rendered contour.
 * This is intentionally not a regression test and must not be committed.
 */
@RunWith(AndroidJUnit4::class)
class FmodHostCaptureDiagnosticTest {
    @Test(timeout = 45_000L)
    fun captureSkylineEngineThenTurbo() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val opened = FmodNativeBridge.open(context)
        assertTrue(opened.error, opened.succeeded)
        val bridge = requireNotNull(opened.bridge)
        try {
            assertSuccess(bridge.loadBanks(FmodCarProfiles.skylineR34.id))
            val buffer = FmodNativeBridge.allocateControlBuffer()

            Log.i(TAG, "CAPTURE-ENGINE begin: 1s silent, 1s 1200RPM, 6s logarithmic 1200->7200RPM, then downshift-shaped 4300->6800->4300RPM")
            runTrace(
                bridge = bridge,
                buffer = buffer,
                durationSeconds = 11.5,
            ) { time ->
                when {
                    time < 1.0 -> Control(audio = false, engine = false, rpm = 1f)
                    time < 2.0 -> Control(audio = true, engine = true, rpm = 1_200f)
                    time < 8.0 -> {
                        val fraction = ((time - 2.0) / 6.0).coerceIn(0.0, 1.0)
                        // Constant musical pitch rate is the cleanest possible input trace.
                        val rpm = 1_200.0 * Math.pow(7_200.0 / 1_200.0, fraction)
                        Control(audio = true, engine = true, rpm = rpm.toFloat())
                    }
                    time < 9.0 -> Control(audio = true, engine = true, rpm = 7_200f)
                    time < 9.08 -> {
                        val fraction = ((time - 9.0) / 0.08).coerceIn(0.0, 1.0)
                        Control(audio = true, engine = true, rpm = (4_300 + 2_500 * fraction).toFloat())
                    }
                    time < 9.38 -> {
                        val fraction = ((time - 9.08) / 0.30).coerceIn(0.0, 1.0)
                        Control(audio = true, engine = true, rpm = (6_800 - 2_500 * fraction).toFloat())
                    }
                    time < 10.5 -> Control(audio = true, engine = true, rpm = 4_300f)
                    else -> Control(audio = false, engine = false, rpm = 1f)
                }
            }

            Log.i(TAG, "CAPTURE-TURBO begin: 1s silent, 1s boost=0.04, 7s linear 0.04->0.333, then 1s hold")
            runTrace(
                bridge = bridge,
                buffer = buffer,
                durationSeconds = 10.5,
            ) { time ->
                when {
                    time < 1.0 -> Control(audio = false, turbo = false, rpm = 1f)
                    time < 2.0 -> Control(audio = true, turbo = true, rpm = 1f, boost = 0.04f)
                    time < 9.0 -> {
                        val fraction = ((time - 2.0) / 7.0).coerceIn(0.0, 1.0)
                        Control(
                            audio = true,
                            turbo = true,
                            rpm = 1f,
                            boost = (0.04 + 0.293 * fraction).toFloat(),
                        )
                    }
                    time < 10.0 -> Control(audio = true, turbo = true, rpm = 1f, boost = 0.333f)
                    else -> Control(audio = false, turbo = false, rpm = 1f)
                }
            }
            Log.i(TAG, "CAPTURE complete")
        } finally {
            bridge.close()
        }
    }

    private fun runTrace(
        bridge: FmodNativeBridge,
        buffer: ByteBuffer,
        durationSeconds: Double,
        controlAt: (Double) -> Control,
    ) {
        val started = SystemClock.elapsedRealtimeNanos()
        val endNanos = started + (durationSeconds * 1_000_000_000.0).toLong()
        var nextNanos = started
        while (true) {
            val now = SystemClock.elapsedRealtimeNanos()
            if (now >= endNanos) break
            if (now < nextNanos) {
                Thread.sleep(1L)
                continue
            }
            val control = controlAt((now - started) / 1_000_000_000.0)
            write(buffer, control)
            assertSuccess(bridge.update(buffer))
            nextNanos += CONTROL_PERIOD_NANOS
            if (nextNanos < now - CONTROL_PERIOD_NANOS * 2L) nextNanos = now + CONTROL_PERIOD_NANOS
        }
    }

    private fun write(buffer: ByteBuffer, control: Control) {
        val layout = FmodNativeBridge.ControlBufferLayout
        val flags = if (control.audio) {
            layout.AUDIO_ENABLED or
                (if (control.engine) layout.ENGINE_ENABLED else 0) or
                (if (control.turbo) layout.TURBO_ENABLED else 0)
        } else {
            0
        }
        buffer.putInt(layout.ENABLED_MASK_OFFSET, flags)
        buffer.putFloat(layout.RPM_OFFSET, control.rpm)
        buffer.putFloat(layout.ENGINE_THROTTLE_OFFSET, 1f)
        buffer.putFloat(layout.BOOST_OFFSET, control.boost)
        buffer.putFloat(layout.BOV_OFFSET, 0f)
        buffer.putFloat(layout.BOV_DECAY_OFFSET, 10f)
        buffer.putFloat(layout.LIMITER_DECAY_OFFSET, 10f)
        buffer.putFloat(layout.MASTER_GAIN_OFFSET, 1f)
        buffer.putFloat(layout.ENGINE_GAIN_OFFSET, 1f)
        buffer.putFloat(layout.TURBO_GAIN_OFFSET, 1f)
        buffer.putFloat(layout.LIMITER_GAIN_OFFSET, 1f)
        buffer.putFloat(layout.SHIFT_GAIN_OFFSET, 1f)
        buffer.putFloat(layout.BACKFIRE_GAIN_OFFSET, 1f)
        buffer.putInt(layout.SHIFT_DIRECTION_OFFSET, 0)
        buffer.putLong(layout.SHIFT_SERIAL_OFFSET, 0L)
        buffer.putLong(layout.LIMITER_SERIAL_OFFSET, 0L)
        buffer.putLong(layout.BOV_SERIAL_OFFSET, 0L)
        buffer.putLong(layout.BACKFIRE_SERIAL_OFFSET, 0L)
        buffer.putFloat(layout.DRIVETRAIN_SPEED_OFFSET, 0f)
        buffer.putFloat(layout.TRANSMISSION_THROTTLE_OFFSET, 0f)
        buffer.putFloat(layout.TRANSMISSION_GAIN_OFFSET, 1f)
    }

    private fun assertSuccess(result: FmodNativeCallResult) {
        val failure = result as? FmodNativeCallResult.Failure
        assertTrue(failure?.detail, result === FmodNativeCallResult.Success)
    }

    private data class Control(
        val audio: Boolean,
        val engine: Boolean = false,
        val turbo: Boolean = false,
        val rpm: Float,
        val boost: Float = 0f,
    )

    private companion object {
        const val TAG = "FmodHostCapture"
        const val CONTROL_PERIOD_NANOS = 2_500_000L
    }
}
