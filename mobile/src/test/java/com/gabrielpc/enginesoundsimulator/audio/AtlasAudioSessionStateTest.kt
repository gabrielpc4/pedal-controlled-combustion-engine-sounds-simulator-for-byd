package com.gabrielpc.enginesoundsimulator.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AtlasAudioSessionStateTest {
    @Test
    fun cameraAndModeSwitchRestartOnlySelectedEngineAndPreserveSharedOwnersAndTails() {
        val session = session()
        val cabin = session.selectPerspective(EngineSoundPerspective.CABIN)
        assertTrue(session.consumeSelectedEngineEventStart(cabin.rendererId))
        assertFalse(session.consumeSelectedEngineEventStart(cabin.rendererId))
        val engineInt = requireNotNull(session.activationFor(ENGINE_INT))
        val transmission = requireNotNull(session.activationFor(TRANSMISSION))
        assertSame(transmission, session.ensurePersistentActivation(TRANSMISSION, cabin.rendererId))
        session.markContinuousOwnerActive(transmission, true)
        session.advanceContinuousClock(transmission, TRANSMISSION_BINDING, 4_800)
        val gear = (session.tryStartFiniteActivation(GEAR_INT, cabin.rendererId) as
            AtlasFiniteEventStartResult.Started).activation
        val gearTail = session.retainFiniteTail(gear, tailId = 7L)
        session.advanceFiniteTail(gearTail, 960)

        session.selectProgramMode(PrimaryEngineLayerSource.COAST)
        session.selectProgramMode(PrimaryEngineLayerSource.FMOD_MIX)
        assertFalse(session.consumeSelectedEngineEventStart(cabin.rendererId))
        assertEquals(engineInt, session.activationFor(ENGINE_INT))
        assertEquals(transmission, session.activationFor(TRANSMISSION))

        val exterior = session.selectPerspective(EngineSoundPerspective.EXTERIOR)
        assertTrue(session.consumeSelectedEngineEventStart(exterior.rendererId))
        val engineExt = requireNotNull(session.activationFor(ENGINE_EXT))
        assertEquals(1L, engineExt.generation)
        assertFalse(session.acceptsSemanticUpdates(ENGINE_INT, cabin.rendererId))
        assertTrue(session.acceptsSemanticUpdates(ENGINE_EXT, exterior.rendererId))
        assertFalse(session.acceptsSemanticUpdates(TRANSMISSION, cabin.rendererId))
        assertTrue(session.acceptsSemanticUpdates(TRANSMISSION, exterior.rendererId))
        assertSame(transmission, session.ensurePersistentActivation(TRANSMISSION, exterior.rendererId))
        assertEquals(4_800L, session.continuousClockFrames(transmission, TRANSMISSION_BINDING))
        session.markContinuousOwnerActive(transmission, false)
        assertSame(transmission, session.activationFor(TRANSMISSION))
        assertTrue(gearTail in session.activeFiniteTails())
        assertEquals(960L, session.finiteTailPlaybackFrame(gearTail))
        val exteriorGear = (session.tryStartFiniteActivation(GEAR_EXT, exterior.rendererId) as
            AtlasFiniteEventStartResult.Started).activation
        val exteriorGearTail = session.retainFiniteTail(exteriorGear, tailId = 8L)
        assertTrue(gearTail in session.activeFiniteTails())
        assertTrue(exteriorGearTail in session.activeFiniteTails())

        val cabinAgain = session.selectPerspective(EngineSoundPerspective.CABIN)
        assertEquals(2L, session.activationFor(ENGINE_INT)?.generation)
        assertTrue(session.acceptsSemanticUpdates(ENGINE_INT, cabinAgain.rendererId))
        assertEquals(transmission, session.activationFor(TRANSMISSION))
        assertTrue(gearTail in session.activeFiniteTails())
    }

    @Test
    fun rapidShiftOnPlayingReusableInstanceIsRejectedUntilNaturalTailCompletion() {
        val session = session()
        val cabin = session.selectPerspective(EngineSoundPerspective.CABIN)
        val first = (session.tryStartFiniteActivation(GEAR_INT, cabin.rendererId) as
            AtlasFiniteEventStartResult.Started).activation
        val tail = session.retainFiniteTail(first, tailId = 1L)

        assertEquals(
            AtlasFiniteEventStartResult.InFlight,
            session.tryStartFiniteActivation(GEAR_INT, cabin.rendererId),
        )

        session.completeFiniteTail(tail)
        val second = (session.tryStartFiniteActivation(GEAR_INT, cabin.rendererId) as
            AtlasFiniteEventStartResult.Started).activation
        assertEquals(2L, second.generation)
    }

    @Test
    fun backfireGlobalPlayingGuardSurvivesCameraSwitch() {
        val session = session()
        val cabin = session.selectPerspective(EngineSoundPerspective.CABIN)
        val cabinBackfire = (session.tryStartFiniteActivation(
            BACKFIRE_INT,
            cabin.rendererId,
            mutualExclusionGroup = "host-backfire-instance-set",
        ) as AtlasFiniteEventStartResult.Started).activation
        val tail = session.retainFiniteTail(cabinBackfire, tailId = 4L)
        val exterior = session.selectPerspective(EngineSoundPerspective.EXTERIOR)

        assertEquals(
            AtlasFiniteEventStartResult.InFlight,
            session.tryStartFiniteActivation(
                BACKFIRE_EXT,
                exterior.rendererId,
                mutualExclusionGroup = "host-backfire-instance-set",
            ),
        )

        session.completeFiniteTail(tail)
        assertTrue(session.tryStartFiniteActivation(
            BACKFIRE_EXT,
            exterior.rendererId,
            mutualExclusionGroup = "host-backfire-instance-set",
        ) is AtlasFiniteEventStartResult.Started)
    }

    @Test
    fun playSequentialResetsPerActivationWhileSchedulerMemorySurvivesRendererConstruction() {
        val session = session()
        session.selectPerspective(EngineSoundPerspective.CABIN)
        val state = session.schedulerState(GEAR_INT, "multi:gear")
        val firstRendererScheduler = scheduler(state)
        firstRendererScheduler.enterActivation(1L)
        assertEquals(0, firstRendererScheduler.selectMember())
        assertEquals(1, firstRendererScheduler.selectMember())

        val replacementRendererScheduler = scheduler(
            session.schedulerState(GEAR_INT, "multi:gear"),
        )
        replacementRendererScheduler.enterActivation(1L)
        assertEquals(2, replacementRendererScheduler.selectMember())
        replacementRendererScheduler.enterActivation(2L)
        assertEquals(0, replacementRendererScheduler.selectMember())
    }

    @Test
    fun blockedPerBindingLifecycleCannotCreateExecutableSession() {
        assertThrows(IllegalArgumentException::class.java) {
            AtlasAudioSessionState(
                atlasFamilyId = "blocked",
                eventContracts = listOf(
                    AtlasAudioSessionEventContract(
                        eventPath = ENGINE_INT,
                        owner = AtlasEventInstanceOwner.SELECTED_PERSPECTIVE_ENGINE,
                        perspectives = setOf(EngineSoundPerspective.CABIN),
                        runtimeExecutable = false,
                    ),
                ),
            )
        }
    }

    @Test
    fun continuousRestartRetainsActivationWhileItsFiniteTailIsStillFinishing() {
        val session = session()
        val cabin = session.selectPerspective(EngineSoundPerspective.CABIN)
        val limiter = session.ensurePersistentActivation(LIMITER, cabin.rendererId)
        session.markContinuousOwnerActive(limiter, true)
        val tail = session.retainFiniteTail(limiter, tailId = 9L)

        session.markContinuousOwnerActive(limiter, false)
        assertSame(limiter, session.activationFor(LIMITER))
        session.markContinuousOwnerActive(limiter, true)
        session.completeFiniteTail(tail)
        assertSame(limiter, session.activationFor(LIMITER))

        session.markContinuousOwnerActive(limiter, false)
        assertEquals(null, session.activationFor(LIMITER))
    }

    @Test
    fun transmissionAndTractionOwnersSurviveButOnlySelectedCameraAcceptsUpdates() {
        val session = AtlasAudioSessionState(
            atlasFamilyId = "test-family",
            eventContracts = listOf(
                contract(
                    ENGINE_INT,
                    AtlasEventInstanceOwner.SELECTED_PERSPECTIVE_ENGINE,
                    EngineSoundPerspective.CABIN,
                ),
                contract(
                    ENGINE_EXT,
                    AtlasEventInstanceOwner.SELECTED_PERSPECTIVE_ENGINE,
                    EngineSoundPerspective.EXTERIOR,
                ),
                contract(
                    TRANSMISSION_INT,
                    AtlasEventInstanceOwner.PROFILE_AUDIO_SESSION_PERSISTENT,
                    EngineSoundPerspective.CABIN,
                ).copy(
                    profileSessionContinuousOwner = true,
                    authoredBindingKeys = setOf(TRANSMISSION_BINDING),
                ),
                contract(
                    TRANSMISSION_EXT,
                    AtlasEventInstanceOwner.PROFILE_AUDIO_SESSION_PERSISTENT,
                    EngineSoundPerspective.EXTERIOR,
                ).copy(
                    profileSessionContinuousOwner = true,
                    authoredBindingKeys = setOf(TRANSMISSION_EXT_BINDING),
                ),
                contract(
                    TRACTION_INT,
                    AtlasEventInstanceOwner.PROFILE_AUDIO_SESSION_PERSISTENT,
                    EngineSoundPerspective.CABIN,
                ).copy(profileSessionContinuousOwner = true),
                contract(
                    TRACTION_EXT,
                    AtlasEventInstanceOwner.PROFILE_AUDIO_SESSION_PERSISTENT,
                    EngineSoundPerspective.EXTERIOR,
                ).copy(profileSessionContinuousOwner = true),
            ),
        )
        val cabin = session.selectPerspective(EngineSoundPerspective.CABIN)
        val cabinTransmission = requireNotNull(session.activationFor(TRANSMISSION_INT))
        val cabinTraction = requireNotNull(session.activationFor(TRACTION_INT))
        session.markContinuousOwnerActive(cabinTransmission, true)
        session.advanceContinuousClock(cabinTransmission, TRANSMISSION_BINDING, 2_400)

        val exterior = session.selectPerspective(EngineSoundPerspective.EXTERIOR)

        assertSame(cabinTransmission, session.activationFor(TRANSMISSION_INT))
        assertFalse(session.acceptsSemanticUpdates(TRANSMISSION_INT, cabin.rendererId))
        assertTrue(session.acceptsSemanticUpdates(TRANSMISSION_EXT, exterior.rendererId))
        assertSame(cabinTraction, session.activationFor(TRACTION_INT))
        assertFalse(session.acceptsSemanticUpdates(TRACTION_INT, cabin.rendererId))
        assertTrue(session.acceptsSemanticUpdates(TRACTION_EXT, exterior.rendererId))
        assertThrows(IllegalArgumentException::class.java) {
            session.ensurePersistentActivation(TRANSMISSION_INT, exterior.rendererId)
        }

        val cabinAgain = session.selectPerspective(EngineSoundPerspective.CABIN)

        assertSame(cabinTransmission, session.activationFor(TRANSMISSION_INT))
        assertEquals(2_400L, session.continuousClockFrames(cabinTransmission, TRANSMISSION_BINDING))
        assertTrue(session.acceptsSemanticUpdates(TRANSMISSION_INT, cabinAgain.rendererId))
        assertFalse(session.acceptsSemanticUpdates(TRANSMISSION_EXT, exterior.rendererId))
        assertTrue(session.acceptsSemanticUpdates(TRACTION_INT, cabinAgain.rendererId))
        assertFalse(session.acceptsSemanticUpdates(TRACTION_EXT, exterior.rendererId))
    }

    @Test
    fun smartRandomSeedChangesOnlyForANewProfileAudioSessionGeneration() {
        val first = session(profileAudioSessionGeneration = 7L)
        val second = session(profileAudioSessionGeneration = 8L)
        val firstScheduler = smartRandomScheduler(first.schedulerState(GEAR_INT, "multi:gear"), 7L)
        val sameSessionReplacement = smartRandomScheduler(
            first.schedulerState(GEAR_INT, "multi:gear"),
            7L,
        )
        val nextSessionScheduler = smartRandomScheduler(
            second.schedulerState(GEAR_INT, "multi:gear"),
            8L,
        )
        firstScheduler.selectMember()
        sameSessionReplacement.selectMember()
        nextSessionScheduler.selectMember()

        assertNotEquals(firstScheduler.lastDraws[0], sameSessionReplacement.lastDraws[0])
        assertNotEquals(firstScheduler.lastDraws[0], nextSessionScheduler.lastDraws[0])
    }

    private fun session(profileAudioSessionGeneration: Long = 1L): AtlasAudioSessionState = AtlasAudioSessionState(
        atlasFamilyId = "test-family",
        eventContracts = listOf(
            contract(ENGINE_INT, AtlasEventInstanceOwner.SELECTED_PERSPECTIVE_ENGINE, EngineSoundPerspective.CABIN),
            contract(ENGINE_EXT, AtlasEventInstanceOwner.SELECTED_PERSPECTIVE_ENGINE, EngineSoundPerspective.EXTERIOR),
            contract(
                TRANSMISSION,
                AtlasEventInstanceOwner.PROFILE_AUDIO_SESSION_PERSISTENT,
                *EngineSoundPerspective.entries.toTypedArray(),
            ).copy(
                authoredBindingKeys = setOf(TRANSMISSION_BINDING),
                profileSessionContinuousOwner = true,
            ),
            contract(GEAR_INT, AtlasEventInstanceOwner.PROFILE_AUDIO_SESSION_PERSISTENT, EngineSoundPerspective.CABIN),
            contract(GEAR_EXT, AtlasEventInstanceOwner.PROFILE_AUDIO_SESSION_PERSISTENT, EngineSoundPerspective.EXTERIOR),
            contract(BACKFIRE_INT, AtlasEventInstanceOwner.PROFILE_AUDIO_SESSION_PERSISTENT, EngineSoundPerspective.CABIN),
            contract(BACKFIRE_EXT, AtlasEventInstanceOwner.PROFILE_AUDIO_SESSION_PERSISTENT, EngineSoundPerspective.EXTERIOR),
            contract(LIMITER, AtlasEventInstanceOwner.PROFILE_AUDIO_SESSION_PERSISTENT, EngineSoundPerspective.CABIN),
        ),
        profileAudioSessionGeneration = profileAudioSessionGeneration,
    )

    private fun contract(
        path: String,
        owner: AtlasEventInstanceOwner,
        vararg perspectives: EngineSoundPerspective,
    ) = AtlasAudioSessionEventContract(path, owner, perspectives.toSet())

    private fun scheduler(state: AtlasEffectSchedulerState): AtlasEffectScheduler = AtlasEffectScheduler(
        atlasFamilyId = "test-family",
        eventPath = GEAR_INT,
        profileAudioSessionGeneration = 1L,
        group = AtlasSchedulingGroup(
            id = "multi:gear",
            composition = AtlasSchedulingComposition.PLAYLIST_ALTERNATIVE,
            selectionKind = "fmodMultiInstrumentPlaylist",
            playMode = "PlaylistPlayMode_PlaySequential",
            playModeValue = 0,
            selectionMode = "PlaylistSelectionMode_SelectNormal",
            selectionModeValue = 1,
            groupTriggerChancePercent = 100.0,
            members = listOf(
                member("a", 0),
                member("b", 1),
                member("c", 2),
            ),
            timelinePlacements = emptyList(),
            maximumSourceCornerContributorsPerLogicalRing = 1,
            maximumFmodSourceChannelsPerLogicalRing = 1,
            maximumCaptureFramesPerLogicalRing = 1,
            streamingRingBufferFrames = 1,
            complete = true,
        ),
        state = state,
    )

    private fun smartRandomScheduler(
        state: AtlasEffectSchedulerState,
        profileAudioSessionGeneration: Long,
    ): AtlasEffectScheduler = AtlasEffectScheduler(
        atlasFamilyId = "test-family",
        eventPath = GEAR_INT,
        profileAudioSessionGeneration = profileAudioSessionGeneration,
        group = AtlasSchedulingGroup(
            id = "multi:gear",
            composition = AtlasSchedulingComposition.PLAYLIST_ALTERNATIVE,
            selectionKind = "fmodMultiInstrumentPlaylist",
            playMode = "PlaylistPlayMode_SmartRandom",
            playModeValue = 1,
            selectionMode = "PlaylistSelectionMode_SelectNormal",
            selectionModeValue = 1,
            groupTriggerChancePercent = 100.0,
            members = listOf(member("a", 0), member("b", 1), member("c", 2)),
            timelinePlacements = emptyList(),
            maximumSourceCornerContributorsPerLogicalRing = 1,
            maximumFmodSourceChannelsPerLogicalRing = 1,
            maximumCaptureFramesPerLogicalRing = 1,
            streamingRingBufferFrames = 1,
            complete = true,
        ),
        state = state,
    )

    private fun member(sourceGuid: String, order: Int) = AtlasSchedulingMember(
        sourceGuid = sourceGuid,
        authoredOrder = order,
        weight = 1.0,
        triggerChancePercent = 100.0,
    )

    private companion object {
        const val ENGINE_INT = "event:/cars/test/engine_int"
        const val ENGINE_EXT = "event:/cars/test/engine_ext"
        const val TRANSMISSION = "event:/cars/test/transmission"
        const val TRANSMISSION_INT = "event:/cars/test/transmission_int"
        const val TRANSMISSION_EXT = "event:/cars/test/transmission_ext"
        const val TRACTION_INT = "event:/cars/test/tractioncontrol_int"
        const val TRACTION_EXT = "event:/cars/test/tractioncontrol_ext"
        val TRANSMISSION_BINDING = "binding:" + "2".repeat(64)
        val TRANSMISSION_EXT_BINDING = "binding:" + "3".repeat(64)
        const val GEAR_INT = "event:/cars/test/gear_int"
        const val GEAR_EXT = "event:/cars/test/gear_ext"
        const val BACKFIRE_INT = "event:/cars/test/backfire_int"
        const val BACKFIRE_EXT = "event:/cars/test/backfire_ext"
        const val LIMITER = "event:/cars/test/limiter"
    }
}
