package com.gabrielpc.enginesoundsimulator.audioinstaller;

final class PackScanLimits {
    static final int MAX_ROOTS = 32;
    static final int MAX_DIRECTORIES = 20_000;
    static final int MAX_FILES = 100_000;
    static final int MAX_PACKS = 256;
    static final int MAX_DEPTH = 32;
    static final int MAX_LABEL_CHARACTERS = 2_048;
    static final int PROGRESS_DIRECTORY_INTERVAL = 32;
    static final long PROGRESS_TIME_INTERVAL_NANOS = 100L * 1_000_000L;

    private PackScanLimits() {
    }
}
