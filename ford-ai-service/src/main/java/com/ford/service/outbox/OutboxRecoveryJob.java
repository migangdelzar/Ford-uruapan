package com.ford.service.outbox;

import org.springframework.scheduling.annotation.Scheduled;
import com.ford.service.shared.PostgresAdvisoryExecutionLock;

import java.time.Instant;

public class OutboxRecoveryJob {
    private final OutboxStore store;
    private final PostgresAdvisoryExecutionLock executionLock;

    public OutboxRecoveryJob(OutboxStore store, PostgresAdvisoryExecutionLock executionLock) {
        this.store = store;
        this.executionLock = executionLock;
    }

    @Scheduled(fixedDelayString = "${ford.outbox.recovery-delay-ms:5000}")
    public void reclaimExpired() {
        executionLock.runIfAcquired(OutboxDeliveryJob.LOCK_KEY, () -> store.reclaimExpired(Instant.now()));
    }
}
