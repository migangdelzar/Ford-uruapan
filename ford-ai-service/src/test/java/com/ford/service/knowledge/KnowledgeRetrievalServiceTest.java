package com.ford.service.knowledge;

import com.ford.service.knowledge.application.port.out.KnowledgeRetriever;
import com.ford.service.knowledge.application.KnowledgeRetrievalService;
import com.ford.service.knowledge.domain.KnowledgeChunk;
import com.ford.service.knowledge.domain.KnowledgeQuery;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class KnowledgeRetrievalServiceTest {

    @Test
    void reportsEmptyRetrievalForStaleKnowledgeOnlyQuestion() {
        var retriever = (KnowledgeRetriever) query -> List.of(new KnowledgeChunk(
                UUID.randomUUID(), "stale", Map.of("effectiveTo", Instant.now().minusSeconds(1).toString())));
        var service = new KnowledgeRetrievalService(retriever);

        assertThat(service.retrieve(new KnowledgeQuery(
                UUID.randomUUID(), "comisión", "es-MX", "finance", true))).isEmpty();
    }
}
