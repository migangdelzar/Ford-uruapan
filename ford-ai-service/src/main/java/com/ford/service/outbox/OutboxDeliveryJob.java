package com.ford.service.outbox;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
@ConditionalOnBean(OutboxPublisher.class)
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
