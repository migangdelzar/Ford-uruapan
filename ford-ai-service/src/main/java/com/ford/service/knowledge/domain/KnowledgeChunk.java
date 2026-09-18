package com.ford.service.knowledge.domain;

import java.util.Map;
import java.util.UUID;

public record KnowledgeChunk(UUID documentId, String content, Map<String, Object> metadata) {
    public KnowledgeChunk {
        metadata = Map.copyOf(metadata);
    }
}
