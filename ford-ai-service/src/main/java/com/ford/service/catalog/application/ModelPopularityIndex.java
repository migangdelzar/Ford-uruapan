package com.ford.service.catalog.application;

import java.util.List;
import java.util.Optional;

/** Read-only, exact model-family lookup over reviewed national model-sales evidence. */
@org.springframework.modulith.NamedInterface
public interface ModelPopularityIndex {
    Optional<ModelPopularity> findForCatalogModel(String catalogModel);
    List<ModelPopularity> cumulativeRankedModels();

    /** Missing YTD values sort last; their source state remains authoritative, not zero sales. */
    List<ModelPopularity> ytd2026RankedModels();
}
