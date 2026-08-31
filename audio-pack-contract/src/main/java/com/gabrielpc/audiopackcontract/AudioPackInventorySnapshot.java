package com.gabrielpc.audiopackcontract;

import android.os.Parcel;
import android.os.Parcelable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;

/** Main-app-owned view of the current catalog and app-private audio-pack store. */
public final class AudioPackInventorySnapshot implements Parcelable {
    private final boolean catalogAvailable;
    private final String catalogError;
    private final List<AudioPackIdentity> expected;
    private final List<AudioPackExpectedSource> expectedSources;
    private final List<AudioPackIdentity> exactInstalled;
    private final List<AudioPackIdentity> missing;
    private final List<AudioPackIdentity> stale;
    private final List<AudioPackIdentity> extra;
    private final long availablePrivateBytes;

    public AudioPackInventorySnapshot(
        boolean catalogAvailable,
        String catalogError,
        List<AudioPackIdentity> expected,
        List<AudioPackIdentity> exactInstalled,
        List<AudioPackIdentity> missing,
        List<AudioPackIdentity> stale,
        List<AudioPackIdentity> extra
    ) {
        this(catalogAvailable, catalogError, expected, Collections.emptyList(), exactInstalled, missing, stale, extra, -1L);
    }

    public AudioPackInventorySnapshot(
        boolean catalogAvailable,
        String catalogError,
        List<AudioPackIdentity> expected,
        List<AudioPackIdentity> exactInstalled,
        List<AudioPackIdentity> missing,
        List<AudioPackIdentity> stale,
        List<AudioPackIdentity> extra,
        long availablePrivateBytes
    ) {
        this(catalogAvailable, catalogError, expected, Collections.emptyList(), exactInstalled, missing, stale, extra, availablePrivateBytes);
    }

    public AudioPackInventorySnapshot(
        boolean catalogAvailable,
        String catalogError,
        List<AudioPackIdentity> expected,
        List<AudioPackExpectedSource> expectedSources,
        List<AudioPackIdentity> exactInstalled,
        List<AudioPackIdentity> missing,
        List<AudioPackIdentity> stale,
        List<AudioPackIdentity> extra,
        long availablePrivateBytes
    ) {
        this.catalogAvailable = catalogAvailable;
        this.catalogError = catalogError == null ? "" : catalogError;
        this.expected = immutableCopy(expected);
        this.expectedSources = immutableExpectedSourceCopy(expectedSources);
        this.exactInstalled = immutableCopy(exactInstalled);
        this.missing = immutableCopy(missing);
        this.stale = immutableCopy(stale);
        this.extra = immutableCopy(extra);
        this.availablePrivateBytes = availablePrivateBytes;
    }

    private AudioPackInventorySnapshot(Parcel source) {
        catalogAvailable = source.readInt() != 0;
        catalogError = nonNull(source.readString());
        expected = readIdentityList(source);
        expectedSources = readExpectedSourceList(source);
        exactInstalled = readIdentityList(source);
        missing = readIdentityList(source);
        stale = readIdentityList(source);
        extra = readIdentityList(source);
        availablePrivateBytes = source.readLong();
    }

    public boolean isCatalogAvailable() {
        return catalogAvailable;
    }

    public String getCatalogError() {
        return catalogError;
    }

    public List<AudioPackIdentity> getExpected() {
        return expected;
    }

    public List<AudioPackExpectedSource> getExpectedSources() {
        return expectedSources;
    }

    public List<AudioPackIdentity> getExactInstalled() {
        return exactInstalled;
    }

    public List<AudioPackIdentity> getMissing() {
        return missing;
    }

    public List<AudioPackIdentity> getStale() {
        return stale;
    }

    public List<AudioPackIdentity> getExtra() {
        return extra;
    }

    /** Free bytes in the main app's private filesystem, or -1 when the platform could not report it. */
    public long getAvailablePrivateBytes() {
        return availablePrivateBytes;
    }

    public boolean isReady() {
        if (!catalogAvailable || expected.isEmpty() || !missing.isEmpty()) {
            return false;
        }
        HashSet<AudioPackIdentity> uniqueExpected = new HashSet<>(expected);
        HashSet<AudioPackIdentity> uniqueExact = new HashSet<>(exactInstalled);

        return uniqueExpected.size() == expected.size() &&
            uniqueExact.size() == exactInstalled.size() &&
            uniqueExact.equals(uniqueExpected);
    }

    @Override
    public void writeToParcel(Parcel destination, int flags) {
        destination.writeInt(catalogAvailable ? 1 : 0);
        destination.writeString(catalogError);
        destination.writeTypedList(expected);
        destination.writeTypedList(expectedSources);
        destination.writeTypedList(exactInstalled);
        destination.writeTypedList(missing);
        destination.writeTypedList(stale);
        destination.writeTypedList(extra);
        destination.writeLong(availablePrivateBytes);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    private static List<AudioPackIdentity> immutableCopy(List<AudioPackIdentity> identities) {
        if (identities == null || identities.isEmpty()) {
            return Collections.emptyList();
        }

        return Collections.unmodifiableList(new ArrayList<>(identities));
    }

    private static List<AudioPackIdentity> readIdentityList(Parcel source) {
        ArrayList<AudioPackIdentity> identities = new ArrayList<>();
        source.readTypedList(identities, AudioPackIdentity.CREATOR);

        return Collections.unmodifiableList(identities);
    }

    private static List<AudioPackExpectedSource> immutableExpectedSourceCopy(
        List<AudioPackExpectedSource> sources
    ) {
        if (sources == null || sources.isEmpty()) {
            return Collections.emptyList();
        }

        return Collections.unmodifiableList(new ArrayList<>(sources));
    }

    private static List<AudioPackExpectedSource> readExpectedSourceList(Parcel source) {
        ArrayList<AudioPackExpectedSource> sources = new ArrayList<>();
        source.readTypedList(sources, AudioPackExpectedSource.CREATOR);

        return Collections.unmodifiableList(sources);
    }

    private static String nonNull(String value) {
        return value == null ? "" : value;
    }

    public static final Creator<AudioPackInventorySnapshot> CREATOR = new Creator<AudioPackInventorySnapshot>() {
        @Override
        public AudioPackInventorySnapshot createFromParcel(Parcel source) {
            return new AudioPackInventorySnapshot(source);
        }

        @Override
        public AudioPackInventorySnapshot[] newArray(int size) {
            return new AudioPackInventorySnapshot[size];
        }
    };
}
