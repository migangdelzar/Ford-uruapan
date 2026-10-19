package com.ford.service.catalog.api;

import java.util.Objects;

/** A ranked searchable record paired with the structured evidence returned to the tool. */
public record CatalogRetrievalCandidate<T>(String stableId, String searchText, T evidence) {

    public CatalogRetrievalCandidate {
        if (stableId == null || stableId.isBlank()) {
            throw new IllegalArgumentException("stable candidate id is required");
        }
        searchText = searchText == null ? "" : searchText;
        Objects.requireNonNull(evidence, "structured catalog evidence is required");
    }
}
