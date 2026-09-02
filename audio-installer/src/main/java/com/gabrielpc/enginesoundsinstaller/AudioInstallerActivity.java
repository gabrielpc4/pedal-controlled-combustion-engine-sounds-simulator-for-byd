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

/** Installs the active original Assetto Corsa FMOD group into the dashboard. */
public final class AudioInstallerActivity extends Activity {
    private static final String ORIGINAL_GROUP = "original_cars_pack";
    private static final String MODDED_GROUP = "modded_car_packs";
    private static final Uri INVENTORY_URI = Uri.parse("content://com.gabrielpc.enginesoundsimulator.fmodbanks/packs");
    private final Handler main = new Handler(Looper.getMainLooper());
    private TextView status;
    private ProgressBar progress;
    private Button installAll;
    private Button deleteAll;
    private List<Pack> packs = List.of();
    private int preparedModdedCount;

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(createContent());
        try {
            packs = readIndex();
            refreshIdleState();
        } catch (Exception exception) {
            status.setText("No current original FMOD banks were found. Rebuild this installer after preparing the packs.");
            installAll.setEnabled(false);
        }
    }

    private View createContent() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(48, 36, 48, 36);
        root.setGravity(Gravity.CENTER_VERTICAL);
        root.setBackgroundColor(0xff05080a);
        root.addView(text("ENGINE FMOD BANKS", 30, 0xff00d7e8));
        root.addView(text("ORIGINAL ASSETTO CORSA CARS", 17, 0xffd5e2e8));
        root.addView(text("MODDED CAR PACKS · PREPARED / DISABLED", 14, 0xff71858e));
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
        installAll.setText("INSTALL ORIGINAL CARS");
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
        long carCount = packs.stream().filter(pack -> !pack.dependency).count();
        long installedCars = packs.stream()
                .filter(pack -> !pack.dependency && installed.contains(pack.group + "/" + pack.id))
                .count();
        status.setText(installedCars + " of " + carCount + " original cars installed (" + installed.size() +
                " packages including shared dependencies)" +
                (preparedModdedCount == 0 ? "" : " · " + preparedModdedCount + " modded prepared, disabled"));
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
                    postStatus("Installing " + pack.name + " (" + (index + 1) + "/" + packs.size() + ")", copied, totalBytes);
                    copied += copyPack(pack, totalBytes, copied);
                    waitForPublication(pack.id);
                }
                main.post(() -> {
                    long carCount = packs.stream().filter(pack -> !pack.dependency).count();
                    status.setText("All " + carCount + " original cars and shared dependencies are installed.");
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
        }, "install-original-fmod-banks").start();
    }

    private long copyPack(Pack pack, long totalBytes, long completedBytes) throws IOException {
        Uri destination = Uri.parse(INVENTORY_URI + "/" + pack.group + "/" + pack.id);
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
            if (installedIds().contains(ORIGINAL_GROUP + "/" + packId)) return;
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
            int groupIndex = cursor.getColumnIndex("group");
            int idIndex = cursor.getColumnIndex("id");
            while (cursor.moveToNext() && groupIndex >= 0 && idIndex >= 0) {
                ids.add(cursor.getString(groupIndex) + "/" + cursor.getString(idIndex));
            }
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
                if ("packs".equals(field)) {
                    reader.beginArray();
                    while (reader.hasNext()) {
                        Pack pack = readPack(reader);
                        if (ORIGINAL_GROUP.equals(pack.group) && pack.active) result.add(pack);
                        else if (MODDED_GROUP.equals(pack.group)) preparedModdedCount++;
                    }
                    reader.endArray();
                } else {
                    reader.skipValue();
                }
            }
            reader.endObject();
        }
        if (result.isEmpty()) throw new IOException("Original pack index is empty");
        return result;
    }

    private Pack readPack(JsonReader reader) throws IOException {
        String id = null;
        String name = null;
        String asset = null;
        String group = null;
        long bytes = 0L;
        boolean active = false;
        boolean dependency = false;
        reader.beginObject();
        while (reader.hasNext()) {
            switch (reader.nextName()) {
                case "id": id = reader.nextString(); break;
                case "name": name = reader.nextString(); break;
                case "asset": asset = reader.nextString(); break;
                case "group": group = reader.nextString(); break;
                case "bytes": bytes = reader.nextLong(); break;
                case "active": active = reader.nextBoolean(); break;
                case "dependency": dependency = reader.nextBoolean(); break;
                default: reader.skipValue(); break;
            }
        }
        reader.endObject();
        if (id == null || name == null || asset == null || group == null || bytes <= 0L) {
            throw new IOException("Invalid pack index entry");
        }
        return new Pack(id, name, asset, bytes, group, active, dependency);
    }

    private static final class Pack {
        final String id;
        final String name;
        final String asset;
        final long bytes;
        final String group;
        final boolean active;
        final boolean dependency;

        Pack(String id, String name, String asset, long bytes, String group, boolean active, boolean dependency) {
            this.id = id;
            this.name = name;
            this.asset = asset;
            this.bytes = bytes;
            this.group = group;
            this.active = active;
            this.dependency = dependency;
        }
    }
}
