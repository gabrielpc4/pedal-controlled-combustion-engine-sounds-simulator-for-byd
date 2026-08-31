package com.gabrielpc.enginesoundsimulator.audioinstaller;

import com.gabrielpc.audiopackcontract.AudioPackIdentity;
import com.gabrielpc.audiopackcontract.AudioPackInstallContract;
import com.gabrielpc.audiopackcontract.AudioPackInventorySnapshot;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Reconciles one USB batch without allowing repeated callbacks to inflate exact progress. */
final class AudioPackBatchTracker {
    private final Set<String> successfulIdentities = new HashSet<>();
    private final List<String> duplicateSources = new ArrayList<>();
    private final List<String> staleSources = new ArrayList<>();
    private final List<String> extraSources = new ArrayList<>();

    SuccessDisposition recordSucceeded(String sourceLabel, AudioPackIdentity identity) {
        if (!successfulIdentities.add(identity.exactKey())) {
            duplicateSources.add(sourceLabel + " → " + identity);

            return SuccessDisposition.DUPLICATE;
        }

        return SuccessDisposition.ACCEPTED;
    }

    void recordFailed(String sourceLabel, String errorCode, String detail) {
        String item = sourceLabel + " · " + detail;
        if (AudioPackInstallContract.ERROR_DUPLICATE_PACK.equals(errorCode)) {
            duplicateSources.add(item);
        } else if (
            AudioPackInstallContract.ERROR_STALE_PACK.equals(errorCode) ||
            AudioPackInstallContract.ERROR_MANIFEST_MISMATCH.equals(errorCode)
        ) {
            staleSources.add(item);
        } else if (AudioPackInstallContract.ERROR_UNEXPECTED_PACK.equals(errorCode)) {
            extraSources.add(item);
        }
    }

    Report finish(AudioPackInventorySnapshot inventory) {
        return new Report(inventory, duplicateSources, staleSources, extraSources);
    }

    enum SuccessDisposition {
        ACCEPTED,
        DUPLICATE,
    }

    static final class Report {
        final AudioPackInventorySnapshot inventory;
        final List<String> duplicateSources;
        final List<String> staleSources;
        final List<String> extraSources;

        Report(
            AudioPackInventorySnapshot inventory,
            List<String> duplicateSources,
            List<String> staleSources,
            List<String> extraSources
        ) {
            this.inventory = inventory;
            this.duplicateSources = immutableCopy(duplicateSources);
            this.staleSources = immutableCopy(staleSources);
            this.extraSources = immutableCopy(extraSources);
        }

        boolean isReady() {
            return inventory.isReady();
        }

        private static List<String> immutableCopy(List<String> values) {
            return Collections.unmodifiableList(new ArrayList<>(values));
        }
    }
}
