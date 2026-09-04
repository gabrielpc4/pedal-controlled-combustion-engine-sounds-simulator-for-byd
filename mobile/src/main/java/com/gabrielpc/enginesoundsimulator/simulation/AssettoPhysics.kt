package com.gabrielpc.enginesoundsimulator.simulation

import android.util.JsonReader
import android.util.JsonToken
import java.io.File
import java.io.InputStreamReader

internal data class AssettoPhysics(
    val profileId: String,
    val sourceCarId: String,
    val engine: AssettoEngineSpec,
    val drivetrain: AssettoDrivetrainSpec,
)

internal data class AssettoEngineSpec(
    val inertia: Double,
    val idleRpm: Double,
    val limiterRpm: Double,
    val limiterHz: Double,
    val tachometerMaximumRpm: Double,
    val shiftLightsRpm: List<Double>,
    val coastReferenceRpm: Double,
    val coastReferenceTorque: Double,
    val coastNonLinearity: Double,
    val torqueCurve: List<AssettoCurvePoint>,
    val throttleCurve: List<AssettoCurvePoint>,
    val turbos: List<AssettoTurboSpec>,
    val backfire: AssettoBackfireSpec,
    val spatial: AssettoSpatialSpec,
)

internal data class AssettoTurboSpec(
    val lagDown: Double,
    val lagUp: Double,
    val maximumBoost: Double,
    val wastegate: Double,
    val referenceRpm: Double,
    val gamma: Double,
    val bovThreshold: Double,
)

internal data class AssettoBackfireSpec(
    val maximumGas: Double,
    val minimumRpm: Double,
    val maximumRpm: Double,
    val triggerGas: Double,
)

internal data class AssettoSpatialSpec(
    val driverEyes: AssettoVector3,
    val bonnetCamera: AssettoVector3,
    val enginePosition: String,
    val wheelbase: Double,
    val cgLocation: Double,
    val frontWheelRadius: Double,
    val rearWheelRadius: Double,
)

internal data class AssettoVector3(val x: Double, val y: Double, val z: Double)

internal fun AssettoPhysics.nativeFmodSpatialCoordinates(): FloatArray {
    val spatial = engine.spatial
    val enginePosition = when (spatial.enginePosition.lowercase()) {
        "rear" -> floatArrayOf(
            0f,
            spatial.rearWheelRadius.toFloat(),
            (-(spatial.wheelbase * spatial.cgLocation) + 0.5).toFloat(),
        )

        "front" -> floatArrayOf(
            0f,
            spatial.frontWheelRadius.toFloat(),
            (spatial.wheelbase * (1.0 - spatial.cgLocation)).toFloat(),
        )

        else -> floatArrayOf(
            0f,
            (0.5 * (spatial.frontWheelRadius + spatial.rearWheelRadius)).toFloat(),
            0f,
        )
    }
    val rearAxle = -(spatial.wheelbase * spatial.cgLocation)
    return enginePosition + floatArrayOf(
        0f,
        spatial.rearWheelRadius.toFloat(),
        (rearAxle - 0.5).toFloat(),
        spatial.driverEyes.x.toFloat(),
        spatial.driverEyes.y.toFloat(),
        spatial.driverEyes.z.toFloat(),
        spatial.bonnetCamera.x.toFloat(),
        spatial.bonnetCamera.y.toFloat(),
        spatial.bonnetCamera.z.toFloat(),
    )
}

