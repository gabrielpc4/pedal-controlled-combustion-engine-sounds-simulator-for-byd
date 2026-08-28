package com.gabrielpc.enginesoundsimulator.drive

import android.content.ContentResolver
import android.net.Uri
import android.os.Debug
import com.gabrielpc.enginesoundsimulator.AppBuildInfo
import com.gabrielpc.enginesoundsimulator.diagnostics.DebugEventLog
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.io.OutputStreamWriter

/** Builds and writes a bounded JSONL diagnostic export away from the real-time threads. */
internal object DriveRuntimeDiagnostics {
    private const val MAX_RECENT_LOG_CHARACTERS = 384 * 1024

    fun markCrackle(snapshot: DriveSnapshot) {
        DebugEventLog.warning(
            "mark_crackle",
            snapshot.diagnosticSummary(),
        )
    }

    /** Compact, stable logcat payload used by the debug-only ADB control harness. */
    fun conciseSummary(snapshot: DriveSnapshot): String = snapshot.diagnosticSummary()

    fun write(
        contentResolver: ContentResolver,
        destination: Uri,
        snapshot: DriveSnapshot,
    ) {
        val output = requireNotNull(contentResolver.openOutputStream(destination, "wt")) {
            "Unable to open diagnostic export destination"
        }
        output.use { stream -> write(stream, snapshot) }
    }

    /** Debug-acceptance export into app-private storage; production UI continues to use SAF. */
    internal fun write(destination: File, snapshot: DriveSnapshot) {
        require(destination.parentFile?.let { it.isDirectory || it.mkdirs() } == true) {
            "Unable to create diagnostic export directory"
        }
        FileOutputStream(destination, false).use { stream -> write(stream, snapshot) }
    }

    private fun write(output: OutputStream, snapshot: DriveSnapshot) {
        val recentLog = DebugEventLog.readRecentLogText().takeLast(MAX_RECENT_LOG_CHARACTERS)
        BufferedWriter(OutputStreamWriter(output, Charsets.UTF_8)).use { writer ->
            buildJsonLines(snapshot, recentLog, System.currentTimeMillis()).forEach { line ->
                writer.write(line)
                writer.newLine()
            }
        }
    }

    internal fun buildJsonLines(
        snapshot: DriveSnapshot,
        recentLog: String,
        exportedAtMs: Long,
    ): Sequence<String> = sequence {
        val runtime = Runtime.getRuntime()
        yield(
            jsonObject(
                "type" to "export",
                "exported_at_ms" to exportedAtMs,
                "build" to AppBuildInfo.diagnosticTitleSuffix,
                "build_number" to AppBuildInfo.buildNumber,
                "git_sha" to AppBuildInfo.gitSha,
                "built_at_utc" to AppBuildInfo.builtAtUtc,
                "heap_used_bytes" to runtime.totalMemory() - runtime.freeMemory(),
                "heap_max_bytes" to runtime.maxMemory(),
            ),
        )
        yield(snapshotJson(snapshot, exportedAtMs))
        for (line in recentLog.lineSequence()) {
            if (line.isNotBlank() && line != "(no errors or warnings yet)") {
                yield(
                    jsonObject(
                        "type" to "event",
                        "exported_at_ms" to exportedAtMs,
                        "message" to line,
                    ),
                )
            }
        }
    }

