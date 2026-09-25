package com.ford.service.ai.adapter.in.tool;

import com.ford.service.knowledge.application.KnowledgeRetrieval;
import com.ford.service.knowledge.domain.KnowledgeChunk;
import com.ford.service.knowledge.domain.KnowledgeQuery;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

public final class KnowledgeTools {

    private final KnowledgeRetrieval retrievalService;

    public KnowledgeTools(KnowledgeRetrieval retrievalService) {
        this.retrievalService = Objects.requireNonNull(retrievalService);
    }

    public KnowledgeRetrieval retrievalService() {
        return retrievalService;
    }

    @Tool(name = "approved_knowledge_search", description = "Busca conocimiento aprobado y vigente para responder en español.")
    public List<KnowledgeChunk> search(String text, String locale, String documentType, ToolContext context) {
        return retrievalService.retrieve(new KnowledgeQuery(tenantId(context), text,
                locale == null || locale.isBlank() ? "es-MX" : locale, documentType, false));
    }

    private static UUID tenantId(ToolContext context) {
        if (context == null || context.getContext().get("tenantId") == null) {
            throw new IllegalArgumentException("tenantId tool context is required");
        }
        return UUID.fromString(context.getContext().get("tenantId").toString());
    }
}
