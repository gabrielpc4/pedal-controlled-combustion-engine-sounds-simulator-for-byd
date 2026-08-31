package com.gabrielpc.audiopackcontract;

oneway interface IAudioPackInstallCallback {
    // completedBytes/totalBytes are stage-local. The client must reset its bar when stage changes.
    void onProgress(String sourceLabel, String stage, String detail, long completedBytes, long totalBytes);
    void onSucceeded(String sourceLabel, String packId, int packVersion, String manifestSha256, int fileCount, long installedBytes);
    void onFailed(String sourceLabel, String stage, String errorCode, String detail);
}
