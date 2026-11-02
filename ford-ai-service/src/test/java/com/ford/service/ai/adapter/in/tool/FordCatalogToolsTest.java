package com.ford.service.ai.adapter.in.tool;

import com.ford.service.catalog.adapter.out.JdbcFordCatalogKnowledgeReader;
import com.ford.service.catalog.adapter.out.JdbcFordCatalogReader;
import com.ford.service.catalog.domain.CatalogFact;
import com.ford.service.catalog.domain.CatalogFilters;
import com.ford.service.catalog.domain.CatalogDocumentHit;
import com.ford.service.catalog.domain.CatalogModelSearchResult;
import org.junit.jupiter.api.Test;
import org.springframework.ai.support.ToolCallbacks;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FordCatalogToolsTest {

    @Test
    void exposesOnlyStructuredHybridAndOfferCatalogTools() {
        var tools = new FordCatalogTools(mock(JdbcFordCatalogReader.class),
                mock(JdbcFordCatalogKnowledgeReader.class));

        assertThat(ToolCallbacks.from(tools))
                .extracting(callback -> callback.getToolDefinition().name())
                .containsExactlyInAnyOrder(
                        "ford_catalog_search", "ford_catalog_hybrid_search", "ford_catalog_offers");
    }

    @Test
    void keepsExactPriceAndColorFiltersOnStructuredSqlSearch() {
        var reader = mock(JdbcFordCatalogReader.class);
        var tools = new FordCatalogTools(reader, mock(JdbcFordCatalogKnowledgeReader.class));
        var fact = new CatalogFact(UUID.randomUUID(), UUID.randomUUID(), "BRONCO", 2026,
                "Big Bend", new BigDecimal("1000000"), "MXN", "Azul", "https://ford.example/bronco");
        when(reader.searchStructuredCatalog(any(CatalogFilters.class), eq(12))).thenReturn(List.of(fact));

        var result = tools.search("BRONCO", 2026, new BigDecimal("900000"), null,
                "Azul", null, null, null, 12);

        assertThat(result).containsExactly(fact);
        verify(reader).searchStructuredCatalog(new CatalogFilters("BRONCO", 2026,
                new BigDecimal("900000"), null, "Azul", null, null, null), 12);
    }

    @Test
    void returnsModelGroupedHybridResultsWithEvidence() {
        var knowledgeReader = mock(JdbcFordCatalogKnowledgeReader.class);
        var tools = new FordCatalogTools(mock(JdbcFordCatalogReader.class), knowledgeReader);
        var modelId = UUID.randomUUID();
        var source = new CatalogDocumentHit(UUID.randomUUID(), "model", "Bronco",
                "SUV familiar", null, null, null, "https://ford.example/bronco");
        var modelResult = new CatalogModelSearchResult(modelId, "BRONCO", List.of(), List.of(source));
        var filters = new CatalogFilters("BRONCO", 2026, null, null, null, null, null, null);
        when(knowledgeReader.searchHybrid("familia", filters, 8)).thenReturn(List.of(modelResult));

        var results = tools.hybridSearch("familia", "BRONCO", 2026, null, null,
                null, null, null, null, 8);

        assertThat(results).containsExactly(modelResult);
        verify(knowledgeReader).searchHybrid("familia", filters, 8);
    }
}
