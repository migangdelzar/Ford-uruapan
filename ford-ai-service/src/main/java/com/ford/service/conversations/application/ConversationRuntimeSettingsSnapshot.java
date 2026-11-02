package com.ford.service.conversations.application;

import java.time.Instant;
import java.util.UUID;

public record ConversationRuntimeSettingsSnapshot(UUID tenantId, long revision,
                                                  ConversationRuntimeSettings settings,
                                                  Instant updatedAt, UUID updatedBy) {
}
