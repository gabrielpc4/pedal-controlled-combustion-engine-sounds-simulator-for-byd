package com.gabrielpc.enginesoundsimulator.audio

import com.gabrielpc.audiopackcontract.AudioPackInstallContract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioPackInstallBatchStateTest {
    @Test
    fun successfulIdentityIsRejectedWhenItAppearsAgainInTheSameBatch() {
        val state = AudioPackInstallBatchState()
        val requirement = EngineAudioPackRequirement("family", 1, "a".repeat(64))
        state.begin()
        val generation = requireNotNull(state.activeGeneration())
        state.requireNotInstalled(generation, requirement)
        state.markInstalled(generation, requirement)

        val error = assertThrows(AudioPackCatalogValidationException::class.java) {
            state.requireNotInstalled(generation, requirement)
        }

        assertEquals(AudioPackInstallContract.ERROR_DUPLICATE_PACK, error.errorCode)
    }

    @Test
    fun failedAttemptCanRetryBecauseOnlyCommittedSuccessesAreRemembered() {
        val state = AudioPackInstallBatchState()
        val requirement = EngineAudioPackRequirement("family", 1, "b".repeat(64))
        state.begin()
        val generation = requireNotNull(state.activeGeneration())

        state.requireNotInstalled(generation, requirement)
        state.requireNotInstalled(generation, requirement)
    }

    @Test
    fun finishingBatchInvalidatesItsGeneration() {
        val state = AudioPackInstallBatchState()
        val requirement = EngineAudioPackRequirement("family", 1, "c".repeat(64))
        state.begin()
        val generation = requireNotNull(state.activeGeneration())
        state.finish()

        assertNull(state.activeGeneration())
        val error = assertThrows(AudioPackCatalogValidationException::class.java) {
            state.requireNotInstalled(generation, requirement)
        }
        assertEquals(AudioPackInstallContract.ERROR_BATCH_NOT_ACTIVE, error.errorCode)
    }

    @Test
    fun aSecondOwnerCannotReplaceAnActiveBatch() {
        val state = AudioPackInstallBatchState()
        val firstGeneration = state.begin()

        val error = assertThrows(IllegalStateException::class.java) { state.begin() }

        assertTrue(error.message.orEmpty().contains("already active"))
        assertEquals(firstGeneration, state.activeGeneration())
    }

    @Test
    fun ownerLossAbortsOnlyTheGenerationThatWasLost() {
        val state = AudioPackInstallBatchState()
        val firstGeneration = state.begin()
        state.abort(firstGeneration)
        val secondGeneration = state.begin()

        state.abort(firstGeneration)

        assertEquals(secondGeneration, state.activeGeneration())
    }
}
