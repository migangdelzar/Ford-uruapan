package com.ford.service.knowledge;

import com.ford.service.knowledge.adapter.out.PgVectorKnowledgeStore;
import com.ford.service.knowledge.application.KnowledgeRetrievalService;
import com.ford.service.knowledge.domain.KnowledgeQuery;
import liquibase.integration.spring.SpringLiquibase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class PgVectorKnowledgeIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new com.ford.service.database.FordPostgresContainer();

    private KnowledgeRetrievalService retrievalService;

    @BeforeEach
    void setUp() throws Exception {
        var dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        var liquibase = new SpringLiquibase();
        liquibase.setDataSource(dataSource);
        liquibase.setChangeLog("classpath:/db/changelog/db.changelog-master.yaml");
        liquibase.afterPropertiesSet();

        EmbeddingModel embeddingModel = new FixedEmbeddingModel();
        var vectorStore = PgVectorStore.builder(new JdbcTemplate(dataSource), embeddingModel)
                .dimensions(2)
                .initializeSchema(true)
                .build();
        vectorStore.afterPropertiesSet();
        var tenantId = UUID.randomUUID();
        vectorStore.add(List.of(
                new Document("Las comisiones de apertura dependen del producto publicado.",
                        Map.of("tenantId", tenantId.toString(), "locale", "es-MX", "approved", true,
                                "documentType", "finance")),
                new Document("Las comisiones de otro distribuidor son distintas.",
                        Map.of("tenantId", UUID.randomUUID().toString(), "locale", "es-MX", "approved", true,
                                "documentType", "finance")),
                new Document("Las comisiones no aprobadas no deben mostrarse.",
                        Map.of("tenantId", tenantId.toString(), "locale", "es-MX", "approved", false,
                                "documentType", "finance"))));
        retrievalService = new KnowledgeRetrievalService(new PgVectorKnowledgeStore(
                vectorStore, JdbcClient.create(dataSource), new tools.jackson.databind.ObjectMapper()));
        this.tenantId = tenantId;
    }

    private UUID tenantId;

    private static final class FixedEmbeddingModel implements EmbeddingModel {
        @Override
        public EmbeddingResponse call(EmbeddingRequest request) {
            var embeddings = request.getInstructions().stream()
                    .map(text -> new Embedding(text.contains("uniquephrase")
                            ? new float[]{0.0f, 1.0f} : new float[]{1.0f, 0.0f}, 0))
                    .toList();
            return new EmbeddingResponse(embeddings);
        }

        @Override
        public float[] embed(String text) {
            return new float[]{1.0f, 0.0f};
        }

        @Override
        public float[] embed(Document document) {
            return document.getText().contains("uniquephrase")
                    ? new float[]{0.0f, 1.0f} : new float[]{1.0f, 0.0f};
        }

        @Override
        public List<float[]> embed(List<String> texts) {
            return texts.stream().map(text -> new float[]{1.0f, 0.0f}).toList();
        }

        @Override
        public int dimensions() {
            return 2;
        }
    }

    @Test
    void retrievesApprovedTenantScopedKnowledgeFromPgvector() {
        var chunks = retrievalService.retrieve(new KnowledgeQuery(
                tenantId, "comisiones", "es-MX", "finance", false));

        assertThat(chunks).singleElement().satisfies(chunk ->
                assertThat(chunk.content()).contains("comisiones de apertura"));
    }

    @Test
    void retrievesKeywordMatchOutsideVectorTopTwenty() {
        var documents = new java.util.ArrayList<Document>();
        for (int index = 0; index < 25; index++) {
            documents.add(new Document(String.format("00000000-0000-0000-0000-%012d", index),
                    "Información general de vehículo " + index,
                    Map.of("tenantId", tenantId.toString(), "locale", "es-MX", "approved", true,
                            "documentType", "vehicle")));
        }
        var keywordMatch = new Document("00000000-0000-0000-0000-999999999999",
                "La uniquephrase distingue esta información exacta.",
                Map.of("tenantId", tenantId.toString(), "locale", "es-MX", "approved", true,
                        "documentType", "vehicle"));
        // Add to the same store used by the retriever.
        var dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        var vectorStore = PgVectorStore.builder(new JdbcTemplate(dataSource), new FixedEmbeddingModel())
                .dimensions(2).initializeSchema(true).build();
        vectorStore.afterPropertiesSet();
        vectorStore.add(documents);
        vectorStore.add(List.of(keywordMatch));
        var retrieval = new KnowledgeRetrievalService(new PgVectorKnowledgeStore(
                vectorStore, JdbcClient.create(dataSource), new tools.jackson.databind.ObjectMapper()));

        var chunks = retrieval.retrieve(new KnowledgeQuery(tenantId, "uniquephrase", "es-MX", "vehicle", false));

        assertThat(chunks).isNotEmpty();
        assertThat(chunks.getFirst().content()).contains("uniquephrase");
    }
}