    private fun snapshotJson(snapshot: DriveSnapshot, exportedAtMs: Long): String {
        val drivetrain = snapshot.drivetrain
        val audio = snapshot.audio
        val telemetry = snapshot.telemetry
        return jsonObject(
            "type" to "snapshot",
            "exported_at_ms" to exportedAtMs,
            "car_id" to snapshot.selectedCarId,
            "car_name" to snapshot.selectedCarName,
            "core_steps" to snapshot.coreSteps,
            "ui_snapshot_builds" to snapshot.uiSnapshotBuildCount,
            "input" to snapshot.activeInput,
            "transmission" to snapshot.transmissionPosition.name,
            "gear" to drivetrain.gear,
            "rpm" to drivetrain.rpm,
            "speed_kmh" to drivetrain.speedKmh,
            "throttle" to snapshot.throttle,
            "brake" to snapshot.brake,
            "telemetry_reader_state" to telemetry.readerState.name,
            "telemetry_delivery" to telemetry.deliveryMode,
            "telemetry_accelerator" to telemetry.accelerator.value,
            "telemetry_brake" to telemetry.brake.value,
            "telemetry_speed" to telemetry.speed.value,
            "telemetry_gearbox_code" to telemetry.gearboxCode,
            "telemetry_error" to telemetry.lastError,
            "shift_serial" to drivetrain.shiftSerial,
            "shifting" to drivetrain.isShifting,
            "sound_enabled" to snapshot.engineSoundEnabled,
            "selected_family" to audio.packLoadFamily,
            "selected_idle_rpm" to snapshot.tuning.engine.idleRpm,
            "selected_forward_gears" to snapshot.tuning.engine.gearRatios.size,
            "selected_preview_present" to (
                snapshot.selectedCarPreviewAsset.takeIf(String::isNotBlank)
                    ?.let { File(it).isFile } ?: false
                ),
            "audio_running" to audio.running,
            "audio_frames" to audio.sampleFramesRendered,
            "audio_status" to audio.sampleStatus,
            "sample_rate" to audio.sampleRate,
            "frames_per_write" to audio.framesPerWrite,
            "buffer_frames" to audio.bufferFrames,
            "target_buffer_ms" to audio.targetBufferMilliseconds,
            "queued_frames" to audio.queuedFrames,
            "buffer_adjustments" to audio.bufferAdjustmentCount,
            "buffer_resize_failures" to audio.bufferResizeFailures,
            "last_buffer_adjustment" to audio.lastBufferAdjustment,
            "underruns" to audio.underruns,
            "startup_underruns" to audio.startupUnderruns,
            "steady_state_underruns" to audio.steadyStateUnderruns,
            "render_p99_us" to audio.renderP99Micros,
            "render_p99_lower_us" to audio.renderP99LowerMicros,
            "render_max_us" to audio.renderMaxMicros,
            "render_samples" to audio.renderSamples,
            "steady_render_p99_us" to audio.steadyRenderP99Micros,
            "steady_render_p99_lower_us" to audio.steadyRenderP99LowerMicros,
            "steady_render_max_us" to audio.steadyRenderMaxMicros,
            "steady_render_samples" to audio.steadyRenderSamples,
            "transition_render_p99_us" to audio.transitionRenderP99Micros,
            "transition_render_p99_lower_us" to audio.transitionRenderP99LowerMicros,
            "transition_render_max_us" to audio.transitionRenderMaxMicros,
            "transition_render_samples" to audio.transitionRenderSamples,
            "gc_count" to runtimeStat("art.gc.gc-count"),
            "blocking_gc_count" to runtimeStat("art.gc.blocking-gc-count"),
            "gc_time_ms" to runtimeStat("art.gc.gc-time"),
            "bytes_allocated" to runtimeStat("art.gc.bytes-allocated"),
            "focus_granted" to audio.focusGranted,
            "loop_wraps" to audio.sampleLoopWraps,
            "effect_triggers" to audio.sampleEffectTriggers,
            "global_real_voice_budget" to audio.sampleGlobalVoiceBudget,
            "global_logical_voices" to audio.sampleGlobalLogicalVoices,
            "global_real_voices" to audio.sampleGlobalRealVoices,
            "global_virtual_voices" to audio.sampleGlobalVirtualVoices,
            "global_rejected_triggers" to audio.sampleGlobalRejectedTriggers,
            "global_stolen_logical_voices" to audio.sampleGlobalStolenLogicalVoices,
            "turbo_controller_gain" to audio.sampleTurboControllerGain,
            "authored_forward_ratios" to audio.authoredForwardRatios,
            "authored_final_drive" to audio.authoredFinalDrive,
            "alternate_gear_set_count" to audio.alternateGearSetCount,
            "alternate_gear_option_count" to audio.alternateGearOptionCount,
            "alternate_gear_set_files" to audio.alternateGearSetFiles,
            "alternate_gear_variants" to audio.alternateGearVariants,
            "hybrid_metadata" to audio.hybridMetadataStatus,
            "quirk_policies" to audio.authoredQuirkPolicies,
            "peak" to audio.samplePeak,
            "over_range_samples" to audio.sampleOverRangeSamples,
            "decoded_bytes" to audio.sampleDecodedBytes,
            "pack_load_status" to audio.packLoadStatus,
            "pack_load_family" to audio.packLoadFamily,
            "pack_load_car" to audio.packLoadCar,
            "pack_load_error" to audio.packLoadError,
            "audio_error" to audio.error,
            "sample_error" to audio.sampleError,
        )
    }

