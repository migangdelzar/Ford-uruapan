package com.ford.service.ai;

import com.ford.service.knowledge.adapter.out.InMemoryKnowledgeStore;
import com.ford.service.knowledge.application.KnowledgeRetrievalService;
import com.ford.service.knowledge.domain.KnowledgeQuery;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SimpleVectorStore;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SimpleVectorStoreRagTest {

    @Test
    void usesInMemoryRagOnlyForLocalTestAndAppliesTenantLocaleFilters() {
        var embeddingModel = mock(EmbeddingModel.class);
        when(embeddingModel.embed(anyString())).thenReturn(new float[]{1.0f, 0.0f});
        when(embeddingModel.embed(any(Document.class))).thenReturn(new float[]{1.0f, 0.0f});
        var vectorStore = SimpleVectorStore.builder(embeddingModel).build();
        var tenantId = UUID.randomUUID();
        vectorStore.add(java.util.List.of(
                new Document("La Ranger XLT tiene garantía de tres años.", Map.of(
                        "tenantId", tenantId.toString(), "locale", "es-MX", "approved", true)),
                new Document("Otro tenant", Map.of(
                        "tenantId", UUID.randomUUID().toString(), "locale", "es-MX", "approved", true))));

        var service = new KnowledgeRetrievalService(new InMemoryKnowledgeStore(vectorStore));
        var chunks = service.retrieve(new KnowledgeQuery(tenantId, "garantía", "es-MX", null, false));

        assertThat(chunks).hasSize(1);
        assertThat(chunks.getFirst().content()).contains("tres años");
    }
}
