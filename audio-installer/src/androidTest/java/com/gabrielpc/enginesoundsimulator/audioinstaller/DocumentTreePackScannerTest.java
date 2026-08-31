package com.gabrielpc.enginesoundsimulator.audioinstaller;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertThrows;

import android.content.Context;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public final class DocumentTreePackScannerTest {
    @Test
    public void recursivelyFindsPacksFromStorageAccessFrameworkProvider() {
        Context context = InstrumentationRegistry.getInstrumentation().getContext();
        Uri tree = DocumentsContract.buildTreeDocumentUri(TestDocumentsProvider.AUTHORITY, "root");
        List<String> progress = new ArrayList<>();

        List<DocumentTreePackScanner.Pack> packs = DocumentTreePackScanner.scan(
            context.getContentResolver(),
            tree,
            (name, count) -> progress.add(name + ":" + count)
        );

        assertEquals(2, packs.size());
        assertEquals("USB/nested/engine.BYDPACK", packs.get(0).displayName);
        assertEquals(-1L, packs.get(0).sizeBytes);
        assertEquals("USB/root.bydpack", packs.get(1).displayName);
        assertEquals(11L, packs.get(1).sizeBytes);
        assertTrue(progress.contains("USB:0"));
        assertTrue(progress.contains("USB/nested:2"));
    }

    @Test
    public void rejectsAnUnboundedSafPackListing() {
        Context context = InstrumentationRegistry.getInstrumentation().getContext();
        Uri tree = DocumentsContract.buildTreeDocumentUri(TestDocumentsProvider.AUTHORITY, "many");

        PackScanLimitException error = assertThrows(
            PackScanLimitException.class,
            () -> DocumentTreePackScanner.scan(context.getContentResolver(), tree, (name, count) -> { })
        );

        assertTrue(error.getMessage().contains("more .bydpack files"));
    }

    @Test
    public void unknownSizeSafPackCanStillBeOpenedAsAReadOnlyDescriptor() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getContext();
        Uri tree = DocumentsContract.buildTreeDocumentUri(TestDocumentsProvider.AUTHORITY, "root");
        List<DocumentTreePackScanner.Pack> packs = DocumentTreePackScanner.scan(
            context.getContentResolver(),
            tree,
            (name, count) -> { }
        );
        DocumentTreePackScanner.Pack unknownSize = packs.stream()
            .filter(pack -> pack.sizeBytes < 0L)
            .findFirst()
            .orElseThrow(AssertionError::new);

        ParcelFileDescriptor descriptor = context.getContentResolver().openFileDescriptor(unknownSize.uri, "r");
        assertNotNull(descriptor);
        try (ParcelFileDescriptor.AutoCloseInputStream input =
            new ParcelFileDescriptor.AutoCloseInputStream(descriptor)
        ) {
            assertEquals('p', input.read());
        }
    }

    @Test
    public void nullProviderListingFailsInsteadOfSilentlyReportingAPartialUsbScan() {
        Context context = InstrumentationRegistry.getInstrumentation().getContext();
        Uri tree = DocumentsContract.buildTreeDocumentUri(TestDocumentsProvider.AUTHORITY, "null-listing");

        IllegalStateException error = assertThrows(
            IllegalStateException.class,
            () -> DocumentTreePackScanner.scan(
                context.getContentResolver(),
                tree,
                (name, count) -> { }
            )
        );

        assertEquals("Document provider returned no listing for USB", error.getMessage());
    }
}
