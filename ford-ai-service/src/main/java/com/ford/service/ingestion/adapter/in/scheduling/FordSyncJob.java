package com.ford.service.ingestion.adapter.in.scheduling;

import com.ford.service.ingestion.application.SyncCoordinator;
import com.ford.service.ingestion.domain.SyncRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

@Component
@ConditionalOnProperty(name = "ford.ingestion.enabled", havingValue = "true")
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
