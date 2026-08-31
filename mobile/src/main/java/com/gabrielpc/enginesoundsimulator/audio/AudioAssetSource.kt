package com.gabrielpc.enginesoundsimulator.audio

import android.content.Context
import android.content.res.AssetManager
import java.io.InputStream

internal fun interface AudioAssetSource {
    fun open(assetPath: String): InputStream
}

internal class MissingAudioPackException(message: String) : IllegalStateException(message)

internal class BundledAudioAssetSource(private val assets: AssetManager) : AudioAssetSource {
    override fun open(assetPath: String): InputStream = assets.open(assetPath, AssetManager.ACCESS_STREAMING)
}

/** Routes only the selected profile directory externally; shared effects stay bundled. */
internal class ProfileAudioAssetSource(
    private val profilePrefix: String,
    private val profileSource: AudioAssetSource,
    private val bundledSource: AudioAssetSource,
) : AudioAssetSource {
    override fun open(assetPath: String): InputStream = if (assetPath.startsWith(profilePrefix)) {
        profileSource.open(assetPath)
    } else {
        bundledSource.open(assetPath)
    }
}

/** Resolves a profile once, before its render loop, so the audio thread performs no pack lookup. */
internal class EngineAudioAssetResolver(context: Context) {
    private val appContext = context.applicationContext
    private val bundledSource = BundledAudioAssetSource(appContext.assets)
    private val packStore = BydAudioPackStore(appContext.filesDir)

    fun sourceFor(profile: EngineSampleProfile): AudioAssetSource {
        if (profile.audioPackRequirement == null) return bundledSource
        val installed = installedPackFor(profile)
        val missing = profile.requiredExternalAssetPaths() - installed.manifest.filesByPath.keys
        if (missing.isNotEmpty()) {
            throw MissingAudioPackException("Installed ${installed.manifest.packId} is missing ${missing.first()}.")
        }
        val profilePrefix = "sample_engine/${profile.assetDirectory}/"

        return ProfileAudioAssetSource(
            profilePrefix = profilePrefix,
            profileSource = AudioAssetSource(installed::open),
            bundledSource = bundledSource,
        )
    }

    fun sharedEffectsSource(): AudioAssetSource = bundledSource

    fun atlasFilesFor(profile: EngineSampleProfile): AtlasShardFileResolver {
        val atlas = requireNotNull(profile.atlasProgram) { "Profile is not backed by an atlas" }
        val installed = installedPackFor(profile)
        atlas.shards.forEach { shard ->
            val path = "sample_engine/${profile.assetDirectory}/${shard.name}"
            val member = installed.manifest.filesByPath[path] ?: throw MissingAudioPackException(
                "Installed ${installed.manifest.packId} is missing $path.",
            )
            require(member.sha256 == shard.sha256) { "Atlas shard hash differs from the runtime catalog: $path" }
            require(member.sizeBytes == shard.bytes) { "Atlas shard size differs from the runtime catalog: $path" }
            require(member.sampleRate == 48_000 && member.channels == 2) {
                "Atlas shard format differs from PCM16/48k/stereo: $path"
            }
        }

        return AtlasShardFileResolver { shardName ->
            require(atlas.requiredShardNames.contains(shardName)) { "Atlas requested an undeclared shard" }
            installed.file("sample_engine/${profile.assetDirectory}/$shardName")
        }
    }

    private fun installedPackFor(profile: EngineSampleProfile): InstalledBydAudioPack {
        val requirement = requireNotNull(profile.audioPackRequirement) { "Profile does not require an audio pack" }
        return packStore.find(requirement) ?: throw MissingAudioPackException(
            "Install ${requirement.packId} v${requirement.packVersion} for ${profile.displayName}.",
        )
    }
}
