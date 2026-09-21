package com.ford.service.ai.application;

import java.util.UUID;

public record AiExecutionContext(UUID tenantId, boolean containsSensitiveData, UUID conversationId,
                                 String memoryContext) {

    public AiExecutionContext(UUID tenantId, boolean containsSensitiveData) {
        this(tenantId, containsSensitiveData, null, null);
    }

    public AiExecutionContext(UUID tenantId, boolean containsSensitiveData, UUID conversationId) {
        this(tenantId, containsSensitiveData, conversationId, null);
    }
}
