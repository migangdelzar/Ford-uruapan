package com.ford.service.catalog;

import com.ford.service.catalog.adapter.out.JdbcCatalogAdapter;
import com.ford.service.catalog.api.VehicleLookup;
import com.ford.service.catalog.api.OfferQuery;
import com.ford.service.catalog.api.PromotionQuery;
import com.ford.service.ai.adapter.in.tool.CatalogTools;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Optional read-only check against a restored, disposable Ford catalog snapshot. */
class CatalogSnapshotParityIntegrationTest {
    private static JdbcCatalogAdapter catalog;
    private static CatalogTools tools;

    @BeforeAll
    static void connectToSnapshotWhenConfigured() {
        var url = System.getenv("FORD_CATALOG_PARITY_JDBC_URL");
        assumeTrue(url != null && !url.isBlank(), "snapshot parity JDBC URL is not configured");
        var username = System.getenv().getOrDefault("FORD_CATALOG_PARITY_USERNAME", "");
        var password = System.getenv().getOrDefault("FORD_CATALOG_PARITY_PASSWORD", "");
        var dataSource = new DriverManagerDataSource(url, username, password);
        catalog = new JdbcCatalogAdapter(JdbcClient.create(dataSource), new com.fasterxml.jackson.databind.ObjectMapper());
        tools = new CatalogTools(catalog);
    }

    @Test
    void readsRealSnapshotTrimPriceFactSourceAndObservationTime() {
        var status = catalog.status();
        assertThat(status.modelCount()).isPositive();
        assertThat(status.pages()).isEqualTo(status.pageCount());
        assertThat(status.vehicles()).isEqualTo(status.activeModelYearCount());
        assertThat(status.embedded()).isEqualTo(status.embeddedDocumentCount());

        var bronco = catalog.find(new VehicleLookup("BRONCO", 2026, "Badlands 2 Puertas")).orElseThrow();

        assertThat(bronco.trim()).isEqualTo("Badlands 2 Puertas");
        assertThat(bronco.price()).isEqualByComparingTo("1550500.00");
        assertThat(bronco.priceSourceUrl())
                .isEqualTo("https://www.forduruapan.mx/BRONCO/26/Comparativo/BADLANDS-2-PUERTAS/?exterior=negro%20basalto&interior=");
        assertThat(bronco.comparisonUrl())
                .isEqualTo("https://www.forduruapan.mx/BRONCO/26/Comparativo/BADLANDS-2-PUERTAS/?exterior=negro%20basalto&interior=");
        assertThat(bronco.technicalSheetUrl())
                .isEqualTo("https://www.forduruapan.mx/BRONCO/26/Ficha-Tecnica/");
        assertThat(bronco.observedAt()).isNotNull();
        assertThat(bronco.facts()).isNotEmpty();
        assertThat(bronco.facts()).allSatisfy(fact -> {
            assertThat(fact.sourceUrl()).contains("/BRONCO/26/Comparativo/BADLANDS-2-PUERTAS/");
            assertThat(fact.observedAt()).isNotNull();
        });
    }

