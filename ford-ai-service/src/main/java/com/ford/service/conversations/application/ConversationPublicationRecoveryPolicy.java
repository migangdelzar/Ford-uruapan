package com.ford.service.conversations.application;

import com.ford.service.channels.api.InboundMessageAccepted;
import com.ford.service.channels.api.InboundTurnReleased;
import com.ford.service.conversations.api.ConversationMemoryIndexRequested;
import com.ford.service.conversations.api.ConversationMemoryProjectionRefreshRequested;
import com.ford.service.conversations.api.HandoffRequested;
import com.ford.service.conversations.api.OutboundDeliveryRequested;
import com.ford.service.conversations.application.port.out.ConversationRepository;
import com.ford.service.shared.ConversationTurnCompleted;
import org.springframework.modulith.events.EventPublication;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Applies an explicit allowlist and tenant-scoped existence check before replaying durable publications. */
@Component
public final class ConversationPublicationRecoveryPolicy {

    private static final int MAX_COMPLETION_ATTEMPTS = 5;

    private final ConversationRepository conversations;

    public ConversationPublicationRecoveryPolicy(ConversationRepository conversations) {
        this.conversations = Objects.requireNonNull(conversations);
    }

    public boolean eligible(EventPublication publication, Instant retryBefore) {
        if (publication == null || retryBefore == null) return false;
        var status = publication.getStatus();
        if (status != EventPublication.Status.PUBLISHED && status != EventPublication.Status.FAILED) return false;
        if (publication.getCompletionAttempts() >= MAX_COMPLETION_ATTEMPTS) return false;
        var publicationDate = publication.getPublicationDate();
        if (publicationDate == null || !publicationDate.isBefore(retryBefore)) return false;

        var reference = conversationReference(publication.getEvent()).orElse(null);
        return reference != null
                && conversations.find(reference.tenantId(), reference.conversationId()).isPresent();
    }

    Optional<ConversationReference> conversationReference(Object event) {
        if (event instanceof InboundMessageAccepted accepted) {
            return reference(accepted.tenantId(), accepted.conversationId());
        }
        if (event instanceof InboundTurnReleased released) {
            return reference(released.tenantId(), released.conversationId());
        }
        if (event instanceof ConversationTurnCompleted completed) {
            return reference(completed.tenantId(), completed.conversationId());
        }
        if (event instanceof HandoffRequested handoff) {
            return reference(handoff.tenantId(), handoff.conversationId());
        }
        if (event instanceof OutboundDeliveryRequested delivery) {
            return reference(delivery.tenantId(), delivery.conversationId());
        }
        if (event instanceof ConversationMemoryIndexRequested memoryIndex && memoryIndex.key() != null) {
            return reference(memoryIndex.key().tenantId(), memoryIndex.key().conversationId());
        }
        if (event instanceof ConversationMemoryProjectionRefreshRequested memoryProjection
                && memoryProjection.key() != null) {
            return reference(memoryProjection.key().tenantId(), memoryProjection.key().conversationId());
        }
        return Optional.empty();
    }

    private static Optional<ConversationReference> reference(UUID tenantId, UUID conversationId) {
        if (tenantId == null || conversationId == null) return Optional.empty();
        return Optional.of(new ConversationReference(tenantId, conversationId));
    }
}

record ConversationReference(UUID tenantId, UUID conversationId) {
    ConversationReference {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(conversationId, "conversationId");
    }
}
