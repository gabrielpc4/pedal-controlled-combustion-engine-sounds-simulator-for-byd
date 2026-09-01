package com.gabrielpc.enginesoundsimulator.audio

/**
 * Additional official Assetto Corsa bank profiles selected because their
 * model/powertrain is a defensible match for one of the supplied new cars.
 * They intentionally use the same generic WAV topology as the pack builder.
 */
internal val assettoBankProfiles = listOf(
    assettoBankProfile("assetto-audi-r8-lms-2016", "Audi R8 LMS 2016", 9_000.0),
    assettoBankProfile("assetto-audi-r8-plus", "Audi R8 Plus", 8_500.0),
    assettoBankProfile("assetto-audi-tt-cup", "Audi TT Cup", 7_500.0),
    assettoBankProfile("assetto-bmw-m4", "BMW M4", 8_500.0),
    assettoBankProfile("assetto-corvette-c7-stingray", "Chevrolet Corvette C7 Stingray", 7_000.0),
    assettoBankProfile("assetto-ferrari-458", "Ferrari 458 Italia", 9_000.0),
    assettoBankProfile("assetto-ferrari-458-gt2", "Ferrari 458 GT2", 9_000.0),
    assettoBankProfile("assetto-ferrari-488-gtb", "Ferrari 488 GTB", 8_500.0),
    assettoBankProfile("assetto-ferrari-488-gt3", "Ferrari 488 GT3", 8_500.0),
    assettoBankProfile("assetto-ferrari-fxx-k", "Ferrari FXX K", 9_000.0),
    assettoBankProfile("assetto-ferrari-laferrari", "Ferrari LaFerrari", 9_250.0),
    assettoBankProfile("assetto-lamborghini-aventador-sv", "Lamborghini Aventador SV", 9_200.0),
    assettoBankProfile("assetto-lamborghini-gallardo-sl", "Lamborghini Gallardo Superleggera", 8_500.0),
    assettoBankProfile("assetto-lamborghini-huracan-performante", "Lamborghini Huracán Performante", 8_500.0),
    assettoBankProfile("assetto-lamborghini-huracan-st", "Lamborghini Huracán ST", 8_500.0),
    assettoBankProfile("assetto-mercedes-amg-gt3", "Mercedes-AMG GT3", 8_500.0),
    assettoBankProfile("assetto-nissan-370z", "Nissan 370Z", 7_500.0),
    assettoBankProfile("assetto-nissan-gtr", "Nissan GT-R", 7_500.0),
    assettoBankProfile("assetto-porsche-911-gt3-rs", "Porsche 911 GT3 RS", 9_500.0),
    assettoBankProfile("assetto-porsche-991-turbo-s", "Porsche 911 Turbo S (991)", 7_500.0),
    assettoBankProfile("assetto-toyota-supra-mkiv", "Toyota Supra Mk IV", 7_500.0),
)

private fun assettoBankProfile(id: String, displayName: String, maximumRpm: Double) =
    genericInstalledProfile(
        id = id,
        displayName = displayName,
        maximumRpm = maximumRpm,
        previewAssetName = "car_previews/$id.jpg",
    )

/** Base-bank figures are not available in the local source package, so the UI stays honest. */
internal val assettoBankSpecifications = emptyMap<String, CarSpecifications>()
