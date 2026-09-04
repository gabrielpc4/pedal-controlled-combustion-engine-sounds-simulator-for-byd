package com.gabrielpc.enginesoundsimulator.audio

/** Thin JNI boundary. All calls are serialized by [EngineAudioEngine]'s control worker. */
internal class NativeFmodBankBridge {
    init {
        System.loadLibrary("fmod")
        System.loadLibrary("fmodstudio")
        System.loadLibrary("byd_fmod_bank_bridge")
    }

    /** Returns a human-readable error or null after the bank and allowed events are ready. */
    external fun open(
        commonStringsBankPath: String,
        commonBankPath: String,
        carBankPath: String,
        alfaBackfireDirectory: String,
        perspective: Int,
        hasTurbo: Boolean,
        idleRpm: Float,
        spatial: FloatArray,
        diagnosticsEnabled: Boolean,
    ): String?

    /** Returns a human-readable error or null after synchronously applying the control frame. */
    external fun update(
        dt: Float,
        rpm: Float,
        drivetrainSpeed: Float,
        throttle: Float,
        perspective: Int,
        boost: Float,
        boostAbsolute: Float,
        bov: Float,
        bovDecay: Float,
        gear: Int,
        isShifting: Boolean,
        shiftProgress: Float,
        shiftSerial: Long,
        limiterPulseCount: Int,
        shiftStartedCount: Int,
        shiftDirection: Int,
        shiftRejectedCount: Int,
        backfirePulseCount: Int,
        backfireSampleIndex: Int,
        tractionActive: Boolean,
        tractionPulseCount: Int,
        simulationFrameId: Long,
    ): String?

    /** Immutable source rows captured from FMOD's actual event/channel hierarchy. */
    external fun voiceSnapshots(): Array<String>

    /** Debug-only data is retained natively and drained at snapshot cadence, never via Logcat. */
    external fun diagnosticRecords(): Array<String>

    /** Returns every bank event discovered before the runtime's playable-event filter. */
    external fun eventCatalog(): Array<String>

    external fun setDiagnosticsEnabled(enabled: Boolean)

    external fun setHostGains(engine: Float, effects: Float)

    external fun setCategoryGains(transmission: Float, gearShift: Float, turbo: Float, backfire: Float)

    external fun setBackfireOnly(enabled: Boolean)

    external fun setBackfireAudioEnabled(enabled: Boolean)

    /** Bit mask for the four Alfa bank-derived one-shot sources (bit 0 = source 1). */
    external fun setBackfireAllowedSamples(mask: Int)

    external fun setEventOverrides(mutedEvents: Array<String>, soloEvents: Array<String>)

    external fun close()
}
