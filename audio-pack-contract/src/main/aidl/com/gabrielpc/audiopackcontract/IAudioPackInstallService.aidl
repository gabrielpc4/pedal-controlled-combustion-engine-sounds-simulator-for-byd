package com.gabrielpc.audiopackcontract;

import android.os.ParcelFileDescriptor;
import com.gabrielpc.audiopackcontract.AudioPackInventorySnapshot;
import com.gabrielpc.audiopackcontract.IAudioPackInstallCallback;

interface IAudioPackInstallService {
    AudioPackInventorySnapshot getInventorySnapshot();
    AudioPackInventorySnapshot beginBatch();
    void install(in ParcelFileDescriptor source, String sourceLabel, long sourceBytes, IAudioPackInstallCallback callback);
    AudioPackInventorySnapshot finishBatch();
    AudioPackInventorySnapshot cleanupObsoletePacks();
    void cancel();
}
