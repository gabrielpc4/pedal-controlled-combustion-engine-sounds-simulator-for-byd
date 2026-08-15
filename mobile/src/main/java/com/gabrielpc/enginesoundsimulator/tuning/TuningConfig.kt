package com.gabrielpc.enginesoundsimulator.tuning

import android.content.Context
import kotlin.math.max
import kotlin.math.min

data class CurvePoint(val x: Double, val y: Double)

data class EngineTuning(
    val idleRpm: Double = 950.0,
    val maxRpm: Double = 10_000.0,
    val redlineRpm: Double = 8_600.0,
    val limiterRpm: Double = 8_850.0,
    val upshiftRpm: Double = 8_250.0,
    val downshiftRpm: Double = 2_250.0,
    val maxTorqueNm: Double = 670.0,
    val peakPowerKw: Double = 390.0,
    val motorMaxRpm: Double = 16_000.0,
    val motorReductionRatio: Double = 10.81,
    val drivetrainEfficiency: Double = 0.92,
    val frontPeakWheelTorqueNm: Double = 3_170.0,
    val rearPeakWheelTorqueNm: Double = 3_975.0,
    val tractionLimitMps2: Double = 10.0,
    val vehicleMassKg: Double = 2_185.0,
    val rotationalMassFactor: Double = 1.10,
    val wheelRadiusMeters: Double = 0.347,
    val dragAreaM2: Double = 0.504,
    val rollingResistanceCoefficient: Double = 0.010,
    val topSpeedKmh: Double = 180.0,
    val syntheticRpmResponseMs: Double = 35.0,
    val simulatorCoastRegenMps2: Double = 0.50,
    val finalDrive: Double = 3.82,
    val throttleAttackMs: Double = 120.0,
    val throttleReleaseMs: Double = 90.0,
    val brakeResponseMs: Double = 55.0,
    val upshiftDurationMs: Double = 270.0,
    val downshiftDurationMs: Double = 340.0,
    val shiftDwellMs: Double = 450.0,
    val gearRatios: List<Double> = DEFAULT_GEARS,
    /** X is normalized road speed, Y is normalized measured front-axle wheel torque. */
    val frontWheelTorqueCurve: List<CurvePoint> = DEFAULT_FRONT_WHEEL_TORQUE_CURVE,
    /** X is normalized road speed, Y is normalized measured rear-axle wheel torque. */
    val rearWheelTorqueCurve: List<CurvePoint> = DEFAULT_REAR_WHEEL_TORQUE_CURVE,
    /** X is physical pedal position, Y is requested motor torque. */
    val throttleCurve: List<CurvePoint> = DEFAULT_THROTTLE_CURVE,
) {
    fun sanitized(): EngineTuning {
        val cleanMaxRpm = maxRpm.coerceIn(6_000.0, 12_000.0)
        val cleanRedline = redlineRpm.coerceIn(4_000.0, cleanMaxRpm - 300.0)
        val cleanLimiter = limiterRpm.coerceIn(cleanRedline, cleanMaxRpm - 100.0)
        val cleanIdle = idleRpm.coerceIn(600.0, min(2_000.0, cleanRedline - 2_000.0))
        val cleanUpshift = upshiftRpm.coerceIn(cleanIdle + 1_000.0, cleanRedline - 100.0)
        val cleanDownshift = downshiftRpm.coerceIn(cleanIdle + 50.0, min(4_500.0, cleanUpshift - 500.0))
        return copy(
            idleRpm = cleanIdle,
            maxRpm = cleanMaxRpm,
            redlineRpm = cleanRedline,
            limiterRpm = cleanLimiter,
            upshiftRpm = cleanUpshift,
            downshiftRpm = cleanDownshift,
            maxTorqueNm = maxTorqueNm.coerceIn(150.0, 1_200.0),
            peakPowerKw = peakPowerKw.coerceIn(100.0, 800.0),
            motorMaxRpm = motorMaxRpm.coerceIn(8_000.0, 25_000.0),
            motorReductionRatio = motorReductionRatio.coerceIn(5.0, 18.0),
            drivetrainEfficiency = drivetrainEfficiency.coerceIn(0.70, 0.99),
            frontPeakWheelTorqueNm = frontPeakWheelTorqueNm.coerceIn(500.0, 6_000.0),
            rearPeakWheelTorqueNm = rearPeakWheelTorqueNm.coerceIn(500.0, 7_000.0),
            tractionLimitMps2 = tractionLimitMps2.coerceIn(3.0, 12.0),
            vehicleMassKg = vehicleMassKg.coerceIn(700.0, 3_500.0),
            rotationalMassFactor = rotationalMassFactor.coerceIn(1.0, 1.30),
            wheelRadiusMeters = wheelRadiusMeters.coerceIn(0.22, 0.50),
            dragAreaM2 = dragAreaM2.coerceIn(0.30, 1.20),
            rollingResistanceCoefficient = rollingResistanceCoefficient.coerceIn(0.005, 0.030),
            topSpeedKmh = topSpeedKmh.coerceIn(100.0, 350.0),
            syntheticRpmResponseMs = syntheticRpmResponseMs.coerceIn(10.0, 250.0),
            simulatorCoastRegenMps2 = simulatorCoastRegenMps2.coerceIn(0.0, 1.50),
            finalDrive = finalDrive.coerceIn(2.0, 6.0),
            throttleAttackMs = throttleAttackMs.coerceIn(15.0, 500.0),
            throttleReleaseMs = throttleReleaseMs.coerceIn(20.0, 800.0),
            brakeResponseMs = brakeResponseMs.coerceIn(15.0, 500.0),
            upshiftDurationMs = upshiftDurationMs.coerceIn(100.0, 900.0),
            downshiftDurationMs = downshiftDurationMs.coerceIn(120.0, 1_000.0),
            shiftDwellMs = shiftDwellMs.coerceIn(100.0, 1_500.0),
            gearRatios = sanitizeGears(gearRatios),
            frontWheelTorqueCurve = sanitizeCurve(
                frontWheelTorqueCurve,
                DEFAULT_FRONT_WHEEL_TORQUE_CURVE,
                lockEndpoints = false,
            ),
            rearWheelTorqueCurve = sanitizeCurve(
                rearWheelTorqueCurve,
                DEFAULT_REAR_WHEEL_TORQUE_CURVE,
                lockEndpoints = false,
            ),
            throttleCurve = sanitizeCurve(throttleCurve, DEFAULT_THROTTLE_CURVE, lockEndpoints = true),
        )
    }

    companion object {
        val DEFAULT_GEARS = listOf(3.14, 2.10, 1.57, 1.24, 1.02, 0.84, 0.69)
        val DEFAULT_FRONT_WHEEL_TORQUE_CURVE = listOf(
            CurvePoint(0.000, 1.000),
            CurvePoint(0.156, 0.989),
            CurvePoint(0.322, 0.906),
            CurvePoint(0.394, 0.761),
            CurvePoint(0.461, 0.622),
            CurvePoint(0.561, 0.459),
            CurvePoint(0.639, 0.366),
            CurvePoint(0.706, 0.309),
            CurvePoint(0.761, 0.266),
            CurvePoint(0.861, 0.221),
            CurvePoint(0.933, 0.190),
            CurvePoint(1.000, 0.169),
        )
        val DEFAULT_REAR_WHEEL_TORQUE_CURVE = listOf(
            CurvePoint(0.000, 1.000),
            CurvePoint(0.156, 0.992),
            CurvePoint(0.322, 0.994),
            CurvePoint(0.394, 0.886),
            CurvePoint(0.461, 0.772),
            CurvePoint(0.561, 0.630),
            CurvePoint(0.639, 0.553),
            CurvePoint(0.706, 0.502),
            CurvePoint(0.761, 0.461),
            CurvePoint(0.861, 0.398),
            CurvePoint(0.933, 0.362),
            CurvePoint(1.000, 0.333),
        )
        val DEFAULT_THROTTLE_CURVE = listOf(
            CurvePoint(0.0, 0.0),
            CurvePoint(0.10, 0.13),
            CurvePoint(0.25, 0.31),
            CurvePoint(0.50, 0.60),
            CurvePoint(0.75, 0.84),
            CurvePoint(1.0, 1.0),
        )
    }
}

