package com.gabrielpc.enginesoundsimulator.audio

/** A selectable car is metadata only; all engine and gearbox values come from its physics pack. */
internal data class FmodBankProfile(
    val id: String,
    val displayName: String,
    val previewAssetName: String,
    val bankPackId: String = id,
    val packGroup: String = FmodBankProfiles.originalCarsPackId,
)

/** The first release deliberately exposes only the 22 first-party Assetto Corsa cars. */
internal object FmodBankProfiles {
    const val originalCarsPackId = "original_cars_pack"
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
        profile("assetto-porsche-911-gt3-rs", "Porsche 911 GT3 RS"),
        profile("assetto-porsche-991-turbo-s", "Porsche 911 Turbo S (991)"),
        profile("assetto-toyota-supra-mkiv", "Toyota Supra Mk IV"),
    )

    fun find(id: String?): FmodBankProfile = all.firstOrNull { it.id == id } ?: default

    fun adjacent(currentId: String, offset: Int): FmodBankProfile {
        val current = all.indexOfFirst { it.id == currentId }.coerceAtLeast(0)
        return all[(current + offset).mod(all.size)]
    }

    private fun profile(id: String, displayName: String): FmodBankProfile = FmodBankProfile(
        id = id,
        displayName = displayName,
        previewAssetName = "car_previews/$id.jpg",
    )
}
