package com.ford.service.ai.application;

import org.springframework.ai.tool.ToolCallback;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

public final class ToolAuthorizationPolicy {

    private static final List<String> DENIED_TERMS = List.of(
            "write", "delete", "update", "insert", "sql", "send", "handoff", "score", "ingest");

    private final List<ToolCallback> tools;

    public ToolAuthorizationPolicy() {
        this(List.of());
    }

    public ToolAuthorizationPolicy(List<ToolCallback> tools) {
        this.tools = List.copyOf(Objects.requireNonNull(tools));
    }

    public List<ToolCallback> readOnlyTools(AiExecutionContext context) {
        return readOnlyTools(context, null);
    }

    public List<ToolCallback> readOnlyTools(AiExecutionContext context, Set<String> allowedNames) {
        if (context == null || context.tenantId() == null) {
            throw new IllegalArgumentException("tenant context is required");
        }
        return tools.stream()
                .filter(tool -> isReadOnly(tool.getToolDefinition().name()))
                .filter(tool -> allowedNames == null || allowedNames.contains(tool.getToolDefinition().name()))
                .toList();
    }

    public Set<String> availableReadOnlyToolNames() {
        return tools.stream()
                .map(tool -> tool.getToolDefinition().name())
                .filter(ToolAuthorizationPolicy::isReadOnly)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private static boolean isReadOnly(String name) {
        var normalized = name.toLowerCase(Locale.ROOT);
        return DENIED_TERMS.stream().noneMatch(normalized::contains);
    }
}
