package com.ford.service.ai.adapter.in.tool;

import com.ford.service.catalog.adapter.out.JdbcFordCatalogKnowledgeReader;
import com.ford.service.catalog.adapter.out.JdbcFordCatalogReader;
import com.ford.service.catalog.domain.CatalogDocumentHit;
import com.ford.service.catalog.domain.CatalogFact;
import com.ford.service.catalog.domain.CatalogFilters;
import com.ford.service.catalog.domain.CatalogModelSearchResult;
import com.ford.service.catalog.domain.CatalogOffer;
import org.springframework.ai.tool.annotation.Tool;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public final class FordCatalogTools {

    private final JdbcFordCatalogReader catalogReader;
    private final JdbcFordCatalogKnowledgeReader knowledgeReader;

    public FordCatalogTools(JdbcFordCatalogReader catalogReader,
                            JdbcFordCatalogKnowledgeReader knowledgeReader) {
        this.catalogReader = Objects.requireNonNull(catalogReader);
        this.knowledgeReader = Objects.requireNonNull(knowledgeReader);
    }

    @Tool(name = "ford_catalog_search",
            description = "Busca datos exactos Ford por modelo, año, precio, color y tipo. No consulta inventario ni existencias.")
    public List<CatalogFact> search(String model, Integer modelYear, BigDecimal minimumPrice,
                                    BigDecimal maximumPrice, String color, String category,
                                    String bodyType, String fuelType, Integer limit) {
        return catalogReader.searchStructuredCatalog(
                new CatalogFilters(model, modelYear, minimumPrice, maximumPrice, color,
                        category, bodyType, fuelType), safeLimit(limit));
    }

    @Tool(name = "ford_catalog_hybrid_search",
            description = "Busca documentos Ford por significado y palabras clave para usos como familia o trabajo; devuelve fuentes.")
    public List<CatalogModelSearchResult> hybridSearch(String text, String model, Integer modelYear,
                                                        BigDecimal minimumPrice, BigDecimal maximumPrice,
                                                        String color, String category, String bodyType,
                                                        String fuelType, Integer limit) {
        return knowledgeReader.searchHybrid(text,
                new CatalogFilters(model, modelYear, minimumPrice, maximumPrice, color,
                        category, bodyType, fuelType), safeLimit(limit));
    }

    @Tool(name = "ford_catalog_offers",
            description = "Lista ofertas Ford vigentes para el identificador de año-modelo encontrado en el catálogo.")
    public List<CatalogOffer> currentOffers(UUID modelYearId) {
        return catalogReader.listCurrentOffers(Objects.requireNonNull(modelYearId));
    }

    private static int safeLimit(Integer requested) {
        return requested == null ? 8 : Math.max(0, Math.min(requested, 100));
    }
}
