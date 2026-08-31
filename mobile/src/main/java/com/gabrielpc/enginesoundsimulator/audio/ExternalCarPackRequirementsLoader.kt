package com.gabrielpc.enginesoundsimulator.audio

import android.content.Context
import android.util.JsonReader
import android.util.JsonToken
import java.io.ByteArrayInputStream
import java.io.InputStreamReader
import java.io.Reader

/** Streams only the small root catalog contract; runtime family JSON is intentionally never parsed here. */
internal object ExternalCarPackRequirementsLoader {
    fun load(context: Context): Set<EngineAudioPackRequirement> {
        context.assets.open(ExternalCarCatalogLoader.ASSET_PATH).use { input ->
            val bytes = input.readBounded(MAXIMUM_ROOT_CATALOG_BYTES)
            return parse(InputStreamReader(ByteArrayInputStream(bytes), Charsets.UTF_8))
        }
    }

    internal fun parse(source: Reader): Set<EngineAudioPackRequirement> = JsonReader(source).use { reader ->
        reader.isLenient = false

        readRoot(reader)
    }

    private fun readRoot(reader: JsonReader): Set<EngineAudioPackRequirement> {
        reader.requireToken(JsonToken.BEGIN_OBJECT, "catalog root")
        reader.beginObject()
        val fields = hashSetOf<String>()
        var schema: String? = null
        var catalogVersion: Int? = null
        var carCount: Int? = null
        var families: List<FamilyRequirement>? = null
        while (reader.hasNext()) {
            val name = reader.nextName()
            require(fields.add(name)) { "Catalog has duplicate '$name'" }
            when (name) {
                "schema" -> schema = reader.nextStrictString("catalog.schema")
                "catalogVersion" -> catalogVersion = reader.nextStrictInt("catalog.catalogVersion")
                "cars" -> carCount = reader.skipArrayAndCount("catalog.cars")
                "families" -> families = readFamilies(reader)
                else -> throw IllegalArgumentException("Catalog has unknown '$name'")
            }
        }
        reader.endObject()
        require(reader.peek() == JsonToken.END_DOCUMENT) { "Catalog has trailing JSON data" }
        require(fields == ROOT_FIELDS) { "Catalog root fields do not match $SCHEMA" }
        require(schema == SCHEMA) { "Unsupported car catalog schema '$schema'" }
        require(catalogVersion == CATALOG_VERSION) { "Unsupported car catalog version '$catalogVersion'" }
        require(requireNotNull(carCount) > 0) { "Car catalog contains no cars" }
        val parsedFamilies = requireNotNull(families)
        require(parsedFamilies.isNotEmpty()) { "Car catalog contains no audio families" }
        require(parsedFamilies.map(FamilyRequirement::familyId).distinct().size == parsedFamilies.size) {
            "Car catalog has duplicate family ids"
        }
        val requirements = parsedFamilies.map(FamilyRequirement::requirement)
        require(requirements.distinct().size == requirements.size) {
            "Car catalog has duplicate exact pack requirements"
        }
        require(requirements.map(EngineAudioPackRequirement::packId).distinct().size == requirements.size) {
            "Car catalog has conflicting requirements for one pack id"
        }

        return requirements.toSet()
    }

    private fun readFamilies(reader: JsonReader): List<FamilyRequirement> {
        reader.requireToken(JsonToken.BEGIN_ARRAY, "catalog.families")
        reader.beginArray()
        val families = mutableListOf<FamilyRequirement>()
        while (reader.hasNext()) {
            families += readFamily(reader, families.size)
        }
        reader.endArray()

        return families
    }

