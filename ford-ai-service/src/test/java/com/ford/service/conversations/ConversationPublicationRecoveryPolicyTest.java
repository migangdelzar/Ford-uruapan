package com.ford.service.conversations.application;

import com.ford.service.channels.api.InboundMessageAccepted;
import com.ford.service.channels.api.InboundTurnReleased;
import com.ford.service.channels.domain.Channel;
import com.ford.service.conversations.api.ConversationMemoryIndexRequested;
import com.ford.service.conversations.api.ConversationMemoryProjectionRefreshRequested;
import com.ford.service.conversations.api.HandoffRequested;
import com.ford.service.conversations.api.OutboundDeliveryRequested;
import com.ford.service.conversations.application.port.out.ConversationRepository;
import com.ford.service.conversations.domain.ConversationMemoryKey;
import com.ford.service.conversations.domain.ConversationMessage;
import com.ford.service.conversations.domain.ConversationSnapshot;
import com.ford.service.conversations.domain.ConversationState;
import com.ford.service.conversations.domain.MemoryProjectionUpdate;
import com.ford.service.shared.ConversationTurnCompleted;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.events.EventPublication;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ConversationPublicationRecoveryPolicyTest {

    @Test
    void extractsTenantConversationForEverySupportedDurableEventType() {
        var tenantId = UUID.randomUUID();
        var conversationId = UUID.randomUUID();
        var memoryKey = new ConversationMemoryKey(tenantId, Channel.WHATSAPP,
                "synthetic-account", "synthetic-participant", conversationId);
        var conversationMessage = new ConversationMessage(UUID.randomUUID(), conversationId,
                ConversationMessage.Direction.INBOUND, "synthetic-message", "synthetic text", Instant.EPOCH);
        var events = List.of(
                new InboundMessageAccepted(UUID.randomUUID(), tenantId, conversationId,
                        Channel.WHATSAPP, "synthetic-inbound", Instant.EPOCH),
                new InboundTurnReleased(UUID.randomUUID(), tenantId, conversationId,
                        Channel.WHATSAPP, "synthetic-released", Instant.EPOCH),
                new ConversationTurnCompleted(tenantId, conversationId, "synthetic-completed",
                        "COLD", "synthetic inbound", "synthetic answer"),
                new HandoffRequested(UUID.randomUUID(), tenantId, conversationId,
                        "synthetic reason", Instant.EPOCH),
                new OutboundDeliveryRequested(UUID.randomUUID(), tenantId, conversationId,
                        Channel.WHATSAPP, "synthetic-recipient", "synthetic outbound"),
                new ConversationMemoryIndexRequested(memoryKey, conversationMessage),
                new ConversationMemoryProjectionRefreshRequested(memoryKey,
                        new MemoryProjectionUpdate("synthetic summary", List.of(), UUID.randomUUID()), 0));
        var policy = new ConversationPublicationRecoveryPolicy(mock(ConversationRepository.class));

        assertThat(events).hasSize(7);
        for (var event : events) {
            assertThat(policy.conversationReference(event))
                    .as("identity for %s", event.getClass().getSimpleName())
                    .contains(new ConversationReference(tenantId, conversationId));
        }
    }

    @Test
    void failsClosedForUnknownAndMalformedEvents() {
        var conversations = mock(ConversationRepository.class);
        var policy = new ConversationPublicationRecoveryPolicy(conversations);
        var missingIdentity = new InboundMessageAccepted(UUID.randomUUID(), null, UUID.randomUUID(),
                Channel.WHATSAPP, "synthetic-missing-tenant", Instant.EPOCH);

        assertThat(policy.conversationReference(new Object())).isEmpty();
        assertThat(policy.conversationReference(null)).isEmpty();
        assertThat(policy.conversationReference(missingIdentity)).isEmpty();
        verify(conversations, never()).find(org.mockito.ArgumentMatchers.any(UUID.class),
                org.mockito.ArgumentMatchers.any(UUID.class));
    }

    @Test
    void requiresOldRetryableUnderLimitPublicationAndExistingTenantConversation() {
        var tenantId = UUID.randomUUID();
        var conversationId = UUID.randomUUID();
        var event = new InboundMessageAccepted(UUID.randomUUID(), tenantId, conversationId,
                Channel.WHATSAPP, "synthetic-retry", Instant.EPOCH);
        var conversations = mock(ConversationRepository.class);
        var policy = new ConversationPublicationRecoveryPolicy(conversations);
        var cutoff = Instant.now().minusSeconds(60);
        when(conversations.find(tenantId, conversationId)).thenReturn(Optional.empty());

        assertThat(policy.eligible(publication(EventPublication.Status.PUBLISHED, cutoff.minusSeconds(1), event, 1),
                cutoff)).isFalse();
        assertThat(policy.eligible(publication(EventPublication.Status.PUBLISHED, cutoff.plusSeconds(1), event, 1),
                cutoff)).isFalse();
        assertThat(policy.eligible(publication(EventPublication.Status.PROCESSING, cutoff.minusSeconds(90), event, 1),
                cutoff)).isFalse();
        assertThat(policy.eligible(publication(EventPublication.Status.FAILED, cutoff.minusSeconds(90), event, 5),
                cutoff)).isFalse();
        assertThat(policy.eligible(publication(EventPublication.Status.PUBLISHED, cutoff.minusSeconds(90),
                new Object(), 1), cutoff)).isFalse();
        assertThat(policy.eligible(publication(EventPublication.Status.PUBLISHED, cutoff.minusSeconds(90), event, 1),
                cutoff)).isFalse();

        verify(conversations, org.mockito.Mockito.times(2)).find(tenantId, conversationId);
        verify(conversations, never()).find(org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.any(UUID.class));
    }

    @Test
    void allowsOldSupportedPublicationWhenTenantConversationExists() {
        var tenantId = UUID.randomUUID();
        var conversationId = UUID.randomUUID();
        var event = new InboundMessageAccepted(UUID.randomUUID(), tenantId, conversationId,
                Channel.WHATSAPP, "synthetic-valid-retry", Instant.EPOCH);
        var conversations = mock(ConversationRepository.class);
        when(conversations.find(tenantId, conversationId)).thenReturn(Optional.of(new ConversationSnapshot(
                tenantId, conversationId, Channel.WHATSAPP, "synthetic-account", "synthetic-participant",
                ConversationState.BOT_ACTIVE, 0, null, null)));
        var policy = new ConversationPublicationRecoveryPolicy(conversations);
        var cutoff = Instant.now().minusSeconds(60);
        var oldPublication = publication(EventPublication.Status.FAILED, cutoff.minusSeconds(90), event, 2);

        assertThat(policy.eligible(oldPublication, cutoff)).isTrue();
        verify(conversations).find(tenantId, conversationId);
    }

    private static EventPublication publication(EventPublication.Status status, Instant date, Object event,
                                                int completionAttempts) {
        var publication = mock(EventPublication.class);
        when(publication.getStatus()).thenReturn(status);
        when(publication.getPublicationDate()).thenReturn(date);
        when(publication.getEvent()).thenReturn(event);
        when(publication.getCompletionAttempts()).thenReturn(completionAttempts);
        return publication;
    }
}
