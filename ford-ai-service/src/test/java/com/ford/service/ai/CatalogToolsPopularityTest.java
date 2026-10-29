package com.ford.service.ai;

import com.ford.service.ai.adapter.in.tool.CatalogTools;
import com.ford.service.catalog.adapter.out.ClasspathModelPopularityIndex;
import com.ford.service.catalog.api.*;
import com.ford.service.catalog.application.ModelPopularityEnricher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.support.ToolCallbacks;

import java.util.List;
import java.util.Optional;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class CatalogToolsPopularityTest {
    private final CatalogPort catalog = mock(CatalogPort.class);
    private final CatalogRetrievalPort retrieval = mock(CatalogRetrievalPort.class);
    private final VehicleEvidence ranger = vehicle(2027);
    private final ModelPopularityEnricher enricher = new ModelPopularityEnricher(new ClasspathModelPopularityIndex());
    private final CatalogTools tools = new CatalogTools(catalog, retrieval, enricher);

    @BeforeEach
    void catalogEvidence() {
        when(catalog.search(any())).thenReturn(List.of(ranger, vehicle(2026)));
        when(catalog.find(any())).thenAnswer(call -> Optional.of(vehicle(((VehicleLookup) call.getArgument(0)).year())));
        when(catalog.promotions(any())).thenReturn(new PromotionPage(0, 0, 20, List.of()));
        when(retrieval.searchVehicles(any())).thenReturn(new CatalogSearchPage<>("ready", 1, List.of(ranger)));
        when(retrieval.searchOffers(any())).thenReturn(new CatalogSearchPage<>("ready", 0, List.of()));
    }

    @Test
    void enrichesStructuredAndSemanticSearchWithoutChangingMetadata() {
        var semantic = tools.searchVehicles("Ranger", null, null, null, null, null, null, null, 5);
        assertThat(semantic.semanticSearch()).isEqualTo("ready");
        assertThat(semantic.resultCount()).isEqualTo(1);
        assertEnriched(semantic.results().getFirst());
        var structured = new CatalogTools(catalog, null, enricher)
                .searchVehicles(null, "Ranger", null, null, null, null, null, null, 5);
        assertThat(structured.semanticSearch()).isEqualTo("not_requested");
        assertThat(structured.resultCount()).isEqualTo(2);
        structured.results().forEach(this::assertEnriched);
    }

    @Test
    void enrichesExactLookupYearOptionsAndUnavailableYearOptions() {
        assertEnriched((VehicleEvidence) tools.getVehicle("Ranger", 2027, null));
        var options = ((CatalogTools.ModelYearOptions) tools.getVehicle("Ranger", null, null)).options();
        assertThat(options).hasSize(2);
        options.forEach(this::assertEnriched);
        when(catalog.find(new VehicleLookup("Ranger", 2020, null))).thenReturn(Optional.empty());
        var missing = (CatalogTools.UnavailableModelYear) tools.getVehicle("Ranger", 2020, null);
        assertThat(missing.options()).hasSize(2);
        missing.options().forEach(this::assertEnriched);
    }

    @Test
    void enrichesCombinedAndBothVehicleComparisons() {
        var combined = tools.searchVehicleAndOffers(null, "Ranger", 2027, null, null, null);
        assertThat(combined.vehicles().results()).hasSize(1);
        combined.vehicles().results().forEach(this::assertEnriched);
        var compared = tools.compareVehicles(List.of(new CatalogTools.VehicleTarget("Ranger", 2027),
                new CatalogTools.VehicleTarget("Ranger", 2026)));
        assertThat(compared).hasSize(2);
        compared.forEach(this::assertEnriched);
        var years = tools.compareModelYears("Ranger", List.of(2027, 2026)).years();
        assertThat(years).hasSize(2);
        years.forEach(snapshot -> assertEnriched(snapshot.vehicle()));
    }

    @Test
    void registeredVehicleCallbacksSerializeParentModelPopularity() throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var inputs = Map.of(
                "search_vehicles", "{\"model\":\"Ranger\"}",
                "get_vehicle", "{\"model\":\"Ranger\",\"year\":2027}",
                "search_vehicle_and_offers", "{\"model\":\"Ranger\",\"year\":2027}",
                "compare_vehicles", "{\"vehicles\":[{\"model\":\"Ranger\",\"year\":2027},{\"model\":\"Ranger\",\"year\":2026}]}",
                "compare_model_years", "{\"model\":\"Ranger\",\"years\":[2027,2026]}");
        for (var callback : ToolCallbacks.from(tools)) {
            var input = inputs.get(callback.getToolDefinition().name());
            if (input == null) continue;
            var response = mapper.readTree(callback.call(input));
            var metrics = response.findValues("modelPopularity");
            assertThat(metrics).isNotEmpty();
            metrics.forEach(metric -> {
                assertThat(metric.path("modelFamily").asText()).isEqualTo("Ranger");
                assertThat(metric.path("cumulativeUnits").asLong()).isEqualTo(17452);
                assertThat(metric.has("trimRank")).isFalse();
                assertThat(metric.has("trimUnits")).isFalse();
            });
        }
    }

    @Test
    void unknownCatalogModelIsUnmodifiedThroughSearchAndLookup() {
        var unknown = new VehicleEvidence("Unknown", 2027, "Ranger", null, "MXN", null, null, null, List.of());
        when(retrieval.searchVehicles(any())).thenReturn(new CatalogSearchPage<>("ready", 1, List.of(unknown)));
        doReturn(Optional.of(unknown)).when(catalog).find(any());
        assertThat(tools.searchVehicles("Unknown", null, null, null, null, null, null, null, 5)
                .results().getFirst()).isSameAs(unknown);
        assertThat(tools.getVehicle("Unknown", 2027, null)).isSameAs(unknown);
    }

    @Test
    void legacyConstructorsKeepUnenrichedBehaviorAndToolSurface() {
        assertEnriched(new CatalogTools(catalog).searchVehicles(null, "Ranger", null, null, null,
                null, null, null, 5).results().getFirst());
        assertEnriched(new CatalogTools(catalog, retrieval).searchVehicles("Ranger", null, null, null, null,
                null, null, null, 5).results().getFirst());
        assertThat(List.of(ToolCallbacks.from(tools))).hasSize(12);
    }

    @Test
    void explicitSalesPeriodOverridesInferenceAndInvalidPeriodFails() throws Exception {
        var callback = java.util.Arrays.stream(ToolCallbacks.from(tools))
                .filter(tool -> tool.getToolDefinition().name().equals("search_vehicles")).findFirst().orElseThrow();
        assertThat(callback.getToolDefinition().inputSchema()).contains("salesPeriod", "CUMULATIVE", "CURRENT_YTD");
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var cumulative = mapper.readTree(callback.call("{\"query\":\"pickup más vendido actualmente\",\"rankingMode\":\"MOST_SOLD\",\"salesPeriod\":\"CUMULATIVE\"}"));
        assertThat(cumulative.at("/mostSoldEligibleModel/sales/units").asLong()).isEqualTo(17452);
        var ytd = mapper.readTree(callback.call("{\"query\":\"pickup más vendido\",\"rankingMode\":\"MOST_SOLD\",\"salesPeriod\":\"CURRENT_YTD\"}"));
        assertThat(ytd.at("/mostSoldEligibleModel/sales/units").asLong()).isEqualTo(4411);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> callback.call("{\"query\":\"Ranger\",\"salesPeriod\":\"LAST_YEAR\"}"))
                .hasMessageContaining("salesPeriod");
    }

    @Test
    void infersCurrentYtdFromAccentlessSpanishTrendPhrase() throws Exception {
        var callback = java.util.Arrays.stream(ToolCallbacks.from(tools))
                .filter(tool -> tool.getToolDefinition().name().equals("search_vehicles")).findFirst().orElseThrow();
        var result = new com.fasterxml.jackson.databind.ObjectMapper().readTree(callback.call(
                "{\"query\":\"ventas en lo que va del ano\",\"rankingMode\":\"MOST_SOLD\"}"));

        assertThat(result.path("selectedSalesPeriod").asText()).isEqualTo("CURRENT_YTD");
        assertThat(result.at("/mostSoldEligibleModel/sales/units").asLong()).isEqualTo(4411);
    }

    @Test
    void currentYtdWithoutReportedEligibleModelKeepsFitOrderAndReportsUnavailable() throws Exception {
        var first = vehicle(2027);
        var second = new VehicleEvidence("Escape", 2027, null, null, "MXN", null, null, null, List.of());
        when(retrieval.searchVehicles(any())).thenReturn(new CatalogSearchPage<>("ready", 2, List.of(first, second)));
        when(catalog.search(any())).thenReturn(List.of());
        var callback = java.util.Arrays.stream(ToolCallbacks.from(tools))
                .filter(tool -> tool.getToolDefinition().name().equals("search_vehicles")).findFirst().orElseThrow();
        var result = new com.fasterxml.jackson.databind.ObjectMapper().readTree(callback.call(
                "{\"query\":\"sinónimo de Ranger\",\"rankingMode\":\"MOST_SOLD\",\"salesPeriod\":\"CURRENT_YTD\"}"));
        assertThat(result.at("/results/0/model").asText()).isEqualTo("Ranger");
        assertThat(result.path("rankingStatus").asText()).isEqualTo("UNAVAILABLE_NO_REPORTED_YTD");
        assertThat(result.path("selectedSalesPeriod").asText()).isEqualTo("CURRENT_YTD");
        assertThat(result.path("mostSoldEligibleModel").isNull()).isTrue();
        assertThat(result.path("periodDisclosure").isNull()).isTrue();
    }

    @Test
    void cumulativeMostSoldDoesNotReturnUnrankedFitResultsWhenNoPopularityCandidateExists() throws Exception {
        when(catalog.search(any())).thenReturn(List.of());
        var result = new com.fasterxml.jackson.databind.ObjectMapper().readTree(
                java.util.Arrays.stream(ToolCallbacks.from(tools))
                        .filter(tool -> tool.getToolDefinition().name().equals("search_vehicles")).findFirst().orElseThrow()
                        .call("{\"query\":\"modelo Ford\",\"rankingMode\":\"MOST_SOLD\",\"salesPeriod\":\"CUMULATIVE\"}"));

        assertThat(result.path("results")).isEmpty();
        assertThat(result.path("resultCount").asInt()).isZero();
        assertThat(result.path("rankingStatus").asText()).isEqualTo("UNAVAILABLE_NO_ELIGIBLE_POPULARITY");
        assertThat(result.path("mostSoldEligibleModel").isNull()).isTrue();
        assertThat(result.path("periodDisclosure").isNull()).isTrue();
    }

    @Test
    void fitFirstCurrentYtdWithoutReportedCandidatesKeepsRrfOrderAndReportsUnavailable() throws Exception {
        var first = vehicle(2027);
        var second = new VehicleEvidence("Escape", 2027, null, null, "MXN", null, null, null, List.of());
        when(retrieval.searchVehicles(any())).thenReturn(new CatalogSearchPage<>("ready", 2, List.of(first, second)));
        when(catalog.search(any())).thenReturn(List.of());
        var result = new com.fasterxml.jackson.databind.ObjectMapper().readTree(
                java.util.Arrays.stream(ToolCallbacks.from(tools))
                        .filter(tool -> tool.getToolDefinition().name().equals("search_vehicles")).findFirst().orElseThrow()
                        .call("{\"query\":\"sinónimo de Ranger\",\"rankingMode\":\"FIT_FIRST\",\"salesPeriod\":\"CURRENT_YTD\"}"));
        assertThat(result.at("/results/0/model").asText()).isEqualTo("Ranger");
        assertThat(result.at("/results/1/model").asText()).isEqualTo("Escape");
        assertThat(result.path("rankingStatus").asText()).isEqualTo("UNAVAILABLE_NO_REPORTED_YTD");
        assertThat(result.path("mostSoldEligibleModel").isNull()).isTrue();
        assertThat(result.path("periodDisclosure").isNull()).isTrue();
    }

    @Test
    void fitFirstCurrentYtdWithNoCatalogPopularityCandidatesReportsUnavailable() throws Exception {
        var first = vehicle(2027);
        when(retrieval.searchVehicles(any())).thenReturn(new CatalogSearchPage<>("ready", 1, List.of(first)));
        when(catalog.search(any())).thenReturn(List.of());
        var result = new com.fasterxml.jackson.databind.ObjectMapper().readTree(
                java.util.Arrays.stream(ToolCallbacks.from(tools))
                        .filter(tool -> tool.getToolDefinition().name().equals("search_vehicles")).findFirst().orElseThrow()
                        .call("{\"query\":\"Ranger\",\"rankingMode\":\"FIT_FIRST\",\"salesPeriod\":\"CURRENT_YTD\"}"));
        assertThat(result.at("/results/0/model").asText()).isEqualTo("Ranger");
        assertThat(result.path("rankingStatus").asText()).isEqualTo("UNAVAILABLE_NO_REPORTED_YTD");
        assertThat(result.path("mostSoldEligibleModel").isNull()).isTrue();
        assertThat(result.path("periodDisclosure").isNull()).isTrue();
    }

    @Test
    void fitFirstPreservesHybridOrderCountStatusAndProvenanceWithSeparateSpotlight() {
        var transit = new VehicleEvidence("Transit", 2027, null, null, "MXN", "transit-url", null, null, List.of());
        when(retrieval.searchVehicles(any())).thenReturn(new CatalogSearchPage<>("ready", 2, List.of(transit, ranger)));
        var result = tools.searchVehicles("camioneta familiar", null, null, null, null, null, null, null, 5, null);
        assertThat(result.rankingMode()).isEqualTo("FIT_FIRST");
        assertThat(result.query()).isEqualTo("camioneta familiar");
        assertThat(result.results()).extracting(VehicleEvidence::model).containsExactly("Transit", "Ranger");
        assertThat(result.results().getFirst().modelPageUrl()).isEqualTo("transit-url");
        assertThat(result.resultCount()).isEqualTo(2);
        assertThat(result.semanticSearch()).isEqualTo("ready");
        assertThat(result.mostSoldEligibleModel().eligibleVehicle().model()).isEqualTo("Ranger");
        assertThat(result.periodDisclosure()).contains("modelo", "INEGI", "2024-01-01", "2026-09-30");
    }

    @Test
    void registeredMostSoldAndCurrentTrendUseStructuredCandidatesNotSalesWording() throws Exception {
        when(catalog.search(any())).thenAnswer(call -> {
            VehicleSearchQuery query = call.getArgument(0);
            assertThat(query.query()).isNull();
            assertThat(query.category()).isEqualTo("Camiones");
            return "Ranger".equals(query.model()) ? List.of(ranger) : List.of();
        });
        var callback = java.util.Arrays.stream(ToolCallbacks.from(tools))
                .filter(tool -> tool.getToolDefinition().name().equals("search_vehicles")).findFirst().orElseThrow();
        assertThat(callback.getToolDefinition().inputSchema()).contains("rankingMode", "MOST_SOLD", "FIT_FIRST");
        assertThat(callback.getToolDefinition().description()).contains("mostSoldEligibleModel", "modelo", "versión", "INEGI");
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var cumulative = mapper.readTree(callback.call("{\"query\":\"pickup más vendido\",\"bodyType\":\"pickup\",\"rankingMode\":\"MOST_SOLD\"}"));
        assertThat(cumulative.path("rankingMode").asText()).isEqualTo("MOST_SOLD");
        assertThat(cumulative.at("/mostSoldEligibleModel/sales/units").asLong()).isEqualTo(17452);
        var current = mapper.readTree(callback.call("{\"query\":\"pickup más vendido actualmente\",\"bodyType\":\"pickup\",\"rankingMode\":\"MOST_SOLD\"}"));
        assertThat(current.at("/mostSoldEligibleModel/sales/units").asLong()).isEqualTo(4411);
        assertThat(current.at("/mostSoldEligibleModel/comparison/units").asLong()).isEqualTo(4800);
        assertThat(current.at("/mostSoldEligibleModel/comparison/status").asText()).isEqualTo("REPORTED");
    }

    @Test
    void invalidRankingModeFailsBeforeCatalogReadsAndMostSoldWithoutEligiblePopularityReturnsUnavailable() {
        clearInvocations(catalog, retrieval);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> tools.searchVehicles("Ranger", null, null,
                null, null, null, null, null, 5, "POPULAR"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("rankingMode");
        verifyNoInteractions(catalog, retrieval);
        when(catalog.search(any())).thenReturn(List.of());
        var result = tools.searchVehicles("más vendido", null, null, null, null, null, null, null, 5, "MOST_SOLD");
        assertThat(result.results()).isEmpty();
        assertThat(result.resultCount()).isZero();
        assertThat(result.rankingStatus()).isEqualTo("UNAVAILABLE_NO_ELIGIBLE_POPULARITY");
        assertThat(result.mostSoldEligibleModel()).isNull();
        assertThat(result.periodDisclosure()).isNull();
    }

    private void assertEnriched(VehicleEvidence result) {
        assertThat(result.modelPopularity()).isNotNull();
        assertThat(result.modelPopularity().modelFamily()).isEqualTo("Ranger");
        assertThat(result.modelPopularity().cumulativeUnits()).isEqualTo(17452);
        assertThat(result.modelPopularity()).isEqualTo(enricher.enrich(ranger).modelPopularity());
        assertThat(result).usingRecursiveComparison().ignoringFields("modelPopularity", "year").isEqualTo(ranger);
    }

    private static VehicleEvidence vehicle(Integer year) {
        return new VehicleEvidence("Ranger", year, null, null, "MXN", null, null, null,
                List.of(new VehicleFact("Motor", "2.0 L", null, "sheet-url", null)));
    }
}