internal data class AssettoDrivetrainSpec(
    val traction: String,
    val reverseRatio: Double,
    val forwardRatios: List<Double>,
    val finalDrive: Double,
    val gearUpTimeSeconds: Double,
    val gearDownTimeSeconds: Double,
    val autoCutoffTimeSeconds: Double,
    val gearboxInertia: Double,
    val clutchMaximumTorque: Double,
    val autoclutchUpshiftProfile: List<AssettoCurvePoint>,
    val autoclutchDownshiftProfile: List<AssettoCurvePoint>,
    val autoclutchOnChanges: Boolean,
    val autoclutchMinimumRpm: Double,
    val autoclutchMaximumRpm: Double,
    val autoclutchSpeed: Double,
    val autoclutchForced: Boolean,
    val autoblipElectronic: Boolean,
    val autoblipProfileMilliseconds: List<AssettoCurvePoint>,
    val automaticUpshiftRpm: Int,
    val automaticDownshiftRpm: Int,
    val automaticSlipThreshold: Double,
    val automaticGasCutoffSeconds: Double,
    val downshiftProtection: Boolean,
    val downshiftOverrevRpm: Int,
    val downshiftLocksNeutral: Boolean,
    val vehicle: AssettoVehicleSpec,
) {
    fun ratioForGear(gear: Int): Double = when {
        gear == -1 -> reverseRatio
        gear == 0 -> 0.0
        gear in 1..forwardRatios.size -> forwardRatios[gear - 1]
        else -> error("Gear $gear is outside the authored drivetrain")
    }
}

internal data class AssettoVehicleSpec(
    val massKg: Double,
    val frontWeightFraction: Double,
    val frontWheelRadiusMeters: Double,
    val rearWheelRadiusMeters: Double,
    val frontWheelInertia: Double,
    val rearWheelInertia: Double,
    val frontGripCoefficient: Double,
    val rearGripCoefficient: Double,
    val frontRollingResistance0: Double,
    val rearRollingResistance0: Double,
    val frontRollingResistance1: Double,
    val rearRollingResistance1: Double,
    val brakeMaximumTorque: Double,
    val brakeFrontShare: Double,
    val aeroSurfaces: List<AssettoAeroSurfaceSpec>,
    val airDensityKgM3: Double,
)

internal data class AssettoAeroSurfaceSpec(
    val chord: Double,
    val span: Double,
    val angleDegrees: Double,
    val liftGain: Double,
    val dragGain: Double,
    val liftCurve: List<AssettoCurvePoint>,
    val dragCurve: List<AssettoCurvePoint>,
    val controllerSpeedCurve: List<AssettoCurvePoint>,
)

internal data class AssettoCurvePoint(val x: Double, val y: Double)

