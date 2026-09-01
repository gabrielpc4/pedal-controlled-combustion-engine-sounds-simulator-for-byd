package com.gabrielpc.enginesoundsimulator.audio

/**
 * Declarative vehicle settings for one native FMOD Studio bank.
 *
 * The bank owns the engine layers, processing, randomisation, and source
 * effects. The profile only supplies dashboard and presentation-drivetrain
 * boundaries; it never describes decoded recordings.
 */
internal data class FmodBankProfile(
    val id: String,
    /** Equal to [id] except for profiles proven to have byte-identical banks. */
    val bankPackId: String = id,
    val displayName: String,
    val previewAssetName: String,
    val minimumRpm: Double = 0.0,
    val maximumRpm: Double = 8_500.0,
    val idleRpm: Double = 900.0,
    val redlineRpm: Double = maximumRpm * 0.92,
    val limiterRpm: Double = maximumRpm * 0.96,
    val upshiftRpm: Double = maximumRpm * 0.90,
    val gearRatios: List<Double> = DEFAULT_GEARS,
    val upshiftDurationSeconds: Double = 0.09,
    val downshiftDurationSeconds: Double = 0.16,
    /** A documented source route is silent, so EXTERIOR must stay on engine_int. */
    val exteriorFallsBackToCabin: Boolean = false,
) {
    val hasExteriorProgram: Boolean = !exteriorFallsBackToCabin

    fun resolvedPerspective(requested: EngineSoundPerspective): EngineSoundPerspective =
        if (requested == EngineSoundPerspective.EXTERIOR && hasExteriorProgram) {
            EngineSoundPerspective.EXTERIOR
        } else {
            EngineSoundPerspective.CABIN
        }

    fun supportsPrimaryLayerSource(@Suppress("UNUSED_PARAMETER") source: PrimaryEngineLayerSource): Boolean = true

    fun hasTurboSounds(perspective: EngineSoundPerspective): Boolean =
        capabilitiesFor(resolvedPerspective(perspective)).any(GenericCarEffect::isTurbo)

    fun capabilitiesFor(perspective: EngineSoundPerspective): Set<GenericCarEffect> {
        val generic = genericCarEffectAvailability[bankPackId]
        if (generic != null) {
            return if (perspective == EngineSoundPerspective.EXTERIOR) generic.exterior else generic.cabin
        }
        return specialBankCapabilities[bankPackId].orEmpty()
    }

    companion object {
        val DEFAULT_GEARS = listOf(3.20, 2.15, 1.56, 1.24, 1.02, 0.84)
    }
}

/** Common road-car figures and Brazilian market-price references for the compact car subtitle. */
internal data class CarSpecifications(
    val horsepower: String,
    val torqueKgfm: String,
    val zeroToHundred: String,
    val weight: String,
    val priceBrl: String,
) {
    fun summary(): String = buildList {
        add("$horsepower HP")
        add("$torqueKgfm kgfm")
        add("0–100 $zeroToHundred")
        add("$weight kg")
        if (priceBrl != "—") add("PRICE $priceBrl")
    }.joinToString("  •  ")
}

/**
 * Every selectable vehicle is backed by an installer-provided FMOD bank.
 * Similar names never share a payload unless the source-bank hash was equal.
 */
internal object FmodBankProfiles {
    const val commonStringsPackId = "assetto-common-strings"
    const val commonPackId = "assetto-common"

    val default = profile(
        id = "lamborghini_huracan_trofeo_evo2_cabin",
        displayName = "Lamborghini Huracán Trofeo EVO2",
        preview = "car_previews/lamborghini-huracan-trofeo-evo2.jpg",
        maximumRpm = 10_000.0,
        idleRpm = 1_040.0,
        redlineRpm = 8_200.0,
        limiterRpm = 8_350.0,
        upshiftRpm = 8_200.0,
        gearRatios = listOf(3.75, 2.38, 1.72, 1.34, 1.11, 0.96, 0.84),
        upshiftSeconds = 0.060,
        downshiftSeconds = 0.150,
    )

