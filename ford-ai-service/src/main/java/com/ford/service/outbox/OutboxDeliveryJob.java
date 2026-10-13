package com.ford.service.outbox;

import org.springframework.scheduling.annotation.Scheduled;
import com.ford.service.shared.PostgresAdvisoryExecutionLock;

import java.time.Instant;

public class OutboxDeliveryJob {
    static final long LOCK_KEY = 4_707_294_181_233L;
    private final OutboxPublisher publisher;
    private final PostgresAdvisoryExecutionLock executionLock;

    public OutboxDeliveryJob(OutboxPublisher publisher, PostgresAdvisoryExecutionLock executionLock) {
        this.publisher = publisher;
        this.executionLock = executionLock;
    }

    @Scheduled(fixedDelayString = "${ford.outbox.fixed-delay-ms:1000}")
    public void deliver() {
        executionLock.runIfAcquired(LOCK_KEY, () -> publisher.deliverOne(Instant.now()));
    }
}
