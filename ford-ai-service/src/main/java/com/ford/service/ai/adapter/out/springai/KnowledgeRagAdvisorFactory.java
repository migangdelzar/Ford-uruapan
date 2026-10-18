package com.ford.service.ai.adapter.out.springai;

import com.ford.service.knowledge.application.KnowledgeRetrieval;
import com.ford.service.knowledge.domain.KnowledgeQuery;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;
import org.springframework.ai.rag.generation.augmentation.ContextualQueryAugmenter;
import org.springframework.ai.rag.postretrieval.document.DocumentPostProcessor;
import org.springframework.ai.rag.retrieval.search.DocumentRetriever;
import org.springframework.ai.chat.client.advisor.api.Advisor;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Composes the existing tenant-scoped knowledge retriever with Spring AI RAG for one turn. */
public final class KnowledgeRagAdvisorFactory {

    private final KnowledgeRetrieval retrieval;
    private final List<DocumentPostProcessor> postProcessors;

    public KnowledgeRagAdvisorFactory(KnowledgeRetrieval retrieval,
                                      List<DocumentPostProcessor> postProcessors) {
        this.retrieval = Objects.requireNonNull(retrieval);
        this.postProcessors = List.copyOf(postProcessors == null ? List.of() : postProcessors);
    }

    public Advisor forTurn(UUID tenantId, String locale) {
        return forTurn(tenantId, locale, null);
    }

    public Advisor forTurn(UUID tenantId, String locale, KnowledgeRagEvidenceTracker evidence) {
        var builder = RetrievalAugmentationAdvisor.builder()
                .documentRetriever(documentRetriever(tenantId, locale, evidence))
                .queryAugmenter(ContextualQueryAugmenter.builder().allowEmptyContext(false).build());
        if (!postProcessors.isEmpty()) {
            builder.documentPostProcessors(postProcessors);
        }
        return builder.build();
    }

    public DocumentRetriever documentRetriever(UUID tenantId, String locale) {
        return documentRetriever(tenantId, locale, null);
    }

    public DocumentRetriever documentRetriever(UUID tenantId, String locale,
                                               KnowledgeRagEvidenceTracker evidence) {
        Objects.requireNonNull(tenantId, "tenantId is required");
        var scopedLocale = locale == null || locale.isBlank() ? "es-MX" : locale;
        return query -> retrieve(tenantId, scopedLocale, query, evidence);
    }

    private List<Document> retrieve(UUID tenantId, String locale, Query query,
                                    KnowledgeRagEvidenceTracker evidence) {
        var chunks = retrieval.retrieve(new KnowledgeQuery(tenantId, query.text(), locale, null, false));
        if (evidence != null) evidence.record(chunks);
        return chunks.stream()
                .map(chunk -> new Document(chunk.documentId().toString(), chunk.content(), chunk.metadata()))
                .toList();
    }
}