    val all = listOf(
        default,
        profile(
            id = "lamborghini_aventador_sv_cabin",
            displayName = "Lamborghini Aventador SV",
            preview = "car_previews/lamborghini-aventador-sv.jpg",
            maximumRpm = 9_200.0,
            redlineRpm = 8_400.0,
            limiterRpm = 8_500.0,
            upshiftRpm = 8_400.0,
            gearRatios = listOf(3.91, 2.44, 1.81, 1.46, 1.18, 0.97, 0.84),
            upshiftSeconds = 0.080,
            downshiftSeconds = 0.260,
        ),
        profile(
            id = "nissan_skyline_r34_cabin",
            displayName = "Nissan Skyline GT-R R34",
            preview = "car_previews/nissan-skyline-gt-r34-v-spec.jpg",
            maximumRpm = 8_500.0,
            idleRpm = 950.0,
            redlineRpm = 8_000.0,
            limiterRpm = 8_200.0,
            upshiftRpm = 7_900.0,
            gearRatios = listOf(3.827, 2.360, 1.685, 1.312, 1.000, 0.793),
            upshiftSeconds = 0.095,
            downshiftSeconds = 0.220,
        ),
        profile("alfa-romeo-4c", "Alfa Romeo 4C"),
        profile("aston-martin-dbrs9-gt3", "Aston Martin DBS", exteriorFallsBackToCabin = true),
        profile("audi-r8-lms-gt2", "Audi R8"),
        profile("audi-tt-cup-2015", "Audi TT"),
        profile("bmw-m8-gtlm", "BMW M8 Competition"),
        profile("bugatti-chiron-pur-sport", "Bugatti Chiron"),
        profile("cadillac-escalade-esv", "Cadillac Escalade"),
        profile("chevrolet-camaro-concept", "Chevrolet Camaro"),
        profile("chevrolet-corvette-c6-z06-stanced", "Chevrolet Corvette C6 Z06", exteriorFallsBackToCabin = true),
        profile("chevrolet-corvette-c7-stingray-hellspec", "Chevrolet Corvette C7 Stingray", exteriorFallsBackToCabin = true),
        profile("ferrari-360-challenge-stradale", "Ferrari 360"),
        profile("ferrari-458-italia-tune", "Ferrari 458 Italia"),
        profile("ferrari-458-italia-gte-ferruccio", "Ferrari 458 Spider"),
        profile("ferrari-488-gte-evo-michelotto", "Ferrari 488 Pista"),
        profile("ferrari-f1-2000", "Ferrari F1 2000", maximumRpm = 16_000.0),
        profile("ferrari-f430-gt2-2007", "Ferrari 430"),
        profile("ferrari-laferrari-trio", "Ferrari LaFerrari", maximumRpm = 9_500.0),
        profile("ferrari-sf90-xx-stradale-2024", "Ferrari SF90 Stradale", maximumRpm = 9_500.0),
        profile("lexus-lfa", "Lexus LFA", maximumRpm = 10_000.0),
        profile(
            id = "lexus-lfa-concept-gt500",
            displayName = "Lexus LFA Concept GT500",
            bankPackId = "lamborghini_aventador_sv_cabin",
            maximumRpm = 9_200.0,
        ),
        profile("lexus-lfa-no-hesi-spec", "Lexus LFA No Hesi Spec", maximumRpm = 10_000.0),
        profile("lexus-lfa-nurburgring-edition", "Lexus LFA Nurburgring Edition", maximumRpm = 10_000.0),
        profile("mercedes-amg-project-one-hypercar", "Mercedes-AMG Project One Hypercar", maximumRpm = 11_000.0),
        profile("mercedes-benz-amg-gt3-evo-2020-sprint", "Mercedes-Benz AMG GT3 EVO 2020"),
        profile("mitsubishi-eclipse-gsx-r", "Mitsubishi Eclipse"),
        profile("mitsubishi-lancer-evolution-viii-gsr", "Mitsubishi Lancer Evolution VIII"),
        profile("nissan-350z", "Nissan 350Z"),
        profile(
            id = "nissan-370z-widebody",
            displayName = "Nissan 370Z Widebody",
            bankPackId = "nissan-350z",
        ),
        profile("nissan-gt-r-nismo-godzilla", "Nissan GT-R NISMO Godzilla"),
        profile("porsche-911-992-turbo-s-pdk", "Porsche 911 Turbo S PDK"),
        profile("porsche-911-gt3-rs-hellspec", "Porsche 911 GT3 RS", maximumRpm = 9_500.0),
        profile("porsche-911-turbo-s", "Porsche 911 Turbo S"),
        profile("porsche-carrera-gt-rs", "Porsche Carrera GT", maximumRpm = 9_000.0),
        profile("toyota-supra-wangan", "Toyota Supra"),
    ) + listOf(
        profile("assetto-audi-r8-lms-2016", "Audi R8 LMS 2016", maximumRpm = 9_000.0),
        profile("assetto-audi-r8-plus", "Audi R8 Plus"),
        profile("assetto-audi-tt-cup", "Audi TT Cup", maximumRpm = 7_500.0),
        profile("assetto-bmw-m4", "BMW M4"),
        profile("assetto-corvette-c7-stingray", "Chevrolet Corvette C7 Stingray", maximumRpm = 7_000.0),
        profile("assetto-ferrari-458", "Ferrari 458 Italia", maximumRpm = 9_000.0),
        profile("assetto-ferrari-458-gt2", "Ferrari 458 GT2", maximumRpm = 9_000.0),
        profile("assetto-ferrari-488-gtb", "Ferrari 488 GTB"),
        profile("assetto-ferrari-488-gt3", "Ferrari 488 GT3"),
        profile("assetto-ferrari-fxx-k", "Ferrari FXX K", maximumRpm = 9_000.0),
        profile("assetto-ferrari-laferrari", "Ferrari LaFerrari", maximumRpm = 9_250.0),
        profile("assetto-lamborghini-aventador-sv", "Lamborghini Aventador SV", maximumRpm = 9_200.0),
        profile("assetto-lamborghini-gallardo-sl", "Lamborghini Gallardo Superleggera"),
        profile("assetto-lamborghini-huracan-performante", "Lamborghini Huracán Performante"),
        profile("assetto-lamborghini-huracan-st", "Lamborghini Huracán ST"),
        profile("assetto-mercedes-amg-gt3", "Mercedes-AMG GT3"),
        profile("assetto-nissan-370z", "Nissan 370Z", maximumRpm = 7_500.0),
        profile("assetto-nissan-gtr", "Nissan GT-R", maximumRpm = 7_500.0),
        profile("assetto-porsche-911-gt3-rs", "Porsche 911 GT3 RS", maximumRpm = 9_500.0),
        profile("assetto-porsche-991-turbo-s", "Porsche 911 Turbo S (991)", maximumRpm = 7_500.0),
        profile("assetto-toyota-supra-mkiv", "Toyota Supra Mk IV", maximumRpm = 7_500.0),
    )

