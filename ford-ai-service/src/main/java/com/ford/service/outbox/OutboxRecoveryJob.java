package com.ford.service.outbox;

import org.springframework.scheduling.annotation.Scheduled;

import java.time.Instant;

public class OutboxRecoveryJob {
    private final OutboxStore store;

    public OutboxRecoveryJob(OutboxStore store) {
        this.store = store;
    }

    @Scheduled(fixedDelayString = "${ford.outbox.recovery-delay-ms:5000}")
    public void reclaimExpired() {
        store.reclaimExpired(Instant.now());
    }
}
