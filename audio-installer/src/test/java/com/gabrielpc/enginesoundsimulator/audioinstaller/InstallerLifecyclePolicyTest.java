package com.gabrielpc.enginesoundsimulator.audioinstaller;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class InstallerLifecyclePolicyTest {
    @Test
    public void stopKeepsTheConnectionButTrueDestroyCancelsAnActiveCopy() {
        assertTrue(InstallerLifecyclePolicy.retainConnectionOnStop());
        assertTrue(InstallerLifecyclePolicy.cancelOnDestroy(true));
        assertFalse(InstallerLifecyclePolicy.cancelOnDestroy(false));
    }

    @Test
    public void cancellationNeverFallsThroughToTheNextPackAfterALocalOpenOrBinderFailure() {
        assertFalse(InstallerLifecyclePolicy.continueAfterLocalSourceFailure(true));
        assertTrue(InstallerLifecyclePolicy.continueAfterLocalSourceFailure(false));
    }
}
