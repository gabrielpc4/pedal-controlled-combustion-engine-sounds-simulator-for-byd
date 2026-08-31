package com.gabrielpc.audiopackcontract;

import android.os.Parcel;
import android.os.Parcelable;
import java.util.Objects;

/** Trusted catalog mapping from one exact pack identity to its deterministic USB filename. */
public final class AudioPackExpectedSource implements Parcelable {
    private final AudioPackIdentity identity;
    private final String sourceFileName;

    public AudioPackExpectedSource(AudioPackIdentity identity, String sourceFileName) {
        this.identity = Objects.requireNonNull(identity, "identity");
        this.sourceFileName = requireSafeFileName(sourceFileName);
    }

    private AudioPackExpectedSource(Parcel source) {
        identity = Objects.requireNonNull(
            source.readParcelable(AudioPackIdentity.class.getClassLoader()),
            "identity"
        );
        sourceFileName = requireSafeFileName(source.readString());
    }

    public AudioPackIdentity getIdentity() {
        return identity;
    }

    public String getSourceFileName() {
        return sourceFileName;
    }

    @Override
    public void writeToParcel(Parcel destination, int flags) {
        destination.writeParcelable(identity, flags);
        destination.writeString(sourceFileName);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    private static String requireSafeFileName(String value) {
        String fileName = Objects.requireNonNull(value, "sourceFileName");
        if (
            fileName.isEmpty() || fileName.length() > 180 || fileName.indexOf('/') >= 0 ||
            fileName.indexOf('\\') >= 0 || !fileName.endsWith(".bydpack")
        ) {
            throw new IllegalArgumentException("Unsafe expected audio-pack filename");
        }

        return fileName;
    }

    public static final Creator<AudioPackExpectedSource> CREATOR = new Creator<AudioPackExpectedSource>() {
        @Override
        public AudioPackExpectedSource createFromParcel(Parcel source) {
            return new AudioPackExpectedSource(source);
        }

        @Override
        public AudioPackExpectedSource[] newArray(int size) {
            return new AudioPackExpectedSource[size];
        }
    };
}
