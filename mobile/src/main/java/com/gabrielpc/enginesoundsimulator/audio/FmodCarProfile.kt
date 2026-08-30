package com.gabrielpc.enginesoundsimulator.audio

data class FmodEventDefinition(
    val path: String,
    val guid: String,
    /** Game-controlled parameters only; FMOD automatic parameters are intentionally omitted. */
    val parameters: Set<String>,
)

enum class FmodAudioCapability {
    ENGINE,
    TURBO,
    LIMITER,
    SHIFTS,
    BACKFIRE,
    TRANSMISSION,
    ENGINE_START,
    ENGINE_SHUTDOWN,
}

enum class FmodCapabilityDelivery {
    DEDICATED_EVENT,
    EMBEDDED_IN_ENGINE,
}

/** Describes where a sound is authored, independently of its semantic capability name. */
data class FmodCapabilityRoute(
    val eventKind: FmodEventKind,
    val delivery: FmodCapabilityDelivery,
)

/** Assetto turbo dynamics used only to drive FMOD parameters, never EV axle torque. */
data class FmodTurboBehavior(
    val referenceRpm: Double,
    val gamma: Double,
    val lagUp: Double,
    val lagDown: Double,
    val maximumBoost: Double,
    val wastegateBoost: Double,
    val normalizedBoostCap: Double,
    val bovThreshold: Double,
    /** Some banks declare the parameter but contain no controller that consumes it. */
    val bovAudible: Boolean,
    /** Some banks declare the parameter but contain no controller that consumes it. */
    val bovDecayAudible: Boolean,
)

/** Assetto trigger gate plus the EV-friendly release threshold used by the app. */
data class FmodBackfireBehavior(
    val armThrottle: Double,
    val fireThrottle: Double,
    val minimumRpm: Double,
    val maximumRpm: Double,
    val debounceSeconds: Double = 1.0,
    val exactZeroRelease: Boolean = false,
)

/** Immutable bank, authored-behavior, and tach metadata for one FMOD-backed car. */
data class FmodCarProfile(
    val id: String,
    val displayName: String,
    val previewAssetName: String,
    val stringsBankAssetName: String,
    val commonBankAssetName: String,
    val carBankAssetName: String,
    val carBankGuid: String,
    val carBankSha256: String,
    val idleRpm: Double,
    val maximumRpm: Double,
    val redlineRpm: Double,
    val limiterRpm: Double,
    val limiterHz: Double,
    val upshiftRpm: Double,
    val gearRatios: List<Double>,
    val upshiftDurationSeconds: Double,
    val downshiftDurationSeconds: Double,
    val turbo: FmodTurboBehavior?,
    val backfire: FmodBackfireBehavior?,
    /** Radius used by the original car's FMOD drivetrain_speed controller. */
    val drivenWheelRadiusMeters: Double,
    /** Native AC drivetrain angular-velocity parameter range, in signed rad/s. */
    val transmissionSpeedMaximumRadPerSecond: Double?,
    /** True when shift/backfire/limiter material is folded into engine_int rather than separable. */
    val engineEventIsMonolithic: Boolean = false,
    /** Only separately audible events that native code is allowed to instantiate. */
    val events: Map<FmodEventKind, FmodEventDefinition>,
    /** Includes embedded sounds that deliberately have no separate event-level control. */
    val capabilityRoutes: Map<FmodAudioCapability, FmodCapabilityRoute>,
) {
    val gearCount: Int get() = gearRatios.size

    fun supports(kind: FmodEventKind): Boolean = events.containsKey(kind)

    fun route(capability: FmodAudioCapability): FmodCapabilityRoute? = capabilityRoutes[capability]

    /** True when the persistent engine event contains the bank's authored ignition sequence. */
    val hasEmbeddedEngineStart: Boolean
        get() = route(FmodAudioCapability.ENGINE_START)?.let { route ->
            route.delivery == FmodCapabilityDelivery.EMBEDDED_IN_ENGINE &&
                route.eventKind == FmodEventKind.ENGINE &&
                supports(route.eventKind)
        } == true
}

object FmodCarProfiles {
    const val SKYLINE_R34_ID = "nissan_skyline_r34_cabin"
    const val HURACAN_TROFEO_EVO2_ID = "lamborghini_huracan_trofeo_evo2_cabin"
    const val AVENTADOR_SV_ID = "lamborghini_aventador_sv_cabin"
    const val ALFA_ROMEO_4C_ID = "ks_alfa_romeo_4c"
    const val TOYOTA_SUPRA_MK4_ID = "zesty_toyota_supra_mk4_shuto_street"

