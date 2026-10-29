package com.ford.service.ingestion;

import com.ford.service.ingestion.application.DefaultSyncCoordinator;
import com.ford.service.ingestion.application.port.out.FordCatalogSource;
import com.ford.service.ingestion.application.port.out.FordInventorySource;
import com.ford.service.ingestion.application.port.out.SyncRunRepository;
import com.ford.service.ingestion.domain.SourceSnapshot;
import com.ford.service.ingestion.domain.SourceNormalization;
import com.ford.service.ingestion.domain.SyncRequest;
import com.ford.service.ingestion.domain.SyncResult;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class SyncCoordinatorTest {

    @Test
    void retriesSafeSourceFailureAndCompletesRun() {
        var repository = new InMemorySyncRunRepository();
        var attempts = new int[1];
        var catalog = (FordCatalogSource) request -> {
            attempts[0]++;
            if (attempts[0] == 1) {
                throw new IllegalStateException("temporary rate limit");
            }
            return new SourceSnapshot(UUID.randomUUID(), List.of());
        };
        var coordinator = new DefaultSyncCoordinator(catalog, request -> new SourceSnapshot(UUID.randomUUID(), List.of()),
                request -> new SourceSnapshot(UUID.randomUUID(), List.of()), request -> new SourceSnapshot(UUID.randomUUID(), List.of()),
                repository, new SourceNormalization(), 2);

        var result = coordinator.run(new SyncRequest(UUID.randomUUID(), Instant.parse("2026-09-18T15:00:00Z")));

        assertThat(result.status()).isEqualTo(SyncResult.Status.COMPLETED);
        assertThat(attempts[0]).isEqualTo(2);
        assertThat(repository.completed).isTrue();
    }

    private static final class InMemorySyncRunRepository implements SyncRunRepository {
        private boolean completed;

        @Override
        public void start(UUID syncRunId, SyncRequest request) {
        }

        @Override
        public void complete(UUID syncRunId, SyncResult result) {
            completed = true;
        }

        @Override
        public void fail(UUID syncRunId, String message) {
        }
    }
}
