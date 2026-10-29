package com.ford.service.conversations.application;

import com.ford.service.channels.api.InboundMessageAccepted;
import com.ford.service.channels.domain.Channel;
import com.ford.service.conversations.application.port.out.ConversationRepository;
import com.ford.service.shared.PostgresAdvisoryExecutionLock;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.events.EventPublication;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.concurrent.atomic.AtomicReference;

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
        var policy = mock(ConversationPublicationRecoveryPolicy.class);
        var lock = mock(PostgresAdvisoryExecutionLock.class);
        when(lock.runIfAcquired(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.any(Runnable.class)))
                .thenReturn(false);

        new ConversationPublicationRecovery(publications, executor, policy, properties, lock)
                .resubmitWhenCapacityReturns();

        verify(publications, never()).resubmitIncompletePublications(
                org.mockito.ArgumentMatchers.<Predicate<EventPublication>>any());
    }

    @Test
    void policyRetriesOnlyOldSupportedConversationPublications() {
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
        var conversations = mock(ConversationRepository.class);
        when(conversations.find(org.mockito.ArgumentMatchers.any(UUID.class),
                org.mockito.ArgumentMatchers.any(UUID.class))).thenReturn(java.util.Optional.of(
                new com.ford.service.conversations.domain.ConversationSnapshot(
                        UUID.randomUUID(), UUID.randomUUID(), Channel.WHATSAPP,
                        "synthetic-account", "synthetic-participant",
                        com.ford.service.conversations.domain.ConversationState.BOT_ACTIVE, 0, null, null)));
        var policy = new ConversationPublicationRecoveryPolicy(conversations);

        assertThat(policy.eligible(oldInbound, cutoff)).isTrue();
        assertThat(policy.eligible(youngInbound, cutoff)).isFalse();
        assertThat(policy.eligible(processingInbound, cutoff)).isFalse();
        assertThat(policy.eligible(failedInbound, cutoff)).isTrue();
        assertThat(policy.eligible(unrelatedEvent, cutoff)).isFalse();
    }

    @Test
    void stopsRetryingAfterTheFiveAttemptLimit() {
        var cutoff = Instant.now().minusSeconds(60);
        var exhausted = publication(EventPublication.Status.FAILED, cutoff.minusSeconds(90),
                new InboundMessageAccepted(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                        Channel.WHATSAPP, "provider-message-exhausted", cutoff), 5);

        var policy = new ConversationPublicationRecoveryPolicy(mock(ConversationRepository.class));
        assertThat(policy.eligible(exhausted, cutoff)).isFalse();
    }

    @Test
    void doesNotResubmitAnOldPublicationWhenItsTenantConversationIsMissing() {
        var tenantId = UUID.randomUUID();
        var conversationId = UUID.randomUUID();
        var cutoff = Instant.now().minusSeconds(60);
        var event = new InboundMessageAccepted(UUID.randomUUID(), tenantId, conversationId,
                Channel.WHATSAPP, "synthetic-orphan", cutoff.minusSeconds(90));
        var oldPublication = publication(EventPublication.Status.PUBLISHED, cutoff.minusSeconds(90), event);
        var conversations = mock(ConversationRepository.class);
        when(conversations.find(tenantId, conversationId)).thenReturn(Optional.empty());

        assertThat(new ConversationPublicationRecoveryPolicy(conversations).eligible(oldPublication, cutoff)).isFalse();
        verify(conversations).find(tenantId, conversationId);
    }

    @Test
    void keepsOldPublicationEligibleWhenItsTenantConversationExists() {
        var tenantId = UUID.randomUUID();
        var conversationId = UUID.randomUUID();
        var cutoff = Instant.now().minusSeconds(60);
        var event = new InboundMessageAccepted(UUID.randomUUID(), tenantId, conversationId,
                Channel.WHATSAPP, "synthetic-valid", cutoff.minusSeconds(90));
        var oldPublication = publication(EventPublication.Status.PUBLISHED, cutoff.minusSeconds(90), event);
        var conversations = mock(ConversationRepository.class);
        when(conversations.find(tenantId, conversationId)).thenReturn(Optional.of(
                new com.ford.service.conversations.domain.ConversationSnapshot(
                        tenantId, conversationId, Channel.WHATSAPP,
                        "synthetic-account", "synthetic-participant",
                        com.ford.service.conversations.domain.ConversationState.BOT_ACTIVE, 0, null, null)));

        assertThat(new ConversationPublicationRecoveryPolicy(conversations).eligible(oldPublication, cutoff)).isTrue();
        verify(conversations).find(tenantId, conversationId);
    }

    @Test
    void doesNotSpendAdmissionPermitOnIneligiblePublication() {
        var publications = mock(org.springframework.modulith.events.IncompleteEventPublications.class);
        var executor = mock(org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor.class);
        when(executor.getThreadPoolExecutor()).thenReturn(new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
                new SynchronousQueue<>()));
        var properties = new ConversationProperties();
        var policy = mock(ConversationPublicationRecoveryPolicy.class);
        var lock = mock(PostgresAdvisoryExecutionLock.class);
        var captured = new AtomicReference<Predicate<EventPublication>>();
        org.mockito.Mockito.doAnswer(invocation -> {
            captured.set(invocation.getArgument(0));
            return null;
        }).when(publications).resubmitIncompletePublications(
                org.mockito.ArgumentMatchers.<Predicate<EventPublication>>any());
        when(lock.runIfAcquired(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.any(Runnable.class)))
                .thenAnswer(invocation -> {
                    invocation.<Runnable>getArgument(1).run();
                    return true;
                });

        new ConversationPublicationRecovery(publications, executor, policy, properties, lock)
                .resubmitWhenCapacityReturns();

        var orphan = mock(EventPublication.class);
        var validFirst = mock(EventPublication.class);
        var validSecond = mock(EventPublication.class);
        when(policy.eligible(org.mockito.ArgumentMatchers.eq(orphan), org.mockito.ArgumentMatchers.any(Instant.class)))
                .thenReturn(false);
        when(policy.eligible(org.mockito.ArgumentMatchers.eq(validFirst), org.mockito.ArgumentMatchers.any(Instant.class)))
                .thenReturn(true);
        when(policy.eligible(org.mockito.ArgumentMatchers.eq(validSecond), org.mockito.ArgumentMatchers.any(Instant.class)))
                .thenReturn(true);

        var predicate = captured.get();
        assertThat(predicate).isNotNull();
        assertThat(predicate.test(orphan)).isFalse();
        assertThat(predicate.test(validFirst)).isTrue();
        assertThat(predicate.test(validSecond)).isFalse();
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
