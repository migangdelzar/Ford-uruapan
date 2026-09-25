package com.ford.service.outbox;

import com.ford.service.observability.AiObservationService;
import com.ford.service.observability.DefaultAiObservationService;
import io.micrometer.observation.ObservationRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;

public class OutboxPublisher {
    private final OutboxStore store;
    private final OutboxDeliveryRouter deliveryRouter;
    private final int maxAttempts;
    private final AiObservationService observations;
    private final Duration lease;
    private final Duration backoffBase;
    private final Duration backoffMax;

    public OutboxPublisher(OutboxStore store, OutboxDeliveryRouter deliveryRouter, int maxAttempts) {
        this(store, deliveryRouter, maxAttempts,
                Duration.ofSeconds(30), Duration.ofSeconds(1), Duration.ofMinutes(1),
                new DefaultAiObservationService(ObservationRegistry.NOOP));
    }

    public OutboxPublisher(OutboxStore store, OutboxDeliveryRouter deliveryRouter, int maxAttempts,
                           AiObservationService observations) {
        this(store, deliveryRouter, maxAttempts,
                Duration.ofSeconds(30), Duration.ofSeconds(1), Duration.ofMinutes(1), observations);
    }

    public OutboxPublisher(OutboxStore store, OutboxDeliveryRouter deliveryRouter, int maxAttempts,
                           Duration lease, Duration backoffBase, Duration backoffMax) {
        this(store, deliveryRouter, maxAttempts, lease, backoffBase, backoffMax,
                new DefaultAiObservationService(ObservationRegistry.NOOP));
    }

    public OutboxPublisher(OutboxStore store, OutboxDeliveryRouter deliveryRouter, int maxAttempts,
                           Duration lease, Duration backoffBase, Duration backoffMax,
                           AiObservationService observations) {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be positive");
        }
        if (lease.isNegative() || lease.isZero() || backoffBase.isNegative() || backoffBase.isZero()
                || backoffMax.isNegative() || backoffMax.isZero() || backoffMax.compareTo(backoffBase) < 0) {
            throw new IllegalArgumentException("lease and retry durations must be positive and ordered");
        }
        this.store = Objects.requireNonNull(store);
        this.deliveryRouter = Objects.requireNonNull(deliveryRouter);
        this.maxAttempts = maxAttempts;
        this.lease = Objects.requireNonNull(lease);
        this.backoffBase = Objects.requireNonNull(backoffBase);
        this.backoffMax = Objects.requireNonNull(backoffMax);
        this.observations = Objects.requireNonNull(observations);
    }

    public void publish(OutboxEvent event) {
        observations.observe("ford.outbox.publish", Map.of("outcome", "success"), () -> {
            store.append(event);
            return null;
        });
    }

    public boolean deliverOne(Instant now) {
        store.reclaimExpired(now);
        var claimed = store.claimNext(now, lease);
        if (claimed.isEmpty()) {
            return false;
        }
        var event = claimed.get();
        try {
            var result = deliveryRouter.deliver(event);
            if (result.accepted()) {
                store.markDelivered(event.id(), result.providerMessageId(), now, event.leaseUntil());
            } else if (result.permanent() || event.attempts() >= maxAttempts) {
                store.moveToDeadLetter(event.id(), result.permanent() ? "permanent" : "transient",
                        safeMessage(result.error()), event.leaseUntil());
            } else {
                store.markRetry(event.id(), "transient", now.plus(retryDelay(event.attempts())),
                        safeMessage(result.error()), event.leaseUntil());
            }
        } catch (RuntimeException exception) {
            if (event.attempts() >= maxAttempts) {
                store.moveToDeadLetter(event.id(), "transient", safeMessage(exception), event.leaseUntil());
            } else {
                store.markRetry(event.id(), "transient", now.plus(retryDelay(event.attempts())),
                        safeMessage(exception), event.leaseUntil());
            }
        }
        return true;
    }

    private Duration retryDelay(int attempts) {
        try {
            var multiplier = 1L << Math.min(attempts, 30);
            var candidate = backoffBase.multipliedBy(multiplier);
            return candidate.compareTo(backoffMax) > 0 ? backoffMax : candidate;
        } catch (ArithmeticException exception) {
            return backoffMax;
        }
    }

    private static String safeMessage(RuntimeException exception) {
        return exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
    }

    private static String safeMessage(String message) {
        return message == null || message.isBlank() ? "Notification delivery rejected" : message;
    }
}
