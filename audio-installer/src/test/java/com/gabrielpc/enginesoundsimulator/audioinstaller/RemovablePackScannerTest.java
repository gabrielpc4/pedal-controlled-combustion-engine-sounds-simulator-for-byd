package com.gabrielpc.enginesoundsimulator.audioinstaller;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public final class RemovablePackScannerTest {
    @Rule
    public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void findsNestedPacksInReadableRemovableRootsAndSkipsEmulatedStorage() throws Exception {
        File storage = temporaryFolder.newFolder("storage");
        File usb = new File(storage, "1234-ABCD");
        File nested = new File(usb, "engine-packs/cars");
        nested.mkdirs();
        write(new File(nested, "family-one.bydpack"), 1);
        write(new File(nested, "ignore.zip"), 2);
        File emulated = new File(storage, "emulated/0/Download");
        emulated.mkdirs();
        write(new File(emulated, "not-usb.bydpack"), 3);
        List<String> progress = new ArrayList<>();

        RemovablePackScanner.Result result = RemovablePackScanner.scan(
            storage,
            (path, count) -> progress.add(path + ":" + count)
        );

        assertEquals(1, result.accessibleRoots);
        assertEquals(1, result.packs.size());
        assertEquals("family-one.bydpack", result.packs.get(0).getName());
    }

    @Test
    public void reportsNoAccessibleRootWhenStorageCannotBeListed() throws Exception {
        File missingStorage = new File(temporaryFolder.getRoot(), "missing");

        RemovablePackScanner.Result result = RemovablePackScanner.scan(missingStorage, (path, count) -> { });

        assertEquals(0, result.accessibleRoots);
        assertEquals(0, result.packs.size());
    }

    @Test
    public void rejectsMorePackFilesThanTheBoundedUsbInventoryCanRepresent() throws Exception {
        File storage = temporaryFolder.newFolder("storage-many");
        File usb = new File(storage, "1234-ABCD");
        usb.mkdirs();
        for (int index = 0; index <= PackScanLimits.MAX_PACKS; index += 1) {
            write(new File(usb, String.format("family-%03d.bydpack", index)), index);
        }

        PackScanLimitException error = assertThrows(
            PackScanLimitException.class,
            () -> RemovablePackScanner.scan(storage, (path, count) -> { })
        );

        assertEquals(
            "USB contains more .bydpack files than the safe scan limit",
            error.getMessage()
        );
    }

    @Test
    public void unreadableNestedDirectoryFailsInsteadOfSilentlyReportingAPartialUsbScan() {
        File unreadable = new FakeFile("/storage/1234-ABCD/unreadable", true, null);
        File usb = new FakeFile("/storage/1234-ABCD", true, new File[]{unreadable});
        File storage = new FakeFile("/storage", true, new File[]{usb});

        PackScanAccessException error = assertThrows(
            PackScanAccessException.class,
            () -> RemovablePackScanner.scan(storage, (path, count) -> { })
        );

        assertEquals(
            "USB scan could not read directory /storage/1234-ABCD/unreadable",
            error.getMessage()
        );
    }

    private static void write(File file, int value) throws Exception {
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write(value & 0xff);
        }
    }

    private static final class FakeFile extends File {
        private final boolean directory;
        private final File[] children;

        FakeFile(String path, boolean directory, File[] children) {
            super(path);
            this.directory = directory;
            this.children = children;
        }

        @Override
        public boolean isDirectory() {
            return directory;
        }

        @Override
        public boolean isFile() {
            return false;
        }

        @Override
        public File[] listFiles() {
            return children;
        }
    }
}
