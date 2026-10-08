package com.ford.service.knowledge.adapter.out;

import com.ford.service.knowledge.application.port.out.KnowledgeRetriever;
import com.ford.service.knowledge.domain.KnowledgeChunk;
import com.ford.service.knowledge.domain.KnowledgeQuery;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;

import java.util.List;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

abstract class VectorStoreKnowledgeStore implements KnowledgeRetriever {

    private final VectorStore vectorStore;

    VectorStoreKnowledgeStore(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    @Override
    public List<KnowledgeChunk> retrieve(KnowledgeQuery query) {
        return searchVectorDocuments(query).stream()
                .filter(document -> matches(document, query))
                .map(VectorStoreKnowledgeStore::toChunk)
                .toList();
    }

    private static org.springframework.ai.vectorstore.filter.Filter.Expression metadataFilter(KnowledgeQuery query) {
        var builder = new FilterExpressionBuilder();
        var expression = builder.eq("tenantId", query.tenantId().toString());
        if (query.locale() != null) {
            expression = builder.and(expression, builder.eq("locale", query.locale()));
        }
        if (query.documentType() != null) {
            expression = builder.and(expression, builder.eq("documentType", query.documentType()));
        }
        return expression.build();
    }

    protected static boolean matches(Document document, KnowledgeQuery query) {
        var metadata = document.getMetadata();
        return query.tenantId().toString().equals(String.valueOf(metadata.get("tenantId")))
                && (query.locale() == null || query.locale().equals(String.valueOf(metadata.get("locale"))))
                && (query.documentType() == null || query.documentType().equals(String.valueOf(metadata.get("documentType"))))
                && (!Boolean.FALSE.equals(metadata.get("approved")));
    }

    protected static KnowledgeChunk toChunk(Document document) {
        var id = UUID.nameUUIDFromBytes(document.getId().getBytes(StandardCharsets.UTF_8));
        return new KnowledgeChunk(id, document.getText(), document.getMetadata());
    }

    protected List<Document> searchVectorDocuments(KnowledgeQuery query) {
        return vectorStore.similaritySearch(SearchRequest.builder()
                        .query(query.text())
                        .topK(20)
                        .similarityThresholdAll()
                        .filterExpression(metadataFilter(query))
                        .build());
    }
}
