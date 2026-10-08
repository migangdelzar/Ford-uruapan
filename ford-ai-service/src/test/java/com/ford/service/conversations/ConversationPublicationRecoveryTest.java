package com.ford.service.conversations.application;

import com.ford.service.channels.api.InboundMessageAccepted;
import com.ford.service.channels.domain.Channel;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.events.EventPublication;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ConversationPublicationRecoveryTest {

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

    private static EventPublication publication(EventPublication.Status status, Instant date, Object event) {
        var publication = mock(EventPublication.class);
        when(publication.getStatus()).thenReturn(status);
        when(publication.getPublicationDate()).thenReturn(date);
        when(publication.getEvent()).thenReturn(event);
        when(publication.getCompletionDate()).thenReturn(Optional.empty());
        when(publication.getCompletionAttempts()).thenReturn(1);
        return publication;
    }
}