data class AudioTuning(
    val masterGain: Double = 0.72,
    val exhaustLevel: Double = 1.00,
    val intakeLevel: Double = 1.00,
    val mechanicalLevel: Double = 1.00,
    val overrunLevel: Double = 1.00,
    val shiftLevel: Double = 1.00,
    val harmonic2: Double = 1.00,
    val harmonic3: Double = 1.00,
    val harmonic4: Double = 1.00,
    val harmonic5: Double = 1.00,
) {
    fun sanitized(): AudioTuning = copy(
        masterGain = masterGain.coerceIn(0.0, 1.20),
        exhaustLevel = exhaustLevel.coerceIn(0.0, 1.50),
        intakeLevel = intakeLevel.coerceIn(0.0, 1.50),
        mechanicalLevel = mechanicalLevel.coerceIn(0.0, 1.50),
        overrunLevel = overrunLevel.coerceIn(0.0, 1.50),
        shiftLevel = shiftLevel.coerceIn(0.0, 1.50),
        harmonic2 = harmonic2.coerceIn(0.0, 1.50),
        harmonic3 = harmonic3.coerceIn(0.0, 1.50),
        harmonic4 = harmonic4.coerceIn(0.0, 1.50),
        harmonic5 = harmonic5.coerceIn(0.0, 1.50),
    )
}

