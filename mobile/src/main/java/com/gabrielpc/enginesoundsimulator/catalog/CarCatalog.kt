package com.gabrielpc.enginesoundsimulator.catalog

import android.content.Context
import android.net.Uri
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.util.zip.ZipFile
import java.io.FileOutputStream
import java.io.InputStream
import java.util.Locale
import java.util.UUID

internal data class GeneratedCarMetadataV1(
    val id: String,
    val displayName: String,
    val brand: String,
    val familyId: String,
    val engine: CarEngineMetadata,
    val gearbox: CarGearboxMetadata,
    val effects: CoreEffectAvailability,
    val quirks: Set<String>,
)

internal data class GeneratedSoundFamilyMetadataV1(
    val id: String,
    val representativeCarId: String,
    val memberCarIds: Set<String>,
    val effects: CoreEffectAvailability,
)

internal data class GeneratedOfficialCatalogV1(
    val catalogSha256: String,
    val cars: Map<String, GeneratedCarMetadataV1>,
    val soundFamilies: Map<String, GeneratedSoundFamilyMetadataV1>,
) {
    /** Built once and reused by pack import/store checks instead of copying 153 map entries per call. */
    val familyMembership: Map<String, Set<String>> = HashMap<String, Set<String>>(
        (soundFamilies.size / 0.75f + 1.0f).toInt(),
    ).also { result ->
        for ((id, family) in soundFamilies) result[id] = family.memberCarIds
    }

    companion object {
        const val MAX_CATALOG_BYTES = 4 * 1024 * 1024
        private val SHA256_PATTERN = Regex("^[0-9a-f]{64}$")
        private val CAR_IDENTIFIER_PATTERN = Regex("^[a-z0-9][a-z0-9._-]{0,127}$")
        private val SYMBOL_PATTERN = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$")
        private val CATALOG_KEYS = setOf(
            "schemaVersion", "audioPolicy", "cars", "soundFamilies", "counts",
            "excludedOfficialPlaceholders", "catalogSha256",
        )
        private val AUDIO_POLICY_KEYS = setOf("format", "sampleRate", "channels", "bitsPerSample")
        private val COUNT_KEYS = setOf(
            "installedOfficialDirectories", "usableCars", "soundFamilies", "unusablePlaceholders",
        )
        private val EXPECTED_COUNTS = mapOf(
            "installedOfficialDirectories" to 180L,
            "usableCars" to 178L,
            "soundFamilies" to 153L,
            "unusablePlaceholders" to 2L,
        )
        private val PLACEHOLDER_KEYS = setOf("id", "reason")
        private val EXPECTED_PLACEHOLDER_IDS = setOf(
            "ks_ferrari_488_challenge_evo",
            "ks_ferrari_488_gt3_2020",
        )
        private val CAR_KEYS = setOf(
            "id", "name", "brand", "official", "installed", "favorite", "familyId",
            "previewPath", "previewSource", "previewSha256", "previewMediaType", "engine",
            "gearbox", "effects", "quirks", "provenance",
        )
        private val CAR_PROVENANCE_KEYS = setOf("kind", "bankPath", "bankSha256", "physicsSha256")
        private val FAMILY_KEYS = setOf(
            "id", "representativeCarId", "memberIds", "sourceBankSha256", "events",
            "eventProbeStatus", "effects",
        )
        private val PREVIEW_MEDIA_TYPES = setOf("image/jpeg", "image/png")
        private val OFFICIAL_CAR_IDS = OfficialCarIndex.cars.mapTo(
            HashSet(mapCapacity(OfficialCarIndex.cars.size)),
        ) { car -> car.id }

        fun parse(bytes: ByteArray): GeneratedOfficialCatalogV1 {
            if (bytes.size > MAX_CATALOG_BYTES) throw JsonValidationException("Catalog is too large")
            if (StrictJson.containsForbiddenLoadToken(bytes)) {
                throw JsonValidationException("Catalog contains the forbidden LOAD role or reference")
            }
            val parsed = StrictJson.parse(bytes)
            val root = parsed.asObject("catalog")
            root.requireExactKeys("catalog", CATALOG_KEYS)
            if (root.getRequired("schemaVersion").asLong("schemaVersion") != 1L) {
                throw JsonValidationException("Unsupported catalog schemaVersion")
            }
            validateAudioPolicy(root.getRequired("audioPolicy"))
            val declaredHash = requireSha(root.getRequired("catalogSha256"), "catalogSha256")
            val actualHash = StrictJson.canonicalSha256ExcludingObjectKey(root, "catalogSha256")
                .toHexString()
            if (declaredHash != actualHash) throw JsonValidationException("Catalog SHA-256 is invalid")

            validateCounts(root.getRequired("counts"))
            validatePlaceholders(root.getRequired("excludedOfficialPlaceholders"))
            val cars = parseCars(root.getRequired("cars"))
            val families = parseFamilies(root.getRequired("soundFamilies"))
            if (cars.keys != OFFICIAL_CAR_IDS) throw JsonValidationException("Catalog is not the exact 178-car official set")
            if (families.size != 153) throw JsonValidationException("Catalog is not the exact 153-family set")

            val actualFamilyMemberCounts = HashMap<String, Int>(mapCapacity(families.size))
            for (car in cars.values) {
                if (car.familyId !in families) {
                    throw JsonValidationException("Catalog car/family references do not match")
                }
                actualFamilyMemberCounts[car.familyId] = (actualFamilyMemberCounts[car.familyId] ?: 0) + 1
            }
            if (actualFamilyMemberCounts.size != families.size) {
                throw JsonValidationException("Catalog car/family references do not match")
            }
            for (family in families.values) {
                val actualMemberCount = actualFamilyMemberCounts[family.id]
                if (
                    actualMemberCount != family.memberCarIds.size ||
                    family.representativeCarId !in family.memberCarIds ||
                    family.memberCarIds.any { memberId -> cars[memberId]?.familyId != family.id }
                ) {
                    throw JsonValidationException("Catalog family ${family.id} membership is inconsistent")
                }
            }
            return GeneratedOfficialCatalogV1(declaredHash, cars, families)
        }

        private fun validateAudioPolicy(value: JsonValue) {
            val policy = value.asObject("audioPolicy")
            policy.requireExactKeys("audioPolicy", AUDIO_POLICY_KEYS)
            if (
                policy.getRequired("format").asString("audioPolicy.format") != "FLAC" ||
                policy.getRequired("sampleRate").asLong("audioPolicy.sampleRate") != 48_000L ||
                policy.getRequired("channels").asLong("audioPolicy.channels") != 2L ||
                policy.getRequired("bitsPerSample").asLong("audioPolicy.bitsPerSample") != 16L
            ) {
                throw JsonValidationException("Catalog audio policy must be FLAC PCM16/48 kHz/stereo")
            }
        }

        private fun validateCounts(value: JsonValue) {
            val counts = value.asObject("counts")
            counts.requireExactKeys("counts", COUNT_KEYS)
            EXPECTED_COUNTS.forEach { (key, number) ->
                if (counts.getRequired(key).asLong("counts.$key") != number) {
                    throw JsonValidationException("Catalog counts.$key must be $number")
                }
            }
        }

        private fun validatePlaceholders(value: JsonValue) {
            val excluded = value.asArray("excludedOfficialPlaceholders")
            val ids = excluded.mapIndexed { index, raw ->
                val item = raw.asObject("excludedOfficialPlaceholders[$index]")
                item.requireExactKeys("excludedOfficialPlaceholders[$index]", PLACEHOLDER_KEYS)
                item.getRequired("reason").asString("excludedOfficialPlaceholders[$index].reason")
                item.getRequired("id").asString("excludedOfficialPlaceholders[$index].id")
            }.toSet()
            if (ids != EXPECTED_PLACEHOLDER_IDS) {
                throw JsonValidationException("Official placeholder set changed")
            }
        }

        private fun parseCars(value: JsonValue): Map<String, GeneratedCarMetadataV1> {
            val rawCars = value.asArray("cars")
            val result = LinkedHashMap<String, GeneratedCarMetadataV1>(mapCapacity(rawCars.size))
            rawCars.forEachIndexed { index, raw ->
                val label = "cars[$index]"
                val car = raw.asObject(label)
                car.requireExactKeys(label, CAR_KEYS)
                val id = requireId(car.getRequired("id"), "$label.id")
                if (!car.getRequired("official").asBoolean("$label.official") ||
                    !car.getRequired("installed").asBoolean("$label.installed") ||
                    car.getRequired("favorite").asBoolean("$label.favorite")
                ) {
                    throw JsonValidationException("$label has invalid compiler-only state")
                }
                validateOptionalPreview(car, label, id)
                validateCarProvenance(car.getRequired("provenance"), label)
                val quirks = car.getRequired("quirks").asArray("$label.quirks")
                    .mapIndexedTo(LinkedHashSet()) { quirkIndex, item ->
                        requireSymbol(item, "$label.quirks[$quirkIndex]")
                    }
                val metadata = GeneratedCarMetadataV1(
                    id = id,
                    displayName = car.getRequired("name").asString("$label.name").trim().also {
                        if (it.isEmpty()) throw JsonValidationException("$label.name must not be blank")
                    },
                    brand = car.getRequired("brand").asString("$label.brand").trim(),
                    familyId = requireSha(car.getRequired("familyId"), "$label.familyId"),
                    engine = SoundFamilyManifestV1.parseEngine(car.getRequired("engine"), "$label.engine"),
                    gearbox = SoundFamilyManifestV1.parseGearbox(car.getRequired("gearbox"), "$label.gearbox"),
                    effects = SoundFamilyManifestV1.parseEffects(car.getRequired("effects")),
                    quirks = quirks,
                )
                val expectedQuirks = OfficialCarQuirks.expectedFor(
                    metadata.id, metadata.engine, metadata.gearbox,
                )
                if (quirks != expectedQuirks) {
                    throw JsonValidationException("$label quirks do not match its authored metadata")
                }
                if (result.put(id, metadata) != null) throw JsonValidationException("Duplicate catalog car $id")
            }
            return result
        }

        private fun validateOptionalPreview(car: Map<String, JsonValue>, label: String, id: String) {
            val path = car.getRequired("previewPath")
            val source = car.getRequired("previewSource")
            val hash = car.getRequired("previewSha256")
            val type = car.getRequired("previewMediaType")
            val allNull = path == JsonValue.NullValue && source == JsonValue.NullValue &&
                hash == JsonValue.NullValue && type == JsonValue.NullValue
            if (allNull) return
            if (
                path == JsonValue.NullValue || source == JsonValue.NullValue ||
                hash == JsonValue.NullValue || type == JsonValue.NullValue
            ) {
                throw JsonValidationException("$label preview metadata must be entirely present or absent")
            }
            val expectedPath = path.asString("$label.previewPath")
            if (!expectedPath.startsWith("previews/$id.") || expectedPath.contains("..") || expectedPath.contains('\\')) {
                throw JsonValidationException("$label.previewPath is invalid")
            }
            val sourceText = source.asString("$label.previewSource")
            if (!sourceText.startsWith("content/cars/$id/") || sourceText.contains("..") || sourceText.contains('\\')) {
                throw JsonValidationException("$label.previewSource is invalid")
            }
            requireSha(hash, "$label.previewSha256")
            if (type.asString("$label.previewMediaType") !in PREVIEW_MEDIA_TYPES) {
                throw JsonValidationException("$label.previewMediaType is invalid")
            }
        }

        private fun validateCarProvenance(value: JsonValue, label: String) {
            val provenance = value.asObject("$label.provenance")
            provenance.requireExactKeys("$label.provenance", CAR_PROVENANCE_KEYS)
            if (provenance.getRequired("kind").asString("$label.provenance.kind") != "kunosAssettoCorsa1164") {
                throw JsonValidationException("$label is not Kunos provenance")
            }
            provenance.getRequired("bankPath").asString("$label.provenance.bankPath")
            requireSha(provenance.getRequired("bankSha256"), "$label.provenance.bankSha256")
            requireSha(provenance.getRequired("physicsSha256"), "$label.provenance.physicsSha256")
        }

        private fun parseFamilies(value: JsonValue): Map<String, GeneratedSoundFamilyMetadataV1> {
            val rawFamilies = value.asArray("soundFamilies")
            val result = LinkedHashMap<String, GeneratedSoundFamilyMetadataV1>(mapCapacity(rawFamilies.size))
            rawFamilies.forEachIndexed { index, raw ->
                val label = "soundFamilies[$index]"
                val family = raw.asObject(label)
                family.requireExactKeys(label, FAMILY_KEYS)
                val id = requireSha(family.getRequired("id"), "$label.id")
                if (requireSha(family.getRequired("sourceBankSha256"), "$label.sourceBankSha256") != id) {
                    throw JsonValidationException("$label source-bank hash does not match its id")
                }
                if (family.getRequired("eventProbeStatus").asString("$label.eventProbeStatus") != "complete") {
                    throw JsonValidationException("$label was not probed completely")
                }
                family.getRequired("events").asArray("$label.events").forEachIndexed { eventIndex, event ->
                    val path = event.asString("$label.events[$eventIndex]")
                    if (!path.startsWith("event:/cars/")) throw JsonValidationException("$label has an invalid event path")
                }
                val members = family.getRequired("memberIds").asArray("$label.memberIds")
                    .mapIndexedTo(LinkedHashSet()) { memberIndex, member ->
                        requireId(member, "$label.memberIds[$memberIndex]")
                    }
                val metadata = GeneratedSoundFamilyMetadataV1(
                    id = id,
                    representativeCarId = requireId(family.getRequired("representativeCarId"), "$label.representativeCarId"),
                    memberCarIds = members,
                    effects = SoundFamilyManifestV1.parseEffects(family.getRequired("effects")),
                )
                if (members.isEmpty() || result.put(id, metadata) != null) {
                    throw JsonValidationException("$label is empty or duplicated")
                }
            }
            return result
        }

        private fun requireId(value: JsonValue, label: String): String =
            value.asString(label).also {
                if (!CAR_IDENTIFIER_PATTERN.matches(it)) throw JsonValidationException("$label is invalid")
            }

        private fun requireSymbol(value: JsonValue, label: String): String =
            value.asString(label).also { if (!SYMBOL_PATTERN.matches(it)) throw JsonValidationException("$label is invalid") }

        private fun requireSha(value: JsonValue, label: String): String =
            value.asString(label).also { if (!SHA256_PATTERN.matches(it)) throw JsonValidationException("$label is invalid SHA-256") }

        private fun mapCapacity(expectedSize: Int): Int =
            if (expectedSize < 3) expectedSize + 1 else (expectedSize / 0.75f + 1.0f).toInt()
    }
}

