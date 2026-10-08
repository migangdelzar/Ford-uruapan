package com.ford.service.knowledge.adapter.out;

import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.document.Document;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import com.ford.service.knowledge.domain.KnowledgeChunk;
import com.ford.service.knowledge.domain.KnowledgeQuery;

import java.util.List;
import java.util.Map;

public final class PgVectorKnowledgeStore extends VectorStoreKnowledgeStore {

    private static final int CANDIDATE_LIMIT = 20;
    private static final int RESULT_LIMIT = 8;
    private static final String KEYWORD_SEARCH_SQL = """
            SELECT id::text AS id, content, metadata::text AS metadata
            FROM public.vector_store
            WHERE metadata->>'tenantId' = :tenantId
              AND (metadata->>'approved' IS NULL OR metadata->>'approved' = 'true')
              AND to_tsvector('simple', coalesce(content, ''))
                  @@ plainto_tsquery('simple', :query)
            """;

    private final JdbcClient jdbcClient;
    private final ObjectMapper objectMapper;
    private final com.ford.service.knowledge.application.ReciprocalRankFusion rankFusion =
            new com.ford.service.knowledge.application.ReciprocalRankFusion();

    public PgVectorKnowledgeStore(VectorStore vectorStore, JdbcClient jdbcClient, ObjectMapper objectMapper) {
        super(vectorStore);
        this.jdbcClient = jdbcClient;
        this.objectMapper = objectMapper;
    }

    @Override
    public List<KnowledgeChunk> retrieve(KnowledgeQuery query) {
        var vectorResults = searchVectorDocuments(query).stream()
                .filter(document -> matches(document, query))
                .toList();
        var sql = new StringBuilder(KEYWORD_SEARCH_SQL);
        if (query.locale() != null) {
            sql.append(" AND metadata->>'locale' = :locale");
        }
        if (query.documentType() != null) {
            sql.append(" AND metadata->>'documentType' = :documentType");
        }
        sql.append(" ORDER BY ts_rank(to_tsvector('simple', coalesce(content, '')), ")
                .append("plainto_tsquery('simple', :query)) DESC, id LIMIT :limit");
        var keywordSearch = jdbcClient.sql(sql.toString())
                .param("tenantId", query.tenantId().toString())
                .param("query", query.text())
                .param("limit", CANDIDATE_LIMIT);
        if (query.locale() != null) {
            keywordSearch.param("locale", query.locale());
        }
        if (query.documentType() != null) {
            keywordSearch.param("documentType", query.documentType());
        }
        var keywordResults = keywordSearch
                .query((rs, rowNum) -> toDocument(
                        rs.getString("id"), rs.getString("content"), rs.getString("metadata")))
                .list();
        return rankFusion.merge(vectorResults, keywordResults, RESULT_LIMIT).stream()
                .filter(document -> matches(document, query))
                .map(PgVectorKnowledgeStore::toChunk)
                .toList();
    }

    private Document toDocument(String id, String content, String metadataJson) {
        try {
            var metadata = objectMapper.readValue(metadataJson, new TypeReference<Map<String, Object>>() { });
            return new Document(id, content, metadata);
        } catch (Exception exception) {
            throw new IllegalStateException("Could not read knowledge metadata", exception);
        }
    }
}
