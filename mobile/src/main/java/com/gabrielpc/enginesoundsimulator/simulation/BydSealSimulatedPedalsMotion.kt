package com.gabrielpc.enginesoundsimulator.simulation

/**
 * Road-speed model used only by SIMULATED PEDALS.
 *
 * The app is installed in a BYD Seal, so simulated road speed follows the Seal AWD's measured
 * full-throttle envelope rather than the mass and torque of whichever combustion car supplies
 * the FMOD bank. The bank's own drivetrain still owns RPM, clutch, turbo and event behaviour.
 */
internal class BydSealSimulatedPedalsMotion {
    private var speedKmh = 0.0
    private var accelerationKmhPerSecond = 0.0

    fun reset(initialSpeedKmh: Double = 0.0) {
        speedKmh = initialSpeedKmh.coerceIn(0.0, TOP_SPEED_KMH)
        accelerationKmhPerSecond = 0.0
    }

    fun step(
        throttle: Double,
        brake: Double,
        transmissionPosition: TransmissionPosition,
        deltaSeconds: Double,
        initialSpeedKmh: Double? = null,
    ): BydSealMotionFrame {
        initialSpeedKmh?.let { speedKmh = it.coerceIn(0.0, TOP_SPEED_KMH) }
        if (transmissionPosition == TransmissionPosition.PARK) {
            speedKmh = 0.0
            accelerationKmhPerSecond = 0.0
            return BydSealMotionFrame(speedKmh, accelerationKmhPerSecond)
        }
        val dt = deltaSeconds.coerceIn(0.001, 0.020)
        val pedal = throttle.coerceIn(0.0, 1.0)
        val brakePedal = brake.coerceIn(0.0, 1.0)
        val canDrive = transmissionPosition == TransmissionPosition.DRIVE
        val propulsion = if (canDrive) fullThrottleAccelerationKmhPerSecond(speedKmh) * pedal else 0.0
        // A non-zero virtual pedal requests its direct fraction of the measured full-load curve.
        // Applying an additional hidden drag at partial pedal would break that requested rule of three.
        val coast = if (pedal <= 0.0) coastDecelerationKmhPerSecond(speedKmh) else 0.0
        val serviceBrake = MAXIMUM_BRAKE_DECELERATION_KMH_PER_SECOND * brakePedal
        accelerationKmhPerSecond = propulsion - coast - serviceBrake
        speedKmh = (speedKmh + accelerationKmhPerSecond * dt).coerceIn(0.0, TOP_SPEED_KMH)
        if (speedKmh <= 0.0 && accelerationKmhPerSecond < 0.0) accelerationKmhPerSecond = 0.0
        if (speedKmh >= TOP_SPEED_KMH && accelerationKmhPerSecond > 0.0) accelerationKmhPerSecond = 0.0
        return BydSealMotionFrame(speedKmh, accelerationKmhPerSecond)
    }

    /** Linear interpolation keeps acceleration continuous as speed crosses a curve sample. */
    private fun fullThrottleAccelerationKmhPerSecond(speed: Double): Double = interpolate(
        FULL_THROTTLE_ACCELERATION_KMH_PER_SECOND,
        speed,
    )

    private fun coastDecelerationKmhPerSecond(speed: Double): Double = interpolate(
        COAST_DECELERATION_KMH_PER_SECOND,
        speed,
    )

    private fun interpolate(points: List<CurvePoint>, x: Double): Double {
        if (x <= points.first().speedKmh) return points.first().value
        if (x >= points.last().speedKmh) return points.last().value
        val upperIndex = points.indexOfFirst { x <= it.speedKmh }.coerceAtLeast(1)
        val lower = points[upperIndex - 1]
        val upper = points[upperIndex]
        val fraction = (x - lower.speedKmh) / (upper.speedKmh - lower.speedKmh)
        return lower.value + (upper.value - lower.value) * fraction
    }

    internal data class CurvePoint(val speedKmh: Double, val value: Double)

    private companion object {
        const val TOP_SPEED_KMH = 190.0
        const val MAXIMUM_BRAKE_DECELERATION_KMH_PER_SECOND = 28.0

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

internal data class BydSealMotionFrame(
    val speedKmh: Double,
    val accelerationKmhPerSecond: Double,
)
