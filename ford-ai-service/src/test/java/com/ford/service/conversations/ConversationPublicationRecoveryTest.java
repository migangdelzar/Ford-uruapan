package com.ford.service.conversations.application;

import com.ford.service.channels.api.InboundMessageAccepted;
import com.ford.service.channels.domain.Channel;
import com.ford.service.shared.PostgresAdvisoryExecutionLock;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.events.EventPublication;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ConversationPublicationRecoveryTest {

    @Test
    void doesNotResubmitPublicationsWhenAnotherInstanceOwnsTheRecoveryLock() {
        var publications = mock(org.springframework.modulith.events.IncompleteEventPublications.class);
        var executor = mock(org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor.class);
        when(executor.getThreadPoolExecutor()).thenReturn(new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(1)));
        var properties = new ConversationProperties();
        var lock = mock(PostgresAdvisoryExecutionLock.class);
        when(lock.runIfAcquired(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.any(Runnable.class)))
                .thenReturn(false);

        new ConversationPublicationRecovery(publications, executor, properties, lock).resubmitWhenCapacityReturns();

        verify(publications, never()).resubmitIncompletePublications(
                org.mockito.ArgumentMatchers.<Predicate<EventPublication>>any());
    }

    @Test
    void retriesOnlyOldUnclaimedInboundConversationPublications() {
        var cutoff = Instant.now().minusSeconds(60);
        var oldInbound = publication(EventPublication.Status.PUBLISHED, cutoff.minusSeconds(1),
                new InboundMessageAccepted(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                        Channel.WHATSAPP, "provider-message", cutoff.minusSeconds(2)));
        var youngInbound = publication(EventPublication.Status.PUBLISHED, cutoff.plusSeconds(1),
                new InboundMessageAccepted(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                        Channel.WHATSAPP, "provider-message-2", cutoff));
        var processingInbound = publication(EventPublication.Status.PROCESSING, cutoff.minusSeconds(90),
                oldInbound.getEvent());
        var failedInbound = publication(EventPublication.Status.FAILED, cutoff.minusSeconds(90), oldInbound.getEvent());
        var unrelatedEvent = publication(EventPublication.Status.PUBLISHED, cutoff.minusSeconds(90), new Object());

        assertThat(ConversationPublicationRecovery.eligible(oldInbound, cutoff)).isTrue();
        assertThat(ConversationPublicationRecovery.eligible(youngInbound, cutoff)).isFalse();
        assertThat(ConversationPublicationRecovery.eligible(processingInbound, cutoff)).isFalse();
        assertThat(ConversationPublicationRecovery.eligible(failedInbound, cutoff)).isTrue();
        assertThat(ConversationPublicationRecovery.eligible(unrelatedEvent, cutoff)).isFalse();
    }

    @Test
    void stopsRetryingAfterTheFiveAttemptLimit() {
        var cutoff = Instant.now().minusSeconds(60);
        var exhausted = publication(EventPublication.Status.FAILED, cutoff.minusSeconds(90),
                new InboundMessageAccepted(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                        Channel.WHATSAPP, "provider-message-exhausted", cutoff), 5);

        assertThat(ConversationPublicationRecovery.eligible(exhausted, cutoff)).isFalse();
    }

    private static EventPublication publication(EventPublication.Status status, Instant date, Object event) {
        return publication(status, date, event, 1);
    }

    private static EventPublication publication(EventPublication.Status status, Instant date, Object event,
                                                int completionAttempts) {
        var publication = mock(EventPublication.class);
        when(publication.getStatus()).thenReturn(status);
        when(publication.getPublicationDate()).thenReturn(date);
        when(publication.getEvent()).thenReturn(event);
        when(publication.getCompletionDate()).thenReturn(Optional.empty());
        when(publication.getCompletionAttempts()).thenReturn(completionAttempts);
        return publication;
    }
}
