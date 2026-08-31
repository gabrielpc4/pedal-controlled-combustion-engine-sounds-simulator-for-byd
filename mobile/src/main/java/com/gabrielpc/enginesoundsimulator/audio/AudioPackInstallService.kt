package com.gabrielpc.enginesoundsimulator.audio

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.RemoteException
import com.gabrielpc.audiopackcontract.AudioPackInventorySnapshot
import com.gabrielpc.audiopackcontract.AudioPackInstallContract
import com.gabrielpc.audiopackcontract.IAudioPackInstallCallback
import com.gabrielpc.audiopackcontract.IAudioPackInstallService
import com.gabrielpc.enginesoundsimulator.EngineSoundsApplication
import java.io.InterruptedIOException
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Signature-gated bridge that keeps validation and final storage inside the main app. */
class AudioPackInstallService : Service() {
    private val importer by lazy(LazyThreadSafetyMode.NONE) { BydAudioPackImporter(this) }
    private val catalogAuthority by lazy(LazyThreadSafetyMode.NONE) { AudioPackCatalogAuthority(this) }
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "audio-pack-install").apply { isDaemon = true }
    }
    private val operationLock = Any()
    private val activeJob = AtomicReference<InstallJob?>(null)
    private val maintenanceGate = AudioPackMaintenanceGate()
    private val batchState = AudioPackInstallBatchState()

    private val binder = object : IAudioPackInstallService.Stub() {
        override fun getInventorySnapshot(): AudioPackInventorySnapshot {
            enforceAuthorizedCaller()

            return catalogAuthority.snapshot()
        }

        override fun beginBatch(): AudioPackInventorySnapshot {
            enforceAuthorizedCaller()

            return synchronized(operationLock) {
                check(activeJob.get() == null) { "Cannot begin a new batch while a pack is being installed" }
                check(!maintenanceGate.isActive()) { "Cannot begin a new batch while audio-pack cleanup is active" }
                val batchGeneration = batchState.begin()
                try {
                    catalogAuthority.snapshot()
                } catch (error: Throwable) {
                    batchState.abort(batchGeneration)
                    throw error
                }
            }
        }

        override fun install(
            source: ParcelFileDescriptor?,
            sourceLabel: String?,
            sourceBytes: Long,
            callback: IAudioPackInstallCallback?,
        ) {
            enforceAuthorizedCaller()
            val cleanLabel = sourceLabel.orEmpty()
                .replace('\n', ' ')
                .replace('\r', ' ')
                .take(MAX_SOURCE_LABEL_LENGTH)
                .ifBlank { "unnamed .bydpack" }
            if (source == null || callback == null) {
                runCatching { source?.close() }
                callback?.safeFailure(
                    cleanLabel,
                    AudioPackInstallContract.STAGE_CONNECT,
                    AudioPackInstallContract.ERROR_CONNECT,
                    "Missing source or callback",
                )
                return
            }

            val job = synchronized(operationLock) {
                val batchGeneration = batchState.activeGeneration()
                if (batchGeneration == null) {
                    source.close()
                    callback.safeFailure(
                        cleanLabel,
                        AudioPackInstallContract.STAGE_CONNECT,
                        AudioPackInstallContract.ERROR_BATCH_NOT_ACTIVE,
                        "Begin an installation batch before sending audio packs",
                    )
                    return
                }
                if (maintenanceGate.isActive()) {
                    source.close()
                    callback.safeFailure(
                        cleanLabel,
                        AudioPackInstallContract.STAGE_CONNECT,
                        AudioPackInstallContract.ERROR_CONNECT,
                        "Audio-pack cleanup is still active",
                    )
                    return
                }
                val accepted = InstallJob(cleanLabel, sourceBytes, source, callback, batchGeneration)
                if (!activeJob.compareAndSet(null, accepted)) {
                    source.close()
                    callback.safeFailure(
                        cleanLabel,
                        AudioPackInstallContract.STAGE_CONNECT,
                        AudioPackInstallContract.ERROR_CONNECT,
                        "Another audio pack is still being installed",
                    )
                    return
                }

                accepted
            }
            try {
                job.linkToInstallerDeath()
                executor.execute { runInstall(job) }
            } catch (error: RemoteException) {
                synchronized(operationLock) {
                    activeJob.compareAndSet(job, null)
                    batchState.abort(job.batchGeneration)
                }
                job.cancel()
            } catch (error: RejectedExecutionException) {
                synchronized(operationLock) {
                    activeJob.compareAndSet(job, null)
                    batchState.abort(job.batchGeneration)
                }
                job.cancel()
                callback.safeFailure(
                    cleanLabel,
                    AudioPackInstallContract.STAGE_CONNECT,
                    AudioPackInstallContract.ERROR_CONNECT,
                    "The main app is shutting down",
                )
            }
        }

        override fun finishBatch(): AudioPackInventorySnapshot {
            enforceAuthorizedCaller()

            return synchronized(operationLock) {
                check(activeJob.get() == null) { "Cannot verify the library while a pack is being installed" }
                batchState.finish()
                catalogAuthority.snapshot()
            }
        }

        override fun cleanupObsoletePacks(): AudioPackInventorySnapshot {
            enforceAuthorizedCaller()

            return synchronized(operationLock) {
                check(!batchState.isActive()) { "Cannot clean audio packs while an installation batch is active" }
                maintenanceGate.runExclusive(activeInstallJob = activeJob.get() != null) {
                    catalogAuthority.cleanupObsolete()
                }
            }
        }

        override fun cancel() {
            enforceAuthorizedCaller()
            activeJob.get()?.cancel()
        }
    }

    override fun onBind(intent: Intent?): IBinder? {
        if (intent?.action != AudioPackInstallContract.SERVICE_ACTION) return null

        return binder
    }

    override fun onUnbind(intent: Intent?): Boolean {
        // The installer deliberately keeps this binding across picker/background transitions.
        // Reaching onUnbind therefore means its owner is gone: invalidate the batch and stop any
        // descriptor/callback that would otherwise outlive that owner.
        cancelActiveBatch()
        return true
    }

    override fun onDestroy() {
        cancelActiveBatch()
        executor.shutdownNow()
        super.onDestroy()
    }

    private fun runInstall(job: InstallJob) {
        job.workerThread.set(Thread.currentThread())
        val progress = ThrottledProgressCallback(job)
        var result: BydAudioPackImportResult? = null
        var failure: Throwable? = null
        try {
            job.throwIfCanceled()
            ParcelFileDescriptor.AutoCloseInputStream(job.source).use { input ->
                result = importer.importFrom(
                    input = input,
                    sourceBytes = job.sourceBytes,
                    observer = BydAudioPackImportObserver { update ->
                        job.throwIfCanceled()
                        progress.publish(update)
                    },
                    acceptancePolicy = BydAudioPackAcceptancePolicy { manifest ->
                        val requirement = catalogAuthority.requireAccepted(manifest)
                        batchState.requireNotInstalled(job.batchGeneration, requirement)
                    },
                )
            }
            result?.let { installed ->
                batchState.markInstalled(
                    job.batchGeneration,
                    EngineAudioPackRequirement(
                        installed.packId,
                        installed.packVersion,
                        installed.manifestSha256,
                    ),
                )
            }
        } catch (error: Throwable) {
            failure = error
        } finally {
            job.unlinkInstallerDeath()
            runCatching { job.source.close() }
            synchronized(operationLock) {
                activeJob.compareAndSet(job, null)
                if (job.installerDied.get()) {
                    batchState.abort(job.batchGeneration)
                }
            }
            job.workerThread.set(null)
        }

        val installed = result
        if (installed != null && failure == null) {
            (application as EngineSoundsApplication).driveController.retrySelectedCarAudioAfterPackInstall(
                EngineAudioPackRequirement(
                    packId = installed.packId,
                    packVersion = installed.packVersion,
                    manifestSha256 = installed.manifestSha256,
                ),
            )
            installed.retentionWarnings.forEach { warning ->
                job.callback.safeProgress(job.sourceLabel, AudioPackInstallContract.STAGE_CLEANUP, warning)
            }
            job.callback.safeSuccess(job.sourceLabel, installed)
            return
        }
        val error = failure
        val canceled = job.canceled.get() || error is InterruptedIOException || Thread.currentThread().isInterrupted
        job.callback.safeFailure(
            sourceLabel = job.sourceLabel,
            stage = if (canceled) {
                AudioPackInstallContract.STAGE_CANCELED
            } else {
                (error as? BydAudioPackStorageException)?.stage?.name ?: progress.currentStage
            },
            errorCode = if (canceled) {
                AudioPackInstallContract.ERROR_CANCELED
            } else {
                (error as? AudioPackCatalogValidationException)?.errorCode
                    ?: AudioPackInstallContract.ERROR_INSTALLATION
            },
            detail = if (canceled) "Installation canceled; the previous installed pack is unchanged" else
                error.failureDetail(),
        )
    }

    private fun enforceAuthorizedCaller() {
        enforceCallingPermission(
            AudioPackInstallContract.INSTALL_PERMISSION,
            "Only the signed BYD audio installer may install packs",
        )
    }

    private fun cancelActiveBatch() {
        val job = synchronized(operationLock) {
            batchState.abort()
            activeJob.get()
        }
        job?.cancel()
    }

    private class InstallJob(
        val sourceLabel: String,
        val sourceBytes: Long,
        val source: ParcelFileDescriptor,
        val callback: IAudioPackInstallCallback,
        val batchGeneration: Long,
    ) {
        val canceled = AtomicBoolean(false)
        val installerDied = AtomicBoolean(false)
        val workerThread = AtomicReference<Thread?>(null)
        private val deathRecipient = IBinder.DeathRecipient {
            installerDied.set(true)
            cancel()
        }

        fun linkToInstallerDeath() {
            callback.asBinder().linkToDeath(deathRecipient, 0)
        }

        fun unlinkInstallerDeath() {
            runCatching { callback.asBinder().unlinkToDeath(deathRecipient, 0) }
        }

        fun cancel() {
            canceled.set(true)
            runCatching { source.close() }
            workerThread.get()?.interrupt()
        }

        fun throwIfCanceled() {
            if (canceled.get() || Thread.currentThread().isInterrupted) {
                throw InterruptedIOException("Audio-pack installation was canceled")
            }
        }
    }

    private class ThrottledProgressCallback(private val job: InstallJob) {
        var currentStage: String = AudioPackInstallContract.STAGE_CONNECT
            private set
        private var lastDetail = ""
        private var lastBytes = Long.MIN_VALUE
        private var lastSentNanos = 0L

        fun publish(progress: BydAudioPackImportProgress) {
            val stage = progress.stage.name
            val now = System.nanoTime()
            val stageChanged = stage != currentStage
            val byteAdvance = if (lastBytes == Long.MIN_VALUE) Long.MAX_VALUE else progress.completedBytes - lastBytes
            val finished = progress.totalBytes >= 0L && progress.completedBytes >= progress.totalBytes
            if (
                !stageChanged && !finished && byteAdvance < CALLBACK_BYTE_INTERVAL &&
                now - lastSentNanos < CALLBACK_TIME_INTERVAL_NANOS
            ) {
                return
            }

            currentStage = stage
            lastDetail = progress.detail
            lastBytes = progress.completedBytes
            lastSentNanos = now
            try {
                job.callback.onProgress(
                    job.sourceLabel,
                    stage,
                    progress.detail,
                    progress.completedBytes,
                    progress.totalBytes,
                )
            } catch (error: RemoteException) {
                job.cancel()
                throw InterruptedIOException("Audio installer disconnected").apply { initCause(error) }
            }
        }
    }

    private companion object {
        const val MAX_SOURCE_LABEL_LENGTH = 240
        const val CALLBACK_BYTE_INTERVAL = 256L * 1024L
        const val CALLBACK_TIME_INTERVAL_NANOS = 100L * 1_000_000L
    }
}

