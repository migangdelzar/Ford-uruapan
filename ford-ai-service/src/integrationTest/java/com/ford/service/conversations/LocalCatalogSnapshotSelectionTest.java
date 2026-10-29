package com.ford.service.conversations;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class LocalCatalogSnapshotSelectionTest {
    @Test
    void defaultsOnlyToTheOctoberBaselineSnapshot() {
        var selection = LocalCatalogSnapshotSelection.fromEnvironment(Map.of());

        assertThat(selection.label()).isEqualTo(LocalCatalogSnapshotSelection.Label.OCT22_BASELINE);
        assertThat(selection.exportDirectory()).isEqualTo(Path.of("build/reports/local-qwen/source-export")
                .toAbsolutePath().normalize());
    }

    @Test
    void requiresAnExplicitExportDirectoryForCandidate() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> LocalCatalogSnapshotSelection.fromEnvironment(
                        Map.of("FORD_CATALOG_SNAPSHOT_LABEL", "oct25-candidate")))
                .withMessageContaining("candidate export directory is required");
    }

    @Test
    void selectsCandidateOnlyWhenBothLabelAndDirectoryAreExplicit() {
        var selection = LocalCatalogSnapshotSelection.fromEnvironment(Map.of(
                "FORD_CATALOG_SNAPSHOT_LABEL", "oct25-candidate",
                "FORD_LOCAL_CATALOG_EXPORT_DIR", "build/candidate-export"));

        assertThat(selection.label()).isEqualTo(LocalCatalogSnapshotSelection.Label.OCT25_CANDIDATE);
        assertThat(selection.exportDirectory()).isEqualTo(Path.of("build/candidate-export")
                .toAbsolutePath().normalize());
    }

    @Test
    void rejectsUnknownLabelsInsteadOfSilentlyCallingThemBaseline() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> LocalCatalogSnapshotSelection.fromEnvironment(
                        Map.of("FORD_CATALOG_SNAPSHOT_LABEL", "candidate-ish")))
                .withMessageContaining("snapshot label must be oct22-baseline or oct25-candidate");
    }
}
