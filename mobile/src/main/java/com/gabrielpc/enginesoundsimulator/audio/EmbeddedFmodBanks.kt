package com.gabrielpc.enginesoundsimulator.audio

import android.content.Context
import com.gabrielpc.enginesoundsimulator.simulation.AssettoPhysics
import com.gabrielpc.enginesoundsimulator.simulation.AssettoPhysicsLoader
import java.io.InputStream

/** APK metadata stays separate from the large archives; only selected audio is materialized. */
internal class EmbeddedFmodBanks(context: Context) {
    private val assets = context.assets
    private val store = FmodBankStore(context.noBackupFilesDir.resolve("embedded-audio"))
    private val packIds by lazy { assets.list(ROOT).orEmpty().toSet() }

    fun contains(profile: FmodBankProfile): Boolean =
        profile.bankPackId in packIds &&
            FmodBankProfiles.commonPackId in packIds &&
            FmodBankProfiles.commonStringsPackId in packIds

    fun bankFiles(profile: FmodBankProfile): FmodBankFiles = synchronized(preparationLock) {
        require(contains(profile)) { "The APK does not contain ${profile.displayName}." }
        listOf(
            FmodBankProfiles.originalCarsPackId to FmodBankProfiles.commonStringsPackId,
            FmodBankProfiles.originalCarsPackId to FmodBankProfiles.commonPackId,
            profile.packGroup to profile.bankPackId,
        ).forEach { (group, id) ->
            store.prepareEmbeddedPack(group, id, assets, "$ROOT/$id")
        }

        FmodBankFiles(
            commonStrings = store.sharedBankFile(FmodBankProfiles.commonStringsPackId),
            common = store.sharedBankFile(FmodBankProfiles.commonPackId),
            car = store.bankFile(profile),
            physics = store.physicsFile(profile),
        )
    }

    fun physics(profile: FmodBankProfile): AssettoPhysics =
        AssettoPhysicsLoader.load(assets.open("$ROOT/${profile.bankPackId}/physics.json"))

    fun openPreview(profile: FmodBankProfile): InputStream? =
        runCatching { assets.open("$ROOT/${profile.bankPackId}/preview") }.getOrNull()

    private companion object {
        const val ROOT = "embedded_banks"
        val preparationLock = Any()
    }
}
