package com.gabrielpc.enginesoundsimulator.audio

import com.gabrielpc.enginesoundsimulator.catalog.CarEngineMetadata
import com.gabrielpc.enginesoundsimulator.catalog.CarGearboxMetadata
import com.gabrielpc.enginesoundsimulator.catalog.OfficialCarQuirks

internal data class AuthoredAlternateGearOption(
    val label: String,
    val ratio: Double,
)

internal data class AuthoredAlternateGearSet(
    val sourceFile: String,
    val sourceSha256: String,
    val options: List<AuthoredAlternateGearOption>,
)

/** Hybrid provenance is retained, but never fed into the separately calibrated Seal EV model. */
internal data class AuthoredHybridMetadata(
    val sourceFile: String,
    val sourceSha256: String,
    val maximumEnergyKjPerLap: Double,
    val dischargeTimeMs: Double,
    val hasButtonOverride: Boolean,
    val defaultController: Double,
    val heatTorquePercent: Double,
    val hasFrontMotors: Boolean,
    val frontDischargeTimeMs: Double,
    val controllerFiles: List<Pair<String, String>>,
)

internal enum class QuirkExecutionSite(val diagnosticName: String) {
    RUNTIME_AUDIO("runtime_audio"),
    COMPILER_CAPTURE("compiler_capture"),
    EXCLUDED_SEAL_PHYSICS("excluded_seal_physics"),
    METADATA_ONLY("metadata_only"),
}

internal data class AuthoredQuirkPolicy(
    val id: String,
    val executionSite: QuirkExecutionSite,
    val detail: String,
)

/**
 * Exact non-PCM metadata accompanying one selected AC car.
 *
 * [defaultFinalDrive] is retained as authored provenance. The dashboard intentionally does not
 * feed it into Seal road-speed physics: its presentation gearbox preserves the default forward
 * ratio spacing and derives a separate final drive so top gear meets the configured Seal top
 * speed. Alternate `.rto` files are option pools, not a recorded AC setup selection, so they are
 * exposed here without silently selecting a combination.
 */
internal data class AuthoredCarMetadata(
    val defaultForwardRatios: List<Double> = emptyList(),
    val defaultFinalDrive: Double? = null,
    val alternateGearSets: List<AuthoredAlternateGearSet> = emptyList(),
    val hybrid: AuthoredHybridMetadata? = null,
    val quirkPolicies: List<AuthoredQuirkPolicy> = emptyList(),
) {
    val alternateOptionCount: Int = alternateGearSets.sumOf { it.options.size }
    val alternateSourceFiles: String = alternateGearSets.joinToString(",") { it.sourceFile }
        .ifBlank { "none" }
    val alternateGearDiagnostic: String = alternateGearSets.joinToString(";") { set ->
        buildString {
            append(set.sourceFile).append('@').append(set.sourceSha256).append('{')
            set.options.forEachIndexed { index, option ->
                if (index > 0) append(',')
                append(option.label).append('=').append(option.ratio)
            }
            append('}')
        }
    }.ifBlank { "none" }
    val defaultForwardRatiosDiagnostic: String = defaultForwardRatios.joinToString(",")
        .ifBlank { "none" }
    val hybridDiagnostic: String = hybrid?.let { metadata ->
        buildString {
            append("metadata_only:file=").append(metadata.sourceFile)
            append('@').append(metadata.sourceSha256)
            append(":energy_kj=").append(metadata.maximumEnergyKjPerLap)
            append(":discharge_ms=").append(metadata.dischargeTimeMs)
            append(":button_override=").append(metadata.hasButtonOverride)
            append(":default_controller=").append(metadata.defaultController)
            append(":heat_torque_pct=").append(metadata.heatTorquePercent)
            append(":front_motors=").append(metadata.hasFrontMotors)
            append(":front_discharge_ms=").append(metadata.frontDischargeTimeMs)
            append(":controllers=")
            metadata.controllerFiles.forEachIndexed { index, controller ->
                if (index > 0) append(',')
                append(controller.first).append('@').append(controller.second)
            }
        }
    } ?: "none"
    val quirkDiagnostic: String = quirkPolicies.joinToString(",") { policy ->
        "${policy.id}:${policy.executionSite.diagnosticName}"
    }.ifBlank { "none" }

    companion object {
        val EMPTY = AuthoredCarMetadata()
    }
}

