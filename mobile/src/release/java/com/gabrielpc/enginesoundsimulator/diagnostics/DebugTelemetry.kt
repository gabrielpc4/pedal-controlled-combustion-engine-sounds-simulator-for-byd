package com.gabrielpc.enginesoundsimulator.diagnostics

import com.gabrielpc.enginesoundsimulator.audio.EngineAudioFrame

/** Release APKs contain no capture storage, receiver, or native diagnostic activation. */
internal object DebugTelemetry {
    fun isCaptureActive(): Boolean = false

    fun performanceEnabled(): Boolean = false

    @Suppress("UnusedParameter")
    fun recordSimulationPerformance(cpuNanos: Long, wallNanos: Long) = Unit

    @Suppress("UnusedParameter")
    fun recordAudioPerformance(
        cpuNanos: Long,
        wallNanos: Long,
        hostGainCalls: Int,
        categoryGainCalls: Int,
        overrideBatchCalls: Int,
        simulationFrameId: Long,
        previousSimulationFrameId: Long,
        limiterPulseCount: Int,
        shiftPulseCount: Int,
        rejectedShiftPulseCount: Int,
        backfirePulseCount: Int,
        tractionPulseCount: Int,
        deadlineMissed: Boolean,
    ) = Unit

    @Suppress("UnusedParameter")
    fun recordMixerSnapshotPerformance(cpuNanos: Long, wallNanos: Long) = Unit

    fun nativeDiagnosticsEnabled(): Boolean = false

    fun scenarioOverride(timestampNanos: Long): DebugScenarioOverride? = null

    fun backfireOnly(): Boolean = false

    @Suppress("LongParameterList", "UnusedParameter")
    fun recordSimulation(
        timestampNanos: Long,
        simulationFrameId: Long,
        profileId: String,
        inputMode: String,
        perspectiveOrdinal: Int,
        rawSpeedKmh: Double,
        presentationSpeedKmh: Double,
        presentationAccelerationKmhPerSecond: Double,
        fmodDrivetrainSpeedKmh: Double,
        rpm: Double,
        gear: Int,
        clutch: Double,
        transmissionPosition: Int,
        throttle: Double,
        brake: Double,
        boost: Double,
        bov: Double,
        bovDecaySeconds: Double,
        isShifting: Boolean,
        shiftProgress: Double,
        shiftSerial: Long,
        shiftDirection: Int,
        limiterPulse: Boolean,
        backfireTriggered: Boolean,
        tractionLimitActive: Boolean,
        tractionLimitPulse: Boolean,
    ) = Unit

    @Suppress("UnusedParameter")
    fun recordAudioConsumption(
        timestampNanos: Long,
        controlTickId: Long,
        previousSimulationFrameId: Long,
        profileId: String,
        frame: EngineAudioFrame,
    ) = Unit

    @Suppress("UnusedParameter")
    fun recordNativeRecords(timestampNanos: Long, profileId: String, rows: Array<String>) = Unit

    @Suppress("UnusedParameter")
    fun recordBankEventCatalog(timestampNanos: Long, profileId: String, rows: Array<String>) = Unit

    @Suppress("UnusedParameter")
    fun recordBankContext(profileId: String, bankSha256: String) = Unit
}