    private val engineRoute = FmodCapabilityRoute(
        FmodEventKind.ENGINE,
        FmodCapabilityDelivery.DEDICATED_EVENT,
    )

    private fun dedicated(kind: FmodEventKind) = FmodCapabilityRoute(
        kind,
        FmodCapabilityDelivery.DEDICATED_EVENT,
    )

    private fun embeddedInEngine() = FmodCapabilityRoute(
        FmodEventKind.ENGINE,
        FmodCapabilityDelivery.EMBEDDED_IN_ENGINE,
    )

    val skylineR34 = FmodCarProfile(
        id = SKYLINE_R34_ID,
        displayName = "Nissan Skyline GT-R R34",
        previewAssetName = "car_previews/nissan_skyline_r34.jpg",
        stringsBankAssetName = "fmod/common.strings.bank",
        commonBankAssetName = "fmod/common.bank",
        carBankAssetName = "fmod/ks_nissan_skyline_r34.bank",
        carBankGuid = "ce941cbe-fe23-4184-acd1-67f43f609cbf",
        carBankSha256 = "a50ba96017868f37c50804350ea7a159b1f13ef347af95aca28dd1b8743bbc93",
        idleRpm = 800.0,
        maximumRpm = 8_500.0,
        redlineRpm = 8_000.0,
        limiterRpm = 8_000.0,
        limiterHz = 50.0,
        upshiftRpm = 7_900.0,
        gearRatios = listOf(3.827, 2.360, 1.685, 1.312, 1.000, 0.793),
        // Deliberately preserve the app's established cosmetic timings.
        upshiftDurationSeconds = 0.095,
        downshiftDurationSeconds = 0.220,
        turbo = FmodTurboBehavior(
            referenceRpm = 3_400.0,
            gamma = 2.0,
            lagUp = 0.9988,
            lagDown = 0.995,
            maximumBoost = 2.4,
            wastegateBoost = 0.8,
            normalizedBoostCap = 1.0 / 3.0,
            bovThreshold = 0.5,
            bovAudible = false,
            bovDecayAudible = false,
        ),
        backfire = FmodBackfireBehavior(
            armThrottle = 0.8,
            fireThrottle = 0.3,
            minimumRpm = 4_750.0,
            maximumRpm = 12_000.0,
        ),
        drivenWheelRadiusMeters = 0.3266,
        transmissionSpeedMaximumRadPerSecond = null,
        events = mapOf(
            FmodEventKind.ENGINE to event(
                "event:/cars/ks_nissan_skyline_r34/engine_int",
                "4dc2bcfa-509f-4cec-90b5-f18f39940f65",
                "rpms", "throttle",
            ),
            FmodEventKind.TURBO to event(
                "event:/cars/ks_nissan_skyline_r34/turbo",
                "591bbaac-7e8b-4e46-99a0-d0f9ec9e6568",
                "boost", "bov", "bov_decay",
            ),
            FmodEventKind.LIMITER to event(
                "event:/cars/ks_nissan_skyline_r34/limiter",
                "bd8ea933-9e48-4c25-af24-749d3778e285",
                "decay",
            ),
            FmodEventKind.SHIFTS to event(
                "event:/cars/ks_nissan_skyline_r34/gear_int",
                "54e83e8c-2365-4978-b40f-5c79ac2a3e5e",
                "state",
            ),
            FmodEventKind.BACKFIRE to event(
                "event:/cars/ks_nissan_skyline_r34/backfire_int",
                "b8e2dd29-06b4-4f10-a5a5-7e90a5bdb571",
                "throttle",
            ),
        ),
        capabilityRoutes = mapOf(
            FmodAudioCapability.ENGINE to engineRoute,
            FmodAudioCapability.TURBO to dedicated(FmodEventKind.TURBO),
            FmodAudioCapability.LIMITER to dedicated(FmodEventKind.LIMITER),
            FmodAudioCapability.SHIFTS to dedicated(FmodEventKind.SHIFTS),
            FmodAudioCapability.BACKFIRE to dedicated(FmodEventKind.BACKFIRE),
        ),
    )

