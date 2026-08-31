package com.gabrielpc.enginesoundsimulator.audioinstaller;

/** Keeps long copies alive through stop/resume, but never retains a destroyed Activity callback. */
final class InstallerLifecyclePolicy {
    private InstallerLifecyclePolicy() {
    }

    static boolean retainConnectionOnStop() {
        return true;
    }

    static boolean cancelOnDestroy(boolean installing) {
        return installing;
    }

    static boolean continueAfterLocalSourceFailure(boolean cancelRequested) {
        return !cancelRequested;
    }
}
