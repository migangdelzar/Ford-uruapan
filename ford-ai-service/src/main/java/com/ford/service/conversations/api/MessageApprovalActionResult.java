package com.ford.service.conversations.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record MessageApprovalActionResult(UUID approvalId, String status, long conversationVersion, UUID messageId) {
    public MessageApprovalActionResult {
        Objects.requireNonNull(approvalId, "approval ID is required");
        if (status == null || !Set.of("QUEUED", "REJECTED", "HUMAN_PENDING", "HUMAN_ACTIVE").contains(status)
                || conversationVersion < 0 || ("QUEUED".equals(status) != (messageId != null))) {
            throw new IllegalArgumentException("invalid approval action receipt");
        }
    }
}