internal data class CarCatalogEntry(
    val id: String,
    val displayName: String,
    val brand: String,
    val familyId: String?,
    val installed: Boolean,
    val favorite: Boolean,
    val previewFile: File?,
    val engine: CarEngineMetadata?,
    val gearbox: CarGearboxMetadata?,
    val effects: CoreEffectAvailability?,
    val quirks: Set<String>,
)

internal data class CarCatalogSnapshot(
    val entries: List<CarCatalogEntry>,
    val catalogSha256: String?,
    val installedFamilyCount: Int,
) {
    init {
        require(entries.size == 178)
    }

    fun find(carId: String): CarCatalogEntry? = entries.firstOrNull { it.id == carId }
}

internal class CarFavoritesRepository(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val officialIds = OfficialCarIndex.cars.mapTo(hashSetOf()) { it.id }
    private val lock = Any()

    fun favoriteIds(): Set<String> = synchronized(lock) {
        preferences.getStringSet(KEY_FAVORITES, emptySet()).orEmpty()
            .filterTo(linkedSetOf(), officialIds::contains)
    }

    fun isFavorite(carId: String): Boolean = carId in favoriteIds()

    fun setFavorite(carId: String, favorite: Boolean) {
        require(carId in officialIds) { "Unknown official car $carId" }
        synchronized(lock) {
            val next = preferences.getStringSet(KEY_FAVORITES, emptySet()).orEmpty()
                .filterTo(linkedSetOf(), officialIds::contains)
            if (favorite) next += carId else next -= carId
            preferences.edit().putStringSet(KEY_FAVORITES, next).apply()
        }
    }

    fun toggle(carId: String): Boolean = synchronized(lock) {
        val next = !preferences.getStringSet(KEY_FAVORITES, emptySet()).orEmpty().contains(carId)
        setFavorite(carId, next)
        next
    }

    private companion object {
        const val PREFERENCES_NAME = "car_catalog_favorites_v1"
        const val KEY_FAVORITES = "favorite_car_ids"
    }
}

