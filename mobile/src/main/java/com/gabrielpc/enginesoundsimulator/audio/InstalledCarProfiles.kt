package com.gabrielpc.enginesoundsimulator.audio

/**
 * Profiles whose WAV layers are supplied by the companion installer. Their
 * source folders remain local authoring inputs; neither banks nor WAVs live in
 * the dashboard APK.
 */
private val exteriorFallsBackToInterior = setOf(
    // Their original engine_ext routes emit all-zero PCM in the FMOD capture
    // at every tested listener position. Keep the exterior toggle audible
    // until a source-side external program can be recovered.
    "aston-martin-dbrs9-gt3",
    "chevrolet-corvette-c6-z06-stanced",
    "chevrolet-corvette-c7-stingray-hellspec",
)

internal val installedCarProfiles = listOf(
    genericInstalledProfile("alfa-romeo-4c", "Alfa Romeo 4C"),
    genericInstalledProfile("aston-martin-dbrs9-gt3", "Aston Martin DBS"),
    genericInstalledProfile("audi-r8-lms-gt2", "Audi R8"),
    genericInstalledProfile("audi-tt-cup-2015", "Audi TT"),
    genericInstalledProfile("bmw-m8-gtlm", "BMW M8 Competition"),
    genericInstalledProfile("bugatti-chiron-pur-sport", "Bugatti Chiron"),
    genericInstalledProfile("cadillac-escalade-esv", "Cadillac Escalade"),
    genericInstalledProfile("chevrolet-camaro-concept", "Chevrolet Camaro"),
    genericInstalledProfile("chevrolet-corvette-c6-z06-stanced", "Chevrolet Corvete C6 ZO6"),
    genericInstalledProfile("chevrolet-corvette-c7-stingray-hellspec", "Chevrolet Corvette Singray"),
    genericInstalledProfile("ferrari-360-challenge-stradale", "Ferrari 360"),
    genericInstalledProfile("ferrari-458-italia-tune", "Ferrari 458 Italia"),
    genericInstalledProfile("ferrari-458_italia-gte-ferruccio", "Ferrari 458 Spider"),
    genericInstalledProfile("ferrari-488-gte-evo-michelotto", "Ferrari 488 Pista"),
    genericInstalledProfile("ferrari-f1-2000", "Ferrari F1 2000", maximumRpm = 16_000.0),
    genericInstalledProfile("ferrari-f430-gt2-2007", "Ferrari 430"),
    genericInstalledProfile("ferrari-laferrari-trio", "Ferrari LaFerrari", maximumRpm = 9_500.0),
    genericInstalledProfile("ferrari-sf90-xx-stradale-2024", "Ferrari SF90 Stradale", maximumRpm = 9_500.0),
    genericInstalledProfile("lexus-lfa", "Lexus LFA", maximumRpm = 10_000.0),
    lamborghiniAventadorSvProfile().copy(
        id = "lexus-lfa-concept-gt500",
        audioPackId = "lamborghini_aventador_sv_cabin",
        displayName = "Lexus LFA Concept GT500",
        previewAssetName = "car_previews/lexus-lfa-concept-gt500.jpg",
    ),
    genericInstalledProfile("lexus-lfa-no-hesi-spec", "Lexus LFA No Hesi Spec", maximumRpm = 10_000.0),
    genericInstalledProfile("lexus-lfa-nurburgring-edition", "Lexus LFA Nurburgring Edition", maximumRpm = 10_000.0),
    genericInstalledProfile("mercedes-amg-project-one-hypercar", "Mercedes-AMG Project One Hypercar", maximumRpm = 11_000.0),
    genericInstalledProfile("mercedes-benz-amg-gt3-evo-2020-sprint", "Mercedes-Benz AMG GT3 EVO 2020"),
    genericInstalledProfile("mitsubishi-eclipse-gsx-r", "Mitsubishi Eclipse"),
    genericInstalledProfile("mitsubishi-lancer-evolution-viii-gsr", "Mitsubishi Lance Evolution VII"),
    genericInstalledProfile("nissan-350z", "Nissan 350z"),
    genericInstalledProfile(
        id = "nissan-370z-widebody",
        displayName = "Nissan 370Z Widebody",
        audioPackId = "nissan-350z",
        assetDirectory = "nissan-350z",
    ),
    genericInstalledProfile("nissan-gt-r-nismo-godzilla", "Nissan GT-R NISMO Godzilla"),
    genericInstalledProfile("porsche-911-992-turbo-s-pdk", "Porsche 911 Turbo S PDK"),
    genericInstalledProfile("porsche-911-gt3-rs-hellspec", "Porsche 911 GT3 RS", maximumRpm = 9_500.0),
    genericInstalledProfile("porsche-911-turbo-s", "Porsche 911 Turbo S"),
    genericInstalledProfile("porsche-carrera-gt-rs", "Porsche Carrera GT", maximumRpm = 9_000.0),
    genericInstalledProfile("toyota-supra-wangan", "Toyota Supra"),
)

