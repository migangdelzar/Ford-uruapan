package com.ford.service.conversations.application.port.out;

import com.ford.service.conversations.api.CommandReceipt;
import com.ford.service.conversations.domain.MessageApproval;
import com.ford.service.operator.application.AuthenticatedOperator;

/**
 * Effects must join the approval transaction (no REQUIRES_NEW, direct channel send, or asynchronous commit).
 * Queueing requires fresh evidence, authority, exact-text/privacy checks, and channel-window validation.
 * Return receipts only after message/outbox persistence or actual takeover; never synthesize acceptance.
 */
public interface MessageApprovalEffects {
    CommandReceipt validateAndQueue(AuthenticatedOperator operator, MessageApproval approval,
                                    String exactText, String commandKey);
    TakeoverReceipt takeover(AuthenticatedOperator operator, MessageApproval approval, String commandKey);

    record TakeoverReceipt(String status, long conversationVersion) {
        public TakeoverReceipt {
            if ((!"HUMAN_PENDING".equals(status) && !"HUMAN_ACTIVE".equals(status)) || conversationVersion < 0) {
                throw new IllegalArgumentException("invalid takeover receipt");
            }
        }
    }
}