internal object AssettoPhysicsLoader {
    fun load(file: File): AssettoPhysics {
        val root = file.inputStream().use { input ->
            JsonReader(InputStreamReader(input, Charsets.UTF_8)).use(::readValue).asObject()
        }
        require(root.string("schema") == "byd-assetto-physics-v1") { "Unsupported Assetto physics format" }
        val car = root.objectValue("car")
        val drivetrain = root.objectValue("drivetrain")
        val vehicle = drivetrain.objectValue("vehicle")

        return AssettoPhysics(
            profileId = root.string("profileId"),
            sourceCarId = root.string("sourceCarId"),
            engine = AssettoEngineSpec(
                inertia = car.double("engine_inertia"),
                idleRpm = car.double("idle_rpm"),
                limiterRpm = car.double("limiter_rpm"),
                limiterHz = car.double("limiter_hz"),
                tachometerMaximumRpm = car.double("tachometer_maximum"),
                shiftLightsRpm = car.numberList("shift_lights"),
                coastReferenceRpm = car.double("coast_reference_rpm"),
                coastReferenceTorque = car.double("coast_reference_torque"),
                coastNonLinearity = car.double("coast_non_linearity"),
                torqueCurve = car.curve("torque_curve"),
                throttleCurve = car.curve("throttle_curve"),
                turbos = car.objectList("turbos").map { turbo ->
                    AssettoTurboSpec(
                        lagDown = turbo.double("lag_down"),
                        lagUp = turbo.double("lag_up"),
                        maximumBoost = turbo.double("maximum_boost"),
                        wastegate = turbo.double("wastegate"),
                        referenceRpm = turbo.double("reference_rpm"),
                        gamma = turbo.double("gamma"),
                        bovThreshold = turbo.double("bov_threshold"),
                    )
                },
                backfire = car.objectValue("backfire").let { backfire ->
                    AssettoBackfireSpec(
                        maximumGas = backfire.double("maximum_gas"),
                        minimumRpm = backfire.double("minimum_rpm"),
                        maximumRpm = backfire.double("maximum_rpm"),
                        triggerGas = backfire.double("trigger_gas"),
                    )
                },
                spatial = AssettoSpatialSpec(
                    driverEyes = car.vector("driver_eyes"),
                    bonnetCamera = car.vector("bonnet_camera"),
                    enginePosition = car.string("engine_position"),
                    wheelbase = car.double("wheelbase"),
                    cgLocation = car.double("cg_location"),
                    frontWheelRadius = car.double("front_wheel_radius"),
                    rearWheelRadius = car.double("rear_wheel_radius"),
                ),
            ),
            drivetrain = AssettoDrivetrainSpec(
                traction = drivetrain.string("traction"),
                reverseRatio = drivetrain.double("reverse_ratio"),
                forwardRatios = drivetrain.numberList("forward_ratios"),
                finalDrive = drivetrain.double("final_drive"),
                gearUpTimeSeconds = drivetrain.double("gear_up_time_s"),
                gearDownTimeSeconds = drivetrain.double("gear_down_time_s"),
                autoCutoffTimeSeconds = drivetrain.double("auto_cutoff_time_s"),
                gearboxInertia = drivetrain.double("gearbox_inertia"),
                clutchMaximumTorque = drivetrain.double("clutch_max_torque"),
                autoclutchUpshiftProfile = drivetrain.curve("autoclutch_upshift_profile"),
                autoclutchDownshiftProfile = drivetrain.curve("autoclutch_downshift_profile"),
                autoclutchOnChanges = drivetrain.boolean("autoclutch_use_on_changes"),
                autoclutchMinimumRpm = drivetrain.double("autoclutch_min_rpm"),
                autoclutchMaximumRpm = drivetrain.double("autoclutch_max_rpm"),
                autoclutchSpeed = drivetrain.double("autoclutch_speed"),
                autoclutchForced = drivetrain.boolean("autoclutch_forced"),
                autoblipElectronic = drivetrain.boolean("autoblip_electronic"),
                autoblipProfileMilliseconds = drivetrain.curve("autoblip_profile_ms"),
                automaticUpshiftRpm = drivetrain.int("auto_up_rpm"),
                automaticDownshiftRpm = drivetrain.int("auto_down_rpm"),
                automaticSlipThreshold = drivetrain.double("auto_slip_threshold"),
                automaticGasCutoffSeconds = drivetrain.double("auto_gas_cutoff_s"),
                downshiftProtection = drivetrain.boolean("downshift_protection"),
                downshiftOverrevRpm = drivetrain.int("downshift_overrev_rpm"),
                downshiftLocksNeutral = drivetrain.boolean("downshift_lock_neutral"),
                vehicle = AssettoVehicleSpec(
                    massKg = vehicle.double("mass_kg"),
                    frontWeightFraction = vehicle.double("front_weight_fraction"),
                    frontWheelRadiusMeters = vehicle.double("front_wheel_radius_m"),
                    rearWheelRadiusMeters = vehicle.double("rear_wheel_radius_m"),
                    frontWheelInertia = vehicle.double("front_wheel_inertia"),
                    rearWheelInertia = vehicle.double("rear_wheel_inertia"),
                    frontGripCoefficient = vehicle.double("front_grip_coefficient"),
                    rearGripCoefficient = vehicle.double("rear_grip_coefficient"),
                    frontRollingResistance0 = vehicle.double("front_rolling_resistance_0"),
                    rearRollingResistance0 = vehicle.double("rear_rolling_resistance_0"),
                    frontRollingResistance1 = vehicle.double("front_rolling_resistance_1"),
                    rearRollingResistance1 = vehicle.double("rear_rolling_resistance_1"),
                    brakeMaximumTorque = vehicle.double("brake_max_torque"),
                    brakeFrontShare = vehicle.double("brake_front_share"),
                    aeroSurfaces = vehicle.objectList("aero_surfaces").map { surface ->
                        AssettoAeroSurfaceSpec(
                            chord = surface.double("chord"),
                            span = surface.double("span"),
                            angleDegrees = surface.double("angle_degrees"),
                            liftGain = surface.double("lift_gain"),
                            dragGain = surface.double("drag_gain"),
                            liftCurve = surface.curve("lift_curve"),
                            dragCurve = surface.curve("drag_curve"),
                            controllerSpeedCurve = surface.curve("controller_speed_curve"),
                        )
                    },
                    airDensityKgM3 = vehicle.double("air_density_kg_m3"),
                ),
            ),
        ).also { require(it.profileId.isNotBlank()) }
    }