internal fun authoredCarMetadata(
    carId: String,
    engine: CarEngineMetadata,
    gearbox: CarGearboxMetadata,
    turboControllerBank: TurboControllerBankSpec?,
): AuthoredCarMetadata {
    val alternateSets = gearbox.alternateGearSets.map { set ->
        AuthoredAlternateGearSet(
            sourceFile = set.file,
            sourceSha256 = set.sha256,
            options = set.options.map { option ->
                AuthoredAlternateGearOption(option.label, option.ratio)
            },
        )
    }
    val hybrid = engine.hybridConfig?.let { metadata ->
        AuthoredHybridMetadata(
            sourceFile = metadata.file,
            sourceSha256 = metadata.sha256,
            maximumEnergyKjPerLap = metadata.maximumEnergyKjPerLap,
            dischargeTimeMs = metadata.dischargeTimeMs,
            hasButtonOverride = metadata.hasButtonOverride,
            defaultController = metadata.defaultController,
            heatTorquePercent = metadata.heatTorquePercent,
            hasFrontMotors = metadata.hasFrontMotors,
            frontDischargeTimeMs = metadata.frontDischargeTimeMs,
            controllerFiles = metadata.controllerFiles.map { it.file to it.sha256 },
        )
    }
    val quirks = OfficialCarQuirks.expectedFor(carId, engine, gearbox).sorted().map { quirk ->
        when (quirk) {
            OfficialCarQuirks.ALL_WHEEL_DRIVE -> AuthoredQuirkPolicy(
                quirk,
                QuirkExecutionSite.EXCLUDED_SEAL_PHYSICS,
                "AC traction metadata cannot replace the calibrated Seal Performance axle model",
            )
            OfficialCarQuirks.HYBRID -> AuthoredQuirkPolicy(
                quirk,
                QuirkExecutionSite.EXCLUDED_SEAL_PHYSICS,
                "ERS/KERS provenance is retained; deployment cannot alter Seal motion or PCM without authored tracks",
            )
            OfficialCarQuirks.GEAR_DEPENDENT_TURBO -> {
                val hasCompletePhysicalModel = engine.turboCount > 0 &&
                    engine.turboPhysics.turbos.size == engine.turboCount
                val complete = hasCompletePhysicalModel ||
                    turboControllerBank?.hasCompleteAudioCoverage == true
                AuthoredQuirkPolicy(
                    quirk,
                    if (complete) QuirkExecutionSite.RUNTIME_AUDIO else QuirkExecutionSite.METADATA_ONLY,
                    if (hasCompletePhysicalModel) {
                        "ctrl_turbo programs replace mapped wastegates inside the authored physical boost/BOV model"
                    } else if (complete) {
                        "legacy ctrl_turbo programs modulate turbo/spool/BOV tracks"
                    } else {
                        "partial controller files cannot normalize missing turbo physics"
                    },
                )
            }
            OfficialCarQuirks.BMW_M3_E30_GRA_ADDITIONAL_DSP -> AuthoredQuirkPolicy(
                quirk,
                QuirkExecutionSite.COMPILER_CAPTURE,
                "FMOD Gain compatibility is transparent at authored 0 dB/non-inverted state and is baked into PCM",
            )
            OfficialCarQuirks.AUTHORED_BOV_LANE_SILENT -> AuthoredQuirkPolicy(
                quirk,
                QuirkExecutionSite.COMPILER_CAPTURE,
                "compiler omits the inaudible BOV lane; runtime must not synthesize it",
            )
            else -> error("Unsupported official quirk $quirk")
        }
    }
    return AuthoredCarMetadata(
        defaultForwardRatios = gearbox.forwardRatios.toList(),
        defaultFinalDrive = gearbox.finalDrive,
        alternateGearSets = alternateSets,
        hybrid = hybrid,
        quirkPolicies = quirks,
    )
}
