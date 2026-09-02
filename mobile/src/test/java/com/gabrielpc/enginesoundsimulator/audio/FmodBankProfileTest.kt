package com.gabrielpc.enginesoundsimulator.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FmodBankProfileTest {
    @Test
    fun everySelectableCarHasOneNativeBankProfileAndValidPresentationLimits() {
        assertEquals(58, FmodBankProfiles.all.size)
        assertEquals(FmodBankProfiles.all.size, FmodBankProfiles.all.map(FmodBankProfile::id).distinct().size)
        assertEquals(
            FmodBankProfiles.all.size,
            FmodBankProfiles.all.map(FmodBankProfile::previewAssetName).distinct().size,
        )
        FmodBankProfiles.all.forEach { profile ->
            assertTrue(profile.bankPackId.isNotBlank())
            assertTrue(profile.idleRpm in profile.minimumRpm..profile.redlineRpm)
            assertTrue(profile.redlineRpm <= profile.limiterRpm)
            assertTrue(profile.limiterRpm <= profile.maximumRpm)
            assertTrue(profile.upshiftRpm in profile.idleRpm..profile.redlineRpm)
            assertTrue(profile.gearRatios.zipWithNext().all { (left, right) -> left > right })
        }
    }

    @Test
    fun everyRuntimeProfileRequiresTheOriginalAssettoSharedBanks() {
        val carPackIds = FmodBankProfiles.all.map(FmodBankProfile::bankPackId).toSet()

        assertTrue(FmodBankProfiles.commonStringsPackId in FmodBankProfiles.requiredPackIds)
        assertTrue(FmodBankProfiles.commonPackId in FmodBankProfiles.requiredPackIds)
        assertEquals(carPackIds.size + 2, FmodBankProfiles.requiredPackIds.size)
    }

    @Test
    fun onlyVerifiedIdenticalBanksShareAnInstallerPayload() {
        val repeatedPackIds = FmodBankProfiles.all.groupBy(FmodBankProfile::bankPackId)
            .filterValues { it.size > 1 }
        assertEquals(
            setOf("lamborghini_aventador_sv_cabin", "nissan-350z"),
            repeatedPackIds.keys,
        )
        assertEquals(
            setOf("lamborghini_aventador_sv_cabin", "lexus-lfa-concept-gt500"),
            repeatedPackIds.getValue("lamborghini_aventador_sv_cabin").map(FmodBankProfile::id).toSet(),
        )
        assertEquals(
            setOf("nissan-350z", "nissan-370z-widebody"),
            repeatedPackIds.getValue("nissan-350z").map(FmodBankProfile::id).toSet(),
        )
    }

    @Test
    fun nativeMixerPreservesExactEventAndSoundOwnership() {
        val id = "event:/cars/alfa/engine_int\u001e4c_in_on_mid"
        val source = parseNativeVoiceSnapshots(
            arrayOf(
                listOf(
                    id,
                    "event:/cars/alfa/engine_int",
                    "engine_int",
                    "4c_in_on_mid",
                    "0.42",
                    "0.5",
                    "2",
                    "0",
                    "1",
                ).joinToString("\u001f"),
            ),
        ).single()

        assertEquals(id, source.id)
        assertEquals("engine_int", source.eventName)
        assertEquals("4c_in_on_mid", source.soundName)
        assertEquals(42, source.audibilityPercent)
        assertEquals(2, source.voiceCount)
        assertTrue(source.isActive)
    }

    @Test
    fun sourceControlsKeepExactStableIdsAcrossJni() {
        val controls = mapOf(
            "event:/cars/alfa/engine_int\u001e4c_in_on_mid" to SourceMixControl(
                gain = 1.75,
                muted = true,
                solo = false,
            ),
        )

        val fields = encodeNativeSourceControls(controls).single().split('\u001f')
        assertEquals(4, fields.size)
        assertEquals(controls.keys.single(), fields[0])
        assertEquals("1.75", fields[1])
        assertEquals("1", fields[2])
        assertEquals("0", fields[3])
    }
}
