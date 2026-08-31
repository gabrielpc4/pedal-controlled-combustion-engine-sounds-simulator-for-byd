package com.gabrielpc.enginesoundsimulator.audioinstaller;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import com.gabrielpc.audiopackcontract.AudioPackIdentity;
import com.gabrielpc.audiopackcontract.AudioPackInstallContract;
import com.gabrielpc.audiopackcontract.AudioPackInventorySnapshot;
import com.gabrielpc.audiopackcontract.IAudioPackInstallCallback;
import com.gabrielpc.audiopackcontract.IAudioPackInstallService;
import java.io.File;
import java.io.FileNotFoundException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Small USB-to-main-app bridge; it never owns or stores the installed WAV library. */
public final class AudioInstallerActivity extends Activity {
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService scannerExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService serviceExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService cancelExecutor = Executors.newSingleThreadExecutor();
    private final List<PackSource> discoveredPacks = new ArrayList<>();
    private final List<PlannedPackSource> packsToInstall = new ArrayList<>();
    private final AudioPackRetryState retryState = new AudioPackRetryState();
    private volatile IAudioPackInstallService installService;
    private volatile boolean cancelRequested;
    private volatile boolean destroyed;
    private AudioPackInventorySnapshot latestInventory;
    private AudioPackBatchTracker batchTracker;
    private boolean serviceBound;
    private boolean installing;
    private boolean scanning;
    private boolean serviceOperationPending;
    private int installIndex;
    private int succeeded;
    private int failed;

    private TextView statusText;
    private TextView detailText;
    private TextView logText;
    private ProgressBar progressBar;
    private Button installButton;
    private Button cancelButton;
    private Button scanButton;
    private Button chooseTreeButton;
    private Button cleanupButton;

    private final Object progressLock = new Object();
    private ProgressUiUpdate pendingProgress;
    private boolean progressUiPosted;