/** Combines the immutable 178-car index, optional external compiler catalog, packs and favorites. */
internal class CarCatalogRepository(
    context: Context,
    private val favorites: CarFavoritesRepository = CarFavoritesRepository(context),
) {
    private val applicationContext = context.applicationContext
    private val mutationLock = Any()
    private val catalogFile = File(applicationContext.filesDir, "assetto_sound_library_v1/catalog-v1.json")
    private val familyStore = InstalledSoundFamilyStore(applicationContext.filesDir)
    /** Packages placed here are picked up automatically when an uninstalled car is selected. */
    val autoInstallDirectory: File = File(
        applicationContext.getExternalFilesDir(null) ?: applicationContext.filesDir,
        "assetto_sound_library_v1/auto-install",
    )
    @Volatile private var generatedCatalog: GeneratedOfficialCatalogV1? = null
    @Volatile private var installedFamilies: Map<String, InstalledSoundFamily> = emptyMap()

    init {
        recoverCatalogTransaction()
        generatedCatalog = loadCatalogIfValid()
        familyStore.recoverInterruptedTransactions()
        installedFamilies = loadCompatibleInstalledFamilies()
    }

    fun snapshot(): CarCatalogSnapshot {
        val catalog = generatedCatalog
        val families = installedFamilies
        val favoriteIds = favorites.favoriteIds()
        val byCar = linkedMapOf<String, InstalledSoundFamily>()
        families.values.forEach { family -> family.manifest.memberCarIds.forEach { byCar[it] = family } }
        val entries = OfficialCarIndex.cars.map { seed ->
            val generated = catalog?.cars?.get(seed.id)
            val installedFamily = byCar[seed.id]
            val manifestCar = installedFamily?.manifest?.car(seed.id)
            CarCatalogEntry(
                id = seed.id,
                displayName = manifestCar?.displayName ?: generated?.displayName ?: seed.displayName,
                brand = manifestCar?.brand?.takeIf(String::isNotBlank)
                    ?: generated?.brand?.takeIf(String::isNotBlank)
                    ?: seed.brand.takeIf(String::isNotBlank)
                    ?: inferBrand(seed.displayName),
                familyId = installedFamily?.manifest?.familyId ?: generated?.familyId,
                installed = installedFamily != null,
                favorite = seed.id in favoriteIds,
                previewFile = installedFamily?.previewFile(seed.id),
                engine = manifestCar?.engine ?: generated?.engine,
                gearbox = manifestCar?.gearbox ?: generated?.gearbox,
                effects = installedFamily?.manifest?.effects ?: generated?.effects,
                // Manifest quirks are a union across every member sharing one sound bank. Prefer
                // the generated car-level record so an AWD or hybrid sibling cannot leak policy
                // onto a mechanically different car using the same family.
                quirks = generated?.quirks ?: manifestCar?.let { car ->
                    OfficialCarQuirks.expectedFor(car.id, car.engine, car.gearbox)
                }.orEmpty(),
            )
        }.let(::orderCarCatalogEntriesForSelector)
        return CarCatalogSnapshot(entries, catalog?.catalogSha256, families.size)
    }

    fun installedFamilyForCar(carId: String): InstalledSoundFamily? =
        installedFamilies.values.firstOrNull { carId in it.manifest.memberCarIds }

    fun setFavorite(carId: String, favorite: Boolean): CarCatalogSnapshot {
        favorites.setFavorite(carId, favorite)
        return snapshot()
    }

    fun toggleFavorite(carId: String): CarCatalogSnapshot {
        favorites.toggle(carId)
        return snapshot()
    }

    fun refreshInstalledFamilies(): CarCatalogSnapshot {
        familyStore.recoverInterruptedTransactions()
        installedFamilies = loadCompatibleInstalledFamilies()
        return snapshot()
    }

    fun importPack(input: InputStream): CarCatalogSnapshot = synchronized(mutationLock) {
        val result = createPackImporter().importFrom(input)
        acceptImportedFamily(result)
    }

    fun importPack(uri: Uri): CarCatalogSnapshot = synchronized(mutationLock) {
        val result = createPackImporter().importFromUri(uri)
        acceptImportedFamily(result)
    }

    /** Finds and installs the family containing [carId] from the private auto-install inbox. */
    fun autoInstallForCar(carId: String, progress: (String, Int?) -> Unit = { _, _ -> }): CarCatalogSnapshot {
        require(carId in OfficialCarIndex.cars.map { it.id }) { "Unknown official car $carId" }
        autoInstallDirectory.mkdirs()
        val inboxes = listOf(
            autoInstallDirectory,
            File(applicationContext.filesDir, "assetto_sound_library_v1/auto-install"),
        ).distinctBy { it.absolutePath }
        progress("Procurando pacote local…", 5)
        val candidates = inboxes.flatMap { inbox ->
            inbox.listFiles { file -> file.isFile && file.extension.equals("aclib", true) }.orEmpty().toList()
        }.distinctBy { it.absolutePath }.sortedBy { it.name }
        val source = candidates.firstOrNull { file ->
            runCatching {
                ZipFile(file).use { zip ->
                    val entry = zip.getEntry("manifest.json") ?: return@use false
                    val bytes = zip.getInputStream(entry).use { input ->
                        val out = java.io.ByteArrayOutputStream()
                        val buffer = ByteArray(16 * 1024)
                        var totalRead = 0L
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            totalRead += count
                            if (totalRead > SoundFamilyManifestV1.MAX_MANIFEST_BYTES) return@use ByteArray(0)
                            out.write(buffer, 0, count)
                        }
                        out.toByteArray()
                    }
                    // This is only a cheap inbox routing check. The importer below remains the
                    // authoritative schema/hash/FLAC validator. Older otherwise-valid packs can
                    // contain fields introduced after this app build, so parsing here must not
                    // make them look as if they were absent.
                    String(bytes, Charsets.UTF_8).contains("\"$carId\"")
                }
            }.getOrDefault(false)
        } ?: throw PackValidationException(
            "Nenhum pacote .aclib para este carro. Copie o pacote para ${autoInstallDirectory.absolutePath}.",
        )
        progress("Copiando ${source.name}…", 15)
        val total = source.length().coerceAtLeast(1L)
        var copied = 0L
        val counting = object : java.io.FilterInputStream(FileInputStream(source)) {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                val count = super.read(buffer, offset, length)
                if (count > 0) {
                    copied += count
                    progress("Copiando ${source.name}…", (15 + copied * 45 / total).toInt().coerceAtMost(60))
                }
                return count
            }
        }
        progress("Validando e decodificando…", null)
        counting.use { input ->
            synchronized(mutationLock) {
                val result = createPackImporter().importFrom(input)
                acceptImportedFamily(result)
            }
        }
        progress("Ativando áudio…", 95)
        return snapshot().also { progress("Pacote instalado", 100) }
    }

    /**
     * Production SAF batch path. Every archive is independently validated and atomically
     * committed; installed-family discovery and selector reconstruction happen once after the
     * complete distinct batch instead of once per document.
     */
    fun importPacks(uris: List<Uri>): CarCatalogSnapshot = synchronized(mutationLock) {
        if (uris.isEmpty()) return@synchronized snapshot()
        val importer = createPackImporter()
        CatalogImportBatchPolicy.importDistinctAndClose(
            sources = uris,
            importOne = { source ->
                val result = importer.importFromUri(source)
                validateImportedFamilyMembership(result)
            },
            closeBatch = {
                installedFamilies = loadCompatibleInstalledFamilies()
                snapshot()
            },
        )
    }

    /** Blocking validation/decoding importer; invoke it only from a cancellable I/O worker. */
    private fun createPackImporter(): AclibPackImporter {
        val catalog = generatedCatalog
        return AclibPackImporter(
            applicationContext,
            officialFamilyMembership = catalog?.familyMembership,
            expectedCatalogSha256 = catalog?.catalogSha256,
        )
    }

    /** Installs only validated metadata; the generated catalog itself remains local/private. */
    fun importGeneratedCatalog(input: InputStream): CarCatalogSnapshot = synchronized(mutationLock) {
        val bytes = input.use { it.readBoundedCatalog(GeneratedOfficialCatalogV1.MAX_CATALOG_BYTES) }
        val parsed = GeneratedOfficialCatalogV1.parse(bytes)
        catalogFile.parentFile?.mkdirs()
        val temporary = File(catalogFile.parentFile, ".catalog-${UUID.randomUUID()}.partial")
        val backup = File(catalogFile.parentFile, ".catalog-${UUID.randomUUID()}.backup")
        try {
            FileOutputStream(temporary).use { fileOutput ->
                val output = BufferedOutputStream(fileOutput)
                output.write(bytes)
                output.flush()
                fileOutput.fd.sync()
            }
            val replacing = catalogFile.exists()
            if (replacing && !catalogFile.renameTo(backup)) {
                throw IllegalStateException("Could not stage previous private catalog")
            }
            if (!temporary.renameTo(catalogFile)) {
                if (replacing) backup.renameTo(catalogFile)
                throw IllegalStateException("Could not atomically install private catalog")
            }
            backup.delete()
            generatedCatalog = parsed
            // A catalog replacement changes the provenance boundary. Packs compiled
            // for another catalog remain private on disk, but are immediately excluded
            // from selection and decoding until a matching catalog is restored.
            installedFamilies = loadCompatibleInstalledFamilies()
            snapshot()
        } finally {
            temporary.delete()
        }
    }

    /** Called after AclibPackImporter succeeds; also rejects a family inconsistent with full metadata. */
    private fun acceptImportedFamily(result: AclibImportResult): CarCatalogSnapshot {
        validateImportedFamilyMembership(result)
        installedFamilies = loadCompatibleInstalledFamilies()
        return snapshot()
    }

    private fun validateImportedFamilyMembership(result: AclibImportResult) {
        val expected = generatedCatalog?.soundFamilies?.get(result.family.manifest.familyId)
        if (
            expected != null &&
            !result.family.manifest.memberCarIds.matchesUniqueMembership(expected.memberCarIds)
        ) {
            throw PackValidationException("Imported family membership differs from the official catalog")
        }
    }

    private fun loadCompatibleInstalledFamilies(): Map<String, InstalledSoundFamily> {
        val catalog = generatedCatalog
        return familyStore.loadInstalled(
            expectedCatalogSha256 = catalog?.catalogSha256,
            officialFamilyMembership = catalog?.familyMembership,
        )
    }

    private fun loadCatalogIfValid(): GeneratedOfficialCatalogV1? = try {
        if (catalogFile.isFile && catalogFile.length() <= GeneratedOfficialCatalogV1.MAX_CATALOG_BYTES) {
            GeneratedOfficialCatalogV1.parse(catalogFile.readBytes())
        } else {
            null
        }
    } catch (_: Exception) {
        null
    }

    private fun recoverCatalogTransaction() {
        val directory = catalogFile.parentFile ?: return
        directory.mkdirs()
        directory.listFiles()?.filter { it.name.startsWith(".catalog-") && it.name.endsWith(".partial") }
            ?.forEach(File::delete)
        val backups = directory.listFiles()?.filter {
            it.name.startsWith(".catalog-") && it.name.endsWith(".backup")
        }.orEmpty().sortedByDescending(File::lastModified)
        if (catalogFile.exists()) {
            backups.forEach(File::delete)
        } else {
            backups.firstOrNull()?.renameTo(catalogFile)
            backups.drop(1).forEach(File::delete)
        }
    }

    private fun inferBrand(displayName: String): String = displayName.substringBefore(' ')
}