    val maximumSupportedRpm = all.maxOf(FmodBankProfile::maximumRpm)

    val requiredPackIds: Set<String> =
        (all.map(FmodBankProfile::bankPackId) + commonStringsPackId + commonPackId).toSet()

    fun find(id: String?): FmodBankProfile = all.firstOrNull { it.id == id } ?: default

    fun adjacent(currentId: String, offset: Int): FmodBankProfile {
        val current = all.indexOfFirst { it.id == currentId }.coerceAtLeast(0)
        return all[(current + offset).mod(all.size)]
    }

    fun specificationsFor(id: String): CarSpecifications = specifications[id] ?: unavailableSpecifications

    private fun profile(
        id: String,
        displayName: String,
        bankPackId: String = id,
        preview: String = "car_previews/$id.jpg",
        maximumRpm: Double = 8_500.0,
        idleRpm: Double = 900.0,
        redlineRpm: Double = maximumRpm * 0.92,
        limiterRpm: Double = maximumRpm * 0.96,
        upshiftRpm: Double = maximumRpm * 0.90,
        gearRatios: List<Double> = FmodBankProfile.DEFAULT_GEARS,
        upshiftSeconds: Double = 0.09,
        downshiftSeconds: Double = 0.16,
        exteriorFallsBackToCabin: Boolean = false,
    ) = FmodBankProfile(
        id = id,
        bankPackId = bankPackId,
        displayName = displayName,
        previewAssetName = preview,
        maximumRpm = maximumRpm,
        idleRpm = idleRpm,
        redlineRpm = redlineRpm,
        limiterRpm = limiterRpm,
        upshiftRpm = upshiftRpm,
        gearRatios = gearRatios,
        upshiftDurationSeconds = upshiftSeconds,
        downshiftDurationSeconds = downshiftSeconds,
        exteriorFallsBackToCabin = exteriorFallsBackToCabin,
    )

    private val unavailableSpecifications = CarSpecifications("—", "—", "—", "—", "—")

