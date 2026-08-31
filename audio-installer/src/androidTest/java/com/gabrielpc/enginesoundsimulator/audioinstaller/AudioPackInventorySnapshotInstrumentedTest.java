package com.gabrielpc.enginesoundsimulator.audioinstaller;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.os.Parcel;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.gabrielpc.audiopackcontract.AudioPackIdentity;
import com.gabrielpc.audiopackcontract.AudioPackExpectedSource;
import com.gabrielpc.audiopackcontract.AudioPackInventorySnapshot;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public final class AudioPackInventorySnapshotInstrumentedTest {
    @Test
    public void parcelRoundTripPreservesThirtyTwoOfThirtyTwoFinalVerification() {
        List<AudioPackIdentity> expected = identities(32);
        List<AudioPackExpectedSource> sources = expectedSources(expected);
        AudioPackInventorySnapshot original = new AudioPackInventorySnapshot(
            true,
            "",
            expected,
            sources,
            expected,
            Collections.emptyList(),
            Collections.singletonList(new AudioPackIdentity("old", 1, repeated('a'))),
            Collections.singletonList(new AudioPackIdentity("extra", 1, repeated('b'))),
            -1L
        );
        Parcel parcel = Parcel.obtain();
        try {
            original.writeToParcel(parcel, 0);
            parcel.setDataPosition(0);

            AudioPackInventorySnapshot restored = AudioPackInventorySnapshot.CREATOR.createFromParcel(parcel);

            assertTrue(restored.isReady());
            assertEquals(32, restored.getExpected().size());
            assertEquals("family-00-v1.bydpack", restored.getExpectedSources().get(0).getSourceFileName());
            assertEquals(32, restored.getExactInstalled().size());
            assertEquals(1, restored.getStale().size());
            assertEquals(1, restored.getExtra().size());
        } finally {
            parcel.recycle();
        }
    }

    @Test
    public void repeatedExactIdentityCannotMasqueradeAsThirtyTwoUniquePacks() {
        List<AudioPackIdentity> expected = identities(32);
        List<AudioPackIdentity> repeated = new ArrayList<>(expected.subList(0, 31));
        repeated.add(expected.get(0));
        AudioPackInventorySnapshot snapshot = new AudioPackInventorySnapshot(
            true,
            "",
            expected,
            repeated,
            Collections.emptyList(),
            Collections.emptyList(),
            Collections.emptyList()
        );

        assertFalse(snapshot.isReady());
    }

    private static List<AudioPackIdentity> identities(int count) {
        List<AudioPackIdentity> result = new ArrayList<>();
        for (int index = 0; index < count; index += 1) {
            result.add(new AudioPackIdentity(
                String.format("family-%02d", index),
                1,
                String.format("%064x", index + 1)
            ));
        }

        return result;
    }

    private static String repeated(char value) {
        char[] characters = new char[64];
        java.util.Arrays.fill(characters, value);

        return new String(characters);
    }

    private static List<AudioPackExpectedSource> expectedSources(List<AudioPackIdentity> identities) {
        List<AudioPackExpectedSource> result = new ArrayList<>();
        for (int index = 0; index < identities.size(); index += 1) {
            result.add(new AudioPackExpectedSource(
                identities.get(index),
                String.format("family-%02d-v1.bydpack", index)
            ));
        }

        return result;
    }
}
