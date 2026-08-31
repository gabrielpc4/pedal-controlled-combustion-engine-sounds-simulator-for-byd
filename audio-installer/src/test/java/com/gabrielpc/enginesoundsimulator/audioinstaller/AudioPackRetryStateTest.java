package com.gabrielpc.enginesoundsimulator.audioinstaller;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.gabrielpc.audiopackcontract.AudioPackIdentity;
import org.junit.Test;

public final class AudioPackRetryStateTest {
    @Test
    public void actualInstalledIdentitySkipsOnlyItsCandidatesEvenWhenUsbFileWasMisnamed() {
        AudioPackIdentity expectedForFilename = identity("expected", 1);
        AudioPackIdentity actualInsideFile = identity("actual", 2);
        AudioPackRetryState state = new AudioPackRetryState();

        state.recordInstalled(actualInsideFile);

        assertTrue(state.needsAttempt(expectedForFilename));
        assertFalse(state.needsAttempt(actualInsideFile));
    }

    @Test
    public void resetAllowsAFreshInventoryDrivenRetry() {
        AudioPackIdentity identity = identity("family", 3);
        AudioPackRetryState state = new AudioPackRetryState();
        state.recordInstalled(identity);

        state.reset();

        assertTrue(state.needsAttempt(identity));
    }

    private static AudioPackIdentity identity(String packId, int version) {
        return new AudioPackIdentity(packId, version, String.format("%064x", version));
    }
}
