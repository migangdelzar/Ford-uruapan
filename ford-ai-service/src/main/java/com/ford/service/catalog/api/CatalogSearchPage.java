package com.ford.service.catalog.api;

import java.util.List;
import java.util.Set;
import org.springframework.modulith.NamedInterface;

/** Ranked structured catalog evidence and the truthful state of its semantic leg. */
@NamedInterface
public record CatalogSearchPage<T>(String semanticSearch, int resultCount, List<T> results) {

    private static final Set<String> SEMANTIC_STATES = Set.of("ready", "unavailable", "not_requested");

    public CatalogSearchPage {
        if (!SEMANTIC_STATES.contains(semanticSearch)) {
            throw new IllegalArgumentException("unsupported semantic search state");
        }
        if (resultCount < 0) {
            throw new IllegalArgumentException("resultCount cannot be negative");
        }
        results = List.copyOf(results == null ? List.of() : results);
    }
}
