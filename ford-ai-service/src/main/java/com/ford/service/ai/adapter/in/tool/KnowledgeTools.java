package com.ford.service.ai.adapter.in.tool;

import com.ford.service.knowledge.application.KnowledgeRetrievalService;

import java.util.Objects;

public final class KnowledgeTools {

    private final KnowledgeRetrievalService retrievalService;

    public KnowledgeTools(KnowledgeRetrievalService retrievalService) {
        this.retrievalService = Objects.requireNonNull(retrievalService);
    }

    public KnowledgeRetrievalService retrievalService() {
        return retrievalService;
    }
}