    @Test
    void readsRealOffersPromotionsAndComparisonEvidenceWithDatesAndSources() {
        var asOf = LocalDate.of(2026, 10, 17);
        var offers = catalog.offers(new OfferQuery(
                "BRONCO", 2026, "financing", null, null, null, null, null, 42, false, asOf, 10));
        assertThat(offers).isNotEmpty();
        assertThat(offers).anySatisfy(offer -> {
            assertThat(offer.offerId()).isNotBlank();
            assertThat(offer.title()).isEqualTo("BRONCO Base 2 puertas manual - 42-month financing");
            assertThat(offer.cashPrice()).isEqualByComparingTo("998500.00");
            assertThat(offer.monthlyPayment()).isEqualByComparingTo("22171.55");
            assertThat(offer.termMonths()).isEqualTo(42);
            assertThat(offer.sourceUrl()).isEqualTo("https://www.forduruapan.mx/Financiamiento/");
            assertThat(offer.observedAt()).isNotNull();
        });

        var promotions = catalog.promotions(new PromotionQuery("BRONCO SPORT", 2026, asOf, false, 10));
        assertThat(promotions.results()).hasSize(1);
        assertThat(promotions.totalCount()).isEqualTo(1);
        assertThat(promotions.results().getFirst().id()).isNotBlank();
        assertThat(promotions.results().getFirst().validFrom()).isEqualTo(LocalDate.of(2026, 10, 1));
        assertThat(promotions.results().getFirst().validUntil()).isEqualTo(LocalDate.of(2026, 11, 2));
        assertThat(promotions.results().getFirst().validityStatus()).isEqualTo("active");
        assertThat(promotions.results().getFirst().sourceUrl()).isEqualTo("https://www.forduruapan.mx/Promociones/");
        assertThat(promotions.results().getFirst().modelUrl())
                .isEqualTo("https://www.forduruapan.mx/BRONCO-SPORT/26/");
        assertThat(promotions.results().getFirst().observedAt()).isNotNull();

        var pageLinks = catalog.pageLinks("https://www.forduruapan.mx/BRONCO/26/", 0, 10, false);
        assertThat(pageLinks.title()).isEqualTo("BRONCO 2026 | Ford Uruapan");

        var comparisons = catalog.variantComparisons("BRONCO", 2026, "Outer Banks", 0, 10);
        assertThat(comparisons).isNotEmpty();
        assertThat(comparisons.getFirst().competitorMake()).isEqualTo("Jeep");
        assertThat(comparisons.getFirst().competitorModel()).isEqualTo("Grand Cherokee");
        assertThat(comparisons.getFirst().sourceUrl()).contains("/BRONCO/26/Comparativo/OUTER-BANKS/");
        assertThat(comparisons.getFirst().observedAt()).isNotNull();
    }

    @Test
    void comparisonToolsReturnPairedChangesAndMissingYearEvidenceFromSnapshot() {
        var trims = tools.compareTrims("BRONCO", 2026, java.util.List.of(
                "Badlands 2 Puertas", "Outer Banks", "Unpublished Trim"));
        assertThat(trims.trims()).hasSize(2);
        assertThat(trims.missingTrims()).containsExactly("Unpublished Trim");
        assertThat(trims.comparisons()).singleElement().satisfies(pair -> {
            assertThat(pair.priceDifferenceMxn()).isEqualByComparingTo("-250000.00");
            assertThat(pair.differences()).allSatisfy(difference -> {
                assertThat(difference.left().sourceUrl()).isNotBlank();
                assertThat(difference.right().sourceUrl()).isNotBlank();
            });
        });

        var years = tools.compareModelYears("EXPEDITION", java.util.List.of(2025, 2026, 2027, 2024));
        assertThat(years.years()).hasSize(3);
        assertThat(years.missingYears()).containsExactly(2024);
        assertThat(years.yearPairs()).hasSize(3);
        assertThat(years.years()).anySatisfy(snapshot -> {
            assertThat(snapshot.vehicle().year()).isEqualTo(2026);
            assertThat(snapshot.activePromotions()).isNotEmpty();
        });

        var crossModel = tools.compareVehicles(java.util.List.of(
                new CatalogTools.VehicleTarget("BRONCO", 2026),
                new CatalogTools.VehicleTarget("EXPEDITION", 2026)));
        var bronco = crossModel.stream().filter(item -> item.model().equals("BRONCO"))
                .findFirst().orElseThrow();
        assertThat(bronco.images()).isNotEmpty();
        assertThat(bronco.images().getFirst().url()).contains("forduruapan.mx/Assets/");
        assertThat(bronco.images().getFirst().sourceUrl()).contains("/BRONCO/26");
        assertThat(bronco.images().getFirst().observedAt()).isNotNull();
        var broncoFacts = catalog.find(new VehicleLookup("BRONCO", 2026, null)).orElseThrow().facts();
        assertThat(broncoFacts).extracting("type").contains("feature", "specification");
        assertThat(broncoFacts.stream().filter(fact -> fact.type().equals("feature")))
                .allSatisfy(fact -> {
                    assertThat(fact.sourceUrl()).isNotBlank();
                    assertThat(fact.observedAt()).isNotNull();
                });
    }
}
