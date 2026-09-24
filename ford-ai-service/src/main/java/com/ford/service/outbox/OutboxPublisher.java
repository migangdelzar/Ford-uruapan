package com.ford.service.outbox;

import com.ford.service.observability.AiObservationService;
import com.ford.service.observability.DefaultAiObservationService;
import io.micrometer.observation.ObservationRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

public final class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);
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
        var started = System.nanoTime();
        var claimed = store.claimNext(now);
        if (claimed.isEmpty()) {
            return false;
        }
        var event = claimed.get();
        try {
            var result = notificationPort.notify(new Notification(event.tenantId(), event.eventType(), event.payload()));
            if (result.accepted()) {
                store.markDelivered(event.id(), result.providerMessageId());
                log.info("Outbox delivery completed eventType={} outcome=delivered attempts={} durationMs={}",
                        event.eventType(), event.attempts(), elapsedMs(started));
            } else {
                store.markFailed(event.id(), result.error(), event.attempts() >= maxAttempts);
                log.warn("Outbox delivery rejected eventType={} outcome={} attempts={} durationMs={}",
                        event.eventType(), event.attempts() >= maxAttempts ? "dead_letter" : "retrying",
                        event.attempts(), elapsedMs(started));
            }
        } catch (RuntimeException exception) {
            store.markFailed(event.id(), safeMessage(exception), event.attempts() >= maxAttempts);
            log.error("Outbox delivery failed eventType={} outcome={} attempts={} durationMs={} errorType={}",
                    event.eventType(), event.attempts() >= maxAttempts ? "dead_letter" : "retrying",
                    event.attempts(), elapsedMs(started), exception.getClass().getSimpleName());
            log.debug("Outbox delivery failure details eventType={}", event.eventType(), exception);
        }
        return true;
    }

    private static long elapsedMs(long started) {
        return (System.nanoTime() - started) / 1_000_000L;
    }

    private static String safeMessage(RuntimeException exception) {
        return exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
    }
}