/** Favorites stay visible at the top of the selector while each group remains predictable. */
internal fun orderCarCatalogEntriesForSelector(entries: List<CarCatalogEntry>): List<CarCatalogEntry> =
    entries.sortedWith(
        compareByDescending<CarCatalogEntry> { it.favorite }
            .thenBy { it.displayName.lowercase(Locale.ROOT) },
    )

private fun InputStream.readBoundedCatalog(maximumBytes: Int): ByteArray {
    val output = java.io.ByteArrayOutputStream(minOf(maximumBytes, 768 * 1024))
    val buffer = ByteArray(16 * 1024)
    var total = 0
    while (true) {
        val count = read(buffer)
        if (count < 0) break
        total += count
        if (total > maximumBytes) throw JsonValidationException("Catalog exceeds $maximumBytes bytes")
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}

private fun List<String>.matchesUniqueMembership(expected: Set<String>): Boolean =
    size == expected.size && all(expected::contains)

private fun ByteArray.toHexString(): String {
    val digits = "0123456789abcdef"
    val result = CharArray(size * 2)
    for (index in indices) {
        val value = this[index].toInt() and 0xff
        result[index * 2] = digits[value ushr 4]
        result[index * 2 + 1] = digits[value and 0x0f]
    }
    return result.concatToString()
}
