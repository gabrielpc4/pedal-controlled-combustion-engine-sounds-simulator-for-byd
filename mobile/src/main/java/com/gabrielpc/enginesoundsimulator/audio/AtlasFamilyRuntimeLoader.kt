package com.gabrielpc.enginesoundsimulator.audio

import android.content.Context
import java.security.MessageDigest

/**
 * Holds at most one verified family runtime. The root catalog deliberately contains only
 * descriptors, so inventory and car selection never retain every full FMOD event graph.
 */
internal class AtlasFamilyRuntimeLoader(
    private val openAsset: (String) -> java.io.InputStream,
    private val descriptors: Map<String, AtlasFamilyRuntimeDescriptor>,
) {
    constructor(context: Context, descriptors: Map<String, AtlasFamilyRuntimeDescriptor>) : this(
        openAsset = { assetName -> context.assets.open(assetName) },
        descriptors = descriptors,
    )
    private var cachedId: String? = null
    private var cachedProgram: FullEventAtlasProgram? = null

    @Synchronized
    fun load(descriptor: AtlasFamilyRuntimeDescriptor): FullEventAtlasProgram {
        require(descriptors[descriptor.id] == descriptor) { "Unknown atlas family ${descriptor.id}" }
        require(descriptor.runtimeBytes in 1..MAXIMUM_RUNTIME_BYTES) {
            "Atlas runtime ${descriptor.id} exceeds the family runtime cap"
        }
        if (cachedId == descriptor.id) return requireNotNull(cachedProgram)

        val bytes = openAsset(descriptor.runtimeAssetName).use { input ->
            input.readBounded(descriptor.runtimeBytes)
        }
        require(bytes.size.toLong() == descriptor.runtimeBytes) {
            "Atlas runtime ${descriptor.id} has an unexpected byte count"
        }
        val actualHash = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
        require(actualHash == descriptor.runtimeSha256) { "Atlas runtime ${descriptor.id} hash differs from its catalog" }
        val parsed = FullEventAtlasParser.parse(AtlasRuntimeJson.parse(bytes))
        require(parsed.id == descriptor.id) { "Atlas runtime id does not match family ${descriptor.id}" }

        // The previous object has no native mappings; renderer shutdown precedes profile switching.
        cachedId = descriptor.id
        cachedProgram = parsed
        return parsed
    }

    private companion object {
        const val MAXIMUM_RUNTIME_BYTES = 4L * 1024L * 1024L
    }
}
