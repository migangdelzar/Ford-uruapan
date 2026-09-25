package com.ford.service.ingestion.adapter.in.scheduling;

import com.ford.service.ingestion.application.SyncCoordinator;
import com.ford.service.ingestion.domain.SyncRequest;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Instant;
import java.util.UUID;

public final class FordSyncJob {
    private final SyncCoordinator coordinator;
    private final UUID tenantId;

    public FordSyncJob(SyncCoordinator coordinator, FordSyncProperties properties) {
        this.coordinator = coordinator;
        this.tenantId = properties.tenantId();
    }

    @Scheduled(cron = "${ford.ingestion.cron:0 0 * * * *}")
    public void synchronize() {
        coordinator.run(new SyncRequest(tenantId, Instant.now()));
    }
}
