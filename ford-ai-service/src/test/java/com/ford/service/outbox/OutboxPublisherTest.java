package com.ford.service.outbox;

import com.ford.service.observability.AiObservationService;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Optional;
import java.util.UUID;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class OutboxPublisherTest {

    @Test
    void claimsAnEventOnceAndMarksItDelivered() {
        var store = new InMemoryOutboxStore();
        var event = event();
        store.events.add(event);
        var observations = mock(AiObservationService.class);
        var publisher = new OutboxPublisher(store, notification -> NotificationResult.delivered("provider-1"),
                3, observations);

        assertThat(publisher.deliverOne(Instant.now())).isTrue();
        assertThat(publisher.deliverOne(Instant.now())).isFalse();
        assertThat(store.events.peek().status()).isEqualTo(OutboxEvent.Status.DELIVERED);
        verify(observations).event("ford.outbox.delivery", Map.of("outcome", "success"));
    }

    @Test
    void movesRepeatedFailuresToDeadLetter() {
        var store = new InMemoryOutboxStore();
        store.events.add(event());
        var observations = mock(AiObservationService.class);
        var publisher = new OutboxPublisher(store, notification -> {
            throw new IllegalStateException("provider unavailable");
        }, 2, observations);

        publisher.deliverOne(Instant.now());
        publisher.deliverOne(Instant.now());

        assertThat(store.events.peek().status()).isEqualTo(OutboxEvent.Status.DEAD_LETTER);
        assertThat(store.events.peek().attempts()).isEqualTo(2);
        verify(observations).event("ford.outbox.delivery", Map.of(
                "outcome", "retry", "failure_category", "delivery_error"));
        verify(observations).event("ford.outbox.delivery", Map.of(
                "outcome", "dead_letter", "failure_category", "delivery_error"));
    }

    private static OutboxEvent event() {
        return new OutboxEvent(UUID.randomUUID(), UUID.randomUUID(), "handoff", UUID.randomUUID(),
                "HANDOFF_CREATED", "{\"reason\":\"HOT_LEAD\"}", OutboxEvent.Status.PENDING,
                0, Instant.now(), null);
    }

    private static final class InMemoryOutboxStore implements OutboxStore {
        private final ArrayDeque<OutboxEvent> events = new ArrayDeque<>();

        @Override
        public void append(OutboxEvent event) {
            events.add(event);
        }

        @Override
        public Optional<OutboxEvent> claimNext(Instant now) {
            var event = events.stream().filter(candidate -> candidate.status() == OutboxEvent.Status.PENDING
                    && !candidate.availableAt().isAfter(now)).findFirst();
            event.ifPresent(candidate -> replace(candidate, candidate.claim()));
            return event.map(candidate -> find(candidate.id()));
        }

        @Override
        public void markDelivered(UUID eventId, String providerMessageId) {
            replace(find(eventId), find(eventId).delivered());
        }

        @Override
        public void markFailed(UUID eventId, String error, boolean deadLetter) {
            var event = find(eventId);
            replace(event, event.failed(error, deadLetter));
        }

        private OutboxEvent find(UUID id) {
            return events.stream().filter(event -> event.id().equals(id)).findFirst().orElseThrow();
        }

        private void replace(OutboxEvent oldValue, OutboxEvent newValue) {
            var values = events.stream().map(event -> event == oldValue ? newValue : event).toList();
            events.clear();
            events.addAll(values);
        }
    }
}
