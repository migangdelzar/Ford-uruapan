package com.ford.service.conversations.api;

import jakarta.validation.constraints.NotNull;

public record ConversationMemoryRestartRequest(@NotNull RestartReason reason) {
    public enum RestartReason {
        CUSTOMER_REQUEST, STALE_CONTEXT, OPERATOR_RECOVERY, OTHER, LEGACY_CLIENT_UNSPECIFIED
    }
}
