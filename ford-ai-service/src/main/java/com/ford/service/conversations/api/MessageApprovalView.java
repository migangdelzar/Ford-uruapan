package com.ford.service.conversations.api;

import com.ford.service.conversations.domain.MessageApproval.Action;
import java.util.Set;
import java.util.UUID;

/** Internal draft view for authorized advisors; never append it to a customer transcript. */
public record MessageApprovalView(UUID id, UUID tenantId, UUID conversationId, long version,
                                  long conversationVersion, String reasonCode, String route,
                                  String draftText, Set<Action> allowedActions) {
    public MessageApprovalView { allowedActions = Set.copyOf(allowedActions); }
    @Override public String toString() { return "MessageApprovalView[id=" + id + ", version=" + version + "]"; }
}
