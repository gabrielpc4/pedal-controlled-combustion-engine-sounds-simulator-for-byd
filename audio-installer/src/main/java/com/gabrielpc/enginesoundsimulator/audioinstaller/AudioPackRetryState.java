package com.gabrielpc.enginesoundsimulator.audioinstaller;

import com.gabrielpc.audiopackcontract.AudioPackIdentity;
import java.util.HashSet;
import java.util.Set;

/** Tracks actual successful identities so duplicate USB candidates can be skipped safely. */
final class AudioPackRetryState {
    private final Set<String> installedIdentityKeys = new HashSet<>();

    void reset() {
        installedIdentityKeys.clear();
    }

    void recordInstalled(AudioPackIdentity actualIdentity) {
        installedIdentityKeys.add(actualIdentity.exactKey());
    }

    boolean needsAttempt(AudioPackIdentity expectedIdentity) {
        return !installedIdentityKeys.contains(expectedIdentity.exactKey());
    }
}