private fun IAudioPackInstallCallback.safeSuccess(
    sourceLabel: String,
    result: BydAudioPackImportResult,
) {
    runCatching {
        onSucceeded(
            sourceLabel,
            result.packId,
            result.packVersion,
            result.manifestSha256,
            result.fileCount,
            result.installedBytes,
        )
    }
}

private fun IAudioPackInstallCallback.safeFailure(
    sourceLabel: String,
    stage: String,
    errorCode: String,
    detail: String,
) {
    runCatching { onFailed(sourceLabel, stage, errorCode, detail) }
}

private fun IAudioPackInstallCallback.safeProgress(sourceLabel: String, stage: String, detail: String) {
    runCatching { onProgress(sourceLabel, stage, detail, 0L, -1L) }
}

private fun Throwable?.failureDetail(): String {
    if (this == null) return "Audio-pack installation failed before an error detail was available"
    val causalMessages = generateSequence(this) { it.cause }
        .mapNotNull { it.message?.trim()?.takeIf(String::isNotEmpty) }
    val cleanupMessages = generateSequence(this) { it.cause }
        .flatMap { error -> error.suppressed.asSequence() }
        .mapNotNull { it.message?.trim()?.takeIf(String::isNotEmpty) }

    return (causalMessages + cleanupMessages)
        .distinct()
        .take(4)
        .joinToString(": ")
        .ifBlank { this::class.java.simpleName }
}
