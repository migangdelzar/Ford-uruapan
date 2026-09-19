package com.ford.service.outbox;

import com.ford.service.observability.AiObservationService;
import com.ford.service.observability.DefaultAiObservationService;
import io.micrometer.observation.ObservationRegistry;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

public final class OutboxPublisher {
    private final OutboxStore store;
    private final NotificationPort notificationPort;
    private final int maxAttempts;
    private final AiObservationService observations;

    public OutboxPublisher(OutboxStore store, NotificationPort notificationPort, int maxAttempts) {
        this(store, notificationPort, maxAttempts,
                new DefaultAiObservationService(ObservationRegistry.NOOP));
    }

    public OutboxPublisher(OutboxStore store, NotificationPort notificationPort, int maxAttempts,
                           AiObservationService observations) {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be positive");
        }
        this.store = Objects.requireNonNull(store);
        this.notificationPort = Objects.requireNonNull(notificationPort);
        this.maxAttempts = maxAttempts;
        this.observations = Objects.requireNonNull(observations);
    }

    public void publish(OutboxEvent event) {
        observations.observe("ford.outbox.publish", Map.of("outcome", "success"), () -> {
            store.append(event);
            return null;
        });
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
