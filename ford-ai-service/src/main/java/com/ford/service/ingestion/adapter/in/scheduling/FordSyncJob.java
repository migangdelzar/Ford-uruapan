package com.ford.service.ingestion.adapter.in.scheduling;

import com.ford.service.ingestion.application.SyncCoordinator;
import com.ford.service.ingestion.domain.SyncRequest;
import com.ford.service.shared.PostgresAdvisoryExecutionLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Instant;
import java.util.UUID;

public final class FordSyncJob {
    private static final Logger log = LoggerFactory.getLogger(FordSyncJob.class);
    private final SyncCoordinator coordinator;
    private final UUID tenantId;
    private static final long SYNC_LOCK_KEY = 4_707_294_181_231L;
    private final PostgresAdvisoryExecutionLock executionLock;

    public FordSyncJob(SyncCoordinator coordinator, FordSyncProperties properties, PostgresAdvisoryExecutionLock executionLock) {
        this.coordinator = coordinator;
        this.tenantId = properties.tenantId();
        this.executionLock = executionLock;
    }

    @Scheduled(cron = "${ford.ingestion.cron:0 0 * * * *}")
    public void synchronize() {
        var acquired = executionLock.runIfAcquired(SYNC_LOCK_KEY,
                () -> coordinator.run(new SyncRequest(tenantId, Instant.now())));
        if (!acquired) log.info("Ford catalog sync skipped because another application instance owns the sync lock");
    }
}
