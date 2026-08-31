package com.gabrielpc.audiopackcontract;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class AudioPackInstallContractTest {
    @Test
    public void progressIsExplicitlyStageLocalSoExtractionCanResetAfterArchiveCopy() {
        assertEquals(1_000, AudioPackInstallContract.stageProgressPermille(80L, 80L));
        assertEquals(0, AudioPackInstallContract.stageProgressPermille(0L, 240L));
        assertEquals(500, AudioPackInstallContract.stageProgressPermille(120L, 240L));
    }

    @Test
    public void unknownStageSizeKeepsTheProgressBarIndeterminate() {
        assertEquals(-1, AudioPackInstallContract.stageProgressPermille(0L, -1L));
        assertEquals(1_000, AudioPackInstallContract.stageProgressPermille(9L, 9L));
    }
}
