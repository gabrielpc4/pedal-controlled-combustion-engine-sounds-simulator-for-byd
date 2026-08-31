package com.gabrielpc.enginesoundsimulator.audio

import com.gabrielpc.audiopackcontract.AudioPackInstallContract

/** Single-client batch identity guard used by the signature-protected install service. */
internal class AudioPackInstallBatchState {
    private var generation = 0L
    private var active = false
    private val installed = linkedSetOf<EngineAudioPackRequirement>()

    @Synchronized
    fun begin(): Long {
        check(!active) { "Another audio-pack installation batch is already active" }
        generation += 1L
        active = true
        installed.clear()

        return generation
    }

    @Synchronized
    fun activeGeneration(): Long? = generation.takeIf { active }

    @Synchronized
    fun requireNotInstalled(batchGeneration: Long, requirement: EngineAudioPackRequirement) {
        if (!active || generation != batchGeneration) {
            throw AudioPackCatalogValidationException(
                AudioPackInstallContract.ERROR_BATCH_NOT_ACTIVE,
                "The installation batch is no longer active",
            )
        }
        if (requirement in installed) {
            throw AudioPackCatalogValidationException(
                AudioPackInstallContract.ERROR_DUPLICATE_PACK,
                "Duplicate ${requirement.packId} v${requirement.packVersion} in this USB batch",
            )
        }
    }

    @Synchronized
    fun markInstalled(batchGeneration: Long, requirement: EngineAudioPackRequirement) {
        check(active && generation == batchGeneration) { "The installation batch ended before commit" }
        check(installed.add(requirement)) { "The same audio pack was committed twice in one batch" }
    }

    @Synchronized
    fun finish() {
        check(active) { "No audio-pack installation batch is active" }
        active = false
        installed.clear()
    }

    @Synchronized
    fun abort(batchGeneration: Long? = null) {
        if (!active || (batchGeneration != null && generation != batchGeneration)) return
        active = false
        installed.clear()
    }

    @Synchronized
    fun isActive(): Boolean = active
}
