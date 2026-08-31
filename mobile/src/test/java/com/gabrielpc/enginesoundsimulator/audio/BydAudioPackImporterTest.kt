package com.gabrielpc.enginesoundsimulator.audio

import com.gabrielpc.audiopackcontract.AudioPackInstallContract
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InterruptedIOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BydAudioPackImporterTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun digestHexEncodingIsLowercaseAndUnsigned() {
        assertEquals("000f10ff", byteArrayOf(0, 15, 16, -1).toHex())
    }

    @Test
    fun validPackInstallsIntoPrivateStorageAndMatchesAnExactRequirement() {
        val privateFiles = temporaryFolder.newFolder("private")
        val wav = pcm16Wav(sampleRate = 48_000, channels = 2, frames = 40)
        val path = "sample_engine/test_car/load.wav"
        val manifest = manifestBytes("test-car", 3, path, wav)

        val result = BydAudioPackImporter(privateFiles).importFrom(
            ByteArrayInputStream(packBytes(manifest, mapOf(path to wav))),
        )

        assertEquals("test-car", result.packId)
        assertEquals(3, result.packVersion)
        assertEquals(1, result.fileCount)
        assertEquals(wav.size.toLong(), result.installedBytes)
        assertFalse(result.replacedExistingPack)
        val exactRequirement = EngineAudioPackRequirement("test-car", 3, sha256(manifest))
        val installed = BydAudioPackStore(privateFiles).find(exactRequirement)
        assertNotNull(installed)
        assertArrayEquals(wav, requireNotNull(installed).open(path).use { input -> input.readBytes() })
        assertNull(
            BydAudioPackStore(privateFiles).find(
                exactRequirement.copy(manifestSha256 = "0".repeat(64)),
            ),
        )
    }

    @Test
    fun catalogPolicyRejectsIndividuallyValidForeignPackBeforeExtractionOrCommit() {
        val privateFiles = temporaryFolder.newFolder("private")
        val wav = pcm16Wav(sampleRate = 48_000, channels = 2, frames = 40)
        val path = "sample_engine/foreign/load.wav"
        val manifest = manifestBytes("foreign", 1, path, wav)
        val expected = EngineAudioPackRequirement("expected", 1, "a".repeat(64))
        val authority = AudioPackCatalogAuthority(
            catalogProvider = { CurrentAudioPackCatalog(setOf(expected)) },
            installedProvider = { emptyList() },
        )
        val stages = mutableListOf<BydAudioPackImportStage>()

        val error = assertThrows(AudioPackCatalogValidationException::class.java) {
            BydAudioPackImporter(privateFiles).importFrom(
                input = ByteArrayInputStream(packBytes(manifest, mapOf(path to wav))),
                observer = BydAudioPackImportObserver { stages += it.stage },
                acceptancePolicy = BydAudioPackAcceptancePolicy { authority.requireAccepted(it) },
            )
        }

        assertEquals(AudioPackInstallContract.ERROR_UNEXPECTED_PACK, error.errorCode)
        assertEquals(BydAudioPackImportStage.VALIDATE_CATALOG, stages.last())
        assertFalse(BydAudioPackImportStage.EXTRACT_WAV in stages)
        assertTrue(BydAudioPackStore(privateFiles).installed().isEmpty())
    }

    @Test
    fun catalogIsRecheckedAfterExtractionAndFailureStillCannotCommit() {
        val privateFiles = temporaryFolder.newFolder("private")
        val wav = pcm16Wav(sampleRate = 48_000, channels = 2, frames = 40)
        val path = "sample_engine/current/load.wav"
        val manifestBytes = manifestBytes("current", 1, path, wav)
        val validations = AtomicInteger()
        val policy = BydAudioPackAcceptancePolicy {
            if (validations.incrementAndGet() == 2) {
                throw AudioPackCatalogValidationException(
                    AudioPackInstallContract.ERROR_MANIFEST_MISMATCH,
                    "Catalog changed before commit",
                )
            }
        }

        assertThrows(AudioPackCatalogValidationException::class.java) {
            BydAudioPackImporter(privateFiles).importFrom(
                input = ByteArrayInputStream(packBytes(manifestBytes, mapOf(path to wav))),
                acceptancePolicy = policy,
            )
        }

        assertEquals(2, validations.get())
        assertTrue(BydAudioPackStore(privateFiles).installed().isEmpty())
    }

    @Test
    fun invalidNewVersionCannotDamageThePreviouslyInstalledPack() {
        val privateFiles = temporaryFolder.newFolder("private")
        val importer = BydAudioPackImporter(privateFiles)
        val path = "sample_engine/test_car/load.wav"
        val originalWav = pcm16Wav(sampleRate = 44_100, channels = 1, frames = 40)
        val originalManifest = manifestBytes("test-car", 1, path, originalWav)
        importer.importFrom(ByteArrayInputStream(packBytes(originalManifest, mapOf(path to originalWav))))

        val replacementWav = pcm16Wav(sampleRate = 48_000, channels = 2, frames = 48)
        val replacementManifest = manifestBytes(
            packId = "test-car",
            packVersion = 2,
            path = path,
            wav = replacementWav,
            declaredSha256 = "f".repeat(64),
        )
        assertThrows(BydAudioPackValidationException::class.java) {
            importer.importFrom(ByteArrayInputStream(packBytes(replacementManifest, mapOf(path to replacementWav))))
        }

        val installed = BydAudioPackStore(privateFiles).find(
            EngineAudioPackRequirement("test-car", 1, sha256(originalManifest)),
        )
        assertNotNull(installed)
        assertArrayEquals(originalWav, requireNotNull(installed).open(path).use { input -> input.readBytes() })
    }

    @Test
    fun successfulNewVersionPrunesObsoleteReadyVersionsForTheSamePackId() {
        val privateFiles = temporaryFolder.newFolder("private")
        val importer = BydAudioPackImporter(privateFiles)
        val path = "sample_engine/test_car/load.wav"
        val versionOneWav = pcm16Wav(sampleRate = 44_100, channels = 1, frames = 40)
        val versionOneManifest = manifestBytes("test-car", 1, path, versionOneWav)
        val versionTwoWav = pcm16Wav(sampleRate = 48_000, channels = 2, frames = 48)
        val versionTwoManifest = manifestBytes("test-car", 2, path, versionTwoWav)

        importer.importFrom(ByteArrayInputStream(packBytes(versionOneManifest, mapOf(path to versionOneWav))))
        importer.importFrom(ByteArrayInputStream(packBytes(versionTwoManifest, mapOf(path to versionTwoWav))))

        val store = BydAudioPackStore(privateFiles)
        val versionOne = store.find(EngineAudioPackRequirement("test-car", 1, sha256(versionOneManifest)))
        val versionTwo = store.find(EngineAudioPackRequirement("test-car", 2, sha256(versionTwoManifest)))
        assertNull(versionOne)
        assertArrayEquals(versionTwoWav, requireNotNull(versionTwo).open(path).use { it.readBytes() })
        assertEquals(1, store.installed().size)
    }

    @Test
    fun successfulNewVersionKeepsTheExactVersionReferencedByTheCurrentCatalog() {
        val privateFiles = temporaryFolder.newFolder("private")
        val path = "sample_engine/test_car/load.wav"
        val versionOneWav = pcm16Wav(sampleRate = 44_100, channels = 1, frames = 40)
        val versionOneManifest = manifestBytes("test-car", 1, path, versionOneWav)
        BydAudioPackImporter(privateFiles).importFrom(
            ByteArrayInputStream(packBytes(versionOneManifest, mapOf(path to versionOneWav))),
        )
        val versionOneRequirement = EngineAudioPackRequirement("test-car", 1, sha256(versionOneManifest))
        val importer = BydAudioPackImporter(
            privateFilesDirectory = privateFiles,
            catalogRequirements = { setOf(versionOneRequirement) },
        )
        val versionTwoWav = pcm16Wav(sampleRate = 48_000, channels = 2, frames = 48)
        val versionTwoManifest = manifestBytes("test-car", 2, path, versionTwoWav)

        importer.importFrom(ByteArrayInputStream(packBytes(versionTwoManifest, mapOf(path to versionTwoWav))))

        val store = BydAudioPackStore(privateFiles, catalogRequirements = { setOf(versionOneRequirement) })
        assertNotNull(store.find(versionOneRequirement))
        assertNotNull(store.find(EngineAudioPackRequirement("test-car", 2, sha256(versionTwoManifest))))
        assertEquals(2, store.installed().size)
    }

    @Test
    fun pruningKeepsAnAlreadyOpenPreviousPackStreamReadable() {
        val privateFiles = temporaryFolder.newFolder("private")
        val importer = BydAudioPackImporter(privateFiles)
        val path = "sample_engine/test-car/load.wav"
        val originalWav = pcm16Wav(sampleRate = 44_100, channels = 1, frames = 40)
        val originalManifest = manifestBytes("test-car", 1, path, originalWav)
        importer.importFrom(ByteArrayInputStream(packBytes(originalManifest, mapOf(path to originalWav))))
        val previousStream = requireNotNull(
            BydAudioPackStore(privateFiles).find(EngineAudioPackRequirement("test-car", 1, sha256(originalManifest))),
        ).open(path)

        val replacementWav = pcm16Wav(sampleRate = 48_000, channels = 2, frames = 48)
        val replacementManifest = manifestBytes("test-car", 2, path, replacementWav)
        importer.importFrom(ByteArrayInputStream(packBytes(replacementManifest, mapOf(path to replacementWav))))

        previousStream.use { assertArrayEquals(originalWav, it.readBytes()) }
    }

    @Test
    fun explicitCleanupRemovesOnlyNonCatalogPacksAndKeepsOpenStreamsReadable() {
        val privateFiles = temporaryFolder.newFolder("private")
        val importer = BydAudioPackImporter(privateFiles)
        val retainedPath = "sample_engine/retained/load.wav"
        val retainedWav = pcm16Wav(sampleRate = 44_100, channels = 1, frames = 40)
        val retainedManifest = manifestBytes("retained", 1, retainedPath, retainedWav)
        importer.importFrom(ByteArrayInputStream(packBytes(retainedManifest, mapOf(retainedPath to retainedWav))))
        val obsoletePath = "sample_engine/obsolete/load.wav"
        val obsoleteWav = pcm16Wav(sampleRate = 48_000, channels = 2, frames = 48)
        val obsoleteManifest = manifestBytes("obsolete", 1, obsoletePath, obsoleteWav)
        importer.importFrom(ByteArrayInputStream(packBytes(obsoleteManifest, mapOf(obsoletePath to obsoleteWav))))
        val store = BydAudioPackStore(privateFiles)
        val obsoleteRequirement = EngineAudioPackRequirement("obsolete", 1, sha256(obsoleteManifest))
        val openObsoleteStream = requireNotNull(store.find(obsoleteRequirement)).open(obsoletePath)
        val retainedRequirement = EngineAudioPackRequirement("retained", 1, sha256(retainedManifest))

        val removed = store.cleanupObsolete(setOf(retainedRequirement))

        assertEquals(1, removed.size)
        assertNotNull(store.find(retainedRequirement))
        assertNull(store.find(obsoleteRequirement))
        openObsoleteStream.use { assertArrayEquals(obsoleteWav, it.readBytes()) }
    }

    @Test
    fun knownArchiveSizeIsPreflightedBeforeTheInputIsRead() {
        val privateFiles = temporaryFolder.newFolder("private")
        val importer = BydAudioPackImporter(
            privateFilesDirectory = privateFiles,
            capacityProvider = BydAudioPackCapacityProvider { 10L },
        )
        val unreadable = object : InputStream() {
            override fun read(): Int = error("The storage preflight must run before copying")
        }

        val error = assertThrows(BydAudioPackStorageException::class.java) {
            importer.importFrom(unreadable, sourceBytes = 11L)
        }

        assertEquals(BydAudioPackImportStage.RECEIVE, error.stage)
        assertTrue(error.message.orEmpty().contains("requires 11 B but only 10 B"))
    }

    @Test
    fun manifestPreflightReportsExtractionStageAndKeepsPreviousPackIntact() {
        val privateFiles = temporaryFolder.newFolder("private")
        val path = "sample_engine/test-car/load.wav"
        val originalWav = pcm16Wav(sampleRate = 44_100, channels = 1, frames = 40)
        val originalManifest = manifestBytes("test-car", 1, path, originalWav)
        BydAudioPackImporter(privateFiles).importFrom(
            ByteArrayInputStream(packBytes(originalManifest, mapOf(path to originalWav))),
        )

        val capacityChecks = AtomicInteger()
        val importer = BydAudioPackImporter(
            privateFilesDirectory = privateFiles,
            capacityProvider = BydAudioPackCapacityProvider {
                if (capacityChecks.incrementAndGet() == 1) Long.MAX_VALUE else 1L
            },
        )
        val replacementWav = pcm16Wav(sampleRate = 48_000, channels = 2, frames = 48)
        val replacementManifest = manifestBytes("test-car", 2, path, replacementWav)
        val stages = mutableListOf<BydAudioPackImportStage>()

        val error = assertThrows(BydAudioPackStorageException::class.java) {
            importer.importFrom(
                input = ByteArrayInputStream(packBytes(replacementManifest, mapOf(path to replacementWav))),
                sourceBytes = 512L,
                observer = BydAudioPackImportObserver { progress -> stages += progress.stage },
            )
        }

        assertEquals(BydAudioPackImportStage.EXTRACT_WAV, error.stage)
        assertEquals(BydAudioPackImportStage.EXTRACT_WAV, stages.last())
        assertTrue(error.message.orEmpty().contains("incoming archive"))
        assertNotNull(BydAudioPackStore(privateFiles).find(EngineAudioPackRequirement("test-car", 1, sha256(originalManifest))))
        assertNull(BydAudioPackStore(privateFiles).find(EngineAudioPackRequirement("test-car", 2, sha256(replacementManifest))))
    }

    @Test
    fun canceledReplacementLeavesThePreviousReadyPackUntouched() {
        val privateFiles = temporaryFolder.newFolder("private")
        val importer = BydAudioPackImporter(privateFiles)
        val path = "sample_engine/test-car/load.wav"
        val originalWav = pcm16Wav(sampleRate = 44_100, channels = 1, frames = 40)
        val originalManifest = manifestBytes("test-car", 1, path, originalWav)
        importer.importFrom(ByteArrayInputStream(packBytes(originalManifest, mapOf(path to originalWav))))

        val canceled = object : InputStream() {
            override fun read(): Int = throw InterruptedIOException("USB copy canceled")
        }
        assertThrows(InterruptedIOException::class.java) { importer.importFrom(canceled) }

        val installed = requireNotNull(
            BydAudioPackStore(privateFiles).find(EngineAudioPackRequirement("test-car", 1, sha256(originalManifest))),
        )
        assertArrayEquals(originalWav, installed.open(path).use { it.readBytes() })
    }

    @Test
    fun importerRejectsUnsafeUnlistedAndMetadataMismatchedMembers() {
        val privateFiles = temporaryFolder.newFolder("private")
        val importer = BydAudioPackImporter(privateFiles)
        val wav = pcm16Wav(sampleRate = 48_000, channels = 2, frames = 40)
        val path = "sample_engine/test_car/load.wav"
        val manifest = manifestBytes("test-car", 1, path, wav)

        val unlisted = assertThrows(BydAudioPackValidationException::class.java) {
            importer.importFrom(
                ByteArrayInputStream(
                    packBytes(manifest, mapOf(path to wav, "sample_engine/test_car/extra.wav" to wav)),
                ),
            )
        }
        assertTrue(unlisted.message.orEmpty().contains("exactly match"))

        val unsafe = assertThrows(BydAudioPackValidationException::class.java) {
            importer.importFrom(
                ByteArrayInputStream(packBytes(manifest, mapOf(path to wav, "../outside.wav" to wav))),
            )
        }
        assertTrue(unsafe.message.orEmpty().contains("unsafe"))

        val wrongMetadata = manifestBytes(
            packId = "test-car",
            packVersion = 1,
            path = path,
            wav = wav,
            declaredSampleRate = 44_100,
        )
        val mismatch = assertThrows(BydAudioPackValidationException::class.java) {
            importer.importFrom(ByteArrayInputStream(packBytes(wrongMetadata, mapOf(path to wav))))
        }
        assertTrue(mismatch.message.orEmpty().contains("metadata does not match"))
    }

    @Test
    fun manifestParserRejectsDuplicateAndUnknownKeys() {
        val duplicate = """
            {"schemaVersion":1,"schemaVersion":1,"packId":"test-car","packVersion":1,"files":[]}
        """.trimIndent().toByteArray()
        assertThrows(PackJsonException::class.java) { BydAudioPackManifest.parse(duplicate) }

        val unknown = """
            {"schemaVersion":1,"packId":"test-car","packVersion":1,"files":[],"surprise":true}
        """.trimIndent().toByteArray()
        assertThrows(IllegalArgumentException::class.java) { BydAudioPackManifest.parse(unknown) }
    }

    @Test
    fun rendererLoadsProfileAndSharedWavsThroughTheAssetSourceAbstraction() {
        val wav = pcm16Wav(sampleRate = 44_100, channels = 2, frames = 40)
        val openedPaths = linkedSetOf<String>()
        val source = AudioAssetSource { path ->
            openedPaths += path
            ByteArrayInputStream(wav)
        }

        val renderer = SampleEngineRenderer.load(
            assetSource = source,
            outputSampleRate = 48_000,
            profile = EngineSampleProfiles.default,
        )

        assertEquals(
            EngineSampleProfiles.default.loopLayersForLoad(loadOnlyProgram = true).size,
            renderer.diagnostics().loadedLoops,
        )
        assertTrue(openedPaths.any { path -> path.startsWith("sample_engine/${EngineSampleProfiles.default.assetDirectory}/") })
        assertTrue(openedPaths.any { path -> path.startsWith("sample_engine/shared/") })
    }

    @Test
    fun externalProfileSourceDoesNotInterceptBundledSharedEffectsOrSimilarPrefixes() {
        val externalPaths = mutableListOf<String>()
        val bundledPaths = mutableListOf<String>()
        val source = ProfileAudioAssetSource(
            profilePrefix = "sample_engine/car/",
            profileSource = AudioAssetSource { path ->
                externalPaths += path
                ByteArrayInputStream(byteArrayOf(1))
            },
            bundledSource = AudioAssetSource { path ->
                bundledPaths += path
                ByteArrayInputStream(byteArrayOf(2))
            },
        )

        assertEquals(1, source.open("sample_engine/car/load.wav").use { it.read() })
        assertEquals(2, source.open("sample_engine/shared/pop.wav").use { it.read() })
        assertEquals(2, source.open("sample_engine/car_extra/load.wav").use { it.read() })
        assertEquals(listOf("sample_engine/car/load.wav"), externalPaths)
        assertEquals(
            listOf("sample_engine/shared/pop.wav", "sample_engine/car_extra/load.wav"),
            bundledPaths,
        )
    }

    @Test
    fun importerMatchesRendererToleranceForAFinalPartialStereoFrame() {
        val privateFiles = temporaryFolder.newFolder("private")
        val aligned = pcm16Wav(sampleRate = 44_100, channels = 2, frames = 40)
        val wav = aligned.copyOf(aligned.size + Short.SIZE_BYTES).apply {
            writeInt32LeAt(4, readInt32Le(this, 4) + Short.SIZE_BYTES)
            writeInt32LeAt(40, readInt32Le(this, 40) + Short.SIZE_BYTES)
        }
        val path = "sample_engine/partial-stereo/load.wav"
        val manifest = manifestBytes("partial-stereo", 1, path, wav)

        BydAudioPackImporter(privateFiles).importFrom(
            ByteArrayInputStream(packBytes(manifest, mapOf(path to wav))),
        )

        assertEquals(40, WavPcmDecoder.decode(ByteArrayInputStream(wav)).frameCount)
    }

    @Test
    fun importerRejectsWavThatTheRendererCannotDecodeBecauseDataPrecedesFormat() {
        val privateFiles = temporaryFolder.newFolder("private")
        val wav = pcm16WavWithDataBeforeFormat(sampleRate = 44_100, channels = 2, frames = 40)
        val path = "sample_engine/data-first/load.wav"
        val manifest = manifestBytesForMetadata(
            packId = "data-first",
            packVersion = 1,
            path = path,
            wav = wav,
            sampleRate = 44_100,
            channels = 2,
            frames = 40,
        )

        val error = assertThrows(BydAudioPackValidationException::class.java) {
            BydAudioPackImporter(privateFiles).importFrom(
                ByteArrayInputStream(packBytes(manifest, mapOf(path to wav))),
            )
        }

        assertTrue(error.message.orEmpty().contains("data appeared before"))
        assertTrue(BydAudioPackStore(privateFiles).installed().isEmpty())
    }

    @Test
    fun storeRecoversAValidPackMovedToTheTransactionBackup() {
        val privateFiles = temporaryFolder.newFolder("private")
        val wav = pcm16Wav(sampleRate = 48_000, channels = 1, frames = 40)
        val path = "sample_engine/recovery/load.wav"
        val manifest = manifestBytes("recovery", 1, path, wav)
        val requirement = EngineAudioPackRequirement("recovery", 1, sha256(manifest))
        BydAudioPackImporter(privateFiles).importFrom(
            ByteArrayInputStream(packBytes(manifest, mapOf(path to wav))),
        )
        val pack = requireNotNull(BydAudioPackStore(privateFiles).find(requirement))
        val backupRoot = java.io.File(privateFiles, "${BydAudioPackStore.ROOT_DIRECTORY}/.backup").apply {
            assertTrue(isDirectory || mkdirs())
        }
        val backup = java.io.File(backupRoot, pack.rootDirectory.name)
        assertTrue(pack.rootDirectory.renameTo(backup))

        val recovered = BydAudioPackStore(privateFiles).find(requirement)

        assertNotNull(recovered)
        assertFalse(backup.exists())
        assertArrayEquals(wav, requireNotNull(recovered).open(path).use { it.readBytes() })
    }

    @Test
    fun recoveryDeletesInvalidInstalledDirectoriesThatWouldOtherwiseLeakPrivateStorage() {
        val privateFiles = temporaryFolder.newFolder("private")
        val invalid = java.io.File(
            privateFiles,
            "${BydAudioPackStore.ROOT_DIRECTORY}/installed/incomplete--v1--${"a".repeat(64)}",
        ).apply { assertTrue(mkdirs()) }
        java.io.File(invalid, "orphan.wav").writeBytes(ByteArray(128 * 1024))

        assertTrue(BydAudioPackStore(privateFiles).installed().isEmpty())

        assertFalse(invalid.exists())
    }

    @Test
    fun directorySyncFailureAfterInstallRenameRecoversToACompleteOldOrNewPack() {
        val privateFiles = temporaryFolder.newFolder("private")
        val path = "sample_engine/durable/load.wav"
        val originalWav = pcm16Wav(sampleRate = 44_100, channels = 1, frames = 40)
        val originalManifest = manifestBytes("durable", 1, path, originalWav)
        BydAudioPackImporter(privateFiles).importFrom(
            ByteArrayInputStream(packBytes(originalManifest, mapOf(path to originalWav))),
        )
        val replacementWav = pcm16Wav(sampleRate = 48_000, channels = 2, frames = 48)
        val replacementManifest = manifestBytes("durable", 2, path, replacementWav)
        val operations = FaultAfterInstallRenameFileOperations()

        assertThrows(java.io.IOException::class.java) {
            BydAudioPackImporter(
                privateFilesDirectory = privateFiles,
                fileOperations = operations,
            ).importFrom(ByteArrayInputStream(packBytes(replacementManifest, mapOf(path to replacementWav))))
        }

        val recovered = BydAudioPackStore(privateFiles).installed()
        assertTrue(recovered.isNotEmpty())
        recovered.forEach { pack ->
            val bytes = pack.open(path).use(InputStream::readBytes)
            assertTrue(bytes.contentEquals(originalWav) || bytes.contentEquals(replacementWav))
        }
        assertTrue(
            recovered.any { pack ->
                pack.manifest.requirement() == EngineAudioPackRequirement("durable", 1, sha256(originalManifest))
            } || recovered.any { pack ->
                pack.manifest.requirement() == EngineAudioPackRequirement("durable", 2, sha256(replacementManifest))
            },
        )
    }

    @Test
    fun importerStopsEnumeratingWhenTheMemberLimitIsExceeded() {
        val privateFiles = temporaryFolder.newFolder("private")
        val wav = pcm16Wav(sampleRate = 48_000, channels = 1, frames = 40)
        val path = "sample_engine/member-limit/load.wav"
        val manifest = manifestBytes("member-limit", 1, path, wav)
        val importer = BydAudioPackImporter(
            privateFilesDirectory = privateFiles,
            limits = BydAudioPackLimits(maximumMemberCount = 1),
        )

        val error = assertThrows(BydAudioPackValidationException::class.java) {
            importer.importFrom(ByteArrayInputStream(packBytes(manifest, mapOf(path to wav))))
        }

        assertTrue(error.message.orEmpty().contains("too many members"))
        assertTrue(BydAudioPackStore(privateFiles).installed().isEmpty())
    }

    @Test
    fun preflightRejectsTwoThousandFortyNineMembersBeforeZipFileEnumeration() {
        val privateFiles = temporaryFolder.newFolder("private")

        val error = assertThrows(BydAudioPackValidationException::class.java) {
            BydAudioPackImporter(privateFiles).importFrom(
                ByteArrayInputStream(packWithEmptyEntries(BydAudioPackLimits().maximumMemberCount + 1)),
            )
        }

        assertTrue(error.message.orEmpty().contains("too many members"))
        assertTrue(BydAudioPackStore(privateFiles).installed().isEmpty())
    }

    @Test
    fun preflightRejectsCentralDirectoryPastItsMetadataLimit() {
        val privateFiles = temporaryFolder.newFolder("private")
        val wav = pcm16Wav(sampleRate = 48_000, channels = 1, frames = 40)
        val path = "sample_engine/central-limit/load.wav"
        val manifest = manifestBytes("central-limit", 1, path, wav)
        val importer = BydAudioPackImporter(
            privateFilesDirectory = privateFiles,
            limits = BydAudioPackLimits(maximumCentralDirectoryBytes = 46L),
        )

        val error = assertThrows(BydAudioPackValidationException::class.java) {
            importer.importFrom(ByteArrayInputStream(packBytes(manifest, mapOf(path to wav))))
        }

        assertTrue(error.message.orEmpty().contains("central directory exceeds"))
    }

    @Test
    fun preflightRejectsCorruptZip64SentinelsWithoutOpeningZipFile() {
        val privateFiles = temporaryFolder.newFolder("private")
        val archive = packWithEmptyEntries(1)
        val eocd = findEocdOffset(archive)
        archive.writeUInt16LeAt(eocd + 8, 0xffff)
        archive.writeUInt16LeAt(eocd + 10, 0xffff)
        archive.writeUInt32LeAt(eocd + 12, 0xffff_ffffL)
        archive.writeUInt32LeAt(eocd + 16, 0xffff_ffffL)

        val error = assertThrows(BydAudioPackValidationException::class.java) {
            BydAudioPackImporter(privateFiles).importFrom(ByteArrayInputStream(archive))
        }

        assertTrue(error.message.orEmpty().contains("ZIP64 end locator"))
    }

    @Test
    fun preflightRejectsUnsignedZip64OffsetOverflow() {
        val privateFiles = temporaryFolder.newFolder("private")
        val ordinary = packWithEmptyEntries(1)
        val oldEocd = findEocdOffset(ordinary)
        val archive = ByteArray(oldEocd + 56 + 20 + 22)
        ordinary.copyInto(archive, endIndex = oldEocd)
        val zip64 = oldEocd
        archive.writeUInt32LeAt(zip64, 0x0606_4b50L)
        archive.writeUInt64LeAt(zip64 + 4, 44L)
        archive.writeUInt32LeAt(zip64 + 16, 0L)
        archive.writeUInt32LeAt(zip64 + 20, 0L)
        archive.writeUInt64LeAt(zip64 + 24, 1L)
        archive.writeUInt64LeAt(zip64 + 32, 1L)
        archive.writeUInt64LeAt(zip64 + 40, ordinary.readUInt32LeAt(oldEocd + 12))
        archive.writeUInt64LeAt(zip64 + 48, Long.MIN_VALUE)
        val locator = zip64 + 56
        archive.writeUInt32LeAt(locator, 0x0706_4b50L)
        archive.writeUInt32LeAt(locator + 4, 0L)
        archive.writeUInt64LeAt(locator + 8, zip64.toLong())
        archive.writeUInt32LeAt(locator + 16, 1L)
        val eocd = locator + 20
        ordinary.copyInto(archive, destinationOffset = eocd, startIndex = oldEocd, endIndex = oldEocd + 22)
        archive.writeUInt16LeAt(eocd + 8, 0xffff)
        archive.writeUInt16LeAt(eocd + 10, 0xffff)
        archive.writeUInt32LeAt(eocd + 12, 0xffff_ffffL)
        archive.writeUInt32LeAt(eocd + 16, 0xffff_ffffL)

        val error = assertThrows(BydAudioPackValidationException::class.java) {
            BydAudioPackImporter(privateFiles).importFrom(ByteArrayInputStream(archive))
        }

        assertTrue(error.message.orEmpty().contains("overflows"))
    }

    @Test
    fun installedPackLookupDoesNotWaitForAnotherLargePackToFinishCopying() {
        val privateFiles = temporaryFolder.newFolder("private")
        val importer = BydAudioPackImporter(privateFiles)
        val installedWav = pcm16Wav(sampleRate = 48_000, channels = 1, frames = 40)
        val installedPath = "sample_engine/installed/load.wav"
        val installedManifest = manifestBytes("installed", 1, installedPath, installedWav)
        val requirement = EngineAudioPackRequirement("installed", 1, sha256(installedManifest))
        importer.importFrom(
            ByteArrayInputStream(packBytes(installedManifest, mapOf(installedPath to installedWav))),
        )

        val pendingWav = pcm16Wav(sampleRate = 44_100, channels = 2, frames = 48)
        val pendingPath = "sample_engine/pending/load.wav"
        val pendingManifest = manifestBytes("pending", 1, pendingPath, pendingWav)
        val copyStarted = CountDownLatch(1)
        val releaseCopy = CountDownLatch(1)
        val importFailure = AtomicReference<Throwable?>(null)
        val gatedInput = gateBeforeFirstRead(
            packBytes(pendingManifest, mapOf(pendingPath to pendingWav)),
            copyStarted,
            releaseCopy,
        )
        val importThread = Thread {
            runCatching { importer.importFrom(gatedInput) }
                .exceptionOrNull()
                ?.let(importFailure::set)
        }.apply { start() }

        try {
            assertTrue(copyStarted.await(2, TimeUnit.SECONDS))
            val lookupStarted = System.nanoTime()
            val found = BydAudioPackStore(privateFiles).find(requirement)
            val lookupElapsedMs = (System.nanoTime() - lookupStarted) / 1_000_000L

            assertNotNull(found)
            assertTrue("lookup took ${lookupElapsedMs}ms", lookupElapsedMs < 500L)
        } finally {
            releaseCopy.countDown()
            importThread.join(2_000L)
        }
        assertFalse(importThread.isAlive)
        assertNull(importFailure.get())
    }

    private fun manifestBytes(
        packId: String,
        packVersion: Int,
        path: String,
        wav: ByteArray,
        declaredSha256: String = sha256(wav),
        declaredSampleRate: Int = readInt32Le(wav, 24),
    ): ByteArray {
        val channels = readInt16Le(wav, 22)
        val dataBytes = readInt32Le(wav, 40)
        val frames = dataBytes / (channels * Short.SIZE_BYTES)

        return """
            {"schemaVersion":1,"packId":"$packId","packVersion":$packVersion,"files":[{"path":"$path","sizeBytes":${wav.size},"sha256":"$declaredSha256","sampleRate":$declaredSampleRate,"channels":$channels,"frameCount":$frames}]}
        """.trimIndent().toByteArray()
    }

    private fun packBytes(manifest: ByteArray, files: Map<String, ByteArray>): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry(BydAudioPackManifest.MANIFEST_NAME))
            zip.write(manifest)
            zip.closeEntry()
            files.forEach { (path, bytes) ->
                zip.putNextEntry(ZipEntry(path))
                zip.write(bytes)
                zip.closeEntry()
            }
        }

        return output.toByteArray()
    }

    private fun packWithEmptyEntries(count: Int): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            repeat(count) { index ->
                zip.putNextEntry(ZipEntry("entry-$index"))
                zip.closeEntry()
            }
        }

        return output.toByteArray()
    }

    private fun findEocdOffset(bytes: ByteArray): Int {
        for (index in bytes.size - 22 downTo 0) {
            if (bytes.readUInt32LeAt(index) == 0x0605_4b50L) return index
        }
        error("Test ZIP has no EOCD")
    }

    private fun ByteArray.readUInt32LeAt(offset: Int): Long =
        (this[offset].toLong() and 0xffL) or
            ((this[offset + 1].toLong() and 0xffL) shl 8) or
            ((this[offset + 2].toLong() and 0xffL) shl 16) or
            ((this[offset + 3].toLong() and 0xffL) shl 24)

    private fun ByteArray.writeUInt16LeAt(offset: Int, value: Int) {
        this[offset] = value.toByte()
        this[offset + 1] = (value ushr 8).toByte()
    }

    private fun ByteArray.writeUInt32LeAt(offset: Int, value: Long) {
        repeat(4) { index -> this[offset + index] = (value ushr (index * 8)).toByte() }
    }

    private fun ByteArray.writeUInt64LeAt(offset: Int, value: Long) {
        repeat(8) { index -> this[offset + index] = (value ushr (index * 8)).toByte() }
    }

    private class FaultAfterInstallRenameFileOperations : BydAudioPackFileOperations {
        private var failNextInstalledSync = false

        override fun ensureDirectory(directory: java.io.File): Boolean =
            PlatformBydAudioPackFileOperations.ensureDirectory(directory)

        override fun move(source: java.io.File, destination: java.io.File): Boolean {
            val moved = PlatformBydAudioPackFileOperations.move(source, destination)
            if (moved && source.parentFile?.name == ".staging" && destination.parentFile?.name == "installed") {
                failNextInstalledSync = true
            }

            return moved
        }

        override fun deleteFile(file: java.io.File): Boolean =
            PlatformBydAudioPackFileOperations.deleteFile(file)

        override fun deleteTree(directory: java.io.File): Boolean =
            PlatformBydAudioPackFileOperations.deleteTree(directory)

        override fun syncDirectory(directory: java.io.File) {
            PlatformBydAudioPackFileOperations.syncDirectory(directory)
            if (failNextInstalledSync && directory.name == "installed") {
                failNextInstalledSync = false
                throw java.io.IOException("Injected power-loss boundary after install rename")
            }
        }
    }

    private fun manifestBytesForMetadata(
        packId: String,
        packVersion: Int,
        path: String,
        wav: ByteArray,
        sampleRate: Int,
        channels: Int,
        frames: Int,
    ): ByteArray = """
        {"schemaVersion":1,"packId":"$packId","packVersion":$packVersion,"files":[{"path":"$path","sizeBytes":${wav.size},"sha256":"${sha256(wav)}","sampleRate":$sampleRate,"channels":$channels,"frameCount":$frames}]}
    """.trimIndent().toByteArray()

    private fun pcm16Wav(sampleRate: Int, channels: Int, frames: Int): ByteArray {
        val pcmBytes = channels * frames * Short.SIZE_BYTES
        val output = ByteArrayOutputStream(44 + pcmBytes)
        output.writeAscii("RIFF")
        output.writeInt32Le(36 + pcmBytes)
        output.writeAscii("WAVE")
        output.writeAscii("fmt ")
        output.writeInt32Le(16)
        output.writeInt16Le(1)
        output.writeInt16Le(channels)
        output.writeInt32Le(sampleRate)
        output.writeInt32Le(sampleRate * channels * Short.SIZE_BYTES)
        output.writeInt16Le(channels * Short.SIZE_BYTES)
        output.writeInt16Le(16)
        output.writeAscii("data")
        output.writeInt32Le(pcmBytes)
        repeat(frames * channels) { index -> output.writeInt16Le(index * 97 - 12_000) }

        return output.toByteArray()
    }

    private fun pcm16WavWithDataBeforeFormat(sampleRate: Int, channels: Int, frames: Int): ByteArray {
        val pcmBytes = channels * frames * Short.SIZE_BYTES
        val output = ByteArrayOutputStream(44 + pcmBytes)
        output.writeAscii("RIFF")
        output.writeInt32Le(36 + pcmBytes)
        output.writeAscii("WAVE")
        output.writeAscii("data")
        output.writeInt32Le(pcmBytes)
        repeat(frames * channels) { index -> output.writeInt16Le(index * 97 - 12_000) }
        output.writeAscii("fmt ")
        output.writeInt32Le(16)
        output.writeInt16Le(1)
        output.writeInt16Le(channels)
        output.writeInt32Le(sampleRate)
        output.writeInt32Le(sampleRate * channels * Short.SIZE_BYTES)
        output.writeInt16Le(channels * Short.SIZE_BYTES)
        output.writeInt16Le(16)

        return output.toByteArray()
    }

    private fun gateBeforeFirstRead(
        bytes: ByteArray,
        copyStarted: CountDownLatch,
        releaseCopy: CountDownLatch,
    ): InputStream {
        val delegate = ByteArrayInputStream(bytes)
        var firstRead = true

        return object : InputStream() {
            override fun read(): Int {
                awaitGate()

                return delegate.read()
            }

            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                awaitGate()

                return delegate.read(buffer, offset, length)
            }

            private fun awaitGate() {
                if (!firstRead) return
                firstRead = false
                copyStarted.countDown()
                releaseCopy.await()
            }
        }
    }

    private fun ByteArrayOutputStream.writeAscii(value: String) = write(value.toByteArray(Charsets.US_ASCII))

    private fun ByteArrayOutputStream.writeInt16Le(value: Int) {
        write(value and 0xff)
        write((value ushr 8) and 0xff)
    }

    private fun ByteArrayOutputStream.writeInt32Le(value: Int) {
        repeat(4) { shift -> write((value ushr (shift * 8)) and 0xff) }
    }

    private fun readInt16Le(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or ((bytes[offset + 1].toInt() and 0xff) shl 8)

    private fun readInt32Le(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or
            ((bytes[offset + 1].toInt() and 0xff) shl 8) or
            ((bytes[offset + 2].toInt() and 0xff) shl 16) or
            ((bytes[offset + 3].toInt() and 0xff) shl 24)

    private fun ByteArray.writeInt32LeAt(offset: Int, value: Int) {
        repeat(4) { shift -> this[offset + shift] = (value ushr (shift * 8)).toByte() }
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).toHex()
}
