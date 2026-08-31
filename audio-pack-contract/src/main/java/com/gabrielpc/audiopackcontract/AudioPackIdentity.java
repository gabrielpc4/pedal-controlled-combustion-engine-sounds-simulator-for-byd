package com.gabrielpc.audiopackcontract;

import android.os.Parcel;
import android.os.Parcelable;
import java.util.Objects;

/** Exact identity of one catalog requirement or one installed audio pack. */
public final class AudioPackIdentity implements Parcelable {
    private final String packId;
    private final int packVersion;
    private final String manifestSha256;

    public AudioPackIdentity(String packId, int packVersion, String manifestSha256) {
        this.packId = Objects.requireNonNull(packId, "packId");
        this.packVersion = packVersion;
        this.manifestSha256 = Objects.requireNonNull(manifestSha256, "manifestSha256");
    }

    private AudioPackIdentity(Parcel source) {
        packId = Objects.requireNonNull(source.readString(), "packId");
        packVersion = source.readInt();
        manifestSha256 = Objects.requireNonNull(source.readString(), "manifestSha256");
    }

    public String getPackId() {
        return packId;
    }

    public int getPackVersion() {
        return packVersion;
    }

    public String getManifestSha256() {
        return manifestSha256;
    }

    public String exactKey() {
        return packId + ":" + packVersion + ":" + manifestSha256;
    }

    @Override
    public void writeToParcel(Parcel destination, int flags) {
        destination.writeString(packId);
        destination.writeInt(packVersion);
        destination.writeString(manifestSha256);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof AudioPackIdentity)) {
            return false;
        }
        AudioPackIdentity identity = (AudioPackIdentity) other;

        return packVersion == identity.packVersion &&
            packId.equals(identity.packId) &&
            manifestSha256.equals(identity.manifestSha256);
    }

    @Override
    public int hashCode() {
        return Objects.hash(packId, packVersion, manifestSha256);
    }

    @Override
    public String toString() {
        return packId + " v" + packVersion + " [" + manifestSha256.substring(0, Math.min(8, manifestSha256.length())) + "]";
    }

    public static final Creator<AudioPackIdentity> CREATOR = new Creator<AudioPackIdentity>() {
        @Override
        public AudioPackIdentity createFromParcel(Parcel source) {
            return new AudioPackIdentity(source);
        }

        @Override
        public AudioPackIdentity[] newArray(int size) {
            return new AudioPackIdentity[size];
        }
    };
}