    val huracanTrofeoEvo2 = FmodCarProfile(
        id = HURACAN_TROFEO_EVO2_ID,
        displayName = "Lamborghini Huracan Trofeo EVO2",
        previewAssetName = "car_previews/huracan_trofeo_evo2.jpg",
        stringsBankAssetName = "fmod/common.strings.bank",
        commonBankAssetName = "fmod/common.bank",
        carBankAssetName = "fmod/fx_lamborghini_huracan_trofeo_evo2.bank",
        carBankGuid = "40e767d1-1f6e-4f72-b010-3925a72569c6",
        carBankSha256 = "74f5053dfcae0529027b37da993ece36d2ff3d26102af8370bfe6589d8f2479c",
        idleRpm = 1_040.0,
        maximumRpm = 9_000.0,
        redlineRpm = 8_200.0,
        limiterRpm = 8_350.0,
        limiterHz = 40.0,
        upshiftRpm = 8_200.0,
        gearRatios = listOf(3.75, 2.38, 1.72, 1.34, 1.11, 0.96, 0.84),
        upshiftDurationSeconds = 0.060,
        downshiftDurationSeconds = 0.150,
        // The bank contains a reusable turbo graph, but this naturally aspirated car has no TURBO section.
        turbo = null,
        backfire = FmodBackfireBehavior(
            armThrottle = 0.8,
            fireThrottle = 0.3,
            minimumRpm = 3_500.0,
            maximumRpm = 15_000.0,
        ),
        drivenWheelRadiusMeters = 0.3425,
        transmissionSpeedMaximumRadPerSecond = 260.0,
        events = mapOf(
            FmodEventKind.ENGINE to event(
                "event:/cars/fx_lamborghini_huracan_trofeo_evo2/engine_int",
                "752bc95e-9da1-49bc-8214-80681a78da6c",
                "rpms", "throttle",
            ),
            FmodEventKind.SHIFTS to event(
                "event:/cars/fx_lamborghini_huracan_trofeo_evo2/gear_int",
                "5205eb2b-0fca-45f0-b49f-d7868d84bb3c",
                "state",
            ),
            // backfire_int is empty; the exterior event is the sole authored backfire source.
            FmodEventKind.BACKFIRE to event(
                "event:/cars/fx_lamborghini_huracan_trofeo_evo2/backfire_ext",
                "c643a4fe-64a0-4954-885d-6d341f66fcad",
                "throttle",
            ),
            FmodEventKind.TRANSMISSION to event(
                "event:/cars/fx_lamborghini_huracan_trofeo_evo2/transmission",
                "fb26c601-b8e7-4df1-bffe-8b01dac57a81",
                "drivetrain_speed", "throttle",
            ),
        ),
        capabilityRoutes = mapOf(
            FmodAudioCapability.ENGINE to engineRoute,
            FmodAudioCapability.LIMITER to embeddedInEngine(),
            FmodAudioCapability.SHIFTS to dedicated(FmodEventKind.SHIFTS),
            FmodAudioCapability.BACKFIRE to dedicated(FmodEventKind.BACKFIRE),
            FmodAudioCapability.TRANSMISSION to dedicated(FmodEventKind.TRANSMISSION),
        ),
    )

    val aventadorSv = FmodCarProfile(
        id = AVENTADOR_SV_ID,
        displayName = "Lamborghini Aventador SV",
        previewAssetName = "car_previews/aventador_sv.jpg",
        stringsBankAssetName = "fmod/common.strings.bank",
        commonBankAssetName = "fmod/common.bank",
        carBankAssetName = "fmod/tr_lamborghini_aventador_sv.bank",
        carBankGuid = "513e17ef-cf00-4135-827f-4b29a30af327",
        carBankSha256 = "b83116900c41666fedf7b7256793d3d8808930a40ab938f1b089efd13bf63e42",
        idleRpm = 850.0,
        maximumRpm = 9_000.0,
        redlineRpm = 8_400.0,
        limiterRpm = 8_500.0,
        limiterHz = 15.0,
        upshiftRpm = 8_400.0,
        gearRatios = listOf(3.91, 2.44, 1.81, 1.46, 1.18, 0.97, 0.84),
        upshiftDurationSeconds = 0.080,
        downshiftDurationSeconds = 0.260,
        turbo = null,
        backfire = FmodBackfireBehavior(
            armThrottle = 0.8,
            fireThrottle = 0.28,
            minimumRpm = 3_800.0,
            maximumRpm = 12_000.0,
        ),
        drivenWheelRadiusMeters = 0.342975,
        transmissionSpeedMaximumRadPerSecond = 350.0,
        engineEventIsMonolithic = true,
        events = mapOf(
            FmodEventKind.ENGINE to event(
                "event:/cars/tr_lamborghini_aventador_sv/engine_int",
                "6cb6a0ee-9c84-410a-ba44-27023c861a77",
                "rpms", "throttle",
            ),
            FmodEventKind.TRANSMISSION to event(
                "event:/cars/tr_lamborghini_aventador_sv/transmission",
                "cc2f6139-2e3e-4390-895a-dc5f00abb2ca",
                "drivetrain_speed", "throttle",
            ),
        ),
        capabilityRoutes = mapOf(
            FmodAudioCapability.ENGINE to engineRoute,
            FmodAudioCapability.LIMITER to embeddedInEngine(),
            FmodAudioCapability.SHIFTS to embeddedInEngine(),
            FmodAudioCapability.BACKFIRE to embeddedInEngine(),
            FmodAudioCapability.TRANSMISSION to dedicated(FmodEventKind.TRANSMISSION),
        ),
    )

