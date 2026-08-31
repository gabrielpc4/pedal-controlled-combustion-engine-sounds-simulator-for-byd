package com.gabrielpc.enginesoundsimulator.audioinstaller;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.util.Base64;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.gabrielpc.audiopackcontract.AudioPackInstallContract;
import com.gabrielpc.audiopackcontract.IAudioPackInstallCallback;
import com.gabrielpc.audiopackcontract.IAudioPackInstallService;
import java.io.File;
import java.io.FileOutputStream;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Exercises the real signature permission, Binder FD transfer, API ZipFile, and atomic importer. */
@RunWith(AndroidJUnit4.class)
public final class AudioPackServiceIntegrationTest {
    @Test
    public void signedInstallerCanReadInventoryAndForeignPackIsRejectedBeforeCommit() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File source = new File(context.getCacheDir(), "binder-probe.bydpack");
        try (FileOutputStream output = new FileOutputStream(source)) {
            output.write(Base64.decode(PIPELINE_ZIP64_PACK, Base64.DEFAULT));
        }
        CountDownLatch connected = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);
        AtomicReference<IAudioPackInstallService> service = new AtomicReference<>();
        AtomicReference<String> failure = new AtomicReference<>();
        AtomicReference<String> installedPack = new AtomicReference<>();
        List<ProgressSample> progress = new CopyOnWriteArrayList<>();
        ServiceConnection connection = new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder binder) {
                service.set(IAudioPackInstallService.Stub.asInterface(binder));
                connected.countDown();
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {
                failure.compareAndSet(null, "service disconnected");
                finished.countDown();
            }
        };
        Intent intent = new Intent(AudioPackInstallContract.SERVICE_ACTION).setComponent(
            new ComponentName(AudioPackInstallContract.MAIN_PACKAGE, AudioPackInstallContract.SERVICE_CLASS)
        );
        assertTrue(context.bindService(intent, connection, Context.BIND_AUTO_CREATE));
        try {
            assertTrue(connected.await(5, TimeUnit.SECONDS));
            assertNotNull(service.get().getInventorySnapshot());
            service.get().beginBatch();
            assertThrows(IllegalStateException.class, () -> service.get().beginBatch());
            IAudioPackInstallCallback callback = new IAudioPackInstallCallback.Stub() {
                @Override
                public void onProgress(String label, String stage, String detail, long completed, long total) {
                    progress.add(new ProgressSample(stage, completed, total));
                }

                @Override
                public void onSucceeded(
                    String label,
                    String packId,
                    int version,
                    String manifestSha256,
                    int files,
                    long bytes
                ) {
                    installedPack.set(packId + ":" + version + ":" + files);
                    finished.countDown();
                }

                @Override
                public void onFailed(String label, String stage, String errorCode, String detail) {
                    failure.set(stage + ":" + errorCode + ": " + detail);
                    finished.countDown();
                }
            };
            try (ParcelFileDescriptor descriptor = ParcelFileDescriptor.open(
                source,
                ParcelFileDescriptor.MODE_READ_ONLY
            )) {
                service.get().install(descriptor, source.getName(), source.length(), callback);
            }
            assertTrue(finished.await(15, TimeUnit.SECONDS));
            assertNull(installedPack.get());
            assertNotNull(failure.get());
            assertTrue(
                failure.get().contains(AudioPackInstallContract.ERROR_CATALOG_UNAVAILABLE) ||
                    failure.get().contains(AudioPackInstallContract.ERROR_UNEXPECTED_PACK)
            );
            assertNotNull(service.get().finishBatch());
            assertTrue(progress.stream().anyMatch(sample ->
                AudioPackInstallContract.STAGE_RECEIVE.equals(sample.stage)
            ));
            assertTrue(progress.stream().anyMatch(sample ->
                AudioPackInstallContract.STAGE_OPEN_ARCHIVE.equals(sample.stage)
            ));
            assertTrue(progress.stream().anyMatch(sample ->
                AudioPackInstallContract.STAGE_READ_MANIFEST.equals(sample.stage)
            ));
            assertTrue(progress.stream().anyMatch(sample ->
                AudioPackInstallContract.STAGE_VALIDATE_CATALOG.equals(sample.stage)
            ));
            for (ProgressSample sample : progress) {
                assertTrue(sample.completed >= 0L);
                assertTrue(sample.total < 0L || sample.completed <= sample.total);
            }
        } finally {
            context.unbindService(connection);
            source.delete();
        }
    }

    private static final class ProgressSample {
        final String stage;
        final long completed;
        final long total;

        ProgressSample(String stage, long completed, long total) {
            this.stage = stage;
            this.completed = completed;
            this.total = total;
        }
    }

    private static final String PIPELINE_ZIP64_PACK =
        "UEsDBBQAAAAIAAAAIQD2sSXC2AAAAE8BAAANAAAAbWFuaWZlc3QuanNvbl2PMU/EMAyF9/6KqPPBuWmSpozHxMrAghByE+ca0aZVU0Bwuv9OGonjxGLpPT9/tk8FY2U0PY34REv0UyjvWLXb3BnN24NNsux8sLTczMvUUXnp/c87P1BM6jkJxk655ujab5CI4zzQK4WjD7S/Ru6HCe3tJ35kdh6K/psOX2vmVVz8+T1yqTYct5bzpnHkhJCgbWtMqwyBdloq0NpxXRs0ldFVo5ySKC0XUMtOGGihFle78mGPuFLiCg0Al5bpMQQa4u+P2XQLjnQ/vYd1y0O2z6m+FOfiB1BLAwQtAAAACAAAACEATNw4Y///////////IwAUAHNhbXBsZV9lbmdpbmUvYmluZGVyLXByb2JlL2xvYWQud2F2AQAQAHwAAAAAAAAAgQAAAAAAAAABfACD/1JJRkZ0AAAAV0FWRWZtdCAQAAAAAQABAIC7AAAAdwEAAgAQAGRhdGFQAAAAINGB0eLRQ9Kk0gXTZtPH0yjUidTq1EvVrNUN1m7Wz9Yw15HX8tdT2LTYFdl22dfZONqZ2vraW9u82x3cftzf3EDdod0C3mPexN4l34bf599QSwECFAMUAAAACAAAACEA9rElwtgAAABPAQAADQAAAAAAAAAAAAAAgAEAAAAAbWFuaWZlc3QuanNvblBLAQItAy0AAAAIAAAAIQBM3DhjgQAAAHwAAAAjAAAAAAAAAAAAAACAAQMBAABzYW1wbGVfZW5naW5lL2JpbmRlci1wcm9iZS9sb2FkLndhdlBLBQYAAAAAAgACAIwAAADZAQAAAAA=";
}
