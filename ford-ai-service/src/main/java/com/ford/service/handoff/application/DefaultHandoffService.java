package com.ford.service.handoff.application;

import com.ford.service.handoff.api.HandoffActionRequested;
import com.ford.service.handoff.api.OperatorPrincipal;
import com.ford.service.conversations.api.ConversationHandoffMessagePort;
import com.ford.service.handoff.application.port.out.HandoffRepository;
import com.ford.service.handoff.domain.HandoffRequest;
import com.ford.service.handoff.domain.HandoffStatus;
import com.ford.service.handoff.domain.HandoffTicket;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Service
public class DefaultHandoffService implements HandoffService {

    private final HandoffRepository repository;
    private final ApplicationEventPublisher eventPublisher;
    private final ConversationHandoffMessagePort handoffMessages;

    public DefaultHandoffService(HandoffRepository repository, ApplicationEventPublisher eventPublisher,
                                 ConversationHandoffMessagePort handoffMessages) {
        this.repository = Objects.requireNonNull(repository);
        this.eventPublisher = Objects.requireNonNull(eventPublisher);
        this.handoffMessages = Objects.requireNonNull(handoffMessages);
    }

    @Override
    @Transactional
    public HandoffTicket request(HandoffRequest request, OperatorPrincipal principal) {
        requireOperator(principal);
        Objects.requireNonNull(request);
        if (request.conversationId() == null || request.reason() == null || request.reason().isBlank()) {
            throw new IllegalArgumentException("conversation and reason are required");
        }
        if (request.packet().alternateContact().isPresent()) {
            throw new SecurityException("alternate contact can only be attached by the consented conversation pipeline");
        }
        var tenantId = repository.findConversationTenant(request.conversationId());
        requireTenant(principal, tenantId);
        var existing = repository.findOpenByConversation(tenantId, request.conversationId());
        if (existing.isPresent()) return existing.get();
        var ticket = repository.save(new HandoffTicket(
                UUID.randomUUID(), tenantId, request.conversationId(), request.leadId(),
                HandoffStatus.HUMAN_PENDING, com.ford.service.handoff.domain.HandoffPriority.NORMAL,
                null, request.reason(), "operator:" + tenantId + ":" + request.conversationId(), 0,
                Instant.now(), null, null, request.packet()));
        if (!repository.transitionConversationToPending(tenantId, request.conversationId())) {
            throw new IllegalStateException("Conversation cannot enter human handoff from its current state");
        }
        publishAuditAndEvent("HANDOFF_REQUESTED", ticket, principal);
        return ticket;
    }

    @Override
    @Transactional
    public HandoffTicket claim(UUID ticketId, long expectedVersion, OperatorPrincipal principal) {
        requireOperator(principal);
        var current = repository.findRequired(Objects.requireNonNull(ticketId));
        requireTenant(principal, current.tenantId());
        return repository.claim(ticketId, current.tenantId(), principal.operatorId(), expectedVersion, Instant.now())
                .map(ticket -> {
                    handoffMessages.sendAdvisorTakeoverMessage(ticket.tenantId(), ticket.conversationId(),
                            principal.operatorId(), ticket.id());
                    return publishAuditAndReturn("HANDOFF_CLAIMED", ticket, principal);
                })
                .orElseThrow(() -> staleVersion(ticketId));
    }

    @Override
    @Transactional
    public HandoffTicket pauseBot(UUID conversationId, long expectedVersion, OperatorPrincipal principal) {
        requireOperator(principal);
        var current = repository.findByConversation(Objects.requireNonNull(conversationId));
        requireTenant(principal, current.tenantId());
        return repository.pause(conversationId, current.tenantId(), principal.operatorId(), expectedVersion, Instant.now())
                .map(ticket -> {
                    if (current.status() == HandoffStatus.HUMAN_PENDING) {
                        handoffMessages.sendAdvisorTakeoverMessage(ticket.tenantId(), ticket.conversationId(),
                                principal.operatorId(), ticket.id());
                    }
                    return publishAuditAndReturn("HANDOFF_PAUSED", ticket, principal);
                })
                .orElseThrow(() -> staleVersion(conversationId));
    }

    @Override
    @Transactional
    public HandoffTicket resumeBot(UUID conversationId, long expectedVersion, OperatorPrincipal principal) {
        requireOperator(principal);
        var current = repository.findByConversation(Objects.requireNonNull(conversationId));
        requireTenant(principal, current.tenantId());
        return repository.resume(conversationId, current.tenantId(), principal.operatorId(), expectedVersion, Instant.now())
                .map(ticket -> publishAuditAndReturn("HANDOFF_RESUMED", ticket, principal))
                .orElseThrow(() -> staleVersion(conversationId));
    }

    @Override
    @Transactional(readOnly = true)
    public List<HandoffTicket> list(OperatorPrincipal principal) {
        requireOperator(principal);
        return principal.tenantIds().stream()
                .flatMap(tenantId -> repository.list(tenantId).stream())
                .toList();
    }

    private HandoffTicket publishAuditAndReturn(String action, HandoffTicket ticket, OperatorPrincipal principal) {
        publishAuditAndEvent(action, ticket, principal);
        return ticket;
    }

    private void publishAuditAndEvent(String action, HandoffTicket ticket, OperatorPrincipal principal) {
        repository.recordAudit(action, ticket, principal);
        eventPublisher.publishEvent(new HandoffActionRequested(
                ticket.tenantId(), ticket.id(), ticket.conversationId(), principal.operatorId(),
                action, ticket.version(), Instant.now()));
    }

    private static void requireOperator(OperatorPrincipal principal) {
        if (principal == null || !principal.isOperator()) {
            throw new SecurityException("Operator authority is required");
        }
    }

    private static void requireTenant(OperatorPrincipal principal, UUID tenantId) {
        if (!principal.canAccess(tenantId)) {
            throw new SecurityException("Operator is not authorized for tenant");
        }
    }

    private static IllegalStateException staleVersion(UUID aggregateId) {
        return new IllegalStateException("Handoff version is stale or unavailable: " + aggregateId);
    }
}
