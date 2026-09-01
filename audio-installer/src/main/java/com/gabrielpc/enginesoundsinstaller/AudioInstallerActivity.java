package com.gabrielpc.enginesoundsinstaller;

import android.app.Activity;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.util.JsonReader;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Installs the bundled FMOD Studio banks into the dashboard's private bank store. */
public final class AudioInstallerActivity extends Activity {
    private static final Uri INVENTORY_URI = Uri.parse("content://com.gabrielpc.enginesoundsimulator.fmodbanks/packs");
    private final Handler main = new Handler(Looper.getMainLooper());
    private TextView status;
    private ProgressBar progress;
    private Button installAll;
    private Button deleteAll;
    private List<Pack> packs = List.of();

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(createContent());
        try {
            packs = readIndex();
            refreshIdleState();
        } catch (Exception exception) {
            status.setText("No bundled FMOD banks were found. Build this installer after preparing the bank packages.");
            installAll.setEnabled(false);
        }
    }

    private View createContent() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(48, 36, 48, 36);
        root.setGravity(Gravity.CENTER_VERTICAL);
        root.setBackgroundColor(0xff05080a);

        TextView title = text("ENGINE FMOD BANKS", 30, 0xff00d7e8);
        root.addView(title);
        root.addView(text("Install every car FMOD bank into Engine Sounds Simulator.", 17, 0xffd5e2e8));
        status = text("Preparing…", 18, 0xffffffff);
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(-1, -2);
        statusParams.topMargin = 32;
        root.addView(status, statusParams);
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(1000);
        LinearLayout.LayoutParams progressParams = new LinearLayout.LayoutParams(-1, 18);
        progressParams.topMargin = 18;
        root.addView(progress, progressParams);

        LinearLayout actions = new LinearLayout(this);
        actions.setGravity(Gravity.START);
        actions.setPadding(0, 32, 0, 0);
        installAll = new Button(this);
        installAll.setText("INSTALL ALL");
        installAll.setOnClickListener(view -> installAll());
        actions.addView(installAll);
        deleteAll = new Button(this);
        deleteAll.setText("DELETE ALL");
        deleteAll.setOnClickListener(view -> deleteAll());
        LinearLayout.LayoutParams deleteParams = new LinearLayout.LayoutParams(-2, -2);
        deleteParams.leftMargin = 24;
        actions.addView(deleteAll, deleteParams);
        root.addView(actions);
        return root;
    }

    private void refreshIdleState() {
        Set<String> installed = installedIds();
        status.setText(installed.size() + " of " + packs.size() + " FMOD banks installed");
        progress.setProgress(packs.isEmpty() ? 0 : installed.size() * 1000 / packs.size());
    }

    private void installAll() {
        setBusy(true);
        new Thread(() -> {
            long totalBytes = packs.stream().mapToLong(pack -> pack.bytes).sum();
            long copied = 0L;
            try {
                for (int index = 0; index < packs.size(); index++) {
                    Pack pack = packs.get(index);
                    final int current = index + 1;
                    final long before = copied;
                    postStatus("Installing " + pack.name + " (" + current + "/" + packs.size() + ")", before, totalBytes);
                    copied += copyPack(pack, totalBytes, before);
                    waitForPublication(pack.id);
                }
                main.post(() -> {
                    status.setText("All " + packs.size() + " FMOD banks are installed.");
                    progress.setProgress(1000);
                    setBusy(false);
                });
            } catch (Exception exception) {
                String detail = exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
                main.post(() -> {
                    status.setText("Installation failed: " + detail + ". You can retry safely.");
                    setBusy(false);
                });
            }
        }, "install-fmod-banks").start();
    }

    private long copyPack(Pack pack, long totalBytes, long completedBytes) throws IOException {
        Uri destination = Uri.withAppendedPath(INVENTORY_URI, pack.id);
        ParcelFileDescriptor descriptor = getContentResolver().openFileDescriptor(destination, "w");
        if (descriptor == null) throw new IOException("Engine Sounds Simulator is not installed");
        long copied = 0L;
        try (InputStream source = getAssets().open("packs/" + pack.asset);
             FileOutputStream target = new FileOutputStream(descriptor.getFileDescriptor())) {
            byte[] buffer = new byte[256 * 1024];
            int count;
            while ((count = source.read(buffer)) >= 0) {
                target.write(buffer, 0, count);
                copied += count;
                postStatus("Installing " + pack.name, completedBytes + copied, totalBytes);
            }
        } finally {
            descriptor.close();
        }
        return copied;
    }

    private void waitForPublication(String packId) throws IOException {
        for (int attempt = 0; attempt < 100; attempt++) {
            if (installedIds().contains(packId)) return;
            try {
                Thread.sleep(100L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException("Installation interrupted", interrupted);
            }
        }
        throw new IOException("Engine Sounds Simulator did not publish " + packId);
    }

    private void deleteAll() {
        setBusy(true);
        new Thread(() -> {
            try {
                getContentResolver().delete(INVENTORY_URI, null, null);
                main.post(() -> {
                    status.setText("All installed FMOD banks were deleted.");
                    progress.setProgress(0);
                    setBusy(false);
                });
            } catch (Exception exception) {
                main.post(() -> {
                    status.setText("Could not delete the FMOD banks: " + exception.getMessage());
                    setBusy(false);
                });
            }
        }, "delete-fmod-banks").start();
    }

    private Set<String> installedIds() {
        Set<String> ids = new HashSet<>();
        try (Cursor cursor = getContentResolver().query(INVENTORY_URI, null, null, null, null)) {
            if (cursor == null) return ids;
            int index = cursor.getColumnIndex("id");
            while (cursor.moveToNext() && index >= 0) ids.add(cursor.getString(index));
        }
        return ids;
    }

    private void postStatus(String message, long copied, long total) {
        main.post(() -> {
            status.setText(message + " — " + copied / (1024 * 1024) + " / " + total / (1024 * 1024) + " MB");
            progress.setProgress(total <= 0 ? 0 : (int) Math.min(1000L, copied * 1000L / total));
        });
    }

    private void setBusy(boolean busy) {
        installAll.setEnabled(!busy);
        deleteAll.setEnabled(!busy);
    }

    private TextView text(String value, int size, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        return view;
    }

    private List<Pack> readIndex() throws IOException {
        List<Pack> result = new ArrayList<>();
        try (JsonReader reader = new JsonReader(new InputStreamReader(getAssets().open("packs/index.json")))) {
            reader.beginObject();
            while (reader.hasNext()) {
                String field = reader.nextName();
                if (!"packs".equals(field)) {
                    reader.skipValue();
                    continue;
                }
                reader.beginArray();
                while (reader.hasNext()) result.add(readPack(reader));
                reader.endArray();
            }
            reader.endObject();
        }
        if (result.isEmpty()) throw new IOException("Pack index is empty");
        return result;
    }

    private Pack readPack(JsonReader reader) throws IOException {
        String id = null;
        String name = null;
        String asset = null;
        long bytes = 0L;
        reader.beginObject();
        while (reader.hasNext()) {
            switch (reader.nextName()) {
                case "id": id = reader.nextString(); break;
                case "name": name = reader.nextString(); break;
                case "asset": asset = reader.nextString(); break;
                case "bytes": bytes = reader.nextLong(); break;
                default: reader.skipValue(); break;
            }
        }
        reader.endObject();
        if (id == null || name == null || asset == null || bytes <= 0L) throw new IOException("Invalid pack index entry");
        return new Pack(id, name, asset, bytes);
    }

    private static final class Pack {
        final String id;
        final String name;
        final String asset;
        final long bytes;

        Pack(String id, String name, String asset, long bytes) {
            this.id = id;
            this.name = name;
            this.asset = asset;
            this.bytes = bytes;
        }
    }
}
