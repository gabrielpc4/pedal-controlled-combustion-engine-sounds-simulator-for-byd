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
    ): String?

    /** Returns a human-readable error or null after synchronously applying the control frame. */
    external fun update(
        dt: Float,
        rpm: Float,
        drivetrainSpeed: Float,
        throttle: Float,
        perspective: Int,
        boost: Float,
        bov: Float,
        bovDecay: Float,
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

    external fun setEventMute(eventName: String, muted: Boolean)
    external fun setEventSolo(eventName: String, solo: Boolean)

    external fun close()
}
