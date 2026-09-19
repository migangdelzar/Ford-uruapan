package com.ford.service.ingestion.application;

import com.ford.service.ingestion.application.port.out.AuthorizedScraperSource;
import com.ford.service.ingestion.application.port.out.FordCatalogSource;
import com.ford.service.ingestion.application.port.out.FordInventorySource;
import com.ford.service.ingestion.application.port.out.ManualImportSource;
import com.ford.service.ingestion.application.port.out.SyncRunRepository;
import com.ford.service.ingestion.domain.SourceNormalization;
import com.ford.service.ingestion.domain.SourceSnapshot;
import com.ford.service.ingestion.domain.SyncRequest;
import com.ford.service.ingestion.domain.SyncResult;
import com.ford.service.observability.AiObservationService;
import com.ford.service.observability.DefaultAiObservationService;
import com.ford.service.shared.SourceCandidate;
import io.micrometer.observation.ObservationRegistry;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import java.util.Map;
import java.util.function.Supplier;

public final class DefaultSyncCoordinator implements SyncCoordinator {

    private final FordCatalogSource catalogSource;
    private final FordInventorySource inventorySource;
    private final AuthorizedScraperSource scraperSource;
    private final ManualImportSource manualSource;
    private final SyncRunRepository syncRunRepository;
    private final SourceNormalization normalization;
    private final int maxAttempts;
    private final AiObservationService observations;

    public DefaultSyncCoordinator(FordCatalogSource catalogSource,
                                  FordInventorySource inventorySource,
                                  AuthorizedScraperSource scraperSource,
                                  ManualImportSource manualSource,
                                  SyncRunRepository syncRunRepository,
                                  SourceNormalization normalization,
                                  int maxAttempts) {
        this(catalogSource, inventorySource, scraperSource, manualSource, syncRunRepository,
                normalization, maxAttempts, new DefaultAiObservationService(ObservationRegistry.NOOP));
    }

    public DefaultSyncCoordinator(FordCatalogSource catalogSource,
                                  FordInventorySource inventorySource,
                                  AuthorizedScraperSource scraperSource,
                                  ManualImportSource manualSource,
                                  SyncRunRepository syncRunRepository,
                                  SourceNormalization normalization,
                                  int maxAttempts,
                                  AiObservationService observations) {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be positive");
        }
        this.catalogSource = java.util.Objects.requireNonNull(catalogSource);
        this.inventorySource = java.util.Objects.requireNonNull(inventorySource);
        this.scraperSource = java.util.Objects.requireNonNull(scraperSource);
        this.manualSource = java.util.Objects.requireNonNull(manualSource);
        this.syncRunRepository = java.util.Objects.requireNonNull(syncRunRepository);
        this.normalization = java.util.Objects.requireNonNull(normalization);
        this.maxAttempts = maxAttempts;
        this.observations = java.util.Objects.requireNonNull(observations);
    }

    @Override
    public SyncResult run(SyncRequest request) {
        return observations.observe("ford.catalog.sync", Map.of("outcome", "success"),
                () -> runInternal(request));
    }

    private SyncResult runInternal(SyncRequest request) {
        var syncRunId = UUID.randomUUID();
        syncRunRepository.start(syncRunId, request);
        var snapshots = new ArrayList<SourceSnapshot>();
        try {
            snapshots.add(fetch(() -> catalogSource.fetchCatalog(request)));
            snapshots.add(fetch(() -> inventorySource.fetchInventory(request)));
            snapshots.add(fetch(() -> scraperSource.fetchAuthorized(request)));
            snapshots.add(fetch(() -> manualSource.fetchManual(request)));
        } catch (RuntimeException exception) {
            syncRunRepository.fail(syncRunId, safeMessage(exception));
            return new SyncResult(syncRunId, SyncResult.Status.FAILED, 0, 0, 0, false, safeMessage(exception));
        }

        var unique = new LinkedHashMap<String, SourceCandidate>();
        var quarantined = 0;
        var stale = false;
        for (var snapshot : snapshots) {
            var result = normalization.normalize(snapshot, request.requestedAt());
            quarantined += result.quarantined().size();
            for (var candidate : result.accepted()) {
                unique.putIfAbsent(candidate.metadata().sourceSystem() + ":" + candidate.metadata().sourceRecordId(), candidate);
                stale |= candidate.metadata().freshnessStatus().name().equals("STALE");
            }
        }
        var syncResult = new SyncResult(syncRunId, SyncResult.Status.COMPLETED,
                unique.size() + quarantined, unique.size(), quarantined, stale, null);
        syncRunRepository.complete(syncRunId, syncResult);
        return syncResult;
    }

    private SourceSnapshot fetch(Supplier<SourceSnapshot> source) {
        RuntimeException last = null;
        for (var attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return source.get();
            } catch (RuntimeException exception) {
                last = exception;
                if (attempt < maxAttempts) {
                    backoff(attempt);
                }
            }
        }
        throw last;
    }

    private void backoff(int attempt) {
        try {
            Thread.sleep(Duration.ofMillis(Math.min(250L * attempt, 1_000L)));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Sync retry interrupted", exception);
        }
    }

    private static String safeMessage(Throwable exception) {
        var message = exception.getMessage();
        return message == null || message.isBlank() ? exception.getClass().getSimpleName() : message;
    }
}
