package com.gabrielpc.enginesoundsimulator.telemetry

import com.gabrielpc.enginesoundsimulator.drive.InputMode
import com.gabrielpc.enginesoundsimulator.simulation.TransmissionPosition
import java.lang.reflect.Modifier
import kotlin.math.roundToInt

internal data class BydGearboxConstants(
    val park: Int,
    val reverse: Int,
    val neutral: Int,
    val drive: Int,
    val sport: Int?,
    val manual: Int?,
) {
    fun toTransmissionPosition(raw: Int): TransmissionPosition? {
        return when (raw) {
            park -> TransmissionPosition.PARK
            neutral -> TransmissionPosition.NEUTRAL
            drive -> TransmissionPosition.DRIVE
            reverse -> TransmissionPosition.NEUTRAL
            sport, manual -> TransmissionPosition.DRIVE
            else -> null
        }
    }

    companion object {
        fun fallback(): BydGearboxConstants = BydGearboxConstants(
            park = 1,
            reverse = 2,
            neutral = 3,
            drive = 4,
            sport = 5,
            manual = 6,
        )

        fun discover(deviceClass: Class<*>): BydGearboxConstants {
            val fallback = fallback()
            val intsBySuffix = deviceClass.fields
                .filter { field ->
                    Modifier.isStatic(field.modifiers) &&
                        field.type == Int::class.javaPrimitiveType
                }
                .associate { field ->
                    field.name.uppercase() to runCatching { field.getInt(null) }.getOrNull()
                }

            fun pick(vararg tokens: String, default: Int): Int {
                val match = intsBySuffix.entries.firstOrNull { (name, value) ->
                    value != null && tokens.all { token -> name.contains(token) }
                }?.value
                return match ?: default
            }

            return BydGearboxConstants(
                park = pick("AUTO", "MODE", "P", default = fallback.park),
                reverse = pick("AUTO", "MODE", "R", default = fallback.reverse),
                neutral = pick("AUTO", "MODE", "N", default = fallback.neutral),
                drive = pick("AUTO", "MODE", "D", default = fallback.drive),
                sport = intsBySuffix.entries.firstOrNull { (name, _) ->
                    name.contains("AUTO") && name.contains("MODE") && name.endsWith("_S")
                }?.value ?: fallback.sport,
                manual = intsBySuffix.entries.firstOrNull { (name, _) ->
                    name.contains("AUTO") && name.contains("MODE") && name.endsWith("_M")
                }?.value ?: fallback.manual,
            )
        }
    }
}

internal fun gearboxCodeToTransmissionPosition(code: String?): TransmissionPosition? {
    return when (code?.trim()?.uppercase()) {
        "P" -> TransmissionPosition.PARK
        "N" -> TransmissionPosition.NEUTRAL
        "D" -> TransmissionPosition.DRIVE
        "R", "S", "M" -> TransmissionPosition.NEUTRAL
        else -> null
    }
}

internal fun TelemetrySnapshot.resolvedTransmissionPosition(
    constants: BydGearboxConstants = BydGearboxConstants.fallback(),
): TransmissionPosition? {
    gearboxCodeToTransmissionPosition(gearboxCode)?.let { return it }

    val raw = gearboxAutoMode.value?.roundToInt()
        ?: gearboxAutoMode.raw?.roundToInt()
        ?: return null

    return constants.toTransmissionPosition(raw)
        ?: BydGearboxConstants.fallback().toTransmissionPosition(raw)
}

internal fun TelemetrySnapshot.gearboxSignalAvailable(): Boolean =
    resolvedTransmissionPosition() != null

internal fun TelemetrySnapshot.transmissionFollowsVehicle(mode: InputMode): Boolean {
    return mode == InputMode.RealPedals && gearboxSignalAvailable()
}

internal data class ResolvedTransmissionControl(
    val position: TransmissionPosition,
    val lockedToVehicle: Boolean,
    val lastVehiclePosition: TransmissionPosition?,
    val syncManualPosition: Boolean,
)

/**
 * In REAL pedal mode, the BYD gearbox is the baseline. The driver may override P/N/D in the app
 * until the physical lever moves; any real-world change resynchronizes the manual override too.
 */
internal fun resolveTransmissionControl(
    mode: InputMode,
    telemetry: TelemetrySnapshot,
    manualPosition: TransmissionPosition,
    lastVehiclePosition: TransmissionPosition?,
): ResolvedTransmissionControl {
    if (!telemetry.transmissionFollowsVehicle(mode)) {
        return ResolvedTransmissionControl(
            position = manualPosition,
            lockedToVehicle = false,
            lastVehiclePosition = null,
            syncManualPosition = false,
        )
    }

    val vehiclePosition = telemetry.resolvedTransmissionPosition()
        ?: return ResolvedTransmissionControl(
            position = manualPosition,
            lockedToVehicle = false,
            lastVehiclePosition = lastVehiclePosition,
            syncManualPosition = false,
        )

    if (lastVehiclePosition == null) {
        return ResolvedTransmissionControl(
            position = vehiclePosition,
            lockedToVehicle = true,
            lastVehiclePosition = vehiclePosition,
            syncManualPosition = true,
        )
    }

    if (vehiclePosition != lastVehiclePosition) {
        return ResolvedTransmissionControl(
            position = vehiclePosition,
            lockedToVehicle = true,
            lastVehiclePosition = vehiclePosition,
            syncManualPosition = true,
        )
    }

    return ResolvedTransmissionControl(
        position = manualPosition,
        lockedToVehicle = true,
        lastVehiclePosition = lastVehiclePosition,
        syncManualPosition = false,
    )
}
