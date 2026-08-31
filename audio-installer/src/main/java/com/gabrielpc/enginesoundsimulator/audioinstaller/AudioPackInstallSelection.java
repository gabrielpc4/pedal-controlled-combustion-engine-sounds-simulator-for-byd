package com.gabrielpc.enginesoundsimulator.audioinstaller;

import com.gabrielpc.audiopackcontract.AudioPackExpectedSource;
import com.gabrielpc.audiopackcontract.AudioPackIdentity;
import com.gabrielpc.audiopackcontract.AudioPackInventorySnapshot;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Pure selection step that prevents exact installed packs from ever being opened or copied.
 * Every USB candidate for a missing catalog filename is retained: if one copy is unreadable or
 * corrupt, the activity can safely try the next copy and stop once that identity is installed.
 */
final class AudioPackInstallSelection {
    static Result select(List<String> discoveredFileNames, AudioPackInventorySnapshot inventory) {
        Set<String> missingIdentityKeys = new HashSet<>();
        for (AudioPackIdentity identity : inventory.getMissing()) {
            missingIdentityKeys.add(identity.exactKey());
        }
        Map<String, AudioPackExpectedSource> missingByFileName = new HashMap<>();
        for (AudioPackExpectedSource source : inventory.getExpectedSources()) {
            if (missingIdentityKeys.contains(source.getIdentity().exactKey())) {
                String normalizedFileName = normalizeFileName(source.getSourceFileName());
                AudioPackExpectedSource previous = missingByFileName.put(normalizedFileName, source);
                if (previous != null) {
                    throw new IllegalArgumentException(
                        "Catalog maps more than one missing pack to " + source.getSourceFileName()
                    );
                }
            }
        }
        if (missingByFileName.size() != missingIdentityKeys.size()) {
            throw new IllegalArgumentException("Catalog does not map every missing pack to a USB filename");
        }
        List<Candidate> candidates = new ArrayList<>();
        Set<String> availableFileNames = new HashSet<>();
        for (int index = 0; index < discoveredFileNames.size(); index += 1) {
            String fileName = discoveredFileNames.get(index);
            String normalizedFileName = normalizeFileName(fileName);
            AudioPackExpectedSource expected = missingByFileName.get(normalizedFileName);
            if (expected != null) {
                candidates.add(new Candidate(index, expected.getIdentity()));
                availableFileNames.add(normalizedFileName);
            }
        }
        List<String> unavailable = new ArrayList<>();
        for (Map.Entry<String, AudioPackExpectedSource> entry : missingByFileName.entrySet()) {
            if (!availableFileNames.contains(entry.getKey())) {
                unavailable.add(entry.getValue().getSourceFileName());
            }
        }
        Collections.sort(unavailable, String.CASE_INSENSITIVE_ORDER);

        return new Result(candidates, unavailable);
    }

    static final class Candidate {
        final int discoveredIndex;
        final AudioPackIdentity expectedIdentity;

        Candidate(int discoveredIndex, AudioPackIdentity expectedIdentity) {
            this.discoveredIndex = discoveredIndex;
            this.expectedIdentity = expectedIdentity;
        }
    }

    static final class Result {
        final List<Candidate> candidates;
        final List<String> unavailableFileNames;

        Result(List<Candidate> candidates, List<String> unavailableFileNames) {
            this.candidates = Collections.unmodifiableList(new ArrayList<>(candidates));
            this.unavailableFileNames = Collections.unmodifiableList(new ArrayList<>(unavailableFileNames));
        }
    }

    private static String normalizeFileName(String fileName) {
        return fileName.toLowerCase(Locale.US);
    }

    private AudioPackInstallSelection() {
    }
}
