package com.gabrielpc.enginesoundsimulator.audioinstaller;

import java.io.File;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

final class RemovablePackScanner {
    interface ProgressListener {
        void onDirectory(String path, int packsFound);
    }

    static final class Result {
        final List<File> packs;
        final int accessibleRoots;

        Result(List<File> packs, int accessibleRoots) {
            this.packs = packs;
            this.accessibleRoots = accessibleRoots;
        }
    }

    static Result scan(File storageDirectory, ProgressListener progressListener) {
        File[] roots = safeListFiles(storageDirectory);
        if (roots == null) {
            return new Result(Collections.emptyList(), 0);
        }

        List<File> packs = new ArrayList<>();
        ScanBudget budget = new ScanBudget();
        int accessibleRoots = 0;
        for (File root : roots) {
            if (!isCandidateRoot(root)) {
                continue;
            }
            File[] firstChildren = safeListFiles(root);
            if (firstChildren == null) {
                continue;
            }
            accessibleRoots += 1;
            if (accessibleRoots > PackScanLimits.MAX_ROOTS) {
                throw new PackScanLimitException("USB scan exceeded the removable-root limit");
            }
            scanRoot(root, firstChildren, packs, progressListener, budget);
        }
        packs.sort(Comparator.comparing(File::getAbsolutePath, String.CASE_INSENSITIVE_ORDER));

        return new Result(Collections.unmodifiableList(packs), accessibleRoots);
    }

    private static void scanRoot(
        File root,
        File[] firstChildren,
        List<File> packs,
        ProgressListener progressListener,
        ScanBudget budget
    ) {
        String rootPath = canonicalPath(root);
        if (rootPath == null) {
            return;
        }
        String rootPrefix = rootPath + File.separator;
        ArrayDeque<Node> pending = new ArrayDeque<>();
        for (File child : firstChildren) {
            pending.addLast(new Node(child, 1));
        }
        Set<String> visitedDirectories = new HashSet<>();
        visitedDirectories.add(rootPath);
        ProgressEmitter progress = new ProgressEmitter(progressListener);
        String lastDirectory = root.getAbsolutePath();
        progress.publish(lastDirectory, packs.size(), true);
        while (!pending.isEmpty()) {
            if (Thread.currentThread().isInterrupted()) {
                return;
            }
            Node node = pending.removeFirst();
            File candidate = node.file;
            String canonical = canonicalPath(candidate);
            if (canonical == null || canonical.length() > PackScanLimits.MAX_LABEL_CHARACTERS ||
                !canonical.startsWith(rootPrefix)
            ) {
                continue;
            }
            if (candidate.isFile()) {
                budget.files += 1;
                budget.requireWithinLimits();
                if (candidate.getName().toLowerCase(Locale.US).endsWith(".bydpack")) {
                    packs.add(candidate);
                    budget.packs += 1;
                    budget.requireWithinLimits();
                }
                continue;
            }
            if (!candidate.isDirectory() || !visitedDirectories.add(canonical)) {
                continue;
            }
            if (node.depth > PackScanLimits.MAX_DEPTH) {
                throw new PackScanLimitException("USB scan exceeded the directory-depth limit");
            }
            budget.directories += 1;
            budget.requireWithinLimits();
            File[] children = safeListFiles(candidate);
            if (children == null) {
                throw new PackScanAccessException(
                    "USB scan could not read directory " + candidate.getAbsolutePath()
                );
            }
            for (File child : children) {
                pending.addLast(new Node(child, node.depth + 1));
            }
            lastDirectory = candidate.getAbsolutePath();
            progress.publish(lastDirectory, packs.size(), false);
        }
        progress.publish(lastDirectory, packs.size(), true);
    }

    private static boolean isCandidateRoot(File root) {
        if (!root.isDirectory()) {
            return false;
        }
        String name = root.getName().toLowerCase(Locale.US);

        return !name.equals("emulated") && !name.equals("self");
    }

    private static File[] safeListFiles(File directory) {
        try {
            return directory.listFiles();
        } catch (SecurityException ignored) {
            return null;
        }
    }

    private static String canonicalPath(File file) {
        try {
            return file.getCanonicalPath();
        } catch (IOException | SecurityException ignored) {
            return null;
        }
    }

    private static final class Node {
        final File file;
        final int depth;

        Node(File file, int depth) {
            this.file = file;
            this.depth = depth;
        }
    }

    private static final class ScanBudget {
        int directories;
        int files;
        int packs;

        void requireWithinLimits() {
            if (directories > PackScanLimits.MAX_DIRECTORIES) {
                throw new PackScanLimitException("USB scan exceeded the directory limit");
            }
            if (files > PackScanLimits.MAX_FILES) {
                throw new PackScanLimitException("USB scan exceeded the file limit");
            }
            if (packs > PackScanLimits.MAX_PACKS) {
                throw new PackScanLimitException("USB contains more .bydpack files than the safe scan limit");
            }
        }
    }

    private static final class ProgressEmitter {
        private final ProgressListener listener;
        private int directoriesSincePublish = PackScanLimits.PROGRESS_DIRECTORY_INTERVAL;
        private long lastPublishNanos;

        ProgressEmitter(ProgressListener listener) {
            this.listener = listener;
        }

        void publish(String path, int packsFound, boolean force) {
            directoriesSincePublish += 1;
            long now = System.nanoTime();
            if (!force && (directoriesSincePublish < PackScanLimits.PROGRESS_DIRECTORY_INTERVAL ||
                now - lastPublishNanos < PackScanLimits.PROGRESS_TIME_INTERVAL_NANOS)) {
                return;
            }
            listener.onDirectory(path, packsFound);
            directoriesSincePublish = 0;
            lastPublishNanos = now;
        }
    }

    private RemovablePackScanner() {
    }
}
