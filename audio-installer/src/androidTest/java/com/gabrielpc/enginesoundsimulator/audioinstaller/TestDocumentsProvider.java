package com.gabrielpc.enginesoundsimulator.audioinstaller;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.List;

/** Lightweight content provider that exposes the exact DocumentsContract cursor shape used by SAF. */
public final class TestDocumentsProvider extends ContentProvider {
    static final String AUTHORITY = "com.gabrielpc.enginesoundsimulator.audioinstaller.test.documents";

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor query(
        Uri uri,
        String[] projection,
        String selection,
        String[] selectionArgs,
        String sortOrder
    ) {
        String parentDocumentId = parentDocumentId(uri.getPathSegments());
        if ("null-listing".equals(parentDocumentId)) {
            return null;
        }
        MatrixCursor cursor = new MatrixCursor(projection == null ? DOCUMENT_COLUMNS : projection);
        if ("root".equals(parentDocumentId)) {
            addDirectory(cursor, "nested", "nested");
            addFile(cursor, "root-pack", "root.bydpack", 11L);
            addFile(cursor, "ignored", "ordinary.zip", 20L);
        } else if ("nested".equals(parentDocumentId)) {
            addFile(cursor, "nested-pack", "engine.BYDPACK", null);
        } else if ("many".equals(parentDocumentId)) {
            for (int index = 0; index <= PackScanLimits.MAX_PACKS; index += 1) {
                addFile(cursor, "pack-" + index, String.format("family-%03d.bydpack", index), 1L);
            }
        }

        return cursor;
    }

    @Override
    public String getType(Uri uri) {
        return "vnd.android.document/directory";
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) {
            throw new FileNotFoundException("Test provider is read-only");
        }
        String documentId = parentDocumentId(uri.getPathSegments());
        if (!"root-pack".equals(documentId) && !"nested-pack".equals(documentId)) {
            throw new FileNotFoundException("Unknown test document " + documentId);
        }
        File file = new File(getContext().getCacheDir(), documentId + ".bydpack");
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write("provider-pack".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        } catch (IOException error) {
            throw new FileNotFoundException(error.getMessage());
        }

        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException();
    }

    private static String parentDocumentId(List<String> segments) {
        int documentIndex = segments.indexOf("document");

        return documentIndex >= 0 && documentIndex + 1 < segments.size() ?
            segments.get(documentIndex + 1) : null;
    }

    private static void addDirectory(MatrixCursor cursor, String id, String name) {
        cursor.newRow()
            .add(DocumentsContract.Document.COLUMN_DOCUMENT_ID, id)
            .add(DocumentsContract.Document.COLUMN_DISPLAY_NAME, name)
            .add(DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.MIME_TYPE_DIR)
            .add(DocumentsContract.Document.COLUMN_SIZE, null);
    }

    private static void addFile(MatrixCursor cursor, String id, String name, Long size) {
        cursor.newRow()
            .add(DocumentsContract.Document.COLUMN_DOCUMENT_ID, id)
            .add(DocumentsContract.Document.COLUMN_DISPLAY_NAME, name)
            .add(DocumentsContract.Document.COLUMN_MIME_TYPE, "application/octet-stream")
            .add(DocumentsContract.Document.COLUMN_SIZE, size);
    }

    private static final String[] DOCUMENT_COLUMNS = {
        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE,
        DocumentsContract.Document.COLUMN_SIZE,
    };
}
