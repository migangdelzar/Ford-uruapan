package com.ford.service.outbox;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import java.time.Instant;

@Component
@ConditionalOnProperty(name = "ford.outbox.enabled", havingValue = "true")
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