data class TuningConfig(
    val engine: EngineTuning = EngineTuning(),
    val audio: AudioTuning = AudioTuning(),
) {
    fun sanitized(): TuningConfig = copy(engine = engine.sanitized(), audio = audio.sanitized())

    companion object {
        val DEFAULT = TuningConfig()
    }
}

class TuningRepository(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun load(): TuningConfig {
        val defaults = TuningConfig.DEFAULT
        val currentCalibration = preferences.getInt(KEY_CALIBRATION_REVISION, 0) == CALIBRATION_REVISION
        val storedEngine = defaults.engine.copy(
            idleRpm = number(KEY_IDLE, defaults.engine.idleRpm),
            maxRpm = number(KEY_MAX_RPM, defaults.engine.maxRpm),
            redlineRpm = number(KEY_REDLINE_RPM, defaults.engine.redlineRpm),
            limiterRpm = number(KEY_LIMITER_RPM, defaults.engine.limiterRpm),
            upshiftRpm = number(KEY_UPSHIFT, defaults.engine.upshiftRpm),
            downshiftRpm = number(KEY_DOWNSHIFT, defaults.engine.downshiftRpm),
            maxTorqueNm = number(KEY_TORQUE, defaults.engine.maxTorqueNm),
            peakPowerKw = number(KEY_PEAK_POWER, defaults.engine.peakPowerKw),
            motorMaxRpm = number(KEY_MOTOR_MAX_RPM, defaults.engine.motorMaxRpm),
            motorReductionRatio = number(KEY_MOTOR_REDUCTION, defaults.engine.motorReductionRatio),
            drivetrainEfficiency = number(KEY_DRIVETRAIN_EFFICIENCY, defaults.engine.drivetrainEfficiency),
            frontPeakWheelTorqueNm = number(KEY_FRONT_WHEEL_TORQUE, defaults.engine.frontPeakWheelTorqueNm),
            rearPeakWheelTorqueNm = number(KEY_REAR_WHEEL_TORQUE, defaults.engine.rearPeakWheelTorqueNm),
            tractionLimitMps2 = number(KEY_TRACTION_LIMIT, defaults.engine.tractionLimitMps2),
            vehicleMassKg = number(KEY_MASS, defaults.engine.vehicleMassKg),
            rotationalMassFactor = number(KEY_ROTATIONAL_MASS, defaults.engine.rotationalMassFactor),
            wheelRadiusMeters = number(KEY_WHEEL_RADIUS, defaults.engine.wheelRadiusMeters),
            dragAreaM2 = number(KEY_DRAG_AREA, defaults.engine.dragAreaM2),
            rollingResistanceCoefficient = number(KEY_ROLLING_RESISTANCE, defaults.engine.rollingResistanceCoefficient),
            topSpeedKmh = number(KEY_TOP_SPEED, defaults.engine.topSpeedKmh),
            syntheticRpmResponseMs = number(KEY_SYNTHETIC_RPM_RESPONSE, defaults.engine.syntheticRpmResponseMs),
            simulatorCoastRegenMps2 = number(
                KEY_SIMULATOR_COAST_REGEN,
                defaults.engine.simulatorCoastRegenMps2,
            ),
            finalDrive = number(KEY_FINAL_DRIVE, defaults.engine.finalDrive),
            throttleAttackMs = number(KEY_THROTTLE_ATTACK, defaults.engine.throttleAttackMs),
            throttleReleaseMs = number(KEY_THROTTLE_RELEASE, defaults.engine.throttleReleaseMs),
            brakeResponseMs = number(KEY_BRAKE_RESPONSE, defaults.engine.brakeResponseMs),
            upshiftDurationMs = number(KEY_UPSHIFT_DURATION, defaults.engine.upshiftDurationMs),
            downshiftDurationMs = number(KEY_DOWNSHIFT_DURATION, defaults.engine.downshiftDurationMs),
            shiftDwellMs = number(KEY_SHIFT_DWELL, defaults.engine.shiftDwellMs),
            gearRatios = decodeNumbers(preferences.getString(KEY_GEARS, null), defaults.engine.gearRatios),
            frontWheelTorqueCurve = decodeCurve(
                preferences.getString(KEY_FRONT_WHEEL_TORQUE_CURVE, null),
                defaults.engine.frontWheelTorqueCurve,
            ),
            rearWheelTorqueCurve = decodeCurve(
                preferences.getString(KEY_REAR_WHEEL_TORQUE_CURVE, null),
                defaults.engine.rearWheelTorqueCurve,
            ),
            throttleCurve = decodeCurve(preferences.getString(KEY_THROTTLE_CURVE, null), defaults.engine.throttleCurve),
        )
        val engine = if (currentCalibration) storedEngine else defaults.engine
        val audio = defaults.audio.copy(
            masterGain = number(KEY_MASTER_GAIN, defaults.audio.masterGain),
            exhaustLevel = number(KEY_EXHAUST, defaults.audio.exhaustLevel),
            intakeLevel = number(KEY_INTAKE, defaults.audio.intakeLevel),
            mechanicalLevel = number(KEY_MECHANICAL, defaults.audio.mechanicalLevel),
            overrunLevel = number(KEY_OVERRUN, defaults.audio.overrunLevel),
            shiftLevel = number(KEY_SHIFT_LEVEL, defaults.audio.shiftLevel),
            harmonic2 = number(KEY_H2, defaults.audio.harmonic2),
            harmonic3 = number(KEY_H3, defaults.audio.harmonic3),
            harmonic4 = number(KEY_H4, defaults.audio.harmonic4),
            harmonic5 = number(KEY_H5, defaults.audio.harmonic5),
        )
        if (!currentCalibration) {
            preferences.edit().putInt(KEY_CALIBRATION_REVISION, CALIBRATION_REVISION).apply()
        }
        return TuningConfig(engine, audio).sanitized()
    }

    fun save(config: TuningConfig) {
        val clean = config.sanitized()
        preferences.edit()
            .putInt(KEY_CALIBRATION_REVISION, CALIBRATION_REVISION)
            .putString(KEY_IDLE, clean.engine.idleRpm.toString())
            .putString(KEY_MAX_RPM, clean.engine.maxRpm.toString())
            .putString(KEY_REDLINE_RPM, clean.engine.redlineRpm.toString())
            .putString(KEY_LIMITER_RPM, clean.engine.limiterRpm.toString())
            .putString(KEY_UPSHIFT, clean.engine.upshiftRpm.toString())
            .putString(KEY_DOWNSHIFT, clean.engine.downshiftRpm.toString())
            .putString(KEY_TORQUE, clean.engine.maxTorqueNm.toString())
            .putString(KEY_PEAK_POWER, clean.engine.peakPowerKw.toString())
            .putString(KEY_MOTOR_MAX_RPM, clean.engine.motorMaxRpm.toString())
            .putString(KEY_MOTOR_REDUCTION, clean.engine.motorReductionRatio.toString())
            .putString(KEY_DRIVETRAIN_EFFICIENCY, clean.engine.drivetrainEfficiency.toString())
            .putString(KEY_FRONT_WHEEL_TORQUE, clean.engine.frontPeakWheelTorqueNm.toString())
            .putString(KEY_REAR_WHEEL_TORQUE, clean.engine.rearPeakWheelTorqueNm.toString())
            .putString(KEY_TRACTION_LIMIT, clean.engine.tractionLimitMps2.toString())
            .putString(KEY_MASS, clean.engine.vehicleMassKg.toString())
            .putString(KEY_ROTATIONAL_MASS, clean.engine.rotationalMassFactor.toString())
            .putString(KEY_WHEEL_RADIUS, clean.engine.wheelRadiusMeters.toString())
            .putString(KEY_DRAG_AREA, clean.engine.dragAreaM2.toString())
            .putString(KEY_ROLLING_RESISTANCE, clean.engine.rollingResistanceCoefficient.toString())
            .putString(KEY_TOP_SPEED, clean.engine.topSpeedKmh.toString())
            .putString(KEY_SYNTHETIC_RPM_RESPONSE, clean.engine.syntheticRpmResponseMs.toString())
            .putString(KEY_SIMULATOR_COAST_REGEN, clean.engine.simulatorCoastRegenMps2.toString())
            .putString(KEY_FINAL_DRIVE, clean.engine.finalDrive.toString())
            .putString(KEY_THROTTLE_ATTACK, clean.engine.throttleAttackMs.toString())
            .putString(KEY_THROTTLE_RELEASE, clean.engine.throttleReleaseMs.toString())
            .putString(KEY_BRAKE_RESPONSE, clean.engine.brakeResponseMs.toString())
            .putString(KEY_UPSHIFT_DURATION, clean.engine.upshiftDurationMs.toString())
            .putString(KEY_DOWNSHIFT_DURATION, clean.engine.downshiftDurationMs.toString())
            .putString(KEY_SHIFT_DWELL, clean.engine.shiftDwellMs.toString())
            .putString(KEY_GEARS, encodeNumbers(clean.engine.gearRatios))
            .putString(KEY_FRONT_WHEEL_TORQUE_CURVE, encodeCurve(clean.engine.frontWheelTorqueCurve))
            .putString(KEY_REAR_WHEEL_TORQUE_CURVE, encodeCurve(clean.engine.rearWheelTorqueCurve))
            .putString(KEY_THROTTLE_CURVE, encodeCurve(clean.engine.throttleCurve))
            .putString(KEY_MASTER_GAIN, clean.audio.masterGain.toString())
            .putString(KEY_EXHAUST, clean.audio.exhaustLevel.toString())
            .putString(KEY_INTAKE, clean.audio.intakeLevel.toString())
            .putString(KEY_MECHANICAL, clean.audio.mechanicalLevel.toString())
            .putString(KEY_OVERRUN, clean.audio.overrunLevel.toString())
            .putString(KEY_SHIFT_LEVEL, clean.audio.shiftLevel.toString())
            .putString(KEY_H2, clean.audio.harmonic2.toString())
            .putString(KEY_H3, clean.audio.harmonic3.toString())
            .putString(KEY_H4, clean.audio.harmonic4.toString())
            .putString(KEY_H5, clean.audio.harmonic5.toString())
            .apply()
    }

    fun reset(): TuningConfig {
        preferences.edit().clear().apply()
        return TuningConfig.DEFAULT
    }

    private fun number(key: String, fallback: Double): Double =
        preferences.getString(key, null)?.toDoubleOrNull() ?: fallback

    private companion object {
        const val PREFERENCES_NAME = "engine_tuning"
        const val KEY_CALIBRATION_REVISION = "calibration_revision"
        const val CALIBRATION_REVISION = 2
        const val KEY_IDLE = "idle_rpm"
        const val KEY_MAX_RPM = "max_rpm"
        const val KEY_REDLINE_RPM = "redline_rpm"
        const val KEY_LIMITER_RPM = "limiter_rpm"
        const val KEY_UPSHIFT = "upshift_rpm"
        const val KEY_DOWNSHIFT = "downshift_rpm"
        const val KEY_TORQUE = "max_torque"
        const val KEY_PEAK_POWER = "peak_power"
        const val KEY_MOTOR_MAX_RPM = "motor_max_rpm"
        const val KEY_MOTOR_REDUCTION = "motor_reduction"
        const val KEY_DRIVETRAIN_EFFICIENCY = "drivetrain_efficiency"
        const val KEY_FRONT_WHEEL_TORQUE = "front_peak_wheel_torque"
        const val KEY_REAR_WHEEL_TORQUE = "rear_peak_wheel_torque"
        const val KEY_TRACTION_LIMIT = "traction_limit"
        const val KEY_MASS = "vehicle_mass"
        const val KEY_ROTATIONAL_MASS = "rotational_mass_factor"
        const val KEY_WHEEL_RADIUS = "wheel_radius"
        const val KEY_DRAG_AREA = "drag_area"
        const val KEY_ROLLING_RESISTANCE = "rolling_resistance"
        const val KEY_TOP_SPEED = "top_speed"
        const val KEY_SYNTHETIC_RPM_RESPONSE = "synthetic_rpm_response"
        const val KEY_SIMULATOR_COAST_REGEN = "simulator_coast_regen_mps2"
        const val KEY_FINAL_DRIVE = "final_drive"
        const val KEY_THROTTLE_ATTACK = "throttle_attack"
        const val KEY_THROTTLE_RELEASE = "throttle_release"
        const val KEY_BRAKE_RESPONSE = "brake_response"
        const val KEY_UPSHIFT_DURATION = "upshift_duration"
        const val KEY_DOWNSHIFT_DURATION = "downshift_duration"
        const val KEY_SHIFT_DWELL = "shift_dwell"
        const val KEY_GEARS = "gear_ratios"
        const val KEY_FRONT_WHEEL_TORQUE_CURVE = "front_wheel_torque_curve"
        const val KEY_REAR_WHEEL_TORQUE_CURVE = "rear_wheel_torque_curve"
        const val KEY_THROTTLE_CURVE = "throttle_curve"
        const val KEY_MASTER_GAIN = "master_gain"
        const val KEY_EXHAUST = "exhaust_level"
        const val KEY_INTAKE = "intake_level"
        const val KEY_MECHANICAL = "mechanical_level"
        const val KEY_OVERRUN = "overrun_level"
        const val KEY_SHIFT_LEVEL = "shift_level"
        const val KEY_H2 = "harmonic_2"
        const val KEY_H3 = "harmonic_3"
        const val KEY_H4 = "harmonic_4"
        const val KEY_H5 = "harmonic_5"
    }
}

