package com.ford.service.knowledge.domain;

import java.util.UUID;

public record KnowledgeQuery(
        UUID tenantId,
        String text,
        String locale,
        String documentType,
        boolean includeStale) {
}
