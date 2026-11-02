package com.ford.service.handoff.application;

import com.ford.service.conversations.api.MessageApprovalTakeoverPort;
import com.ford.service.handoff.api.OperatorPrincipal;
import com.ford.service.handoff.domain.HandoffRequest;
import com.ford.service.handoff.domain.HandoffStatus;
import com.ford.service.operator.application.AuthenticatedOperator;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Objects;
import java.util.UUID;

/** Adapts advisor-approval takeover to the existing request-then-claim workflow. */
@Component
public final class DefaultMessageApprovalTakeoverAdapter implements MessageApprovalTakeoverPort {
    private final HandoffService handoffs;

    public DefaultMessageApprovalTakeoverAdapter(HandoffService handoffs) {
        this.handoffs = Objects.requireNonNull(handoffs);
    }

    @Override
    public TakeoverOutcome takeover(AuthenticatedOperator operator, UUID tenantId, UUID conversationId) {
        Objects.requireNonNull(operator);
        var principal = principal(operator);
        var ticket = handoffs.request(new HandoffRequest(tenantId, conversationId, null,
                "advisor_message_approval_takeover"), principal);
        if (ticket.status() == HandoffStatus.HUMAN_PENDING) {
            ticket = handoffs.claim(ticket.id(), ticket.version(), principal);
        }
        return new TakeoverOutcome(ticket.status().name());
    }

    private static OperatorPrincipal principal(AuthenticatedOperator operator) {
        var authorities = new HashSet<>(operator.authorities());
        operator.roles().forEach(role -> authorities.add("ROLE_" + role));
        UUID operatorId;
        try {
            operatorId = UUID.fromString(operator.subject());
        } catch (IllegalArgumentException notUuid) {
            operatorId = UUID.nameUUIDFromBytes(operator.subject().getBytes(StandardCharsets.UTF_8));
        }
        return new OperatorPrincipal(operatorId, operator.tenantIds(), authorities);
    }
}
