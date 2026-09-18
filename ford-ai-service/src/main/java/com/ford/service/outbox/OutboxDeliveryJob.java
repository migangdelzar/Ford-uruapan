package com.ford.service.outbox;

import org.springframework.scheduling.annotation.Scheduled;

import java.time.Instant;

public final class OutboxDeliveryJob {
    private final OutboxPublisher publisher;

    public OutboxDeliveryJob(OutboxPublisher publisher) {
        this.publisher = publisher;
    }

    @Scheduled(fixedDelayString = "${ford.outbox.fixed-delay-ms:1000}")
    public void deliver() {
        publisher.deliverOne(Instant.now());
    }
}
