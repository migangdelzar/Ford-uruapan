package com.ford.service.catalog.api;

import java.util.List;
import java.util.Set;

/** Bounded candidate lists from independent lexical, trigram, and vector retrieval paths. */
public record CatalogCandidateLists<T>(List<CatalogRetrievalCandidate<T>> fullText,
                                       List<CatalogRetrievalCandidate<T>> trigram,
                                       List<CatalogRetrievalCandidate<T>> vector,
                                       String semanticSearch) {

    private static final Set<String> SEMANTIC_STATES = Set.of("ready", "unavailable", "not_requested");

    public CatalogCandidateLists {
        fullText = List.copyOf(fullText == null ? List.of() : fullText);
        trigram = List.copyOf(trigram == null ? List.of() : trigram);
        vector = List.copyOf(vector == null ? List.of() : vector);
        if (!SEMANTIC_STATES.contains(semanticSearch)) {
            throw new IllegalArgumentException("unsupported semantic search state");
        }
    }
}
