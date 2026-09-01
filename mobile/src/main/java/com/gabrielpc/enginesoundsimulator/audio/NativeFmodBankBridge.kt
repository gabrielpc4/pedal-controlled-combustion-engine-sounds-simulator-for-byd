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
        source: Int,
    ): String?

    /** Returns a human-readable error or null after synchronously applying the control frame. */
    external fun update(
        rpm: Float,
        throttle: Float,
        masterGain: Float,
        loadGain: Float,
        coastGain: Float,
        transmissionGain: Float,
        turboGain: Float,
        limiterGain: Float,
        shiftGain: Float,
        overrunGain: Float,
        boost: Float,
        bovDecay: Float,
        shiftSerial: Long,
        shiftDirection: Int,
        triggerOverrun: Boolean,
    ): String?

    external fun close()

    external fun activeEventNames(): Array<String>
}