    private fun readFamily(reader: JsonReader, index: Int): FamilyRequirement {
        val label = "catalog.families[$index]"
        reader.requireToken(JsonToken.BEGIN_OBJECT, label)
        reader.beginObject()
        val fields = hashSetOf<String>()
        var id: String? = null
        var assetDirectory: String? = null
        var requirement: EngineAudioPackRequirement? = null
        var runtimeAssetName: String? = null
        var runtimeBytes: Long? = null
        var runtimeSha256: String? = null
        while (reader.hasNext()) {
            val name = reader.nextName()
            require(fields.add(name)) { "$label has duplicate '$name'" }
            when (name) {
                "id" -> id = reader.nextStrictString("$label.id")
                "assetDirectory" -> assetDirectory = reader.nextStrictString("$label.assetDirectory")
                "packRequirement" -> requirement = readPackRequirement(reader, "$label.packRequirement")
                "runtimeAssetName" -> runtimeAssetName = reader.nextStrictString("$label.runtimeAssetName")
                "runtimeBytes" -> runtimeBytes = reader.nextStrictLong("$label.runtimeBytes")
                "runtimeSha256" -> runtimeSha256 = reader.nextStrictString("$label.runtimeSha256")
                "eagerCapabilities" -> {
                    reader.requireToken(JsonToken.BEGIN_OBJECT, "$label.eagerCapabilities")
                    reader.skipValue()
                }
                else -> throw IllegalArgumentException("$label has unknown '$name'")
            }
        }
        reader.endObject()
        require(fields == FAMILY_FIELDS) { "$label fields do not match the root catalog contract" }
        val familyId = requireNotNull(id)
        require(SAFE_ID.matches(familyId)) { "$label.id is unsafe" }
        require(SAFE_ID.matches(requireNotNull(assetDirectory))) { "$label.assetDirectory is unsafe" }
        val runtimePath = requireNotNull(runtimeAssetName)
        require(SAFE_RUNTIME_ASSET.matches(runtimePath) && ".." !in runtimePath) {
            "$label.runtimeAssetName is unsafe"
        }
        require(requireNotNull(runtimeBytes) in 1..MAXIMUM_RUNTIME_BYTES) { "$label.runtimeBytes is invalid" }
        require(BydAudioPackManifest.isSha256(requireNotNull(runtimeSha256))) {
            "$label.runtimeSha256 is invalid"
        }

        return FamilyRequirement(familyId, requireNotNull(requirement))
    }

    private fun readPackRequirement(reader: JsonReader, label: String): EngineAudioPackRequirement {
        reader.requireToken(JsonToken.BEGIN_OBJECT, label)
        reader.beginObject()
        val fields = hashSetOf<String>()
        var packId: String? = null
        var packVersion: Int? = null
        var manifestSha256: String? = null
        while (reader.hasNext()) {
            val name = reader.nextName()
            require(fields.add(name)) { "$label has duplicate '$name'" }
            when (name) {
                "packId" -> packId = reader.nextStrictString("$label.packId")
                "packVersion" -> packVersion = reader.nextStrictInt("$label.packVersion")
                "manifestSha256" -> manifestSha256 = reader.nextStrictString("$label.manifestSha256")
                else -> throw IllegalArgumentException("$label has unknown '$name'")
            }
        }
        reader.endObject()
        require(fields == PACK_FIELDS) { "$label fields do not match the pack requirement contract" }

        return EngineAudioPackRequirement(
            packId = requireNotNull(packId),
            packVersion = requireNotNull(packVersion),
            manifestSha256 = requireNotNull(manifestSha256),
        )
    }

    private fun JsonReader.skipArrayAndCount(label: String): Int {
        requireToken(JsonToken.BEGIN_ARRAY, label)
        beginArray()
        var count = 0
        while (hasNext()) {
            skipValue()
            count += 1
        }
        endArray()

        return count
    }

    private fun JsonReader.nextStrictString(label: String): String {
        requireToken(JsonToken.STRING, label)

        return nextString()
    }

    private fun JsonReader.nextStrictInt(label: String): Int {
        val value = nextStrictLong(label)
        require(value in Int.MIN_VALUE..Int.MAX_VALUE) { "$label is outside Int range" }

        return value.toInt()
    }

    private fun JsonReader.nextStrictLong(label: String): Long {
        requireToken(JsonToken.NUMBER, label)

        return nextLong()
    }

    private fun JsonReader.requireToken(expected: JsonToken, label: String) {
        require(peek() == expected) { "$label must be ${expected.name.lowercase()}" }
    }

    private data class FamilyRequirement(
        val familyId: String,
        val requirement: EngineAudioPackRequirement,
    )

    private const val SCHEMA = "byd-car-atlas-catalog-v2"
    private const val CATALOG_VERSION = 2
    private const val MAXIMUM_RUNTIME_BYTES = 4L * 1024L * 1024L
    private const val MAXIMUM_ROOT_CATALOG_BYTES = 512L * 1024L
    private val ROOT_FIELDS = setOf("schema", "catalogVersion", "cars", "families")
    private val FAMILY_FIELDS = setOf(
        "id",
        "assetDirectory",
        "packRequirement",
        "runtimeAssetName",
        "runtimeBytes",
        "runtimeSha256",
        "eagerCapabilities",
    )
    private val PACK_FIELDS = setOf("packId", "packVersion", "manifestSha256")
    private val SAFE_ID = Regex("^[a-z0-9][a-z0-9._-]{0,95}$")
    private val SAFE_RUNTIME_ASSET = Regex("^families/[a-z0-9][a-z0-9._-]{0,160}\\.json$")
}
