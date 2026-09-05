package com.gabrielpc.enginesoundsinstaller;

import android.app.Activity;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/** Copies bank files selected from a USB folder into the dashboard's verified private store. */
public final class AudioInstallerActivity extends Activity {
    private static final int PICK_SOURCE_FOLDER = 41;
    private static final Uri INVENTORY_URI = Uri.parse("content://com.gabrielpc.enginesoundsimulator.fmodbanks/packs");
    private final Handler main = new Handler(Looper.getMainLooper());
    private TextView status;
    private ProgressBar progress;
    private Button chooseFolder, installOriginal, installModded, installBoth, deleteAll;
    private Uri sourceTree;

    @Override public void onCreate(Bundle state) { super.onCreate(state); setContentView(createContent()); }

    private LinearLayout createContent() {
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setPadding(48, 36, 48, 36); root.setGravity(Gravity.CENTER_VERTICAL); root.setBackgroundColor(0xff05080a);
        root.addView(text("ENGINE FMOD BANK INSTALLER", 28, 0xff00d7e8));
        root.addView(text("Select the USB folder containing .bydbank files, then install a group.", 16, 0xffd5e2e8));
        status = text("No USB source selected.", 18, 0xffffffff); LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-1, -2); sp.topMargin = 28; root.addView(status, sp);
        chooseFolder = action("CHOOSE USB FOLDER", this::chooseSourceFolder); root.addView(chooseFolder);
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal); progress.setMax(1000); root.addView(progress, new LinearLayout.LayoutParams(-1, 18));
        LinearLayout actions = new LinearLayout(this); actions.setGravity(Gravity.START); installOriginal = action("INSTALL ORIGINAL CARS", () -> installGroup("original_cars_pack")); installModded = action("INSTALL MODDED CARS", () -> installGroup("modded_car_packs")); installBoth = action("INSTALL BOTH", () -> installGroup("both")); deleteAll = action("DELETE ALL", this::deleteAll); actions.addView(installOriginal); actions.addView(installModded); actions.addView(installBoth); actions.addView(deleteAll); LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(-1, -2); ap.topMargin = 24; root.addView(actions, ap);
        return root;
    }

    private Button action(String label, Runnable callback) { Button button = new Button(this); button.setText(label); button.setOnClickListener(view -> callback.run()); return button; }
    private void chooseSourceFolder() { Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE); intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION); startActivityForResult(intent, PICK_SOURCE_FOLDER); }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data); if (requestCode != PICK_SOURCE_FOLDER || resultCode != RESULT_OK || data == null) return;
        sourceTree = data.getData(); try { getContentResolver().takePersistableUriPermission(sourceTree, Intent.FLAG_GRANT_READ_URI_PERMISSION); } catch (SecurityException ignored) { }
        status.setText("USB source selected. Choose a car group to copy.");
    }

    private void installGroup(String group) {
        if (sourceTree == null) { status.setText("Choose the fmod_bank_packs folder on USB first."); return; }
        setBusy(true); new Thread(() -> { try {
            List<SourceFile> all = new ArrayList<>(); collectBanks(sourceTree, DocumentsContract.getTreeDocumentId(sourceTree), all); List<SourceFile> selected = new ArrayList<>();
            for (SourceFile file : all) { boolean original = file.name.startsWith("assetto-") || file.name.equals("alfa-romeo-4c.bydbank"); boolean modded = file.name.startsWith("modded-"); boolean shared = file.name.startsWith("assetto-common"); if (shared || "both".equals(group) || ("original_cars_pack".equals(group) && original) || ("modded_car_packs".equals(group) && modded)) selected.add(file); }
            if (selected.isEmpty()) throw new IOException("No matching .bydbank files found in the selected folder");
            for (int i = 0; i < selected.size(); i++) { SourceFile file = selected.get(i); post("Copying " + file.name + " (" + (i + 1) + "/" + selected.size() + ")", i * 1000 / selected.size()); copy(file); }
            main.post(() -> { status.setText("Files copied and verified successfully."); progress.setProgress(1000); setBusy(false); });
        } catch (Exception error) { main.post(() -> { status.setText("Copy failed: " + error.getMessage()); setBusy(false); }); } }, "copy-fmod-banks-from-usb").start();
    }

    private void collectBanks(Uri tree, String documentId, List<SourceFile> output) throws IOException {
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, documentId);
        try (Cursor cursor = getContentResolver().query(children, new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE}, null, null, null)) {
            if (cursor == null) throw new IOException("USB folder could not be read"); int idColumn = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID); int nameColumn = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME); int typeColumn = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE);
            while (cursor.moveToNext()) { String id = cursor.getString(idColumn); String name = cursor.getString(nameColumn); String type = cursor.getString(typeColumn); Uri child = DocumentsContract.buildDocumentUriUsingTree(tree, id); if (DocumentsContract.Document.MIME_TYPE_DIR.equals(type)) collectBanks(tree, id, output); else if (name != null && name.toLowerCase().endsWith(".bydbank")) output.add(new SourceFile(child, name)); }
        }
    }

    private void copy(SourceFile source) throws IOException {
        String group = source.name.startsWith("modded-") ? "modded_car_packs" : "original_cars_pack"; String id = source.name.substring(0, source.name.length() - ".bydbank".length()); Uri destination = Uri.parse(INVENTORY_URI + "/" + group + "/" + id);
        try (InputStream input = getContentResolver().openInputStream(source.uri); android.os.ParcelFileDescriptor descriptor = getContentResolver().openFileDescriptor(destination, "w")) { if (input == null || descriptor == null) throw new IOException("Engine Sounds Simulator is not installed"); try (FileOutputStream output = new FileOutputStream(descriptor.getFileDescriptor())) { byte[] buffer = new byte[256 * 1024]; int count; while ((count = input.read(buffer)) >= 0) output.write(buffer, 0, count); } }
    }

    private void deleteAll() { try { getContentResolver().delete(INVENTORY_URI, null, null); status.setText("All installed FMOD banks were deleted."); progress.setProgress(0); } catch (Exception error) { status.setText("Delete failed: " + error.getMessage()); } }
    private void post(String message, int value) { main.post(() -> { status.setText(message); progress.setProgress(value); }); }
    private void setBusy(boolean busy) { chooseFolder.setEnabled(!busy); installOriginal.setEnabled(!busy); installModded.setEnabled(!busy); installBoth.setEnabled(!busy); deleteAll.setEnabled(!busy); }
    private TextView text(String value, int size, int color) { TextView view = new TextView(this); view.setText(value); view.setTextSize(size); view.setTextColor(color); return view; }
    private static final class SourceFile { final Uri uri; final String name; SourceFile(Uri uri, String name) { this.uri = uri; this.name = name; } }
}
