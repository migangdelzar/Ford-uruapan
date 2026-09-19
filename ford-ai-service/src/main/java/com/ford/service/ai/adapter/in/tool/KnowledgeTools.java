package com.ford.service.ai.adapter.in.tool;

import com.ford.service.knowledge.application.KnowledgeRetrieval;

import java.util.Objects;

public final class KnowledgeTools {

    private final KnowledgeRetrieval retrievalService;

    public KnowledgeTools(KnowledgeRetrieval retrievalService) {
        this.retrievalService = Objects.requireNonNull(retrievalService);
    }

    public KnowledgeRetrieval retrievalService() {
        return retrievalService;
    }
}
