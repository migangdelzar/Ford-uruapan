package com.ford.service.ingestion;

import com.ford.service.ingestion.application.DefaultSyncCoordinator;
import com.ford.service.ingestion.application.port.out.CanonicalDataWriter;
import com.ford.service.ingestion.application.port.out.FordCatalogSource;
import com.ford.service.ingestion.application.port.out.SyncRunRepository;
import com.ford.service.ingestion.domain.SourceNormalization;
import com.ford.service.ingestion.domain.SourceSnapshot;
import com.ford.service.ingestion.domain.SyncRequest;
import com.ford.service.ingestion.domain.SyncResult;
import com.ford.service.shared.FreshnessStatus;
import com.ford.service.shared.SourceCandidate;
import com.ford.service.shared.SourceKind;
import com.ford.service.shared.SourceMetadata;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class SyncCoordinatorPrecedenceTest {

    private static final Instant NOW = Instant.parse("2026-09-18T18:00:00Z");

    @Test
    void writesOnlyTheHighestPrecedenceActiveCandidateForARecord() {
        var writer = new CapturingWriter();
        var official = candidate(SourceKind.OFFICIAL_FEED, "official", NOW);
        var manual = candidate(SourceKind.MANUAL_OVERRIDE, "manual", NOW);
        var catalog = (FordCatalogSource) request -> snapshot(official);
        var coordinator = new DefaultSyncCoordinator(
                catalog,
                request -> new SourceSnapshot(UUID.randomUUID(), List.of()),
                request -> new SourceSnapshot(UUID.randomUUID(), List.of()),
                request -> snapshot(manual),
                new NoopSyncRunRepository(),
                new SourceNormalization(),
                1,
                new com.ford.service.observability.DefaultAiObservationService(
                        io.micrometer.observation.ObservationRegistry.NOOP),
                writer,
                new com.ford.service.shared.DefaultSourcePrecedencePolicy());

        var result = coordinator.run(new SyncRequest(UUID.randomUUID(), NOW));

        assertThat(result.status()).isEqualTo(SyncResult.Status.COMPLETED);
        assertThat(result.recordsApplied()).isEqualTo(1);
        assertThat(writer.candidates).singleElement().extracting(SourceCandidate::payload)
                .asString().contains("manual");
    }

    private static SourceSnapshot snapshot(SourceCandidate candidate) {
        return new SourceSnapshot(UUID.randomUUID(), List.of(candidate));
    }

    private static SourceCandidate candidate(SourceKind kind, String payload, Instant observedAt) {
        return new SourceCandidate(kind, "{\"id\":\"vehicle-1\",\"name\":\"" + payload + "\"}",
                new SourceMetadata("vehicle-feed", "vehicle-1", observedAt,
                        observedAt.minusSeconds(60), null, FreshnessStatus.FRESH, payload, UUID.randomUUID()));
    }

    private static final class CapturingWriter implements CanonicalDataWriter {
        private final List<SourceCandidate> candidates = new ArrayList<>();

        @Override
        public int write(UUID tenantId, UUID syncRunId, List<SourceCandidate> values) {
            candidates.addAll(values);
            return values.size();
        }
    }

    private static final class NoopSyncRunRepository implements SyncRunRepository {
        @Override
        public void start(UUID syncRunId, SyncRequest request) {
        }

        @Override
        public void complete(UUID syncRunId, SyncResult result) {
        }

        @Override
        public void fail(UUID syncRunId, String message) {
        }
    }
}
