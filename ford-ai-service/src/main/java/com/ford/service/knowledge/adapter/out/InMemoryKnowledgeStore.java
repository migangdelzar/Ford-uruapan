package com.ford.service.knowledge.adapter.out;

import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;

import java.util.List;

/** Test/local-demo adapter. It must not be selected by production profiles. */
public final class InMemoryKnowledgeStore extends VectorStoreKnowledgeStore {

    public InMemoryKnowledgeStore(VectorStore vectorStore) {
        super(vectorStore);
    }

    public void add(List<Document> documents) {
        // This method is intentionally absent: documents are added to the supplied SimpleVectorStore.
    }
}