private fun genericInstalledProfile(
    id: String,
    displayName: String,
    maximumRpm: Double = 8_500.0,
    audioPackId: String = id,
    assetDirectory: String = id,
): EngineSampleProfile {
    val idleRpm = 900.0
    val loadRoots = listOf(maximumRpm * 0.28, maximumRpm * 0.52, maximumRpm * 0.78)
    val load = loadRoots.mapIndexed { index, rpm -> RootedSample("load_${index + 1}.wav", rpm) }
    val coast = loadRoots.mapIndexed { index, rpm -> RootedSample("coast_${index + 1}.wav", rpm) }
    val fullLoad = AutomationCurve(listOf(CurvePoint(0.0, 0.0), CurvePoint(1.0, 0.0)))
    val fullCoast = AutomationCurve(listOf(CurvePoint(0.0, 0.0), CurvePoint(1.0, 0.0)))

    val cabin = bandProfile(
        id = id,
        displayName = displayName,
        assetDirectory = assetDirectory,
        preview = "car_previews/$id.jpg",
        outputSampleRate = 48_000,
        idleRpm = idleRpm,
        maximumRpm = maximumRpm,
        redlineRpm = maximumRpm * 0.92,
        limiterRpm = maximumRpm * 0.96,
        upshiftRpm = maximumRpm * 0.90,
        gears = listOf(3.20, 2.15, 1.56, 1.24, 1.02, 0.84),
        upshiftSeconds = 0.09,
        downshiftSeconds = 0.16,
        idle = RootedSample("idle.wav", idleRpm),
        load = load,
        coast = coast,
        bandGainDb = -6.0,
        loadThrottleCurve = fullLoad,
        coastThrottleCurve = fullCoast,
    )
    val exterior = if (id in exteriorFallsBackToInterior) {
        cabin.cabinProgram
    } else {
        genericProgram("ext_", idleRpm, maximumRpm, fullLoad, fullCoast)
    }
    return cabin.copy(
        audioPackId = audioPackId,
        exteriorProgram = exterior,
    )
}

private fun genericProgram(
    prefix: String,
    idleRpm: Double,
    maximumRpm: Double,
    loadCurve: AutomationCurve,
    coastCurve: AutomationCurve,
): EngineSampleProgram {
    val roots = listOf(maximumRpm * 0.28, maximumRpm * 0.52, maximumRpm * 0.78)
    val layers = buildList {
        add(
            SampleLayerSpec(
                id = "${prefix}idle",
                assetName = "${prefix}idle.wav",
                role = SampleLayerRole.IDLE,
                startRpm = 0.0,
                endRpm = idleRpm * 2.2,
                autopitchRootRpm = idleRpm,
                baseGainDb = -8.0,
                applyIdleGainBoost = false,
                rpmAmplitudeCurves = listOf(
                    AutomationCurve(listOf(CurvePoint(idleRpm, 1.0), CurvePoint(idleRpm * 2.2, 0.0))),
                ),
            ),
        )
        listOf(SampleLayerRole.LOAD to loadCurve, SampleLayerRole.COAST to coastCurve).forEach { (role, curve) ->
            roots.forEachIndexed { index, root ->
                val left = if (index == 0) idleRpm else (roots[index - 1] + root) / 2.0
                val right = if (index == roots.lastIndex) maximumRpm else (root + roots[index + 1]) / 2.0
                val fade = (right - left) * 0.50
                val name = if (role == SampleLayerRole.LOAD) "load" else "coast"
                add(
                    SampleLayerSpec(
                        id = "${prefix}${name}_${index + 1}",
                        assetName = "${prefix}${name}_${index + 1}.wav",
                        role = role,
                        startRpm = (left - fade).coerceAtLeast(0.0),
                        endRpm = (right + fade).coerceAtMost(maximumRpm),
                        autopitchRootRpm = root,
                        baseGainDb = -7.0,
                        applyIdleGainBoost = false,
                        throttleGainDb = curve,
                        rpmAmplitudeCurves = listOf(
                            AutomationCurve(listOf(CurvePoint((left - fade).coerceAtLeast(0.0), 0.0), CurvePoint(left, 1.0))),
                            AutomationCurve(listOf(CurvePoint(right, 1.0), CurvePoint((right + fade).coerceAtMost(maximumRpm), 0.0))),
                        ),
                    ),
                )
            }
        }
    }
    return EngineSampleProgram(layers = layers, supportsLoadOnlyProgram = true)
}

/**
 * Vehicle figures authored with each local car package (`ui/ui_car.json`).
 * Torque is converted from the supplied Nm figure to rounded kgfm because the
 * dashboard's compact subtitle uses kgfm.  Unknown authoring values remain
 * visibly unavailable instead of inventing a road-car statistic for a mod.
 */
internal val installedCarSpecifications = mapOf(
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
    "ferrari-458_italia-gte-ferruccio" to CarSpecifications("465", "46", "—", "1,245", "—"),
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