    private fun DriveSnapshot.diagnosticSummary(): String = buildString {
        append("car=").append(selectedCarId)
        append(" core_steps=").append(coreSteps)
        append(" audio_frames=").append(audio.sampleFramesRendered)
        append(" ui_snapshot_builds=").append(uiSnapshotBuildCount)
        append(" mode=").append(inputMode.name)
        append(" transmission=").append(transmissionPosition.name)
        append(" rpm=").append(drivetrain.rpm.toInt())
        append(" gear=").append(drivetrain.gear)
        append(" speed_kmh=").append("%.1f".format(java.util.Locale.US, drivetrain.speedKmh))
        append(" throttle=").append("%.3f".format(java.util.Locale.US, throttle))
        append(" brake=").append("%.3f".format(java.util.Locale.US, brake))
        append(" sound_enabled=").append(engineSoundEnabled)
        append(" pack_family=").append(audio.packLoadFamily ?: "none")
        append(" pack_car=").append(audio.packLoadCar ?: "none")
        append(" idle_rpm=").append("%.6f".format(java.util.Locale.US, tuning.engine.idleRpm))
        append(" forward_gears=").append(tuning.engine.gearRatios.size)
        append(" preview_present=").append(
            selectedCarPreviewAsset.takeIf(String::isNotBlank)?.let { File(it).isFile } ?: false,
        )
        append(" audio_status=").append(audio.sampleStatus)
        append(" audio_errors=").append(
            if (audio.error == null && audio.sampleError == null && audio.packLoadError == null) {
                "none"
            } else {
                "present"
            },
        )
        append(" sample_rate=").append(audio.sampleRate)
        append(" frames_per_write=").append(audio.framesPerWrite)
        append(" buffer_frames=").append(audio.bufferFrames)
        append(" target_buffer_ms=").append(audio.targetBufferMilliseconds)
        append(" queued_frames=").append(audio.queuedFrames)
        append(" underruns=").append(audio.underruns)
        append(" startup_underruns=").append(audio.startupUnderruns)
        append(" steady_underruns=").append(audio.steadyStateUnderruns)
        append(" resize_failures=").append(audio.bufferResizeFailures)
        append(" loop_wraps=").append(audio.sampleLoopWraps)
        append(" effect_triggers=").append(audio.sampleEffectTriggers)
        append(" global_voices=").append(audio.sampleGlobalLogicalVoices)
        append('/').append(audio.sampleGlobalRealVoices)
        append('/').append(audio.sampleGlobalVirtualVoices)
        append(" global_voice_budget=").append(audio.sampleGlobalVoiceBudget)
        append(" global_rejected=").append(audio.sampleGlobalRejectedTriggers)
        append(" global_stolen=").append(audio.sampleGlobalStolenLogicalVoices)
        append(" turbo_ctrl_gain=").append("%.4f".format(java.util.Locale.US, audio.sampleTurboControllerGain))
        append(" authored_final_drive=").append(audio.authoredFinalDrive ?: "none")
        append(" alternate_gears=").append(audio.alternateGearSetCount)
        append('/').append(audio.alternateGearOptionCount)
        append(" hybrid_metadata=").append(audio.hybridMetadataStatus)
        append(" quirk_policies=").append(audio.authoredQuirkPolicies)
        append(" decoded_bytes=").append(audio.sampleDecodedBytes)
        append(" pack_status=").append(audio.packLoadStatus)
        append(" render_p99_us=").append(audio.renderP99Micros)
        append(" render_p99_lower_us=").append(audio.renderP99LowerMicros)
        append(" render_max_us=").append(audio.renderMaxMicros)
        append(" render_samples=").append(audio.renderSamples)
        append(" steady_render_p99_us=").append(audio.steadyRenderP99Micros)
        append(" steady_render_p99_lower_us=").append(audio.steadyRenderP99LowerMicros)
        append(" steady_render_max_us=").append(audio.steadyRenderMaxMicros)
        append(" steady_render_samples=").append(audio.steadyRenderSamples)
        append(" transition_render_p99_us=").append(audio.transitionRenderP99Micros)
        append(" transition_render_p99_lower_us=").append(audio.transitionRenderP99LowerMicros)
        append(" transition_render_max_us=").append(audio.transitionRenderMaxMicros)
        append(" transition_render_samples=").append(audio.transitionRenderSamples)
        append(" gc_count=").append(runtimeStat("art.gc.gc-count") ?: "unknown")
        append(" blocking_gc_count=").append(runtimeStat("art.gc.blocking-gc-count") ?: "unknown")
        append(" peak=").append("%.4f".format(java.util.Locale.US, audio.samplePeak))
        append(" over_range=").append(audio.sampleOverRangeSamples)
    }

    private fun runtimeStat(name: String): String? = runCatching {
        Debug.getRuntimeStat(name)
    }.getOrNull()

    private fun jsonObject(vararg fields: Pair<String, Any?>): String = buildString {
        append('{')
        fields.forEachIndexed { index, (name, value) ->
            if (index > 0) append(',')
            appendJsonString(name)
            append(':')
            when (value) {
                null -> append("null")
                is Boolean -> append(value)
                is Double -> if (value.isFinite()) append(value) else append("null")
                is Float -> if (value.isFinite()) append(value) else append("null")
                is Number -> append(value)
                else -> appendJsonString(value.toString())
            }
        }
        append('}')
    }

    private fun StringBuilder.appendJsonString(value: String) {
        append('"')
        value.forEach { character ->
            when (character) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (character.code < 0x20) {
                    append("\\u")
                    append(character.code.toString(16).padStart(4, '0'))
                } else {
                    append(character)
                }
            }
        }
        append('"')
    }
}
