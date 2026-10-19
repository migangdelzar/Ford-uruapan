package com.ford.service.catalog.api;

import java.util.Optional;
import org.springframework.modulith.NamedInterface;

/** Creates a query vector aligned to the model stored by Ford-scraping. */
@NamedInterface
public interface CatalogQueryEmbedder {

    Optional<CatalogQueryVector> embed(String query);
}