    private val specifications = mapOf(
        "lamborghini_huracan_trofeo_evo2_cabin" to CarSpecifications("631", "61", "2.9 s", "1,422", "R$ 3.333.920"),
        "lamborghini_aventador_sv_cabin" to CarSpecifications("730", "70", "2.9 s", "1,575", "R$ 5.200.000"),
        "nissan_skyline_r34_cabin" to CarSpecifications("325", "40", "4.9 s", "1,560", "R$ 1.200.000"),
        "aston-martin-dbrs9-gt3" to CarSpecifications("620", "71", "—", "1,370", "—"),
        "audi-r8-lms-gt2" to CarSpecifications("569", "58", "—", "1,350", "—"),
        "audi-tt-cup-2015" to CarSpecifications("310", "42", "5.0 s", "1,125", "—"),
        "bmw-m8-gtlm" to CarSpecifications("500+", "66+", "—", "1,250", "—"),
        "bugatti-chiron-pur-sport" to CarSpecifications("1,500", "163", "2.3 s", "1,995", "—"),
        "cadillac-escalade-esv" to CarSpecifications("403", "58", "6.8 s", "2,235", "—"),
        "chevrolet-camaro-concept" to CarSpecifications("400", "56", "5.4 s", "1,535", "—"),
        "chevrolet-corvette-c6-z06-stanced" to CarSpecifications("505", "65", "4.2 s", "1,420", "—"),
        "chevrolet-corvette-c7-stingray-hellspec" to CarSpecifications("739", "103", "3.8 s", "1,496", "—"),
        "ferrari-360-challenge-stradale" to CarSpecifications("419", "38", "4.0 s", "1,280", "—"),
        "ferrari-458-italia-tune" to CarSpecifications("574", "55", "—", "1,140", "—"),
        "ferrari-458-italia-gte-ferruccio" to CarSpecifications("465", "46", "—", "1,245", "—"),
        "ferrari-488-gte-evo-michelotto" to CarSpecifications("583", "82", "—", "1,270", "—"),
        "ferrari-f1-2000" to CarSpecifications("764", "36", "2.6 s", "535", "—"),
        "ferrari-f430-gt2-2007" to CarSpecifications("550", "50", "—", "1,100", "—"),
        "ferrari-laferrari-trio" to CarSpecifications("805", "89", "<3.0 s", "1,480", "—"),
        "ferrari-sf90-xx-stradale-2024" to CarSpecifications("1,030", "82", "2.2 s", "1,560", "—"),
        "lexus-lfa" to CarSpecifications("552", "49", "3.7 s", "1,480", "—"),
        "lexus-lfa-concept-gt500" to CarSpecifications("507", "46", "—", "1,025", "—"),
        "lexus-lfa-no-hesi-spec" to CarSpecifications("858", "75", "—", "1,562", "—"),
        "lexus-lfa-nurburgring-edition" to CarSpecifications("552", "49", "3.7 s", "1,562", "—"),
        "mercedes-amg-project-one-hypercar" to CarSpecifications("760", "—", "—", "1,300", "—"),
        "mercedes-benz-amg-gt3-evo-2020-sprint" to CarSpecifications("520", "61", "—", "1,265", "—"),
        "mitsubishi-eclipse-gsx-r" to CarSpecifications("510", "60", "—", "1,100", "—"),
        "mitsubishi-lancer-evolution-viii-gsr" to CarSpecifications("305", "39", "5.9 s", "1,400", "—"),
        "nissan-350z" to CarSpecifications("301", "42", "—", "1,510", "—"),
        "nissan-370z-widebody" to CarSpecifications("1,017", "123", "—", "1,500", "—"),
        "nissan-gt-r-nismo-godzilla" to CarSpecifications("831", "101", "2.1 s", "1,700", "—"),
        "porsche-911-992-turbo-s-pdk" to CarSpecifications("968", "121", "2.4 s", "1,640", "—"),
        "porsche-911-gt3-rs-hellspec" to CarSpecifications("701", "66", "3.1 s", "1,395", "—"),
        "porsche-911-turbo-s" to CarSpecifications("640", "77", "2.7 s", "1,850", "—"),
        "porsche-carrera-gt-rs" to CarSpecifications("681", "65", "<3.5 s", "1,340", "—"),
        "toyota-supra-wangan" to CarSpecifications("648", "71", "—", "1,432", "—"),
    )
}

private val specialBankCapabilities = mapOf(
    "nissan_skyline_r34_cabin" to setOf(
        GenericCarEffect.SHIFT_UP,
        GenericCarEffect.SHIFT_DOWN,
        GenericCarEffect.TURBO_LOOP,
        GenericCarEffect.TURBO_DUMP,
        GenericCarEffect.OVERRUN,
    ),
    "lamborghini_huracan_trofeo_evo2_cabin" to setOf(
        GenericCarEffect.TRANSMISSION,
        GenericCarEffect.SHIFT_UP,
        GenericCarEffect.SHIFT_DOWN,
        GenericCarEffect.LIMITER,
        GenericCarEffect.OVERRUN,
    ),
    "lamborghini_aventador_sv_cabin" to setOf(
        GenericCarEffect.TRANSMISSION,
        GenericCarEffect.SHIFT_UP,
        GenericCarEffect.SHIFT_DOWN,
        GenericCarEffect.OVERRUN,
    ),
)

private fun GenericCarEffect.isTurbo(): Boolean =
    this == GenericCarEffect.TURBO_LOOP || this == GenericCarEffect.TURBO_DUMP
