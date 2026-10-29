package com.ford.service.catalog;

import com.ford.service.catalog.api.CatalogPort;
import com.ford.service.catalog.api.CatalogStatus;
import com.ford.service.catalog.api.OfferQuery;
import com.ford.service.catalog.api.PromotionEvidence;
import com.ford.service.catalog.api.PromotionPage;
import com.ford.service.catalog.api.VehicleEvidence;
import com.ford.service.catalog.api.VehicleFact;
import com.ford.service.catalog.api.VehicleLookup;
import com.ford.service.catalog.api.VehicleNameIndexEntry;
import com.ford.service.catalog.api.VariantComparisonEvidence;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CatalogCoverageAuditTest {

    @Test
    void reportsSourcedCoverageAndUnknownGapsWithoutClaimingSourceCompleteness() {
        var catalog = mock(CatalogPort.class);
        var names = List.of(new VehicleNameIndexEntry("Ranger", 2027, "XL Gasolina 4x2"),
                new VehicleNameIndexEntry("Ranger", 2027, "XLT Diésel 4x4"));
        var observedAt = Instant.parse("2026-10-26T12:00:00Z");
        var sourcedVehicle = new VehicleEvidence("Ranger", 2027, "XL Gasolina 4x2",
                new BigDecimal("800000"), "MXN", "https://www.ford.com.mx/ranger/",
                "https://www.ford.com.mx/ranger/precio/", observedAt,
                List.of(new VehicleFact("Motor", "2.3L", null,
                        "https://www.ford.com.mx/ranger/ficha/", observedAt)));
        when(catalog.nameIndexEntries()).thenReturn(names);
        when(catalog.find(new VehicleLookup("Ranger", 2027, "XL Gasolina 4x2")))
                .thenReturn(Optional.of(sourcedVehicle));
        when(catalog.find(new VehicleLookup("Ranger", 2027, "XLT Diésel 4x4"))).thenReturn(Optional.empty());
        when(catalog.variantComparisons(any(), any(), any(), any(Integer.class), any(Integer.class)))
                .thenReturn(List.<VariantComparisonEvidence>of());
        var active = promotion("active", LocalDate.of(2026, 10, 1), LocalDate.of(2026, 11, 1), observedAt);
        var expired = promotion("expired", LocalDate.of(2026, 8, 1), LocalDate.of(2026, 9, 1), observedAt);
        when(catalog.promotions(any())).thenReturn(new PromotionPage(2, 0, 20, List.of(active, expired)));
        when(catalog.offers(any(OfferQuery.class))).thenReturn(List.of());
        when(catalog.status()).thenReturn(new CatalogStatus(1, 1, 2, 2, 2, 0, 0,
                observedAt, null, null, null, 2, 2, 0));

        var audit = new CatalogCoverageAudit(catalog, LocalDate.of(2026, 10, 27));
        var report = audit.audit(new CatalogCoverageAudit.SnapshotMetadata("oct25-candidate", "manifest-sha256",
                observedAt, Map.of("vehicle-variants.csv", 2, "vehicle-facts.csv", 1)));

        assertThat(report.modelYearTrimCount()).isEqualTo(2);
        assertThat(report.sourcedFactCount()).isEqualTo(1);
        assertThat(report.variantsWithoutSourcedFacts()).isEqualTo(1);
        assertThat(report.sourcedPriceCount()).isEqualTo(1);
        assertThat(report.unknownPackageIdentityCount()).isEqualTo(2);
        assertThat(report.activePromotionCount()).isEqualTo(1);
        assertThat(report.expiredPromotionCount()).isEqualTo(1);
        assertThat(report.officialSourceScope()).isEqualTo("UNVERIFIED");
        assertThat(report.sourceSyncStatus()).isEqualTo("NOT_RECORDED");
        assertThat(report.offersTruncated()).isFalse();
        assertThat(report.sourcedOfferCount()).isZero();
        assertThat(report.comparisonRowsTruncated()).isFalse();
        assertThat(report.staleAfterDays()).isEqualTo(90);
        assertThat(report.sourceHosts()).containsExactly("www.ford.com.mx");
        assertThat(report.variants()).hasSize(2);
        assertThat(report.variants()).allSatisfy(variant ->
                assertThat(variant.packageIdentityStatus()).isEqualTo("UNKNOWN"));
    }

    @Test
    void coverageRequirementsKeepUnverifiedAndInventorySeparate() throws Exception {
        try (var input = getClass().getResourceAsStream("/catalog/ford-mx-coverage-requirements.json")) {
            assertThat(input).isNotNull();
            var requirements = new com.fasterxml.jackson.databind.ObjectMapper().readTree(input);
            assertThat(requirements.path("scopeStatus").asText()).isEqualTo("UNVERIFIED");
            assertThat(requirements.path("domains").findValuesAsText("name"))
                    .contains("model_year_trim_identity", "package_identity", "technical_specifications",
                            "published_price", "finance_products", "promotions", "physical_dealer_inventory");
        }
    }

    private static PromotionEvidence promotion(String id, LocalDate from, LocalDate until, Instant observedAt) {
        return new PromotionEvidence(id, "Ranger", 2027, id, null, null, null, null, "MXN", null,
                from, until, null, "https://www.ford.com.mx/ofertas/", observedAt,
                "https://www.ford.com.mx/ranger/");
    }
}
