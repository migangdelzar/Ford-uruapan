package com.ford.service.conversations;

import java.nio.file.Path;
import java.util.Map;

/** Selects exactly one validated local catalog export for a replay JVM. */
record LocalCatalogSnapshotSelection(Label label, Path exportDirectory) {
    private static final Path DEFAULT_BASELINE_DIRECTORY = Path.of("build/reports/local-qwen/source-export");

    LocalCatalogSnapshotSelection {
        if (label == null) throw new IllegalArgumentException("snapshot label is required");
        if (exportDirectory == null) throw new IllegalArgumentException("snapshot export directory is required");
        exportDirectory = exportDirectory.toAbsolutePath().normalize();
    }

    static LocalCatalogSnapshotSelection fromEnvironment(Map<String, String> environment) {
        var values = environment == null ? Map.<String, String>of() : environment;
        var labelText = values.getOrDefault("FORD_CATALOG_SNAPSHOT_LABEL", "oct22-baseline");
        var label = switch (labelText) {
            case "oct22-baseline" -> Label.OCT22_BASELINE;
            case "oct25-candidate" -> Label.OCT25_CANDIDATE;
            default -> throw new IllegalArgumentException(
                    "snapshot label must be oct22-baseline or oct25-candidate");
        };
        var directoryText = values.get("FORD_LOCAL_CATALOG_EXPORT_DIR");
        if ((directoryText == null || directoryText.isBlank()) && label == Label.OCT25_CANDIDATE) {
            throw new IllegalArgumentException("candidate export directory is required");
        }
        var directory = directoryText == null || directoryText.isBlank()
                ? DEFAULT_BASELINE_DIRECTORY : Path.of(directoryText);
        return new LocalCatalogSnapshotSelection(label, directory);
    }

    enum Label {
        OCT22_BASELINE("oct22-baseline"), OCT25_CANDIDATE("oct25-candidate");

        private final String wireName;

        Label(String wireName) {
            this.wireName = wireName;
        }

        String wireName() {
            return wireName;
        }
    }
}
