package com.ford.service.ai.application;

import org.springframework.ai.tool.ToolCallback;

import java.util.List;

public final class ToolAuthorizationPolicy {

    public List<ToolCallback> readOnlyTools(AiExecutionContext context) {
        if (context == null || context.tenantId() == null) {
            throw new IllegalArgumentException("tenant context is required");
        }
        return List.of();
    }
}
