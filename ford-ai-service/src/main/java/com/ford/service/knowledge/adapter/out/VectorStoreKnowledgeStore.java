package com.ford.service.knowledge.adapter.out;

import com.ford.service.ai.application.port.out.KnowledgeRetriever;
import com.ford.service.knowledge.domain.KnowledgeChunk;
import com.ford.service.knowledge.domain.KnowledgeQuery;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

abstract class VectorStoreKnowledgeStore implements KnowledgeRetriever {

    private final VectorStore vectorStore;

    VectorStoreKnowledgeStore(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    @Override
    public List<KnowledgeChunk> retrieve(KnowledgeQuery query) {
        return vectorStore.similaritySearch(SearchRequest.builder()
                        .query(query.text())
                        .topK(8)
                        .similarityThresholdAll()
                        .build())
                .stream()
                .filter(document -> matches(document, query))
                .map(VectorStoreKnowledgeStore::toChunk)
                .toList();
    }

    private static boolean matches(Document document, KnowledgeQuery query) {
        var metadata = document.getMetadata();
        return query.tenantId().toString().equals(String.valueOf(metadata.get("tenantId")))
                && (query.locale() == null || query.locale().equals(String.valueOf(metadata.get("locale"))))
                && (query.documentType() == null || query.documentType().equals(String.valueOf(metadata.get("documentType"))))
                && (!Boolean.FALSE.equals(metadata.get("approved")));
    }

    private static KnowledgeChunk toChunk(Document document) {
        var id = UUID.nameUUIDFromBytes(document.getId().getBytes(StandardCharsets.UTF_8));
        return new KnowledgeChunk(id, document.getText(), document.getMetadata());
    }
}