    val alfaRomeo4c = FmodCarProfile(
        id = ALFA_ROMEO_4C_ID,
        displayName = "Alfa Romeo 4C",
        previewAssetName = "car_previews/alfa_romeo_4c.jpg",
        stringsBankAssetName = "fmod/common.strings.bank",
        commonBankAssetName = "fmod/common.bank",
        carBankAssetName = "fmod/ks_alfa_romeo_4c.bank",
        carBankGuid = "026643c1-7a2f-486c-983e-52b241bf4a19",
        carBankSha256 = "3e2c5d4341afda3131aa6095cdbacc46aa76592fca3b365cae00ae4fe6e3bf76",
        idleRpm = 850.0,
        maximumRpm = 7_700.0,
        redlineRpm = 6_500.0,
        limiterRpm = 6_750.0,
        // Preserve Assetto's 50 Hz authored decay pulse behavior. Monotonic serials keep
        // every pulse observable even when Android delays a control tick.
        limiterHz = 50.0,
        upshiftRpm = 6_300.0,
        gearRatios = listOf(3.9, 2.269, 1.435, 0.978, 0.755, 0.622),
        upshiftDurationSeconds = 0.100,
        downshiftDurationSeconds = 0.200,
        turbo = FmodTurboBehavior(
            referenceRpm = 2_400.0,
            gamma = 2.5,
            lagUp = 0.995,
            lagDown = 0.99,
            maximumBoost = 1.60,
            wastegateBoost = 1.53,
            normalizedBoostCap = 0.95625,
            bovThreshold = 0.5,
            bovAudible = false,
            bovDecayAudible = false,
        ),
        backfire = FmodBackfireBehavior(
            armThrottle = 0.8,
            fireThrottle = 0.0,
            minimumRpm = 6_500.0,
            maximumRpm = 12_000.0,
            exactZeroRelease = true,
        ),
        drivenWheelRadiusMeters = 0.3235,
        transmissionSpeedMaximumRadPerSecond = null,
        events = mapOf(
            FmodEventKind.ENGINE to event(
                "event:/cars/ks_alfa_romeo_4c/engine_int",
                "22821cdc-9832-44ad-98e9-ca3212085353",
                "rpms", "throttle",
            ),
            FmodEventKind.TURBO to event(
                "event:/cars/ks_alfa_romeo_4c/turbo",
                "2abdb44e-4229-4472-9f57-af3f3ef933c4",
                "boost", "bov", "bov_decay",
            ),
            FmodEventKind.LIMITER to event(
                "event:/cars/ks_alfa_romeo_4c/limiter",
                "bdad6001-12d2-4c58-8737-86270a646ae2",
                "decay",
            ),
            FmodEventKind.SHIFTS to event(
                "event:/cars/ks_alfa_romeo_4c/gear_int",
                "5a671ccf-6e08-4f25-af46-a270a2954f33",
                "state",
            ),
            FmodEventKind.BACKFIRE to event(
                "event:/cars/ks_alfa_romeo_4c/backfire_int",
                "278d445d-798a-41dd-ae68-dba7f1d84a57",
                "throttle",
            ),
        ),
        capabilityRoutes = mapOf(
            FmodAudioCapability.ENGINE to engineRoute,
            FmodAudioCapability.TURBO to dedicated(FmodEventKind.TURBO),
            FmodAudioCapability.LIMITER to dedicated(FmodEventKind.LIMITER),
            FmodAudioCapability.SHIFTS to dedicated(FmodEventKind.SHIFTS),
            FmodAudioCapability.BACKFIRE to dedicated(FmodEventKind.BACKFIRE),
        ),
    )

