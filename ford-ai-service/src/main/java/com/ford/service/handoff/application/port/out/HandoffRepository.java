package com.ford.service.handoff.application.port.out;

import com.ford.service.handoff.api.OperatorPrincipal;
import com.ford.service.handoff.domain.HandoffTicket;
import com.ford.service.handoffcontact.api.AlternateContactValue;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface HandoffRepository {
    HandoffTicket save(HandoffTicket ticket);

    HandoffTicket findRequired(UUID id);

    HandoffTicket findByConversation(UUID conversationId);

    default Optional<HandoffTicket> findOpenByConversation(UUID tenantId, UUID conversationId) {
        return Optional.empty();
    }

    List<HandoffTicket> list(UUID tenantId);

    default List<HandoffTicket> listCreatedBetween(UUID tenantId, Instant startInclusive, Instant endExclusive) {
        return list(tenantId).stream()
                .filter(ticket -> !ticket.createdAt().isBefore(startInclusive)
                        && ticket.createdAt().isBefore(endExclusive))
                .toList();
    }

    UUID findConversationTenant(UUID conversationId);

    default boolean transitionConversationToPending(UUID tenantId, UUID conversationId) { return true; }

    Optional<HandoffTicket> claim(UUID ticketId, UUID tenantId, UUID operatorId,
                                   long expectedVersion, Instant claimedAt);

    Optional<HandoffTicket> pause(UUID conversationId, UUID tenantId, UUID operatorId,
                                  long expectedVersion, Instant claimedAt);

    Optional<HandoffTicket> resume(UUID conversationId, UUID tenantId, UUID operatorId,
                                   long expectedVersion, Instant closedAt);

    void recordAudit(String action, HandoffTicket ticket, OperatorPrincipal principal);

    default Optional<AlternateContactValue> findAlternateContact(java.util.Set<UUID> tenantIds, UUID ticketId) {
        return Optional.empty();
    }

    default void recordAlternateContactReveal(UUID ticketId, OperatorPrincipal principal, Instant occurredAt) {
        throw new UnsupportedOperationException("alternate contact reveal audit is unavailable");
    }

    default void recordSystemAudit(String action, HandoffTicket ticket, UUID eventId, Instant occurredAt) {
        // In-memory adapters may observe the event without persistence.
    }
}
