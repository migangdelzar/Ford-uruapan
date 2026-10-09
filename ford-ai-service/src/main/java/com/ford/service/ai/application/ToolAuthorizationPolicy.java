package com.ford.service.ai.application;

import org.springframework.ai.tool.ToolCallback;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public final class ToolAuthorizationPolicy {

    private static final List<String> DENIED_TERMS = List.of(
            "write", "delete", "update", "insert", "sql", "send", "handoff", "score", "ingest");
    private static final Set<String> FORD_MCP_TOOLS = Set.of(
            "search_vehicles", "search_vehicle_and_offers", "get_vehicle", "compare_vehicles",
            "compare_trims", "compare_model_years", "get_variant_comparisons", "list_promotions",
            "search_offers", "get_page_links");

    private final List<ToolCallback> tools;
    private final UUID mcpTenantId;

    public ToolAuthorizationPolicy() {
        this(List.of(), null);
    }

    public ToolAuthorizationPolicy(List<ToolCallback> tools) {
        this(tools, null);
    }

    public ToolAuthorizationPolicy(List<ToolCallback> tools, UUID mcpTenantId) {
        this.tools = List.copyOf(Objects.requireNonNull(tools));
        this.mcpTenantId = mcpTenantId;
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
                .filter(tool -> isMcpToolAllowed(tool.getToolDefinition().name(), context))
                .filter(tool -> allowedNames != null || isDefaultMcpTool(tool.getToolDefinition().name(), context))
                .filter(tool -> !isLegacyCatalogShadowed(tool.getToolDefinition().name(), context))
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

    private boolean isMcpToolAllowed(String name, AiExecutionContext context) {
        if (!FORD_MCP_TOOLS.contains(name)) {
            return true;
        }
        return mcpTenantId != null && mcpTenantId.equals(context.tenantId());
    }

    private boolean isLegacyCatalogShadowed(String name, AiExecutionContext context) {
        return "vehicle_snapshot_search".equals(name) && mcpTenantId != null
                && mcpTenantId.equals(context.tenantId())
                && tools.stream().anyMatch(tool -> "search_vehicles".equals(tool.getToolDefinition().name()));
    }

    private boolean isDefaultMcpTool(String name, AiExecutionContext context) {
        return !isMcpTenant(context) || "search_vehicles".equals(name);
    }

    private boolean isMcpTenant(AiExecutionContext context) {
        return mcpTenantId != null && mcpTenantId.equals(context.tenantId());
    }
}
