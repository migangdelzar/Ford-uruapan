package com.ford.service.ai.api;

import java.time.Instant;

public record BotConfigurationView(boolean enabled, boolean humanHandoffEnabled,
                                   String model, String systemPrompt, Instant updatedAt) {
}
