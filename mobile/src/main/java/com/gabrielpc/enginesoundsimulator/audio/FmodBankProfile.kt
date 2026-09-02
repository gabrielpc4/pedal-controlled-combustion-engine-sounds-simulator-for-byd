package com.gabrielpc.enginesoundsimulator.audio

/** A selectable car is metadata only; all engine and gearbox values come from its physics pack. */
internal data class FmodBankProfile(
    val id: String,
    val displayName: String,
    val previewAssetName: String,
    val bankPackId: String = id,
    val packGroup: String = FmodBankProfiles.originalCarsPackId,
)

/** Catalog metadata for both installable bank groups. Runtime selection filters to installed packs. */
internal object FmodBankProfiles {
    const val originalCarsPackId = "original_cars_pack"
    const val moddedCarsPackId = "modded_car_packs"
    const val commonStringsPackId = "assetto-common-strings"
    const val commonPackId = "assetto-common"

    val default = profile("alfa-romeo-4c", "Alfa Romeo 4C")

    val all: List<FmodBankProfile> = listOf(
        default,
        profile("assetto-audi-r8-lms-2016", "Audi R8 LMS 2016"),
        profile("assetto-audi-r8-plus", "Audi R8 Plus"),
        profile("assetto-audi-tt-cup", "Audi TT Cup"),
        profile("assetto-bmw-m4", "BMW M4"),
        profile("assetto-corvette-c7-stingray", "Chevrolet Corvette C7 Stingray"),
        profile("assetto-ferrari-458", "Ferrari 458 Italia"),
        profile("assetto-ferrari-458-gt2", "Ferrari 458 GT2"),
        profile("assetto-ferrari-488-gtb", "Ferrari 488 GTB"),
        profile("assetto-ferrari-488-gt3", "Ferrari 488 GT3"),
        profile("assetto-ferrari-fxx-k", "Ferrari FXX K"),
        profile("assetto-ferrari-laferrari", "Ferrari LaFerrari"),
        profile("assetto-lamborghini-aventador-sv", "Lamborghini Aventador SV"),
        profile("assetto-lamborghini-gallardo-sl", "Lamborghini Gallardo Superleggera"),
        profile("assetto-lamborghini-huracan-performante", "Lamborghini Huracán Performante"),
        profile("assetto-lamborghini-huracan-st", "Lamborghini Huracán ST"),
        profile("assetto-mercedes-amg-gt3", "Mercedes-AMG GT3"),
        profile("assetto-nissan-370z", "Nissan 370Z"),
        profile("assetto-nissan-gtr", "Nissan GT-R"),
        profile("assetto-nissan-skyline-r34", "Nissan Skyline GT-R R34"),
        profile("assetto-porsche-911-gt3-rs", "Porsche 911 GT3 RS"),
        profile("assetto-porsche-991-turbo-s", "Porsche 911 Turbo S (991)"),
        profile("assetto-toyota-supra-mkiv", "Toyota Supra Mk IV"),
        profile("modded-aston-martin-dbrs9-gt3", "Aston Martin DBS", moddedCarsPackId),
        profile("modded-audi-r8-lms-gt2", "Audi R8", moddedCarsPackId),
        profile("modded-audi-tt-cup-2015", "Audi TT", moddedCarsPackId),
        profile("modded-bmw-m8-gtlm", "BMW M8 Competition", moddedCarsPackId),
        profile("modded-bugatti-chiron-pur-sport", "Bugatti Chiron", moddedCarsPackId),
        profile("modded-cadillac-escalade-esv", "Cadillac Escalade", moddedCarsPackId),
        profile("modded-chevrolet-camaro-concept", "Chevrolet Camaro", moddedCarsPackId),
        profile("modded-chevrolet-corvette-c6-z06-stanced", "Chevrolet Corvette C6 ZO6", moddedCarsPackId),
        profile("modded-chevrolet-corvette-c7-stingray-hellspec", "Chevrolet Corvette Stingray", moddedCarsPackId),
        profile("modded-ferrari-360-challenge-stradale", "Ferrari 360", moddedCarsPackId),
        profile("modded-ferrari-458-italia-gte-ferruccio", "Ferrari 458 Spider", moddedCarsPackId),
        profile("modded-ferrari-458-italia-tune", "Ferrari 458 Italia Tune", moddedCarsPackId),
        profile("modded-ferrari-488-gte-evo-michelotto", "Ferrari 488 Pista", moddedCarsPackId),
        profile("modded-ferrari-f1-2000", "Ferrari F1 2000", moddedCarsPackId),
        profile("modded-ferrari-f430-gt2-2007", "Ferrari 430", moddedCarsPackId),
        profile("modded-ferrari-laferrari-trio", "Ferrari LaFerrari Trio", moddedCarsPackId),
        profile("modded-ferrari-sf90-xx-stradale-2024", "Ferrari SF90 Stradale", moddedCarsPackId),
        profile("modded-lexus-lfa", "Lexus LFA", moddedCarsPackId),
        profile("modded-lexus-lfa-concept-gt500", "Lexus LFA Concept GT500", moddedCarsPackId),
        profile("modded-lexus-lfa-no-hesi-spec", "Lexus LFA No Hesi Spec", moddedCarsPackId),
        profile("modded-lexus-lfa-nurburgring-edition", "Lexus LFA Nürburgring Edition", moddedCarsPackId),
        profile("modded-mercedes-amg-project-one-hypercar", "Mercedes-AMG Project One Hypercar", moddedCarsPackId),
        profile("modded-mercedes-benz-amg-gt3-evo-2020-sprint", "Mercedes-Benz AMG GT3 EVO 2020", moddedCarsPackId),
        profile("modded-mitsubishi-eclipse-gsx-r", "Mitsubishi Eclipse", moddedCarsPackId),
        profile("modded-mitsubishi-lancer-evolution-viii-gsr", "Mitsubishi Lancer Evolution VIII", moddedCarsPackId),
        profile("modded-nissan-350z", "Nissan 350Z", moddedCarsPackId),
        profile("modded-nissan-370z-widebody", "Nissan 370Z Widebody", moddedCarsPackId),
        profile("modded-nissan-gt-r-nismo-godzilla", "Nissan GT-R NISMO Godzilla", moddedCarsPackId),
        profile("modded-porsche-911-992-turbo-s-pdk", "Porsche 911 992 Turbo S PDK", moddedCarsPackId),
        profile("modded-porsche-911-gt3-rs-hellspec", "Porsche 911 GT3 RS Hellspec", moddedCarsPackId),
        profile("modded-porsche-911-turbo-s", "Porsche 911 Turbo S", moddedCarsPackId),
        profile("modded-porsche-carrera-gt-rs", "Porsche Carrera GT RS", moddedCarsPackId),
        profile("modded-toyota-supra-wangan", "Toyota Supra Wangan", moddedCarsPackId),
    )

    fun find(id: String?): FmodBankProfile = all.firstOrNull { it.id == id } ?: default

    fun adjacent(currentId: String, offset: Int): FmodBankProfile {
        val current = all.indexOfFirst { it.id == currentId }.coerceAtLeast(0)
        return all[(current + offset).mod(all.size)]
    }

    private fun profile(
        id: String,
        displayName: String,
        packGroup: String = originalCarsPackId,
    ): FmodBankProfile = FmodBankProfile(
        id = id,
        displayName = displayName,
        previewAssetName = "car_previews/$id.jpg",
        packGroup = packGroup,
    )
}
