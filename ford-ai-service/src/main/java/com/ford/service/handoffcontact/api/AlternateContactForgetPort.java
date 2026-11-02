package com.ford.service.handoffcontact.api;

import java.time.Instant;
import java.util.UUID;

public interface AlternateContactForgetPort {
    int forgetForConversation(UUID tenantId, UUID conversationId, Instant forgottenAt, String actorId);
}
