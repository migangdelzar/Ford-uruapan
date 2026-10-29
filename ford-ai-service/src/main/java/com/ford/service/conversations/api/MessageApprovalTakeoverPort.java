package com.ford.service.conversations.api;

import com.ford.service.operator.application.AuthenticatedOperator;

import java.util.UUID;

/** Public conversation-to-handoff port for transferring advisor-review conversations to a human. */
public interface MessageApprovalTakeoverPort {
    TakeoverOutcome takeover(AuthenticatedOperator operator, UUID tenantId, UUID conversationId);

    record TakeoverOutcome(String status) {
        public TakeoverOutcome {
            if (!"HUMAN_PENDING".equals(status) && !"HUMAN_ACTIVE".equals(status)) {
                throw new IllegalArgumentException("invalid handoff takeover outcome");
            }
        }
    }
}
