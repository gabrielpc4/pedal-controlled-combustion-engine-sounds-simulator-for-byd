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
        limiterPulse: Boolean,
        shiftStarted: Boolean,
        shiftDirection: Int,
        shiftRejected: Boolean,
        backfireTriggered: Boolean,
        tractionActive: Boolean,
        tractionPulse: Boolean,
    ): String?

    /** Immutable source rows captured from FMOD's actual event/channel hierarchy. */
    external fun voiceSnapshots(): Array<String>

    external fun setHostGains(engine: Float, effects: Float)

    external fun setCategoryGains(transmission: Float, gearShift: Float, turbo: Float)

    external fun setEventMute(eventName: String, muted: Boolean)
    external fun setEventSolo(eventName: String, solo: Boolean)

    external fun close()
}
