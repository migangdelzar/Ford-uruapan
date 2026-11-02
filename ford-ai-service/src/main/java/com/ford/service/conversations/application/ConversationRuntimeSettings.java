package com.ford.service.conversations.application;

public record ConversationRuntimeSettings(boolean humanHandoffEnabled, boolean memoryEnabled,
                                         int promptRecentMessageLimit) {
    public ConversationRuntimeSettings {
        if (promptRecentMessageLimit < 1 || promptRecentMessageLimit > 50) {
            throw new IllegalArgumentException("promptRecentMessageLimit must be between 1 and 50");
        }
    }
}
