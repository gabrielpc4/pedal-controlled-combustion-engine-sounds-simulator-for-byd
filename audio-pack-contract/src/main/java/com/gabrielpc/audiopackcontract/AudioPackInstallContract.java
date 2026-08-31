package com.gabrielpc.audiopackcontract;

public final class AudioPackInstallContract {
    /**
     * The byte values delivered by {@code IAudioPackInstallCallback.onProgress} are always local
     * to {@code stage}. A new stage resets its byte range; callers must display the stage label
     * alongside the bar instead of treating it as a single archive-to-install total.
     */
    public static final String MAIN_PACKAGE = "com.gabrielpc.enginesoundsimulator";
    public static final String SERVICE_CLASS = MAIN_PACKAGE + ".audio.AudioPackInstallService";
    public static final String SERVICE_ACTION = MAIN_PACKAGE + ".action.INSTALL_AUDIO_PACK";
    public static final String INSTALL_PERMISSION = MAIN_PACKAGE + ".permission.INSTALL_AUDIO_PACK";

    public static final String STAGE_CONNECT = "CONNECT";
    public static final String STAGE_RECEIVE = "RECEIVE";
    public static final String STAGE_OPEN_ARCHIVE = "OPEN_ARCHIVE";
    public static final String STAGE_PREPARE_STAGING = "PREPARE_STAGING";
    public static final String STAGE_READ_MANIFEST = "READ_MANIFEST";
    public static final String STAGE_VALIDATE_CATALOG = "VALIDATE_CATALOG";
    public static final String STAGE_VALIDATE_LAYOUT = "VALIDATE_LAYOUT";
    public static final String STAGE_EXTRACT_WAV = "EXTRACT_WAV";
    public static final String STAGE_VERIFY_WAV = "VERIFY_WAV";
    public static final String STAGE_FINALIZE = "FINALIZE";
    public static final String STAGE_COMMIT = "COMMIT";
    public static final String STAGE_CLEANUP = "CLEANUP";
    public static final String STAGE_CANCELED = "CANCELED";

    public static final String ERROR_CONNECT = "CONNECT_FAILED";
    public static final String ERROR_BATCH_NOT_ACTIVE = "BATCH_NOT_ACTIVE";
    public static final String ERROR_CATALOG_UNAVAILABLE = "CATALOG_UNAVAILABLE";
    public static final String ERROR_UNEXPECTED_PACK = "UNEXPECTED_PACK";
    public static final String ERROR_STALE_PACK = "STALE_PACK";
    public static final String ERROR_MANIFEST_MISMATCH = "MANIFEST_MISMATCH";
    public static final String ERROR_DUPLICATE_PACK = "DUPLICATE_PACK";
    public static final String ERROR_CANCELED = "CANCELED";
    public static final String ERROR_INSTALLATION = "INSTALLATION_FAILED";

    public static int stageProgressPermille(long completedBytes, long totalBytes) {
        if (totalBytes <= 0L) {
            return -1;
        }
        if (completedBytes <= 0L) {
            return 0;
        }
        if (completedBytes >= totalBytes) {
            return 1_000;
        }

        return (int) ((completedBytes * 1_000L) / totalBytes);
    }

    private AudioPackInstallContract() {
    }
}
