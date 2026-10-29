package com.ford.service.ai;

import com.ford.service.ai.adapter.out.springai.KnowledgeRagAdvisorFactory;
import com.ford.service.ai.adapter.out.springai.KnowledgeRagEvidenceTracker;
import com.ford.service.knowledge.application.KnowledgeRetrieval;
import com.ford.service.knowledge.domain.KnowledgeChunk;
import com.ford.service.knowledge.domain.KnowledgeQuery;
import org.junit.jupiter.api.Test;
import org.springframework.ai.rag.Query;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KnowledgeRagAdvisorFactoryTest {

    @Test
    void adaptsExistingTenantScopedKnowledgeRetrievalToSpringAiDocuments() {
        var retrieval = mock(KnowledgeRetrieval.class);
        var tenantId = UUID.randomUUID();
        var chunkId = UUID.randomUUID();
        when(retrieval.retrieve(new KnowledgeQuery(tenantId, "¿Qué cubre la garantía?", "es-MX", null, false)))
                .thenReturn(List.of(new KnowledgeChunk(chunkId, "La garantía cubre el vehículo.",
                        Map.of("approved", true, "sourceUrl", "https://ford.example/garantia"))));
        var factory = new KnowledgeRagAdvisorFactory(retrieval, List.of());

        var documents = factory.documentRetriever(tenantId, "es-MX")
                .retrieve(new Query("¿Qué cubre la garantía?"));

        verify(retrieval).retrieve(new KnowledgeQuery(tenantId, "¿Qué cubre la garantía?", "es-MX", null, false));
        assertThat(documents).hasSize(1);
        assertThat(documents.getFirst().getId()).isEqualTo(chunkId.toString());
        assertThat(documents.getFirst().getText()).isEqualTo("La garantía cubre el vehículo.");
        assertThat(documents.getFirst().getMetadata())
                .containsEntry("approved", true)
                .containsEntry("sourceUrl", "https://ford.example/garantia");
    }

    @Test
    void recordsRetrievedChunksForTheTurnEvidenceLedger() {
        var retrieval = mock(KnowledgeRetrieval.class);
        var tenantId = UUID.randomUUID();
        var chunkId = UUID.randomUUID();
        var chunk = new KnowledgeChunk(chunkId, "La garantía cubre el vehículo.",
                Map.of("sourceUrl", "https://www.ford.mx/garantia"));
        when(retrieval.retrieve(new KnowledgeQuery(tenantId, "¿Qué cubre la garantía?", "es-MX", null, false)))
                .thenReturn(List.of(chunk));
        var factory = new KnowledgeRagAdvisorFactory(retrieval, List.of());
        var evidence = new KnowledgeRagEvidenceTracker();

        factory.documentRetriever(tenantId, "es-MX", evidence)
                .retrieve(new Query("¿Qué cubre la garantía?"));

        assertThat(evidence.hasRetrievedDocuments()).isTrue();
        assertThat(evidence.sourceUrls()).containsExactly("https://www.ford.mx/garantia");
    }
}
