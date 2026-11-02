package com.ford.service.catalog;

import com.ford.service.catalog.adapter.out.ClasspathModelPopularityIndex;
import com.ford.service.catalog.api.*;
import com.ford.service.catalog.application.CatalogPopularityRecommendationService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class CatalogPopularityRecommendationServiceTest {
    private final CatalogPort catalog = mock(CatalogPort.class);
    private final ClasspathModelPopularityIndex index = new ClasspathModelPopularityIndex();
    private final CatalogPopularityRecommendationService service =
            new CatalogPopularityRecommendationService(catalog, index);

    @Test
    void pickupSpotlightUsesOnlyStructuredFiltersAndSkipsTerritory() {
        when(catalog.search(any())).thenAnswer(call -> {
            VehicleSearchQuery query = call.getArgument(0);
            assertThat(query.query()).isNull();
            assertThat(query.category()).isEqualTo("Camiones");
            assertThat(query.bodyType()).isEqualTo("doble cabina");
            assertThat(query.fuelType()).isEqualTo("diesel");
            return "Ranger".equals(query.model()) ? List.of(vehicle("Ranger", 2027, 600000)) : List.of();
        });
        var recommendation = service.recommend(new VehicleSearchQuery("más vendido", null, null,
                null, null, "Camiones", "doble cabina", "diesel", 5), false, 1);
        assertThat(recommendation.mostSoldEligibleModel().eligibleVehicle().model()).isEqualTo("Ranger");
        assertThat(recommendation.mostSoldEligibleModel().sales().units()).isEqualTo(17452);
        assertThat(recommendation.periodDisclosure()).contains("modelo", "2024-01-01", "2026-09-30", "INEGI");
    }

    @Test
    void exactModelYearAndBudgetAreNeverReplacedByPopularity() {
        when(catalog.search(any())).thenAnswer(call -> {
            VehicleSearchQuery query = call.getArgument(0);
            assertThat(query.model()).isIn("Ranger Crew Cab", "Ranger");
            assertThat(query.modelYear()).isEqualTo(2027);
            assertThat(query.minPrice()).isEqualByComparingTo("500000");
            assertThat(query.maxPrice()).isEqualByComparingTo("700000");
            // Defensive validation also rejects malformed port responses.
            return List.of(vehicle("Territory", 2027, 600000),
                    vehicle("Ranger Crew Cab", 2026, 600000), vehicle("Ranger Crew Cab", 2027, 800000),
                    vehicle("Ranger Crew Cab", 2027, 400000));
        });
        var recommendation = service.recommend(new VehicleSearchQuery("más vendido", "Ranger Crew Cab", 2027,
                new BigDecimal("500000"), new BigDecimal("700000"), null, null, null, 5), false, 5);
        assertThat(recommendation.results()).isEmpty();
        assertThat(recommendation.mostSoldEligibleModel()).isNull();
        assertThat(recommendation.periodDisclosure()).isNull();
    }

    @Test
    void exactAliasFilterDoesNotExpandToCanonicalSiblingButOpenQueryStillUsesFamily() {
        when(catalog.search(any())).thenAnswer(call -> {
            VehicleSearchQuery query = call.getArgument(0);
            return "Ranger Crew Cab".equals(query.model())
                    ? List.of(vehicle("Ranger", 2027, 600000)) : List.of();
        });
        var result = service.recommend(new VehicleSearchQuery(null, "Ranger Crew Cab", null,
                null, null, null, null, null, 5), false, 5);
        assertThat(result.results()).isEmpty();
        assertThat(result.mostSoldEligibleModel()).isNull();
        doAnswer(call -> {
            VehicleSearchQuery query = call.getArgument(0);
            return "Ranger".equals(query.model()) ? List.of(vehicle("Ranger", 2027, 600000)) : List.of();
        }).when(catalog).search(any());
        var open = service.recommend(new VehicleSearchQuery(null, null, null,
                null, null, null, null, null, 5), false, 5);
        assertThat(open.results()).extracting(VehicleEvidence::model).contains("Ranger");
        assertThat(open.mostSoldEligibleModel().eligibleVehicle().model()).isEqualTo("Ranger");
        assertThat(open.mostSoldEligibleModel().sales().units()).isEqualTo(17452);
    }

    @Test
    void cumulativeAndCurrentTrendRankingsUseDifferentMetricsAndSeparateComparison() {
        when(catalog.search(any())).thenAnswer(call -> {
            VehicleSearchQuery query = call.getArgument(0);
            return List.of("Ranger", "Transit", "F-150").contains(query.model())
                    ? List.of(vehicle(query.model(), 2027, 600000)) : List.of();
        });
        var query = new VehicleSearchQuery(null, null, null, null, null, null, null, null, 5);
        assertThat(service.recommend(query, false, 5).results()).extracting(VehicleEvidence::model)
                .containsExactly("Ranger", "Transit", "F-150");
        var current = service.recommend(query, true, 5);
        assertThat(current.results()).extracting(VehicleEvidence::model).containsExactly("Ranger", "F-150", "Transit");
        assertThat(current.mostSoldEligibleModel().sales().units()).isEqualTo(4411);
        assertThat(current.mostSoldEligibleModel().sales().periodStart()).hasToString("2026-01-01");
        assertThat(current.mostSoldEligibleModel().comparison().units()).isEqualTo(4800);
        assertThat(current.mostSoldEligibleModel().comparison().periodStart()).hasToString("2025-01-01");
        assertThat(current.results()).allSatisfy(vehicle -> assertThat(vehicle.modelPopularity()).isNotNull());
    }

    @Test
    void mostSoldKeepsDistinctTrimsInModelPopularityOrderAndDeduplicatesAliases() {
        when(catalog.search(any())).thenAnswer(call -> {
            String model = ((VehicleSearchQuery) call.getArgument(0)).model();
            if (model.equals("Ranger")) return List.of(vehicleTrim("Ranger", "XLT"), vehicleTrim("Ranger", "Wildtrak"));
            return List.of(vehicle(model, 2027, 600000));
        });
        var result = service.recommend(new VehicleSearchQuery(null, null, null, null, null, null, null, null, 5), false, 5);
        assertThat(result.results()).filteredOn(v -> v.model().equals("Ranger"))
                .extracting(VehicleEvidence::trim).containsExactly("XLT", "Wildtrak");
        var ranger = result.results().stream().filter(v -> v.model().equals("Ranger")).toList();
        assertThat(ranger).extracting(VehicleEvidence::modelPopularity).containsOnly(ranger.get(0).modelPopularity());
    }

    @Test
    void acceptsBoundaryLimitsOneAndFive() {
        when(catalog.search(any())).thenAnswer(call -> List.of(vehicle(((VehicleSearchQuery) call.getArgument(0)).model(), 2027, 600000)));
        var query = new VehicleSearchQuery(null, null, null, null, null, null, null, null, 5);
        assertThat(service.recommend(query, false, 1).results()).hasSize(1);
        assertThat(service.recommend(query, false, 5).results()).hasSize(5);
    }

    @Test
    void unreportedCurrentMetricsCannotBecomeZeroSalesSpotlight() {
        when(catalog.search(any())).thenReturn(List.of(vehicle("Escape", 2027, 600000)));
        var query = new VehicleSearchQuery(null, "Escape", null, null, null, null, null, null, 5);
        assertThat(service.recommend(query, true, 5).mostSoldEligibleModel()).isNull();
        assertThat(service.recommend(query, false, 5).mostSoldEligibleModel()).isNotNull();
    }

    @Test
    void unreportedCurrentMetricIsNotRankedButFitFirstAndEvidenceStatusRemain() {
        when(catalog.search(any())).thenAnswer(call -> {
            String model = ((VehicleSearchQuery) call.getArgument(0)).model();
            return List.of("Escape", "Territory", "Ranger", "Transit", "F-150").contains(model)
                    ? List.of(vehicle(model, 2027, 600000)) : List.of();
        });
        var query = new VehicleSearchQuery(null, null, null, null, null, null, null, null, 5);
        var current = service.recommend(query, true, 5);
        assertThat(current.results()).extracting(VehicleEvidence::model).doesNotContain("Escape");
        assertThat(current.mostSoldEligibleModel().eligibleVehicle().model()).isNotEqualTo("Escape");
        assertThat(current.results()).noneMatch(v -> v.model().equals("Escape"));
        assertThat(service.recommend(query, false, 5).results()).extracting(VehicleEvidence::model).contains("Escape");
        assertThat(index.findForCatalogModel("Escape").orElseThrow().ytd2026Status())
                .isEqualTo(com.ford.service.catalog.application.ModelPopularity.SourceState.NOT_REPORTED_OR_NOT_MARKETED);
    }

    @Test
    void rejectsLimitsOutsideOneThroughFive() {
        var query = new VehicleSearchQuery(null, null, null, null, null, null, null, null, 5);
        assertThatThrownBy(() -> service.recommend(query, false, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.recommend(query, false, -1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void unknownModelHasNoSalesClaimOrCatalogSubstitution() {
        var query = new VehicleSearchQuery(null, "Unknown", null, null, null, null, null, null, 5);
        assertThat(service.recommend(query, false, 5).mostSoldEligibleModel()).isNull();
        verifyNoInteractions(catalog);
    }

    private static VehicleEvidence vehicle(String model, int year, int price) {
        return new VehicleEvidence(model, year, "XL", BigDecimal.valueOf(price), "MXN", "model-url",
                "price-url", null, List.of());
    }

    private static VehicleEvidence vehicleTrim(String model, String trim) {
        return new VehicleEvidence(model, 2027, trim, BigDecimal.valueOf(600000), "MXN", "model-url", "price-url", null, List.of());
    }
}