    val toyotaSupraMk4 = FmodCarProfile(
        id = TOYOTA_SUPRA_MK4_ID,
        displayName = "Toyota Supra MK4",
        previewAssetName = "car_previews/toyota_supra_mk4.jpg",
        stringsBankAssetName = "fmod/common.strings.bank",
        commonBankAssetName = "fmod/common.bank",
        carBankAssetName = "fmod/zesty_toyota_supra_mk4_shuto_street.bank",
        carBankGuid = "072e5002-4521-4f3e-88fe-7245f3b304d4",
        carBankSha256 = "64cfba3e153903430d95ec339b81930085708a1f5a74145b01c46d93aa067c0d",
        idleRpm = 980.0,
        maximumRpm = 8_500.0,
        redlineRpm = 8_000.0,
        limiterRpm = 8_000.0,
        limiterHz = 40.0,
        upshiftRpm = 7_950.0,
        gearRatios = listOf(2.5000, 2.0000, 1.5217, 1.2000, 1.0312, 0.8571),
        upshiftDurationSeconds = 0.100,
        downshiftDurationSeconds = 0.150,
        turbo = FmodTurboBehavior(
            referenceRpm = 3_000.0,
            gamma = 4.0,
            lagUp = 0.996,
            lagDown = 0.996,
            maximumBoost = 2.20,
            wastegateBoost = 2.20,
            normalizedBoostCap = 1.0,
            bovThreshold = 0.5,
            bovAudible = true,
            bovDecayAudible = false,
        ),
        backfire = FmodBackfireBehavior(
            armThrottle = 0.8,
            fireThrottle = 0.3,
            minimumRpm = 5_250.0,
            maximumRpm = 12_000.0,
        ),
        drivenWheelRadiusMeters = 0.312,
        transmissionSpeedMaximumRadPerSecond = null,
        events = mapOf(
            FmodEventKind.ENGINE to event(
                "event:/cars/zesty_toyota_supra_mk4_shuto_street/engine_int",
                "0f36224e-063b-448b-b8fb-9eede1ef2095",
                "rpms", "throttle",
            ),
            FmodEventKind.TURBO to event(
                "event:/cars/zesty_toyota_supra_mk4_shuto_street/turbo",
                "b349f7ee-e56d-410f-ab3c-867b68807d71",
                "boost", "bov", "bov_decay",
            ),
            FmodEventKind.SHIFTS to event(
                "event:/cars/zesty_toyota_supra_mk4_shuto_street/gear_int",
                "79053c8e-b974-409f-9d38-bacce8bad70d",
                "state",
            ),
            FmodEventKind.BACKFIRE to event(
                "event:/cars/zesty_toyota_supra_mk4_shuto_street/backfire_int",
                "40c1fa3a-71df-4081-b066-54c2845ee0de",
                "throttle",
            ),
        ),
        capabilityRoutes = mapOf(
            FmodAudioCapability.ENGINE to engineRoute,
            FmodAudioCapability.TURBO to dedicated(FmodEventKind.TURBO),
            FmodAudioCapability.LIMITER to embeddedInEngine(),
            FmodAudioCapability.SHIFTS to dedicated(FmodEventKind.SHIFTS),
            FmodAudioCapability.BACKFIRE to dedicated(FmodEventKind.BACKFIRE),
            FmodAudioCapability.ENGINE_START to embeddedInEngine(),
            FmodAudioCapability.ENGINE_SHUTDOWN to embeddedInEngine(),
        ),
    )

    val default: FmodCarProfile = skylineR34
    val all: List<FmodCarProfile> = listOf(
        skylineR34,
        huracanTrofeoEvo2,
        aventadorSv,
        alfaRomeo4c,
        toyotaSupraMk4,
    )
    val maximumSupportedRpm: Double = all.maxOf(FmodCarProfile::maximumRpm)

    fun findOrNull(id: String?): FmodCarProfile? = all.firstOrNull { it.id == id }

    fun find(id: String?): FmodCarProfile = findOrNull(id) ?: default

    fun indexOf(profile: FmodCarProfile): Int = all.indexOfFirst { it.id == profile.id }.coerceAtLeast(0)

    private fun event(path: String, guid: String, vararg parameters: String) = FmodEventDefinition(
        path = path,
        guid = guid,
        parameters = parameters.toSet(),
    )
}
