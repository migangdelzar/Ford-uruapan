package com.ford.service.catalog.domain;

import java.util.List;
import java.util.UUID;

public record CatalogModelSearchResult(
        UUID modelId,
        String model,
        List<CatalogFact> matchingVariants,
        List<CatalogDocumentHit> sources) {

    public CatalogModelSearchResult {
        matchingVariants = List.copyOf(matchingVariants);
        sources = List.copyOf(sources);
    }
}
