package com.ford.service.handoff.application;

import com.ford.service.handoff.application.port.out.HandoffRepository;
import com.ford.service.handoff.domain.HandoffRequest;
import com.ford.service.handoff.domain.HandoffStatus;
import com.ford.service.handoff.domain.HandoffTicket;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Service
public final class DefaultHandoffService implements HandoffService {

    private final HandoffRepository repository;

    public DefaultHandoffService(HandoffRepository repository) {
        this.repository = Objects.requireNonNull(repository);
    }

    @Override
    public HandoffTicket request(HandoffRequest request) {
        Objects.requireNonNull(request);
        if (request.tenantId() == null || request.conversationId() == null
                || request.reason() == null || request.reason().isBlank()) {
            throw new IllegalArgumentException("tenant, conversation, and reason are required");
        }
        return repository.save(new HandoffTicket(
                UUID.randomUUID(), request.tenantId(), request.conversationId(), request.leadId(),
                HandoffStatus.HUMAN_PENDING, null, request.reason(), 0,
                Instant.now(), null, null));
    }

    @Override
    public HandoffTicket claim(UUID ticketId, UUID operatorId) {
        var ticket = repository.findRequired(ticketId);
        if (ticket.status() != HandoffStatus.HUMAN_PENDING) {
            throw new IllegalStateException("Only pending tickets can be claimed");
        }
        return repository.save(new HandoffTicket(
                ticket.id(), ticket.tenantId(), ticket.conversationId(), ticket.leadId(),
                HandoffStatus.HUMAN_ACTIVE, operatorId, ticket.reason(), ticket.version() + 1,
                ticket.createdAt(), Instant.now(), null));
    }

    @Override
    public HandoffTicket pauseBot(UUID conversationId, UUID operatorId) {
        var ticket = repository.findByConversation(conversationId);
        if (ticket.status() == HandoffStatus.CLOSED) {
            throw new IllegalStateException("Closed ticket cannot pause the bot");
        }
        return ticket;
    }

    @Override
    public HandoffTicket resumeBot(UUID conversationId, UUID operatorId) {
        var ticket = repository.findByConversation(conversationId);
        return repository.save(new HandoffTicket(
                ticket.id(), ticket.tenantId(), ticket.conversationId(), ticket.leadId(),
                HandoffStatus.CLOSED, ticket.assignedOperatorId(), ticket.reason(), ticket.version() + 1,
                ticket.createdAt(), ticket.claimedAt(), Instant.now()));
    }

    @Override
    public List<HandoffTicket> list(UUID tenantId) {
        return repository.list(Objects.requireNonNull(tenantId));
    }
}
