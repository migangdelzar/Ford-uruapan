package com.ford.service.ai.api;

import java.time.Instant;

public record BotConfigurationView(boolean enabled, boolean humanHandoffEnabled,
                                   String model, String cloudProvider, boolean cloudEnabled,
                                   boolean localEnabled, boolean classificationEnabled,
                                   String classificationMode, String classificationProvider,
                                   boolean jevEnabled, boolean memoryEnabled,
                                   boolean semanticMemoryEnabled, int recentMessageLimit,
                                   String systemPrompt, Instant updatedAt) {
}
