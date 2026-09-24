package com.ford.service.handoff.application;

import com.ford.service.handoff.application.port.out.HandoffRepository;
import com.ford.service.handoff.domain.HandoffRequest;
import com.ford.service.handoff.domain.HandoffStatus;
import com.ford.service.handoff.domain.HandoffTicket;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Service
public final class DefaultHandoffService implements HandoffService {

    private static final Logger log = LoggerFactory.getLogger(DefaultHandoffService.class);

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
        var ticket = repository.save(new HandoffTicket(
                UUID.randomUUID(), request.tenantId(), request.conversationId(), request.leadId(),
                HandoffStatus.HUMAN_PENDING, null, request.reason(), 0,
                Instant.now(), null, null));
        log.info("Handoff requested outcome=pending");
        return ticket;
    }

    @Override
    public HandoffTicket claim(UUID ticketId, UUID operatorId) {
        var ticket = repository.findRequired(ticketId);
        if (ticket.status() != HandoffStatus.HUMAN_PENDING) {
            throw new IllegalStateException("Only pending tickets can be claimed");
        }
        var claimed = repository.save(new HandoffTicket(
                ticket.id(), ticket.tenantId(), ticket.conversationId(), ticket.leadId(),
                HandoffStatus.HUMAN_ACTIVE, operatorId, ticket.reason(), ticket.version() + 1,
                ticket.createdAt(), Instant.now(), null));
        log.info("Handoff claimed outcome=active");
        return claimed;
    }

    @Override
    public HandoffTicket pauseBot(UUID conversationId, UUID operatorId) {
        var ticket = repository.findByConversation(conversationId);
        if (ticket.status() == HandoffStatus.CLOSED) {
            throw new IllegalStateException("Closed ticket cannot pause the bot");
        }
        log.info("Handoff pause check completed outcome=eligible");
        return ticket;
    }

    @Override
    public HandoffTicket resumeBot(UUID conversationId, UUID operatorId) {
        var ticket = repository.findByConversation(conversationId);
        var resumed = repository.save(new HandoffTicket(
                ticket.id(), ticket.tenantId(), ticket.conversationId(), ticket.leadId(),
                HandoffStatus.CLOSED, ticket.assignedOperatorId(), ticket.reason(), ticket.version() + 1,
                ticket.createdAt(), ticket.claimedAt(), Instant.now()));
        log.info("Handoff closed outcome=completed");
        return resumed;
    }

    @Override
    public List<HandoffTicket> list(UUID tenantId) {
        return repository.list(Objects.requireNonNull(tenantId));
    }
}
