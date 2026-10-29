package com.ford.service.outbox;

@FunctionalInterface
public interface OutboxDeliveryRouter {
    NotificationResult deliver(OutboxEvent event);
}
