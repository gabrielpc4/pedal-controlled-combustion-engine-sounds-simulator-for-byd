package com.gabrielpc.enginesoundsimulator.audioinstaller;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.gabrielpc.audiopackcontract.AudioPackIdentity;
import com.gabrielpc.audiopackcontract.AudioPackInstallContract;
import com.gabrielpc.audiopackcontract.AudioPackInventorySnapshot;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

public final class AudioPackBatchTrackerTest {
    @Test
    public void thirtyOneOfThirtyTwoNeverReportsReady() {
        List<AudioPackIdentity> expected = identities(32, 1, "a");
        AudioPackInventorySnapshot inventory = snapshot(
            expected,
            expected.subList(0, 31),
            expected.subList(31, 32),
            Collections.emptyList(),
            Collections.emptyList()
        );

        AudioPackBatchTracker.Report report = new AudioPackBatchTracker().finish(inventory);

        assertFalse(report.isReady());
        assertEquals(31, report.inventory.getExactInstalled().size());
        assertEquals(1, report.inventory.getMissing().size());
    }

    @Test
    public void duplicateCannotInflateThirtyOneExactPacksToThirtyTwo() {
        List<AudioPackIdentity> expected = identities(32, 1, "b");
        AudioPackBatchTracker tracker = new AudioPackBatchTracker();
        tracker.recordFailed(
            "duplicate-family.bydpack",
            AudioPackInstallContract.ERROR_DUPLICATE_PACK,
            "Duplicate family-00 v1 in this USB batch"
        );
        AudioPackInventorySnapshot inventory = snapshot(
            expected,
            expected.subList(0, 31),
            expected.subList(31, 32),
            Collections.emptyList(),
            Collections.emptyList()
        );

        AudioPackBatchTracker.Report report = tracker.finish(inventory);

        assertFalse(report.isReady());
        assertEquals(1, report.duplicateSources.size());
        assertEquals(31, report.inventory.getExactInstalled().size());
    }

    @Test
    public void oldVersionOrHashForEveryFamilyIsStaleAndNeverReady() {
        List<AudioPackIdentity> expected = identities(32, 2, "c");
        List<AudioPackIdentity> stale = identities(32, 1, "d");
        AudioPackInventorySnapshot inventory = snapshot(
            expected,
            Collections.emptyList(),
            expected,
            stale,
            Collections.emptyList()
        );

        AudioPackBatchTracker.Report report = new AudioPackBatchTracker().finish(inventory);

        assertFalse(report.isReady());
        assertEquals(32, report.inventory.getMissing().size());
        assertEquals(32, report.inventory.getStale().size());
    }

    @Test
    public void completeExactLibraryCanBeReadyWhileRejectedStrangeInputIsListed() {
        List<AudioPackIdentity> expected = identities(32, 1, "e");
        AudioPackIdentity strange = new AudioPackIdentity("strange", 1, hash("f", 99));
        AudioPackBatchTracker tracker = new AudioPackBatchTracker();
        tracker.recordFailed(
            "strange.bydpack",
            AudioPackInstallContract.ERROR_UNEXPECTED_PACK,
            "strange is not expected by the current app catalog"
        );
        AudioPackInventorySnapshot inventory = snapshot(
            expected,
            expected,
            Collections.emptyList(),
            Collections.emptyList(),
            Collections.singletonList(strange)
        );

        AudioPackBatchTracker.Report report = tracker.finish(inventory);

        assertTrue(report.isReady());
        assertEquals(32, report.inventory.getExactInstalled().size());
        assertEquals(1, report.inventory.getExtra().size());
        assertEquals(1, report.extraSources.size());
    }

    @Test
    public void thirtyTwoUniqueExactPacksAreReady() {
        List<AudioPackIdentity> expected = identities(32, 1, "1");

        AudioPackBatchTracker.Report report = new AudioPackBatchTracker().finish(
            snapshot(
                expected,
                expected,
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList()
            )
        );

        assertTrue(report.isReady());
        assertTrue(report.duplicateSources.isEmpty());
        assertTrue(report.staleSources.isEmpty());
        assertTrue(report.extraSources.isEmpty());
    }

    @Test
    public void repeatedSuccessCallbackIsDefensivelyClassifiedAsDuplicate() {
        AudioPackBatchTracker tracker = new AudioPackBatchTracker();
        AudioPackIdentity identity = new AudioPackIdentity("family", 1, hash("2", 1));

        assertEquals(
            AudioPackBatchTracker.SuccessDisposition.ACCEPTED,
            tracker.recordSucceeded("first.bydpack", identity)
        );
        assertEquals(
            AudioPackBatchTracker.SuccessDisposition.DUPLICATE,
            tracker.recordSucceeded("copy.bydpack", identity)
        );
    }

    private static AudioPackInventorySnapshot snapshot(
        List<AudioPackIdentity> expected,
        List<AudioPackIdentity> exact,
        List<AudioPackIdentity> missing,
        List<AudioPackIdentity> stale,
        List<AudioPackIdentity> extra
    ) {
        return new AudioPackInventorySnapshot(true, "", expected, exact, missing, stale, extra);
    }

    private static List<AudioPackIdentity> identities(int count, int version, String hashPrefix) {
        List<AudioPackIdentity> result = new ArrayList<>();
        for (int index = 0; index < count; index += 1) {
            result.add(new AudioPackIdentity(
                String.format("family-%02d", index),
                version,
                hash(hashPrefix, index)
            ));
        }

        return result;
    }

    private static String hash(String prefix, int index) {
        String suffix = String.format("%063x", index);

        return (prefix + suffix).substring(0, 64);
    }
}
