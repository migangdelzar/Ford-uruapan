package com.ford.service.observability;

import java.time.Duration;
import java.util.UUID;

public record ConversationObservation(UUID tenantId, String channel, String intent,
                                      String provider, Duration duration, boolean handedOff) {
    public ConversationObservation {
        if (channel == null || intent == null || provider == null) {
            throw new IllegalArgumentException("observation dimensions are required");
        }
    }
}
