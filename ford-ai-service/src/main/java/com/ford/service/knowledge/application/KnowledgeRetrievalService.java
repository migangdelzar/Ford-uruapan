package com.ford.service.knowledge.application;

import com.ford.service.knowledge.application.port.out.KnowledgeRetriever;
import com.ford.service.knowledge.domain.KnowledgeChunk;
import com.ford.service.knowledge.domain.KnowledgeQuery;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

public final class KnowledgeRetrievalService implements KnowledgeRetrieval {

    private final KnowledgeRetriever retriever;

    public KnowledgeRetrievalService(KnowledgeRetriever retriever) {
        this.retriever = Objects.requireNonNull(retriever);
    }

    @Override
    public List<KnowledgeChunk> retrieve(KnowledgeQuery query) {
        Objects.requireNonNull(query);
        if (query.tenantId() == null || query.text() == null || query.text().isBlank()) {
            throw new IllegalArgumentException("tenantId and text are required");
        }
        return retriever.retrieve(query).stream()
                .filter(chunk -> query.includeStale() || !isExpired(chunk))
                .filter(chunk -> query.documentType() == null
                        || query.documentType().equals(stringValue(chunk.metadata().get("documentType"))))
                .toList();
    }

    private static boolean isExpired(KnowledgeChunk chunk) {
        var effectiveTo = chunk.metadata().get("effectiveTo");
        return effectiveTo != null && Instant.parse(effectiveTo.toString()).isBefore(Instant.now());
    }

    private static String stringValue(Object value) {
        return value == null ? null : value.toString();
    }
}
