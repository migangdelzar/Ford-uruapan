package com.ford.service.ai.adapter.out.springai;

import com.ford.service.knowledge.domain.KnowledgeChunk;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/** Keeps only this turn's retrieved approved chunks and source URLs. */
public final class KnowledgeRagEvidenceTracker {

    private final List<KnowledgeChunk> chunks = new ArrayList<>();
    private boolean retrievalAttempted;

    public synchronized void record(List<KnowledgeChunk> retrieved) {
        retrievalAttempted = true;
        if (retrieved != null) chunks.addAll(retrieved);
    }

    public synchronized boolean retrievalAttempted() {
        return retrievalAttempted;
    }

    public synchronized boolean hasRetrievedDocuments() {
        return !chunks.isEmpty();
    }

    public synchronized List<String> sourceUrls() {
        var urls = new LinkedHashSet<String>();
        for (var chunk : chunks) {
            addUrl(urls, chunk.metadata().get("sourceUrl"));
            addUrl(urls, chunk.metadata().get("source_url"));
        }
        return List.copyOf(urls);
    }

    /** Concatenates this turn's retrieved chunk content and metadata for grounding checks. */
    public synchronized String evidenceText() {
        var builder = new StringBuilder();
        for (var chunk : chunks) {
            builder.append(chunk.content()).append('\n');
            for (var value : chunk.metadata().values()) {
                if (value != null) {
                    builder.append(value).append('\n');
                }
            }
        }
        return builder.toString();
    }

    private static void addUrl(LinkedHashSet<String> urls, Object value) {
        if (value instanceof String url && !url.isBlank()) urls.add(url.strip());
    }
}
