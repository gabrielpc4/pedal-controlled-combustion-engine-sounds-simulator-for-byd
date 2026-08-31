package com.gabrielpc.enginesoundsimulator.audioinstaller;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import com.gabrielpc.audiopackcontract.AudioPackExpectedSource;
import com.gabrielpc.audiopackcontract.AudioPackIdentity;
import com.gabrielpc.audiopackcontract.AudioPackInventorySnapshot;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

public final class AudioPackInstallSelectionTest {
    @Test
    public void thirtyOneExactPacksCauseOnlyTheMissingPackToBeSelectedForOpening() {
        List<AudioPackIdentity> expected = new ArrayList<>();
        List<AudioPackExpectedSource> expectedSources = new ArrayList<>();
        List<String> discoveredNames = new ArrayList<>();
        for (int index = 0; index < 32; index += 1) {
            AudioPackIdentity identity = new AudioPackIdentity(
                String.format("byd.atlas.family-%02d", index),
                1,
                String.format("%064x", index + 1)
            );
            String fileName = String.format("family-%02d-v1.bydpack", index);
            expected.add(identity);
            expectedSources.add(new AudioPackExpectedSource(identity, fileName));
            discoveredNames.add(fileName);
        }
        AudioPackInventorySnapshot snapshot = new AudioPackInventorySnapshot(
            true,
            "",
            expected,
            expectedSources,
            expected.subList(0, 31),
            Collections.singletonList(expected.get(31)),
            Collections.emptyList(),
            Collections.emptyList(),
            -1L
        );

        AudioPackInstallSelection.Result selection = AudioPackInstallSelection.select(discoveredNames, snapshot);

        assertEquals(1, selection.candidates.size());
        assertEquals(31, selection.candidates.get(0).discoveredIndex);
        assertEquals(expected.get(31), selection.candidates.get(0).expectedIdentity);
        assertEquals(Collections.emptyList(), selection.unavailableFileNames);
    }

    @Test
    public void retainsEveryCaseInsensitiveCandidateSoACorruptFirstCopyCannotHideAValidRetry() {
        AudioPackIdentity missing = new AudioPackIdentity(
            "byd.atlas.family",
            4,
            String.format("%064x", 42)
        );
        AudioPackInventorySnapshot snapshot = new AudioPackInventorySnapshot(
            true,
            "",
            Collections.singletonList(missing),
            Collections.singletonList(new AudioPackExpectedSource(missing, "family-v4.bydpack")),
            Collections.emptyList(),
            Collections.singletonList(missing),
            Collections.emptyList(),
            Collections.emptyList(),
            -1L
        );

        AudioPackInstallSelection.Result selection = AudioPackInstallSelection.select(
            java.util.Arrays.asList(
                "unrelated.bydpack",
                "FAMILY-V4.BYDPACK",
                "family-v4.bydpack"
            ),
            snapshot
        );

        assertEquals(2, selection.candidates.size());
        assertEquals(1, selection.candidates.get(0).discoveredIndex);
        assertEquals(missing, selection.candidates.get(0).expectedIdentity);
        assertEquals(2, selection.candidates.get(1).discoveredIndex);
        assertEquals(missing, selection.candidates.get(1).expectedIdentity);
        assertEquals(Collections.emptyList(), selection.unavailableFileNames);
    }

    @Test
    public void rejectsCatalogFilenamesThatCollideIgnoringUsbFilesystemCase() {
        AudioPackIdentity first = new AudioPackIdentity("first", 1, String.format("%064x", 1));
        AudioPackIdentity second = new AudioPackIdentity("second", 1, String.format("%064x", 2));
        AudioPackInventorySnapshot snapshot = new AudioPackInventorySnapshot(
            true,
            "",
            java.util.Arrays.asList(first, second),
            java.util.Arrays.asList(
                new AudioPackExpectedSource(first, "family.bydpack"),
                new AudioPackExpectedSource(second, "FAMILY.bydpack")
            ),
            Collections.emptyList(),
            java.util.Arrays.asList(first, second),
            Collections.emptyList(),
            Collections.emptyList(),
            -1L
        );

        IllegalArgumentException error = assertThrows(
            IllegalArgumentException.class,
            () -> AudioPackInstallSelection.select(Collections.singletonList("family.bydpack"), snapshot)
        );

        assertEquals("Catalog maps more than one missing pack to FAMILY.bydpack", error.getMessage());
    }

    @Test
    public void currentCatalogUpdateIsSelectedWhileItsInstalledOldIdentityRemainsStale() {
        AudioPackIdentity current = new AudioPackIdentity("family", 2, String.format("%064x", 2));
        AudioPackIdentity old = new AudioPackIdentity("family", 1, String.format("%064x", 1));
        AudioPackInventorySnapshot snapshot = new AudioPackInventorySnapshot(
            true,
            "",
            Collections.singletonList(current),
            Collections.singletonList(new AudioPackExpectedSource(current, "family-v2.bydpack")),
            Collections.emptyList(),
            Collections.singletonList(current),
            Collections.singletonList(old),
            Collections.emptyList(),
            -1L
        );

        AudioPackInstallSelection.Result selection = AudioPackInstallSelection.select(
            Collections.singletonList("family-v2.bydpack"),
            snapshot
        );

        assertEquals(1, selection.candidates.size());
        assertEquals(current, selection.candidates.get(0).expectedIdentity);
    }
}
