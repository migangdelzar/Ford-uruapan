package com.ford.service.observability;

import com.ford.service.ai.configuration.AiProperties;
import com.ford.service.conversations.application.ConversationProperties;
import com.ford.service.outbox.OutboxProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;

import java.util.Locale;
import java.util.Objects;

public final class CapabilityDiagnostics {

    private static final Logger log = LoggerFactory.getLogger(CapabilityDiagnostics.class);

    private final AiProperties aiProperties;
    private final ConversationProperties conversationProperties;
    private final OutboxProperties outboxProperties;
    private final boolean metaOutboundEnabled;
    private final boolean tracesEnabled;
    private final boolean metricsEnabled;

    public CapabilityDiagnostics(AiProperties aiProperties,
                                 ConversationProperties conversationProperties,
                                 OutboxProperties outboxProperties,
                                 boolean metaOutboundEnabled,
                                 boolean tracesEnabled,
                                 boolean metricsEnabled) {
        this.aiProperties = Objects.requireNonNull(aiProperties);
        this.conversationProperties = Objects.requireNonNull(conversationProperties);
        this.outboxProperties = Objects.requireNonNull(outboxProperties);
        this.metaOutboundEnabled = metaOutboundEnabled;
        this.tracesEnabled = tracesEnabled;
        this.metricsEnabled = metricsEnabled;
    }

    public String summary() {
        return "ai.cloud=" + state(aiProperties.cloudEnabled())
                + ", ai.local=" + state(aiProperties.localEnabled())
                + ", ai.provider=" + safeProvider(aiProperties.chatProvider())
                + ", humanHandoff=" + state(conversationProperties.humanHandoffEnabled())
                + ", meta.outbound=" + state(metaOutboundEnabled)
                + ", outbox=" + state(outboxProperties.enabled())
                + ", observability.traces=" + state(tracesEnabled)
                + ", observability.metrics=" + state(metricsEnabled);
    }

    @EventListener(ApplicationReadyEvent.class)
    void logSummary() {
        log.info("Capability summary: {}", summary());
    }

    private static String state(boolean enabled) {
        return enabled ? "enabled" : "disabled";
    }

    private static String safeProvider(String provider) {
        var normalized = provider == null ? "" : provider.strip().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "openai", "ollama" -> normalized;
            default -> "other";
        };
    }
}
