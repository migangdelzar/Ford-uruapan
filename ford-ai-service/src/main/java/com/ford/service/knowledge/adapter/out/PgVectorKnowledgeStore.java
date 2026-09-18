package com.ford.service.knowledge.adapter.out;

import org.springframework.ai.vectorstore.VectorStore;

public final class PgVectorKnowledgeStore extends VectorStoreKnowledgeStore {

    public PgVectorKnowledgeStore(VectorStore vectorStore) {
        super(vectorStore);
    }
}
