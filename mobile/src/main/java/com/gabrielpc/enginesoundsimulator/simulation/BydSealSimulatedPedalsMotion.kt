package com.gabrielpc.enginesoundsimulator.simulation

/**
 * Road-speed model used only by SIMULATED PEDALS.
 *
 * The app is installed in a BYD Seal, so simulated road speed follows the Seal AWD's measured
 * full-throttle envelope rather than the mass and torque of whichever combustion car supplies
 * the FMOD bank. The bank's own drivetrain still owns RPM, clutch, turbo and event behaviour.
 */
internal class BydSealSimulatedPedalsMotion {
    /** Continuous speed generated from the documented BYD Seal acceleration envelope. */
    private var documentedContinuousSpeedKmh = 0.0
    private val motionFrame = BydSealMotionFrame()

    fun reset(initialDocumentedContinuousSpeedKmh: Double = 0.0) {
        documentedContinuousSpeedKmh = initialDocumentedContinuousSpeedKmh
            .coerceIn(0.0, TOP_SPEED_KMH)
    }

    fun step(
        throttle: Double,
        brake: Double,
        simulatedRegen: Double = 0.0,
        transmissionPosition: TransmissionPosition,
        deltaSeconds: Double,
        initialDocumentedContinuousSpeedKmh: Double? = null,
    ): BydSealMotionFrame {
        initialDocumentedContinuousSpeedKmh?.let {
            documentedContinuousSpeedKmh = it.coerceIn(0.0, TOP_SPEED_KMH)
        }
        if (transmissionPosition == TransmissionPosition.PARK) {
            documentedContinuousSpeedKmh = 0.0
            motionFrame.documentedContinuousSpeedKmh = documentedContinuousSpeedKmh
            return motionFrame
        }
        val dt = deltaSeconds.coerceIn(0.001, 0.050)
        val pedal = throttle.coerceIn(0.0, 1.0)
        val brakePedal = brake.coerceIn(0.0, 1.0)
        val canDrive = transmissionPosition == TransmissionPosition.DRIVE
        val propulsion = if (canDrive) {
            fullThrottleAccelerationKmhPerSecond(documentedContinuousSpeedKmh) * pedal
        } else {
            0.0
        }
        // A non-zero virtual pedal requests its direct fraction of the measured full-load curve.
        // Applying an additional hidden drag at partial pedal would break that requested rule of three.
        val coast = if (pedal <= 0.0) {
            coastDecelerationKmhPerSecond(documentedContinuousSpeedKmh)
        } else {
            0.0
        }
        val serviceBrake = MAXIMUM_BRAKE_DECELERATION_KMH_PER_SECOND * brakePedal
        // This is a SIM-only approximation of the Seal's lift-off energy recovery. It changes
        // road speed, not the authored FMOD drivetrain parameters, and remains user-adjustable.
        // Regeneration is a lift-off effect: a partially pressed accelerator keeps propulsion
        // active and must not simultaneously apply regenerative braking. This mirrors the real
        // pedal contract and prevents the regen slider from fighting the requested throttle.
        val regenerativeBrake = if (pedal <= 0.0) {
            MAXIMUM_REGEN_DECELERATION_KMH_PER_SECOND * simulatedRegen.coerceIn(0.0, 1.0)
        } else {
            0.0
        }
        val accelerationKmhPerSecond = propulsion - coast - serviceBrake - regenerativeBrake
        documentedContinuousSpeedKmh = (
            documentedContinuousSpeedKmh + accelerationKmhPerSecond * dt
            ).coerceIn(0.0, TOP_SPEED_KMH)
        motionFrame.documentedContinuousSpeedKmh = documentedContinuousSpeedKmh
        return motionFrame
    }

    /** Linear interpolation keeps acceleration continuous as speed crosses a curve sample. */
    private fun fullThrottleAccelerationKmhPerSecond(documentedContinuousSpeedKmh: Double): Double = interpolate(
        FULL_THROTTLE_ACCELERATION_KMH_PER_SECOND,
        documentedContinuousSpeedKmh,
    )

    private fun coastDecelerationKmhPerSecond(documentedContinuousSpeedKmh: Double): Double = interpolate(
        COAST_DECELERATION_KMH_PER_SECOND,
        documentedContinuousSpeedKmh,
    )

    private fun interpolate(
        points: List<CurvePoint>,
        documentedContinuousSpeedKmh: Double,
    ): Double {
        if (documentedContinuousSpeedKmh <= points.first().documentedSpeedKmh) {
            return points.first().documentedCurveValue
        }
        if (documentedContinuousSpeedKmh >= points.last().documentedSpeedKmh) {
            return points.last().documentedCurveValue
        }
        var low = 1
        var high = points.lastIndex
        while (low < high) {
            val middle = (low + high) ushr 1
            if (points[middle].documentedSpeedKmh < documentedContinuousSpeedKmh) {
                low = middle + 1
            } else {
                high = middle
            }
        }
        val upperIndex = low
        val lower = points[upperIndex - 1]
        val upper = points[upperIndex]
        val fraction = (documentedContinuousSpeedKmh - lower.documentedSpeedKmh) /
            (upper.documentedSpeedKmh - lower.documentedSpeedKmh)
        return lower.documentedCurveValue +
            (upper.documentedCurveValue - lower.documentedCurveValue) * fraction
    }

    internal data class CurvePoint(
        val documentedSpeedKmh: Double,
        val documentedCurveValue: Double,
    )

    private companion object {
        const val TOP_SPEED_KMH = 190.0
        const val MAXIMUM_BRAKE_DECELERATION_KMH_PER_SECOND = 28.0
        const val MAXIMUM_REGEN_DECELERATION_KMH_PER_SECOND = 5.0

        // Digitized from the supplied Seal AWD trace. Its integral reaches 100 km/h in ~3.97 s.
        val FULL_THROTTLE_ACCELERATION_KMH_PER_SECOND = listOf(
            CurvePoint(0.0, 26.0),
            CurvePoint(20.0, 30.5),
            CurvePoint(40.0, 31.0),
            CurvePoint(60.0, 26.0),
            CurvePoint(80.0, 21.0),
            CurvePoint(100.0, 16.5),
            CurvePoint(120.0, 14.0),
            CurvePoint(140.0, 10.8),
            CurvePoint(160.0, 7.2),
            CurvePoint(180.0, 3.4),
            CurvePoint(189.0, 1.1),
            // Keep a finite final sample; the hard 190 km/h cap performs the electronic cut-off.
            CurvePoint(TOP_SPEED_KMH, 0.8),
        )

        // Passive roll-off only: SIMULATED PEDALS no longer exposes a separate synthetic regen control.
        val COAST_DECELERATION_KMH_PER_SECOND = listOf(
            CurvePoint(0.0, 0.0),
            CurvePoint(20.0, 0.35),
            CurvePoint(60.0, 0.55),
            CurvePoint(100.0, 0.90),
            CurvePoint(140.0, 1.40),
            CurvePoint(180.0, 2.40),
            CurvePoint(TOP_SPEED_KMH, 2.80),
        )
    }
}

internal class BydSealMotionFrame(
    var documentedContinuousSpeedKmh: Double = 0.0,
)
