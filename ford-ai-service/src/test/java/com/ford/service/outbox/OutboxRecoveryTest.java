package com.ford.service.outbox;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OutboxRecoveryTest {

    @Test
    void schedulesTransientFailureWithBoundedExponentialBackoff() {
        var store = mock(OutboxStore.class);
        var now = Instant.parse("2026-09-19T15:00:00Z");
        var event = claimedEvent(1, now);
        when(store.claimNext(eq(now), eq(Duration.ofSeconds(30)))).thenReturn(Optional.of(event));
        var publisher = new OutboxPublisher(store, notification -> NotificationResult.rejected("timeout"),
                3, Duration.ofSeconds(30), Duration.ofSeconds(2), Duration.ofSeconds(10));

        assertThat(publisher.deliverOne(now)).isTrue();

        verify(store).reclaimExpired(now);
        verify(store).markRetry(event.id(), "transient", now.plusSeconds(4), "timeout", event.leaseUntil());
    }

    @Test
    void permanentlyRejectedDeliveryMovesDirectlyToDeadLetter() {
        var store = mock(OutboxStore.class);
        var now = Instant.parse("2026-09-19T15:00:00Z");
        var event = claimedEvent(1, now);
        when(store.claimNext(eq(now), eq(Duration.ofSeconds(30)))).thenReturn(Optional.of(event));
        var publisher = new OutboxPublisher(store,
                notification -> NotificationResult.permanentFailure("invalid recipient"),
                3, Duration.ofSeconds(30), Duration.ofSeconds(2), Duration.ofSeconds(10));

        publisher.deliverOne(now);

        verify(store).moveToDeadLetter(event.id(), "permanent", "invalid recipient", event.leaseUntil());
    }

    private static OutboxEvent claimedEvent(int attempts, Instant now) {
        return new OutboxEvent(UUID.randomUUID(), UUID.randomUUID(), "conversation", UUID.randomUUID(),
                "OUTBOUND_DELIVERY_REQUESTED", "{}", OutboxEvent.Status.PROCESSING, attempts,
                now, now.minusSeconds(1), now.plusSeconds(30), "conversation:reply:1", null, null);
    }
}
