package com.ford.service.knowledge.application;

import com.ford.service.ai.application.port.out.EmbeddingPort;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;

import java.util.List;
import java.util.Objects;

public final class KnowledgeIngestionService {

    private final VectorStore vectorStore;
    private final EmbeddingPort embeddingPort;

    public KnowledgeIngestionService(VectorStore vectorStore, EmbeddingPort embeddingPort) {
        this.vectorStore = Objects.requireNonNull(vectorStore);
        this.embeddingPort = Objects.requireNonNull(embeddingPort);
    }

    public void ingest(List<Document> documents) {
        Objects.requireNonNull(documents);
        if (!embeddingPort.modelVersion().isBlank()) {
            vectorStore.add(documents);
        }
    }
}