    private final IAudioPackInstallCallback callback = new IAudioPackInstallCallback.Stub() {
        @Override
        public void onProgress(String label, String stage, String detail, long completed, long total) {
            ProgressUiUpdate update = new ProgressUiUpdate(
                safeCallbackText(label, "unnamed .bydpack"),
                safeCallbackText(stage, AudioPackInstallContract.STAGE_CONNECT),
                safeCallbackText(detail, "No stage detail"),
                completed,
                total
            );
            if (isWarning(update.detail)) {
                postIfAlive(() -> showProgress(
                    update.label,
                    update.stage,
                    update.detail,
                    update.completed,
                    update.total
                ));
                return;
            }
            synchronized (progressLock) {
                pendingProgress = update;
                if (!progressUiPosted) {
                    progressUiPosted = true;
                    mainHandler.postDelayed(AudioInstallerActivity.this::drainProgressUi, PROGRESS_UI_INTERVAL_MS);
                }
            }
        }

        @Override
        public void onSucceeded(
            String label,
            String packId,
            int version,
            String manifestSha256,
            int fileCount,
            long bytes
        ) {
            postIfAlive(() -> {
                discardPendingProgressUi();
                AudioPackIdentity identity = new AudioPackIdentity(packId, version, manifestSha256);
                AudioPackBatchTracker.SuccessDisposition disposition = batchTracker == null ?
                    AudioPackBatchTracker.SuccessDisposition.ACCEPTED :
                    batchTracker.recordSucceeded(label, identity);
                if (disposition == AudioPackBatchTracker.SuccessDisposition.DUPLICATE) {
                    failed += 1;
                    appendLog("DUPLICATE  " + label + " → " + identity);
                } else {
                    retryState.recordInstalled(identity);
                    succeeded += 1;
                    appendLog("OK  " + label + " → " + identity + " · " + fileCount +
                        " WAVs · " + formatBytes(bytes));
                }
                installIndex += 1;
                if (cancelRequested) {
                    finishCanceled("The active pack finished before cancellation reached the main app.");
                    return;
                }
                installNext();
            });
        }

        @Override
        public void onFailed(String label, String stage, String errorCode, String detail) {
            String safeLabel = safeCallbackText(label, "unnamed .bydpack");
            String safeStage = safeCallbackText(stage, AudioPackInstallContract.STAGE_CONNECT);
            String safeErrorCode = safeCallbackText(errorCode, AudioPackInstallContract.ERROR_INSTALLATION);
            String safeDetail = safeCallbackText(detail, "No failure detail");
            postIfAlive(() -> {
                discardPendingProgressUi();
                if (cancelRequested || AudioPackInstallContract.STAGE_CANCELED.equals(safeStage)) {
                    appendLog("CANCELED  " + safeLabel + " · " + safeStage + " · " + safeErrorCode +
                        " · " + safeDetail);
                    installIndex += 1;
                    finishCanceled(safeStage + " · " + safeDetail);
                    return;
                }
                failed += 1;
                if (batchTracker != null) {
                    batchTracker.recordFailed(safeLabel, safeErrorCode, safeDetail);
                }
                appendLog("FAILED  " + safeLabel + " · " + safeStage + " · " + safeErrorCode +
                    " · " + safeDetail);
                installIndex += 1;
                installNext();
            });
        }
    };

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            if (destroyed) {
                return;
            }
            installService = IAudioPackInstallService.Stub.asInterface(binder);
            refreshInventory("Main app connected securely");
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            if (destroyed) {
                return;
            }
            installService = null;
            latestInventory = null;
            batchTracker = null;
            serviceOperationPending = false;
            if (installing) {
                installing = false;
                cancelRequested = false;
                appendLog("FAILED  Main app service disconnected; active transaction was canceled");
            }
            statusText.setText("Main app disconnected");
            updateButtons();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        buildUi();
        requestUsbAccessOrScan();
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (!serviceBound) {
            bindInstallService();
        }
    }

    @Override
    protected void onStop() {
        // Keep the signed bound connection across the document picker, transient backgrounding,
        // and the fixed-landscape activity lifecycle. A true Activity destruction explicitly
        // cancels and unbinds so its callback can never be retained.
        if (!InstallerLifecyclePolicy.retainConnectionOnStop()) {
            releaseInstallServiceBinding();
        }

        super.onStop();
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        mainHandler.removeCallbacksAndMessages(null);
        discardPendingProgressUi();
        if (InstallerLifecyclePolicy.cancelOnDestroy(installing)) {
            IAudioPackInstallService service = installService;
            if (service != null) {
                try {
                    service.cancel();
                } catch (RemoteException ignored) {
                }
            }
        }
        releaseInstallServiceBinding();
        scannerExecutor.shutdownNow();
        serviceExecutor.shutdownNow();
        cancelExecutor.shutdownNow();
        super.onDestroy();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != STORAGE_PERMISSION_REQUEST) {
            return;
        }
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            scanRemovableStorage();
        } else {
            statusText.setText("Direct USB access denied");
            detailText.setText("Choose the USB folder through Android's document picker.");
            chooseTreeButton.setVisibility(View.VISIBLE);
            updateButtons();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != TREE_REQUEST) {
            return;
        }
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            statusText.setText("No USB folder was selected");
            detailText.setText("The folder picker was canceled or is unavailable on this Android device. " +
                "Connect the USB drive and try RESCAN USB, or choose the folder again.");
            chooseTreeButton.setVisibility(View.VISIBLE);
            updateButtons();
            return;
        }
        // The temporary grant is intentionally not persisted. Only opened file descriptors cross to the main app.
        scanDocumentTree(data.getData());
    }

    private void buildUi() {
        int padding = dp(20);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(padding, dp(14), padding, dp(14));
        root.setBackgroundColor(Color.rgb(6, 6, 6));

        TextView title = text("BYD AUDIO LIBRARY INSTALLER", 24, Color.rgb(53, 232, 242));
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        root.addView(title);
        statusText = text("Starting…", 16, Color.WHITE);
        root.addView(statusText, margins(dp(8)));
        detailText = text("", 13, Color.rgb(136, 162, 178));
        root.addView(detailText, margins(dp(4)));

        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(PROGRESS_MAX);
        root.addView(progressBar, new LinearLayout.LayoutParams(-1, dp(20)));

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        scanButton = button("RESCAN USB", view -> requestUsbAccessOrScan());
        chooseTreeButton = button("CHOOSE USB FOLDER", view -> chooseUsbTree());
        installButton = button("INSTALL ALL", view -> installAll());
        cancelButton = button("CANCEL", view -> cancelInstall());
        cleanupButton = button("CLEAN OBSOLETE", view -> cleanupObsoletePacks());
        actions.addView(scanButton, weightedButton());
        actions.addView(chooseTreeButton, weightedButton());
        actions.addView(installButton, weightedButton());
        actions.addView(cancelButton, weightedButton());
        root.addView(actions, margins(dp(10)));
        root.addView(cleanupButton, new LinearLayout.LayoutParams(-1, dp(48)));

        logText = text("Waiting for USB scan…", 12, Color.rgb(225, 236, 241));
        logText.setTextIsSelectable(true);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(logText);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));
        setContentView(root);
        chooseTreeButton.setVisibility(View.VISIBLE);
        updateButtons();
    }

    private void bindInstallService() {
        Intent intent = new Intent(AudioPackInstallContract.SERVICE_ACTION);
        intent.setComponent(new ComponentName(
            AudioPackInstallContract.MAIN_PACKAGE,
            AudioPackInstallContract.SERVICE_CLASS
        ));
        try {
            serviceBound = bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE);
        } catch (SecurityException error) {
            serviceBound = false;
            appendLog("FAILED  CONNECT · signature permission rejected this installer");
        }
        if (!serviceBound) {
            statusText.setText("Install or update the main engine-sound app first");
            updateButtons();
        }
    }

    private void releaseInstallServiceBinding() {
        if (!serviceBound) {
            return;
        }
        unbindService(serviceConnection);
        serviceBound = false;
        installService = null;
    }

    private void requestUsbAccessOrScan() {
        if (installing || scanning) {
            return;
        }
        if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) {
            scanRemovableStorage();
        } else {
            requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, STORAGE_PERMISSION_REQUEST);
        }
    }

    private void scanRemovableStorage() {
        if (installing || scanning) {
            return;
        }
        setScanningUi("Scanning readable removable storage…");
        scannerExecutor.execute(() -> {
            try {
                RemovablePackScanner.Result result = RemovablePackScanner.scan(
                    new File("/storage"),
                    (path, count) -> postIfAlive(() -> detailText.setText(
                        "Scanning " + path + " · " + count + " packs found"
                    ))
                );
                List<PackSource> sources = new ArrayList<>();
                for (File file : result.packs) {
                    sources.add(PackSource.forFile(file));
                }
                postIfAlive(() -> showScanResult(sources, result.accessibleRoots));
            } catch (Exception error) {
                postIfAlive(() -> showScanFailure("USB scan stopped safely", error));
            }
        });
    }

    private void scanDocumentTree(Uri treeUri) {
        if (installing || scanning) {
            return;
        }
        setScanningUi("Scanning selected USB folder…");
        scannerExecutor.execute(() -> {
            try {
                List<DocumentTreePackScanner.Pack> found = DocumentTreePackScanner.scan(
                    getContentResolver(),
                    treeUri,
                    (name, count) -> postIfAlive(() -> detailText.setText(
                        "Scanning " + name + " · " + count + " packs found"
                    ))
                );
                List<PackSource> sources = new ArrayList<>();
                for (DocumentTreePackScanner.Pack pack : found) {
                    sources.add(PackSource.forDocument(pack));
                }
                postIfAlive(() -> showScanResult(sources, 1));
            } catch (Exception error) {
                postIfAlive(() -> showScanFailure("Selected folder could not be read", error));
            }
        });
    }

    private void showScanFailure(String status, Exception error) {
        scanning = false;
        progressBar.setIndeterminate(false);
        progressBar.setProgress(0);
        statusText.setText(status);
        detailText.setText(errorMessage(error));
        appendLog("FAILED  USB_SCAN · " + errorMessage(error));
        chooseTreeButton.setVisibility(View.VISIBLE);
        updateButtons();
    }

    private void showScanResult(List<PackSource> packs, int accessibleRoots) {
        scanning = false;
        discoveredPacks.clear();
        discoveredPacks.addAll(packs);
        progressBar.setIndeterminate(false);
        progressBar.setProgress(0);
        if (packs.isEmpty()) {
            statusText.setText(accessibleRoots == 0 ? "USB was not directly accessible" : "No .bydpack files found");
            detailText.setText(accessibleRoots == 0 ?
                "Use CHOOSE USB FOLDER for Android's Storage Access Framework." :
                "Copy the generated .bydpack files to the mounted USB drive and rescan.");
            logText.setText("No audio packs discovered.");
        } else {
            statusText.setText(packs.size() + " audio pack" + (packs.size() == 1 ? "" : "s") + " found on USB");
            detailText.setText("Files are streamed one at a time; nothing is loaded fully into memory.");
            StringBuilder log = new StringBuilder();
            for (PackSource source : packs) {
                log.append("FOUND  ").append(source.label).append(" · ")
                    .append(formatBytes(source.sizeBytes)).append('\n');
            }
            logText.setText(log.toString());
        }
        appendInventoryToLog(latestInventory);
        if (latestInventory != null) {
            showInventoryStatus(latestInventory, "USB scan found " + packs.size() + " pack file(s)");
        }
        // Keep SAF available even when one direct root was readable. Another USB provider or a
        // subdirectory can still be the location the driver intended to install from.
        chooseTreeButton.setVisibility(View.VISIBLE);
        updateButtons();
    }

    private void chooseUsbTree() {
        if (installing || scanning) {
            return;
        }
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivityForResult(intent, TREE_REQUEST);
        } catch (ActivityNotFoundException | SecurityException error) {
            statusText.setText("USB folder picker unavailable");
            detailText.setText("CONNECT · " + errorMessage(error));
            appendLog("FAILED  USB_PICKER · " + AudioPackInstallContract.ERROR_CONNECT + " · " +
                errorMessage(error));
            updateButtons();
        }
    }

    private void installAll() {
        if (installService == null || discoveredPacks.isEmpty() || installing || scanning || serviceOperationPending) {
            return;
        }
        installing = true;
        serviceOperationPending = true;
        statusText.setText("Preparing missing audio packs…");
        detailText.setText("Reading the main app inventory outside the UI thread");
        updateButtons();
        IAudioPackInstallService service = installService;
        serviceExecutor.execute(() -> {
            try {
                AudioPackInventorySnapshot initial = service.beginBatch();
                postIfAlive(() -> handleBatchStarted(initial));
            } catch (Exception error) {
                postIfAlive(() -> {
                    installing = false;
                    serviceOperationPending = false;
                    statusText.setText("Could not begin installation batch");
                    detailText.setText(errorMessage(error));
                    appendLog("FAILED  " + AudioPackInstallContract.STAGE_CONNECT + " · " +
                        AudioPackInstallContract.ERROR_CONNECT + " · " + errorMessage(error));
                    updateButtons();
                });
            }
        });
    }

    private void handleBatchStarted(AudioPackInventorySnapshot initial) {
        if (destroyed) {
            return;
        }
        serviceOperationPending = false;
        latestInventory = initial;
        if (!initial.isCatalogAvailable() || initial.getExpected().isEmpty()) {
            installing = false;
            statusText.setText("CATALOG UNAVAILABLE · installation blocked");
            detailText.setText(initial.getCatalogError());
            appendInventoryToLog(initial);
            finishBatchQuietly();
            updateButtons();
            return;
        }
        List<String> fileNames = new ArrayList<>();
        for (PackSource source : discoveredPacks) {
            fileNames.add(source.fileName);
        }
        AudioPackInstallSelection.Result selection;
        try {
            selection = AudioPackInstallSelection.select(fileNames, initial);
        } catch (IllegalArgumentException error) {
            installing = false;
            statusText.setText("CATALOG SOURCE TABLE INVALID");
            detailText.setText(error.getMessage());
            appendLog("FAILED  SOURCE_SELECTION · " + error.getMessage());
            finishBatchQuietly();
            updateButtons();
            return;
        }
        packsToInstall.clear();
        for (AudioPackInstallSelection.Candidate candidate : selection.candidates) {
            packsToInstall.add(new PlannedPackSource(
                discoveredPacks.get(candidate.discoveredIndex),
                candidate.expectedIdentity
            ));
        }
        for (String unavailable : selection.unavailableFileNames) {
            appendLog("MISSING ON USB  " + unavailable);
        }
        cancelRequested = false;
        installIndex = 0;
        succeeded = 0;
        failed = 0;
        retryState.reset();
        batchTracker = new AudioPackBatchTracker();
        appendLog("Trying " + packsToInstall.size() + " USB candidate(s) for " +
            initial.getMissing().size() + " missing pack(s); skipping " +
            initial.getExactInstalled().size() + " already exact pack(s)…");
        updateButtons();
        if (packsToInstall.isEmpty()) {
            installing = false;
            verifyFinishedBatch();
            return;
        }
        installNext();
    }

    private void installNext() {
        if (!installing) {
            return;
        }
        if (cancelRequested) {
            finishCanceled("Cancellation requested before the next USB candidate was opened.");
            return;
        }
        while (installIndex < packsToInstall.size() &&
            !retryState.needsAttempt(packsToInstall.get(installIndex).expectedIdentity)
        ) {
            PlannedPackSource skipped = packsToInstall.get(installIndex);
            appendLog("SKIPPED COPY  " + skipped.source.label + " · " +
                skipped.expectedIdentity + " is already installed from another USB candidate");
            installIndex += 1;
        }
        if (installIndex >= packsToInstall.size()) {
            installing = false;
            cancelRequested = false;
            verifyFinishedBatch();
            updateButtons();
            return;
        }
        PlannedPackSource planned = packsToInstall.get(installIndex);
        PackSource source = planned.source;
        statusText.setText("Pack " + (installIndex + 1) + "/" + packsToInstall.size() + " · " + source.label);
        detailText.setText(AudioPackInstallContract.STAGE_CONNECT + " · Opening source");
        progressBar.setProgress(0);
        IAudioPackInstallService service = installService;
        serviceExecutor.execute(() -> {
            ParcelFileDescriptor descriptor = null;
            try {
                descriptor = source.open(this);
                if (cancelRequested) {
                    postIfAlive(() -> finishCanceled(
                        "Cancellation requested while the USB source was opening; no new pack was submitted."
                    ));
                    return;
                }
                if (service == null) {
                    throw new RemoteException("Main app service disconnected before source submission");
                }
                service.install(descriptor, source.label, source.sizeBytes, callback);
            } catch (Exception error) {
                postIfAlive(() -> {
                    if (!InstallerLifecyclePolicy.continueAfterLocalSourceFailure(cancelRequested)) {
                        finishCanceled(AudioPackInstallContract.STAGE_CANCELED +
                            " · USB source submission stopped before commit · " + errorMessage(error));
                        return;
                    }
                    failed += 1;
                    appendLog("FAILED  " + source.label + " · " + AudioPackInstallContract.STAGE_CONNECT +
                        " · " + AudioPackInstallContract.ERROR_CONNECT + " · " + errorMessage(error));
                    installIndex += 1;
                    installNext();
                });
            } finally {
                if (descriptor != null) {
                    try {
                        descriptor.close();
                    } catch (Exception ignored) {
                    }
                }
            }
        });
    }

    private void cancelInstall() {
        IAudioPackInstallService service = installService;
        if (!installing || service == null) {
            return;
        }
        cancelRequested = true;
        updateButtons();
        statusText.setText("Canceling active pack…");
        detailText.setText(AudioPackInstallContract.STAGE_CANCELED +
            " · Waiting for the main app to close the active transaction safely");
        cancelExecutor.execute(() -> {
            try {
                service.cancel();
            } catch (RemoteException error) {
                postIfAlive(() -> appendLog("FAILED  CANCEL · " +
                    AudioPackInstallContract.ERROR_CONNECT + " · Main app disconnected; " +
                    "waiting for the local source operation to stop"));
            }
        });
    }

    private void finishCanceled(String detail) {
        installing = false;
        cancelRequested = false;
        int skipped = Math.max(0, packsToInstall.size() - installIndex);
        progressBar.setIndeterminate(false);
        progressBar.setProgress(0);
        statusText.setText("Canceled · " + succeeded + " installed · " + failed + " failed · " +
            skipped + " skipped");
        detailText.setText(detail);
        finishBatchQuietly();
        updateButtons();
    }

    private void showProgress(String label, String stage, String detail, long completed, long total) {
        statusText.setText("Pack " + (installIndex + 1) + "/" + packsToInstall.size() + " · " + label);
        String bytes = total > 0L ? formatBytes(completed) + " / " + formatBytes(total) : formatBytes(completed);
        detailText.setText(stage + " · Stage progress · " + detail + (completed > 0L ? " · " + bytes : ""));
        int stageProgress = AudioPackInstallContract.stageProgressPermille(completed, total);
        if (isWarning(detail)) {
            appendLog("WARNING  " + label + " · " + detail);
        }
        progressBar.setIndeterminate(stageProgress < 0);
        if (stageProgress >= 0) {
            progressBar.setProgress(stageProgress);
        }
    }

    private void refreshInventory(String connectedDetail) {
        IAudioPackInstallService service = installService;
        if (service == null) {
            return;
        }
        serviceOperationPending = true;
        updateButtons();
        serviceExecutor.execute(() -> {
            try {
                AudioPackInventorySnapshot inventory = service.getInventorySnapshot();
                postIfAlive(() -> {
                    serviceOperationPending = false;
                    latestInventory = inventory;
                    showInventoryStatus(inventory, connectedDetail);
                    appendInventoryToLog(inventory);
                    updateButtons();
                });
            } catch (Exception error) {
                postIfAlive(() -> {
                    serviceOperationPending = false;
                    latestInventory = null;
                    statusText.setText("Main app inventory check failed");
                    detailText.setText(errorMessage(error));
                    appendLog("FAILED  INVENTORY · " + errorMessage(error));
                    updateButtons();
                });
            }
        });
    }

    private void verifyFinishedBatch() {
        IAudioPackInstallService service = installService;
        if (service == null) {
            progressBar.setProgress(0);
            statusText.setText("FINAL VERIFICATION FAILED");
            detailText.setText("The main app disconnected before it could verify the installed library.");
            batchTracker = null;
            return;
        }
        serviceOperationPending = true;
        statusText.setText("Verifying installed audio library…");
        detailText.setText("FINAL_VERIFY · Comparing private storage with the main app catalog");
        progressBar.setIndeterminate(true);
        updateButtons();
        serviceExecutor.execute(() -> {
            try {
                AudioPackInventorySnapshot inventory = service.finishBatch();
                postIfAlive(() -> finishBatchUi(inventory));
            } catch (Exception error) {
                postIfAlive(() -> finishBatchFailureUi(error));
            }
        });
    }

    private void finishBatchUi(AudioPackInventorySnapshot inventory) {
            serviceOperationPending = false;
            latestInventory = inventory;
            AudioPackBatchTracker.Report report = batchTracker == null ?
                new AudioPackBatchTracker().finish(latestInventory) :
                batchTracker.finish(latestInventory);
            progressBar.setIndeterminate(false);
            progressBar.setProgress(report.isReady() ? PROGRESS_MAX : 0);
            showInventoryStatus(
                latestInventory,
                "Final verification · " + succeeded + " accepted · " + failed + " rejected"
            );
            appendInventoryToLog(latestInventory);
            appendBatchProblems(report);
            batchTracker = null;
            updateButtons();
    }

    private void finishBatchFailureUi(Exception error) {
            serviceOperationPending = false;
            latestInventory = null;
            progressBar.setIndeterminate(false);
            progressBar.setProgress(0);
            statusText.setText("FINAL VERIFICATION FAILED");
            detailText.setText(errorMessage(error));
            appendLog("FAILED  FINAL_VERIFY · " + errorMessage(error));
            batchTracker = null;
            updateButtons();
    }

    private void finishBatchQuietly() {
        IAudioPackInstallService service = installService;
        if (service == null) {
            batchTracker = null;
            return;
        }
        serviceOperationPending = true;
        serviceExecutor.execute(() -> {
            try {
                AudioPackInventorySnapshot inventory = service.finishBatch();
                postIfAlive(() -> {
                    serviceOperationPending = false;
                    latestInventory = inventory;
                    appendInventoryToLog(inventory);
                    batchTracker = null;
                    updateButtons();
                });
            } catch (Exception error) {
                postIfAlive(() -> {
                    serviceOperationPending = false;
                    appendLog("FAILED  FINAL_VERIFY · " + errorMessage(error));
                    batchTracker = null;
                    updateButtons();
                });
            }
        });
    }

    private void cleanupObsoletePacks() {
        IAudioPackInstallService service = installService;
        if (service == null || installing || scanning || serviceOperationPending || latestInventory == null) {
            return;
        }
        int obsoleteBefore = latestInventory.getStale().size() + latestInventory.getExtra().size();
        serviceOperationPending = true;
        statusText.setText("Cleaning obsolete audio packs…");
        detailText.setText(AudioPackInstallContract.STAGE_CLEANUP +
            " · Current catalog packs and already-open audio mappings remain untouched");
        progressBar.setIndeterminate(true);
        updateButtons();
        serviceExecutor.execute(() -> {
            try {
                AudioPackInventorySnapshot inventory = service.cleanupObsoletePacks();
                postIfAlive(() -> {
                    serviceOperationPending = false;
                    latestInventory = inventory;
                    progressBar.setIndeterminate(false);
                    progressBar.setProgress(0);
                    int obsoleteAfter = inventory.getStale().size() + inventory.getExtra().size();
                    int removed = Math.max(0, obsoleteBefore - obsoleteAfter);
                    showInventoryStatus(inventory, "Cleanup complete · " + removed + " obsolete packs removed");
                    appendLog("CLEANUP OK  " + removed + " obsolete packs removed");
                    appendInventoryToLog(inventory);
                    updateButtons();
                });
            } catch (Exception error) {
                postIfAlive(() -> {
                    serviceOperationPending = false;
                    progressBar.setIndeterminate(false);
                    progressBar.setProgress(0);
                    statusText.setText("CLEANUP FAILED");
                    detailText.setText(errorMessage(error));
                    appendLog("FAILED  CLEANUP · " + errorMessage(error));
                    updateButtons();
                });
            }
        });
    }

    private void drainProgressUi() {
        ProgressUiUpdate update;
        synchronized (progressLock) {
            update = pendingProgress;
            pendingProgress = null;
            progressUiPosted = false;
        }
        if (!destroyed && update != null) {
            showProgress(update.label, update.stage, update.detail, update.completed, update.total);
        }
    }

    private void discardPendingProgressUi() {
        synchronized (progressLock) {
            pendingProgress = null;
            progressUiPosted = false;
        }
    }

    private void postIfAlive(Runnable action) {
        if (destroyed) {
            return;
        }
        mainHandler.post(() -> {
            if (!destroyed) {
                action.run();
            }
        });
    }

    private void showInventoryStatus(AudioPackInventorySnapshot inventory, String prefix) {
        if (!inventory.isCatalogAvailable()) {
            statusText.setText("CATALOG UNAVAILABLE · installation blocked");
            detailText.setText(inventory.getCatalogError());
            return;
        }
        int exact = inventory.getExactInstalled().size();
        int expected = inventory.getExpected().size();
        statusText.setText((inventory.isReady() ? "READY" : "INCOMPLETE") + " · " + exact + "/" +
            expected + " exact audio packs");
        detailText.setText(prefix + " · missing " + inventory.getMissing().size() + " · stale " +
            inventory.getStale().size() + " · extra " + inventory.getExtra().size() +
            (inventory.getAvailablePrivateBytes() >= 0L ?
                " · free " + formatBytes(inventory.getAvailablePrivateBytes()) : ""));
    }

    private void appendInventoryToLog(AudioPackInventorySnapshot inventory) {
        if (inventory == null) {
            return;
        }
        appendLog("INVENTORY  exact " + inventory.getExactInstalled().size() + "/" +
            inventory.getExpected().size() + " · missing " + inventory.getMissing().size() +
            " · stale " + inventory.getStale().size() + " · extra " + inventory.getExtra().size());
        if (inventory.getAvailablePrivateBytes() >= 0L) {
            appendLog("PRIVATE STORAGE FREE  " + formatBytes(inventory.getAvailablePrivateBytes()));
        }
        if (!inventory.isCatalogAvailable()) {
            appendLog("CATALOG ERROR  " + inventory.getCatalogError());
        }
        appendIdentityList("MISSING", inventory.getMissing());
        appendIdentityList("STALE INSTALLED", inventory.getStale());
        appendIdentityList("EXTRA INSTALLED", inventory.getExtra());
    }

    private void appendIdentityList(String label, List<AudioPackIdentity> identities) {
        for (AudioPackIdentity identity : identities) {
            appendLog(label + "  " + identity);
        }
    }

    private void appendBatchProblems(AudioPackBatchTracker.Report report) {
        for (String duplicate : report.duplicateSources) {
            appendLog("DUPLICATE INPUT  " + duplicate);
        }
        for (String stale : report.staleSources) {
            appendLog("STALE INPUT  " + stale);
        }
        for (String extra : report.extraSources) {
            appendLog("EXTRA INPUT  " + extra);
        }
    }

    private void setScanningUi(String status) {
        scanning = true;
        statusText.setText(status);
        detailText.setText("Looking for .bydpack files without copying them…");
        progressBar.setIndeterminate(true);
        updateButtons();
    }

    private void updateButtons() {
        boolean usableCatalog = latestInventory != null && latestInventory.isCatalogAvailable() &&
            !latestInventory.getExpected().isEmpty();
        scanButton.setEnabled(!installing && !scanning);
        chooseTreeButton.setEnabled(!installing && !scanning);
        installButton.setEnabled(
            !installing && !scanning && !serviceOperationPending && installService != null && usableCatalog &&
                !discoveredPacks.isEmpty() &&
                !latestInventory.isReady()
        );
        cancelButton.setEnabled(installing && installService != null && !cancelRequested);
        cleanupButton.setEnabled(
            !installing && !scanning && !serviceOperationPending && installService != null && latestInventory != null &&
                (!latestInventory.getStale().isEmpty() || !latestInventory.getExtra().isEmpty())
        );
    }

    private void appendLog(String message) {
        logText.append("\n" + message);
    }

    private TextView text(String value, int sp, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(color);

        return view;
    }

    private Button button(String label, View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText(label);
        button.setOnClickListener(listener);

        return button;
    }

    private LinearLayout.LayoutParams weightedButton() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(52), 1f);
        params.setMargins(dp(4), 0, dp(4), 0);

        return params;
    }

    private LinearLayout.LayoutParams margins(int vertical) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.setMargins(0, vertical, 0, vertical);

        return params;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static String formatBytes(long bytes) {
        if (bytes < 0L) {
            return "size unknown";
        }
        if (bytes < 1024L) {
            return bytes + " B";
        }
        double value = bytes;
        String[] units = {"KiB", "MiB", "GiB"};
        int index = -1;
        do {
            value /= 1024.0;
            index += 1;
        } while (value >= 1024.0 && index < units.length - 1);

        return String.format(Locale.US, "%.1f %s", value, units[index]);
    }

    private static String errorMessage(Exception error) {
        String message = error.getMessage();

        return message == null || message.trim().isEmpty() ? error.getClass().getSimpleName() : message;
    }

    private static boolean isWarning(String detail) {
        return detail.startsWith("Retention warning:") || detail.startsWith("Cleanup warning:");
    }

    private static String safeCallbackText(String value, String fallback) {
        return value == null || value.trim().isEmpty() ? fallback : value;
    }

    private static final int STORAGE_PERMISSION_REQUEST = 40;
    private static final int TREE_REQUEST = 41;
    private static final int PROGRESS_MAX = 1_000;
    private static final long PROGRESS_UI_INTERVAL_MS = 50L;

    private static final class ProgressUiUpdate {
        final String label;
        final String stage;
        final String detail;
        final long completed;
        final long total;

        ProgressUiUpdate(String label, String stage, String detail, long completed, long total) {
            this.label = label;
            this.stage = stage;
            this.detail = detail;
            this.completed = completed;
            this.total = total;
        }
    }

    private static final class PlannedPackSource {
        final PackSource source;
        final AudioPackIdentity expectedIdentity;

        PlannedPackSource(PackSource source, AudioPackIdentity expectedIdentity) {
            this.source = source;
            this.expectedIdentity = expectedIdentity;
        }
    }

    private abstract static class PackSource {
        final String label;
        final String fileName;
        final long sizeBytes;

        PackSource(String label, String fileName, long sizeBytes) {
            this.label = label;
            this.fileName = fileName;
            this.sizeBytes = sizeBytes;
        }

        abstract ParcelFileDescriptor open(Context context) throws FileNotFoundException;

        static PackSource forFile(File file) {
            return new PackSource(file.getAbsolutePath(), file.getName(), file.length()) {
                @Override
                ParcelFileDescriptor open(Context context) throws FileNotFoundException {
                    return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
                }
            };
        }

        static PackSource forDocument(DocumentTreePackScanner.Pack pack) {
            String fileName = pack.displayName.substring(pack.displayName.lastIndexOf('/') + 1);
            return new PackSource(pack.displayName, fileName, pack.sizeBytes) {
                @Override
                ParcelFileDescriptor open(Context context) throws FileNotFoundException {
                    ParcelFileDescriptor descriptor = context.getContentResolver().openFileDescriptor(pack.uri, "r");
                    if (descriptor == null) {
                        throw new FileNotFoundException("Document provider returned no file descriptor");
                    }

                    return descriptor;
                }
            };
        }
    }
}
