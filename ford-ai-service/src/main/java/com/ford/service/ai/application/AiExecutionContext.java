package com.ford.service.ai.application;

import java.util.UUID;

public record AiExecutionContext(UUID tenantId, boolean containsSensitiveData, UUID conversationId) {

    public AiExecutionContext(UUID tenantId, boolean containsSensitiveData) {
        this(tenantId, containsSensitiveData, null);
    }
}
