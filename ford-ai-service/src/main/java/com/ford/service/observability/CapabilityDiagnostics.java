package com.ford.service.observability;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;

import java.util.Locale;
import java.util.Objects;

public class CapabilityDiagnostics {

    private static final Logger log = LoggerFactory.getLogger(CapabilityDiagnostics.class);

    private final boolean aiCloudEnabled;
    private final boolean aiLocalEnabled;
    private final String aiProvider;
    private final boolean humanHandoffEnabled;
    private final boolean outboxEnabled;
    private final boolean metaOutboundEnabled;
    private final boolean tracesEnabled;
    private final boolean metricsEnabled;
    private final boolean memoryEnabled;
    private final boolean semanticMemoryEnabled;
    private final int memoryRecentMessageLimit;

    public CapabilityDiagnostics(boolean aiCloudEnabled,
                                 boolean aiLocalEnabled,
                                 String aiProvider,
                                 boolean humanHandoffEnabled,
                                 boolean metaOutboundEnabled,
                                 boolean outboxEnabled,
                                 boolean tracesEnabled,
                                 boolean metricsEnabled) {
        this(aiCloudEnabled, aiLocalEnabled, aiProvider, humanHandoffEnabled,
                metaOutboundEnabled, outboxEnabled, tracesEnabled, metricsEnabled,
                true, false, 20);
    }

    public CapabilityDiagnostics(boolean aiCloudEnabled,
                                 boolean aiLocalEnabled,
                                 String aiProvider,
                                 boolean humanHandoffEnabled,
                                 boolean metaOutboundEnabled,
                                 boolean outboxEnabled,
                                 boolean tracesEnabled,
                                 boolean metricsEnabled,
                                 boolean memoryEnabled,
                                 boolean semanticMemoryEnabled,
                                 int memoryRecentMessageLimit) {
        this.aiCloudEnabled = aiCloudEnabled;
        this.aiLocalEnabled = aiLocalEnabled;
        this.aiProvider = Objects.requireNonNull(aiProvider);
        this.humanHandoffEnabled = humanHandoffEnabled;
        this.metaOutboundEnabled = metaOutboundEnabled;
        this.outboxEnabled = outboxEnabled;
        this.tracesEnabled = tracesEnabled;
        this.metricsEnabled = metricsEnabled;
        this.memoryEnabled = memoryEnabled;
        this.semanticMemoryEnabled = semanticMemoryEnabled;
        this.memoryRecentMessageLimit = memoryRecentMessageLimit;
    }

    public String summary() {
        return "ai.cloud=" + state(aiCloudEnabled)
                + ", ai.local=" + state(aiLocalEnabled)
                + ", ai.provider=" + safeProvider(aiProvider)
                + ", humanHandoff=" + state(humanHandoffEnabled)
                + ", meta.outbound=" + state(metaOutboundEnabled)
                + ", outbox=" + state(outboxEnabled)
                + ", memory=" + state(memoryEnabled)
                + ", memory.semantic=" + state(semanticMemoryEnabled)
                + ", memory.recentLimit=" + memoryRecentMessageLimit
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
