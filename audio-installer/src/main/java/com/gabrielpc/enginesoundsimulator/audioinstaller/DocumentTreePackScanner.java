package com.gabrielpc.enginesoundsimulator.audioinstaller;

import android.content.ContentResolver;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

final class DocumentTreePackScanner {
    interface ProgressListener {
        void onDirectory(String name, int packsFound);
    }

    static final class Pack {
        final Uri uri;
        final String displayName;
        final long sizeBytes;

        Pack(Uri uri, String displayName, long sizeBytes) {
            this.uri = uri;
            this.displayName = displayName;
            this.sizeBytes = sizeBytes;
        }
    }

    static List<Pack> scan(ContentResolver resolver, Uri treeUri, ProgressListener progressListener) {
        String rootId = DocumentsContract.getTreeDocumentId(treeUri);
        ArrayDeque<Node> pending = new ArrayDeque<>();
        pending.add(new Node(rootId, "USB", 0));
        Set<String> visited = new HashSet<>();
        List<Pack> packs = new ArrayList<>();
        int visitedDirectories = 0;
        int visitedFiles = 0;
        int directoriesSinceProgress = PackScanLimits.PROGRESS_DIRECTORY_INTERVAL;
        long lastProgressNanos = 0L;
        String lastDirectoryLabel = "USB";
        while (!pending.isEmpty()) {
            if (Thread.currentThread().isInterrupted()) {
                return Collections.emptyList();
            }
            Node directory = pending.removeFirst();
            if (!visited.add(directory.documentId)) {
                continue;
            }
            if (directory.depth > PackScanLimits.MAX_DEPTH) {
                throw new PackScanLimitException("Selected USB folder exceeded the directory-depth limit");
            }
            visitedDirectories += 1;
            if (visitedDirectories > PackScanLimits.MAX_DIRECTORIES) {
                throw new PackScanLimitException("Selected USB folder exceeded the directory limit");
            }
            directoriesSinceProgress += 1;
            long now = System.nanoTime();
            if (directoriesSinceProgress >= PackScanLimits.PROGRESS_DIRECTORY_INTERVAL &&
                now - lastProgressNanos >= PackScanLimits.PROGRESS_TIME_INTERVAL_NANOS
            ) {
                progressListener.onDirectory(directory.label, packs.size());
                directoriesSinceProgress = 0;
                lastProgressNanos = now;
            }
            lastDirectoryLabel = directory.label;
            Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, directory.documentId);
            try (Cursor cursor = resolver.query(childrenUri, COLUMNS, null, null, null)) {
                if (cursor == null) {
                    throw new IllegalStateException(
                        "Document provider returned no listing for " + directory.label
                    );
                }
                int idColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID);
                int nameColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME);
                int mimeColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE);
                int sizeColumn = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE);
                while (cursor.moveToNext()) {
                    String childId = cursor.getString(idColumn);
                    String name = cursor.getString(nameColumn);
                    String mime = cursor.getString(mimeColumn);
                    String label = directory.label + "/" + name;
                    if (name == null || label.length() > PackScanLimits.MAX_LABEL_CHARACTERS) {
                        throw new PackScanLimitException("Selected USB folder contains an overlong or unnamed entry");
                    }
                    if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) {
                        pending.addLast(new Node(childId, label, directory.depth + 1));
                    } else if (name.toLowerCase(Locale.US).endsWith(".bydpack")) {
                        visitedFiles += 1;
                        if (visitedFiles > PackScanLimits.MAX_FILES) {
                            throw new PackScanLimitException("Selected USB folder exceeded the file limit");
                        }
                        long size = sizeColumn >= 0 && !cursor.isNull(sizeColumn) ? cursor.getLong(sizeColumn) : -1L;
                        Uri uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, childId);
                        packs.add(new Pack(uri, label, size));
                        if (packs.size() > PackScanLimits.MAX_PACKS) {
                            throw new PackScanLimitException(
                                "Selected USB folder contains more .bydpack files than the safe scan limit"
                            );
                        }
                    } else {
                        visitedFiles += 1;
                        if (visitedFiles > PackScanLimits.MAX_FILES) {
                            throw new PackScanLimitException("Selected USB folder exceeded the file limit");
                        }
                    }
                }
            }
        }
        progressListener.onDirectory(lastDirectoryLabel, packs.size());
        packs.sort(Comparator.comparing(pack -> pack.displayName, String.CASE_INSENSITIVE_ORDER));

        return Collections.unmodifiableList(packs);
    }

    private static final String[] COLUMNS = {
        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE,
        DocumentsContract.Document.COLUMN_SIZE,
    };

    private static final class Node {
        final String documentId;
        final String label;
        final int depth;

        Node(String documentId, String label, int depth) {
            this.documentId = documentId;
            this.label = label;
            this.depth = depth;
        }
    }

    private DocumentTreePackScanner() {
    }
}
