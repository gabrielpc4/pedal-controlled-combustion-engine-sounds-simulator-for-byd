package com.gabrielpc.enginesoundsimulator.audio

import com.gabrielpc.audiopackcontract.AudioPackInstallContract
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioPackCatalogAuthorityTest {
    @Test
    fun snapshotSeparatesExactMissingStaleAndExtraIdentities() {
        val expectedOne = requirement("one", 2, '1')
        val expectedTwo = requirement("two", 1, '2')
        val exactOne = installed(expectedOne)
        val staleTwo = installed(requirement("two", 1, '3'))
        val extra = installed(requirement("other", 1, '4'))
        val authority = AudioPackCatalogAuthority(
            catalogProvider = { CurrentAudioPackCatalog(setOf(expectedOne, expectedTwo)) },
            installedProvider = { listOf(exactOne, staleTwo, extra) },
        )

        val snapshot = authority.snapshot()

        assertTrue(snapshot.isCatalogAvailable)
        assertFalse(snapshot.isReady)
        assertEquals(listOf("one"), snapshot.exactInstalled.map { it.packId })
        assertEquals(listOf("two"), snapshot.missing.map { it.packId })
        assertEquals(listOf("two"), snapshot.stale.map { it.packId })
        assertEquals(listOf("other"), snapshot.extra.map { it.packId })
    }

    @Test
    fun exactCurrentIdentityIsAccepted() {
        val expected = requirement("family", 3, 'a')
        val authority = authority(expected)

        assertEquals(expected, authority.requireAccepted(manifest(expected)))
    }

    @Test
    fun individuallyValidStrangePackIsRejected() {
        val authority = authority(requirement("family", 1, 'a'))

        val error = assertThrows(AudioPackCatalogValidationException::class.java) {
            authority.requireAccepted(manifest(requirement("strange", 1, 'b')))
        }

        assertEquals(AudioPackInstallContract.ERROR_UNEXPECTED_PACK, error.errorCode)
    }

    @Test
    fun oldVersionAndWrongManifestHashHaveDistinctErrors() {
        val expected = requirement("family", 2, 'a')
        val authority = authority(expected)

        val old = assertThrows(AudioPackCatalogValidationException::class.java) {
            authority.requireAccepted(manifest(requirement("family", 1, 'a')))
        }
        val wrongHash = assertThrows(AudioPackCatalogValidationException::class.java) {
            authority.requireAccepted(manifest(requirement("family", 2, 'b')))
        }

        assertEquals(AudioPackInstallContract.ERROR_STALE_PACK, old.errorCode)
        assertEquals(AudioPackInstallContract.ERROR_MANIFEST_MISMATCH, wrongHash.errorCode)
    }

    @Test
    fun unavailableCatalogFailsClosedAndCannotBeReady() {
        val authority = AudioPackCatalogAuthority(
            catalogProvider = { CurrentAudioPackCatalog(emptySet(), "catalog missing") },
            installedProvider = { emptyList() },
        )

        val snapshot = authority.snapshot()
        val error = assertThrows(AudioPackCatalogValidationException::class.java) {
            authority.requireAccepted(manifest(requirement("family", 1, 'a')))
        }

        assertFalse(snapshot.isCatalogAvailable)
        assertFalse(snapshot.isReady)
        assertEquals("catalog missing", snapshot.catalogError)
        assertEquals(AudioPackInstallContract.ERROR_CATALOG_UNAVAILABLE, error.errorCode)
    }

    @Test
    fun immutableApkCatalogIsLoadedOnlyOnceAcrossSnapshotAndManyValidations() {
        val expected = requirement("family", 1, 'a')
        val loads = AtomicInteger()
        val authority = AudioPackCatalogAuthority(
            catalogProvider = {
                loads.incrementAndGet()
                CurrentAudioPackCatalog(setOf(expected))
            },
            installedProvider = { emptyList() },
        )

        authority.snapshot()
        repeat(64) { authority.requireAccepted(manifest(expected)) }
        authority.snapshot()

        assertEquals(1, loads.get())
    }

    @Test
    fun signedCatalogIdentityMapsToTheDeterministicReleasePackFilename() {
        val requirement = EngineAudioPackRequirement("byd.atlas.family-one", 7, "a".repeat(64))

        assertEquals(
            "family-one-v7.bydpack",
            AudioPackCatalogAuthority.deterministicSourceFileName(requirement),
        )
        assertThrows(IllegalArgumentException::class.java) {
            AudioPackCatalogAuthority.deterministicSourceFileName(requirement.copy(packId = "foreign.family"))
        }
    }

    private fun authority(vararg expected: EngineAudioPackRequirement): AudioPackCatalogAuthority =
        AudioPackCatalogAuthority(
            catalogProvider = { CurrentAudioPackCatalog(expected.toSet()) },
            installedProvider = { emptyList() },
        )

    private fun requirement(id: String, version: Int, hashCharacter: Char) = EngineAudioPackRequirement(
        packId = id,
        packVersion = version,
        manifestSha256 = hashCharacter.toString().repeat(64),
    )

    private fun manifest(requirement: EngineAudioPackRequirement) = BydAudioPackManifest(
        packId = requirement.packId,
        packVersion = requirement.packVersion,
        files = emptyList(),
        manifestSha256 = requirement.manifestSha256,
    )

    private fun installed(requirement: EngineAudioPackRequirement) = InstalledBydAudioPack(
        rootDirectory = File(requirement.packId),
        manifest = manifest(requirement),
    )
}