internal fun interpolateCurve(points: List<CurvePoint>, input: Double): Double {
    if (points.isEmpty()) return input.coerceIn(0.0, 1.0)
    val x = input.coerceIn(0.0, 1.0)
    if (x <= points.first().x) return points.first().y
    for (index in 0 until points.lastIndex) {
        val left = points[index]
        val right = points[index + 1]
        if (x <= right.x) {
            val width = (right.x - left.x).coerceAtLeast(0.0001)
            val fraction = ((x - left.x) / width).coerceIn(0.0, 1.0)
            return left.y + (right.y - left.y) * fraction
        }
    }
    return points.last().y
}

private fun sanitizeGears(values: List<Double>): List<Double> {
    val source = if (values.size in 3..10) values else EngineTuning.DEFAULT_GEARS
    val output = mutableListOf<Double>()
    source.forEachIndexed { index, raw ->
        val upper = if (index == 0) 5.0 else output.last() - 0.05
        output += raw.coerceIn(0.45, upper.coerceAtLeast(0.50))
    }
    return output
}

private fun sanitizeCurve(
    values: List<CurvePoint>,
    fallback: List<CurvePoint>,
    lockEndpoints: Boolean,
): List<CurvePoint> {
    if (values.size !in 2..16) return fallback
    val sorted = values.map { CurvePoint(it.x.coerceIn(0.0, 1.0), it.y.coerceIn(0.0, 1.15)) }
        .sortedBy { it.x }
        .toMutableList()
    for (index in 1 until sorted.size) {
        val minimum = sorted[index - 1].x + 0.015
        sorted[index] = sorted[index].copy(x = max(minimum, sorted[index].x).coerceAtMost(1.0))
    }
    if (lockEndpoints) {
        sorted[0] = CurvePoint(0.0, 0.0)
        sorted[sorted.lastIndex] = CurvePoint(1.0, 1.0)
    }
    return sorted
}

private fun encodeNumbers(values: List<Double>): String = values.joinToString(",")

private fun decodeNumbers(value: String?, fallback: List<Double>): List<Double> =
    value?.split(',')?.mapNotNull { it.toDoubleOrNull() }?.takeIf { it.isNotEmpty() } ?: fallback

private fun encodeCurve(values: List<CurvePoint>): String =
    values.joinToString(";") { "${it.x},${it.y}" }

private fun decodeCurve(value: String?, fallback: List<CurvePoint>): List<CurvePoint> =
    value?.split(';')?.mapNotNull { item ->
        val components = item.split(',')
        if (components.size != 2) null else {
            val x = components[0].toDoubleOrNull()
            val y = components[1].toDoubleOrNull()
            if (x == null || y == null) null else CurvePoint(x, y)
        }
    }?.takeIf { it.size >= 2 } ?: fallback
