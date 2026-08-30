package com.gabrielpc.enginesoundsimulator.audio

/** Reusable raw snapshot consumed by the 400 Hz FMOD control worker. */
class EngineAudioFrame(
    rpm: Double = FmodCarProfiles.default.idleRpm,
    /** Physical BYD/simulated pedal position; deliberately not combustion-smoothed. */
    throttle: Double = 0.0,
    /** AC-native signed driven-axle angular speed in rad/s; raw telemetry when available. */
    drivetrainSpeed: Double = 0.0,
    enabled: Boolean = false,
    /** App, car, tuning, and ignition/shutdown gain folded into one linear value. */
    masterGain: Double = 1.0,
    shiftSerial: Long = 0L,
    /** -1 down, 0 none, +1 up. */
    shiftDirection: Int = 0,
    /** True while the source simulation owns a cosmetic gear transition. */
    isShifting: Boolean = false,
    /** Source RPM destination sampled when the current cosmetic shift began. */
    shiftTargetRpm: Double = rpm,
    limiterActive: Boolean = false,
    /** Audition option: hold engine/transmission events at full authored load, as the desktop lab does. */
    loadOnlyEnabled: Boolean = false,
    /** Audition option: override only the engine event's throttle parameter. */
    coastOnlyEnabled: Boolean = false,
    eventMixSettings: FmodEventMixSettings = FmodEventMixSettings.DEFAULT,
) {
    var rpm: Double = rpm
        private set
    var throttle: Double = throttle
        private set
    var drivetrainSpeed: Double = drivetrainSpeed
        private set
    var enabled: Boolean = enabled
        private set
    var masterGain: Double = masterGain
        private set
    var shiftSerial: Long = shiftSerial
        private set
    var shiftDirection: Int = shiftDirection
        private set
    var isShifting: Boolean = isShifting
        private set
    var shiftTargetRpm: Double = shiftTargetRpm
        private set
    var limiterActive: Boolean = limiterActive
        private set
    var loadOnlyEnabled: Boolean = loadOnlyEnabled
        private set
    var coastOnlyEnabled: Boolean = coastOnlyEnabled
        private set
    var eventMixSettings: FmodEventMixSettings = eventMixSettings
        private set

    internal fun overwrite(other: EngineAudioFrame) = overwrite(
        rpm = other.rpm,
        throttle = other.throttle,
        drivetrainSpeed = other.drivetrainSpeed,
        enabled = other.enabled,
        masterGain = other.masterGain,
        shiftSerial = other.shiftSerial,
        shiftDirection = other.shiftDirection,
        isShifting = other.isShifting,
        shiftTargetRpm = other.shiftTargetRpm,
        limiterActive = other.limiterActive,
        loadOnlyEnabled = other.loadOnlyEnabled,
        coastOnlyEnabled = other.coastOnlyEnabled,
        eventMixSettings = other.eventMixSettings,
    )

    internal fun overwrite(
        rpm: Double,
        throttle: Double,
        drivetrainSpeed: Double,
        enabled: Boolean,
        masterGain: Double,
        shiftSerial: Long,
        shiftDirection: Int,
        isShifting: Boolean,
        shiftTargetRpm: Double,
        limiterActive: Boolean,
        loadOnlyEnabled: Boolean,
        coastOnlyEnabled: Boolean,
        eventMixSettings: FmodEventMixSettings,
    ) {
        this.rpm = rpm
        this.throttle = throttle
        this.drivetrainSpeed = drivetrainSpeed
        this.enabled = enabled
        this.masterGain = masterGain
        this.shiftSerial = shiftSerial
        this.shiftDirection = shiftDirection
        this.isShifting = isShifting
        this.shiftTargetRpm = shiftTargetRpm
        this.limiterActive = limiterActive
        this.loadOnlyEnabled = loadOnlyEnabled
        this.coastOnlyEnabled = coastOnlyEnabled
        this.eventMixSettings = eventMixSettings
    }
}
