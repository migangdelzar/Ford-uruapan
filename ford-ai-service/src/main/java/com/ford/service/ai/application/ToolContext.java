package com.ford.service.ai.application;

import java.util.UUID;
import java.util.Set;
import java.util.Objects;

public record ToolContext(UUID tenantId, UUID conversationId, Set<String> authorizedToolNames,
                          DecisionTraceCollector traceCollector,
                          PrefetchedComparisonEvidence prefetchedComparison) {
    public ToolContext(UUID tenantId, UUID conversationId, Set<String> authorizedToolNames,
                       DecisionTraceCollector traceCollector) {
        this(tenantId, conversationId, authorizedToolNames, traceCollector, null);
    }

    public ToolContext(UUID tenantId, UUID conversationId) {
        this(tenantId, conversationId, Set.of(), null);
    }

    public ToolContext(UUID tenantId, UUID conversationId, Set<String> authorizedToolNames) {
        this(tenantId, conversationId, authorizedToolNames, null);
    }

    public ToolContext {
        Objects.requireNonNull(tenantId);
        Objects.requireNonNull(conversationId);
        authorizedToolNames = Set.copyOf(authorizedToolNames == null ? Set.of() : authorizedToolNames);
    }

    public ToolContext withPrefetchedComparison(PrefetchedComparisonEvidence evidence) {
        return new ToolContext(tenantId, conversationId, authorizedToolNames, traceCollector,
                Objects.requireNonNull(evidence));
    }
}