    private fun readValue(reader: JsonReader): Any? = when (reader.peek()) {
        JsonToken.BEGIN_OBJECT -> buildMap<String, Any?> {
            reader.beginObject()
            while (reader.hasNext()) put(reader.nextName(), readValue(reader))
            reader.endObject()
        }
        JsonToken.BEGIN_ARRAY -> buildList {
            reader.beginArray()
            while (reader.hasNext()) add(readValue(reader))
            reader.endArray()
        }
        JsonToken.STRING -> reader.nextString()
        JsonToken.NUMBER -> reader.nextDouble()
        JsonToken.BOOLEAN -> reader.nextBoolean()
        JsonToken.NULL -> reader.nextNull().let { null }
        else -> error("Unexpected JSON token ${reader.peek()}")
    }
}

private fun Any?.asObject(): Map<String, Any?> {
    val values = this as? Map<*, *> ?: error("Expected JSON object")
    return values.entries.associate { entry ->
        val key = entry.key as? String ?: error("Expected JSON object key")
        key to entry.value
    }
}
private fun Map<String, Any?>.objectValue(key: String): Map<String, Any?> = getValue(key).asObject()
private fun Map<String, Any?>.objectList(key: String): List<Map<String, Any?>> =
    (getValue(key) as? List<*>)?.map(Any?::asObject) ?: error("Expected $key array")
private fun Map<String, Any?>.string(key: String): String = getValue(key) as? String ?: error("Expected $key string")
private fun Map<String, Any?>.double(key: String): Double = (getValue(key) as? Number)?.toDouble() ?: error("Expected $key number")
private fun Map<String, Any?>.int(key: String): Int = double(key).toInt()
private fun Map<String, Any?>.boolean(key: String): Boolean = getValue(key) as? Boolean ?: error("Expected $key boolean")
private fun Map<String, Any?>.numberList(key: String): List<Double> =
    (getValue(key) as? List<*>)?.map { (it as Number).toDouble() } ?: error("Expected $key number array")
private fun Map<String, Any?>.curve(key: String): List<AssettoCurvePoint> =
    (getValue(key) as? List<*>)?.map { row ->
        val values = row as? List<*> ?: error("Expected $key curve row")
        require(values.size == 2) { "Expected $key curve pair" }
        AssettoCurvePoint((values[0] as Number).toDouble(), (values[1] as Number).toDouble())
    } ?: error("Expected $key curve")
private fun Map<String, Any?>.vector(key: String): AssettoVector3 {
    val values = numberList(key)
    require(values.size == 3) { "Expected $key vector" }
    return AssettoVector3(values[0], values[1], values[2])
}

internal fun interpolateAssettoCurve(points: List<AssettoCurvePoint>, x: Double): Double {
    require(points.isNotEmpty()) { "Assetto curve has no points" }
    if (x <= points.first().x) return points.first().y
    if (x >= points.last().x) return points.last().y

    // Curves are evaluated hundreds of times per second. Locate the first point at or above x
    // with a lower-bound search instead of scanning every authored point for each simulation step.
    var low = 1
    var high = points.lastIndex
    while (low < high) {
        val middle = (low + high) ushr 1
        if (points[middle].x < x) low = middle + 1 else high = middle
    }
    val previous = points[low - 1]
    val next = points[low]
    val span = next.x - previous.x
    return if (span <= 0.0) next.y else previous.y + (next.y - previous.y) * ((x - previous.x) / span)
}
