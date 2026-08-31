package com.gabrielpc.enginesoundsimulator.audio

import android.content.Context
import com.gabrielpc.audiopackcontract.AudioPackIdentity
import com.gabrielpc.audiopackcontract.AudioPackExpectedSource
import com.gabrielpc.audiopackcontract.AudioPackInstallContract
import com.gabrielpc.audiopackcontract.AudioPackInventorySnapshot
import java.io.FileNotFoundException

internal data class CurrentAudioPackCatalog(
    val requirements: Set<EngineAudioPackRequirement>,
    val unavailableReason: String? = null,
    val expectedSourceFileNames: Map<EngineAudioPackRequirement, String> = requirements.associateWith { requirement ->
        "${requirement.packId}-v${requirement.packVersion}.bydpack"
    },
) {
    val isAvailable: Boolean get() = unavailableReason == null
}

internal class AudioPackCatalogValidationException(
    val errorCode: String,
    message: String,
) : BydAudioPackValidationException(message)

/** Main-app authority for the exact catalog identities accepted by the external installer. */
internal class AudioPackCatalogAuthority(
    private val catalogProvider: () -> CurrentAudioPackCatalog,
    private val installedProvider: () -> List<InstalledBydAudioPack>,
    private val availableBytesProvider: () -> Long = { -1L },
    private val cleanupProvider: (Set<EngineAudioPackRequirement>) -> List<String> = { emptyList() },
) {
    private val currentCatalog: CurrentAudioPackCatalog by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        catalogProvider()
    }

    constructor(context: Context) : this(
        catalogProvider = { loadCurrentCatalog(context.applicationContext) },
        installedProvider = { BydAudioPackStore(context.applicationContext.filesDir).installed() },
        availableBytesProvider = { context.applicationContext.filesDir.usableSpace },
        cleanupProvider = { requirements ->
            BydAudioPackStore(context.applicationContext.filesDir).cleanupObsolete(requirements)
        },
    )

    fun snapshot(): AudioPackInventorySnapshot {
        val catalog = currentCatalog
        val expected = catalog.requirements.sortedIdentities()
        val expectedSources = catalog.requirements
            .sortedWith(compareBy(EngineAudioPackRequirement::packId, EngineAudioPackRequirement::packVersion))
            .map { requirement ->
                AudioPackExpectedSource(
                    requirement.identity(),
                    requireNotNull(catalog.expectedSourceFileNames[requirement]) {
                        "Catalog is missing the deterministic filename for ${requirement.packId}"
                    },
                )
            }
        if (!catalog.isAvailable) {
            return AudioPackInventorySnapshot(
                false,
                catalog.unavailableReason,
                expected,
                expectedSources,
                emptyList(),
                expected,
                emptyList(),
                emptyList(),
                availableBytes(),
            )
        }

        val expectedRequirements = catalog.requirements
        val expectedPackIds = expectedRequirements.mapTo(hashSetOf(), EngineAudioPackRequirement::packId)
        val installed = installedProvider().map { pack -> pack.manifest.requirement() }
        val exactInstalled = installed.filterTo(linkedSetOf()) { it in expectedRequirements }
        val stale = installed.filterTo(linkedSetOf()) { requirement ->
            requirement.packId in expectedPackIds && requirement !in expectedRequirements
        }
        val extra = installed.filterTo(linkedSetOf()) { it.packId !in expectedPackIds }

        return AudioPackInventorySnapshot(
            true,
            "",
            expected,
            expectedSources,
            exactInstalled.sortedIdentities(),
            (expectedRequirements - exactInstalled).sortedIdentities(),
            stale.sortedIdentities(),
            extra.sortedIdentities(),
            availableBytes(),
        )
    }

    fun requireAccepted(manifest: BydAudioPackManifest): EngineAudioPackRequirement {
        val catalog = currentCatalog
        if (!catalog.isAvailable) {
            throw AudioPackCatalogValidationException(
                AudioPackInstallContract.ERROR_CATALOG_UNAVAILABLE,
                catalog.unavailableReason ?: "The current app audio catalog is unavailable",
            )
        }
        val incoming = manifest.requirement()
        if (incoming in catalog.requirements) return incoming

        val current = catalog.requirements.singleOrNull { requirement -> requirement.packId == incoming.packId }
        if (current == null) {
            throw AudioPackCatalogValidationException(
                AudioPackInstallContract.ERROR_UNEXPECTED_PACK,
                "${incoming.packId} is not expected by the current app catalog",
            )
        }
        if (current.packVersion != incoming.packVersion) {
            throw AudioPackCatalogValidationException(
                AudioPackInstallContract.ERROR_STALE_PACK,
                "${incoming.packId} v${incoming.packVersion} is not current; this app requires v${current.packVersion}",
            )
        }

        throw AudioPackCatalogValidationException(
            AudioPackInstallContract.ERROR_MANIFEST_MISMATCH,
            "${incoming.packId} v${incoming.packVersion} has the wrong manifest hash; " +
                "this app requires ${current.manifestSha256.take(HASH_PREVIEW_LENGTH)}…",
        )
    }

    fun cleanupObsolete(): AudioPackInventorySnapshot {
        val catalog = currentCatalog
        if (!catalog.isAvailable) {
            throw AudioPackCatalogValidationException(
                AudioPackInstallContract.ERROR_CATALOG_UNAVAILABLE,
                catalog.unavailableReason ?: "The current app audio catalog is unavailable",
            )
        }
        cleanupProvider(catalog.requirements)

        return snapshot()
    }

    companion object {
        private const val HASH_PREVIEW_LENGTH = 12

        private fun loadCurrentCatalog(context: Context): CurrentAudioPackCatalog {
            try {
                val requirements = ExternalCarPackRequirementsLoader.load(context)
                if (requirements.isEmpty()) {
                    return CurrentAudioPackCatalog(
                        emptySet(),
                        "The current main APK audio catalog contains no external pack requirements",
                    )
                }
                val conflictingPackIds = requirements.groupBy(EngineAudioPackRequirement::packId)
                    .filterValues { identities -> identities.size > 1 }
                    .keys
                if (conflictingPackIds.isNotEmpty()) {
                    return CurrentAudioPackCatalog(
                        requirements,
                        "The current main APK audio catalog has conflicting requirements for " +
                            conflictingPackIds.sorted().joinToString(),
                    )
                }

                return CurrentAudioPackCatalog(
                    requirements = requirements,
                    expectedSourceFileNames = requirements.associateWith(::deterministicSourceFileName),
                )
            } catch (_: FileNotFoundException) {
                return CurrentAudioPackCatalog(
                    emptySet(),
                    "The current main APK does not contain its external-car audio catalog",
                )
            } catch (error: Exception) {
                return CurrentAudioPackCatalog(
                    emptySet(),
                    "The current main APK audio catalog is invalid: ${error.message ?: error::class.java.simpleName}",
                )
            }
        }

        internal fun deterministicSourceFileName(requirement: EngineAudioPackRequirement): String {
            val runtimeId = requirement.packId.removePrefix(PACK_ID_PREFIX)
            require(runtimeId != requirement.packId && SAFE_RUNTIME_ID.matches(runtimeId)) {
                "Catalog pack id '${requirement.packId}' cannot map to a deterministic USB filename"
            }

            return "$runtimeId-v${requirement.packVersion}.bydpack"
        }

        private const val PACK_ID_PREFIX = "byd.atlas."
        private val SAFE_RUNTIME_ID = Regex("^[a-z0-9][a-z0-9._-]{0,95}$")
    }

    private fun availableBytes(): Long = runCatching { availableBytesProvider() }
        .getOrDefault(-1L)
        .takeIf { it >= 0L }
        ?: -1L
}

private fun Collection<EngineAudioPackRequirement>.sortedIdentities(): List<AudioPackIdentity> =
    sortedWith(compareBy(EngineAudioPackRequirement::packId, EngineAudioPackRequirement::packVersion))
        .map(EngineAudioPackRequirement::identity)

private fun EngineAudioPackRequirement.identity(): AudioPackIdentity = AudioPackIdentity(
    packId,
    packVersion,
    manifestSha256,
)

internal fun BydAudioPackManifest.requirement(): EngineAudioPackRequirement = EngineAudioPackRequirement(
    packId = packId,
    packVersion = packVersion,
    manifestSha256 = manifestSha256,
)
