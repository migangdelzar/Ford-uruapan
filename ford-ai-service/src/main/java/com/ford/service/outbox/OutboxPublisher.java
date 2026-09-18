package com.ford.service.outbox;

import java.time.Instant;

public final class OutboxPublisher {
    private final OutboxStore store;
    private final NotificationPort notificationPort;
    private final int maxAttempts;

    public OutboxPublisher(OutboxStore store, NotificationPort notificationPort, int maxAttempts) {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be positive");
        }
        this.store = store;
        this.notificationPort = notificationPort;
        this.maxAttempts = maxAttempts;
    }

    public void publish(OutboxEvent event) {
        store.append(event);
    }

    public boolean deliverOne(Instant now) {
        var claimed = store.claimNext(now);
        if (claimed.isEmpty()) {
            return false;
        }
        var event = claimed.get();
        try {
            var result = notificationPort.notify(new Notification(event.tenantId(), event.eventType(), event.payload()));
            if (result.accepted()) {
                store.markDelivered(event.id(), result.providerMessageId());
            } else {
                store.markFailed(event.id(), result.error(), event.attempts() >= maxAttempts);
            }
        } catch (RuntimeException exception) {
            store.markFailed(event.id(), safeMessage(exception), event.attempts() >= maxAttempts);
        }
        return true;
    }

    private static String safeMessage(RuntimeException exception) {
        return exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
    }
}
