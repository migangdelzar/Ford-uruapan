package com.ford.service.ai;

import com.ford.service.ai.adapter.in.tool.CatalogTools;
import com.ford.service.catalog.api.CatalogPort;
import com.ford.service.catalog.api.PromotionEvidence;
import com.ford.service.catalog.api.PromotionQuery;
import com.ford.service.catalog.api.PromotionPage;
import com.ford.service.catalog.api.OfferEvidence;
import com.ford.service.catalog.api.OfferQuery;
import com.ford.service.catalog.api.CatalogPageLinkPage;
import com.ford.service.catalog.api.DocumentProvenance;
import com.ford.service.catalog.api.CatalogStatus;
import com.ford.service.catalog.api.VariantComparisonEvidence;
import com.ford.service.catalog.api.VehicleEvidence;
import com.ford.service.catalog.api.VehicleImage;
import com.ford.service.catalog.api.VehicleLookup;
import com.ford.service.catalog.api.VehicleSearchQuery;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.Test;
import org.springframework.ai.support.ToolCallbacks;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class CatalogToolsTest {

    @Test
    void exposesOnlyReadOnlyCatalogToolNamesToSpringAi() {
        var callbacks = ToolCallbacks.from(new CatalogTools(new FakeCatalog()));

        assertThat(List.of(callbacks).stream()
                .map(callback -> callback.getToolDefinition().name()))
                .containsExactlyInAnyOrder("search_vehicles", "get_vehicle", "list_promotions",
                        "search_offers", "search_vehicle_and_offers", "get_page_links",
                        "get_vehicle_provenance", "get_catalog_status", "compare_vehicles",
                        "compare_trims", "compare_model_years", "get_variant_comparisons");
    }

    @Test
    void searchPreservesSourceAndObservationTimeAndDoesNotInventPrice() {
        var catalog = new FakeCatalog();
        var evidence = new VehicleEvidence("Ranger", 2027, null, null, "MXN",
                "https://www.ford.mx/vehiculos/ranger/", null,
                Instant.parse("2026-10-16T10:00:00Z"), List.of());
        catalog.searchResult = List.of(evidence);
        var tools = new CatalogTools(catalog);

        var result = tools.searchVehicles("Ranger", "Ranger", 2027, null, null,
                null, null, null, 5);

        assertThat(catalog.lastSearch).isEqualTo(new VehicleSearchQuery(
                "Ranger", "Ranger", 2027, null, null, null, null, null, 5));
        assertThat(result).containsExactly(evidence);
        assertThat(result.getFirst().price()).isNull();
        assertThat(result.getFirst().modelPageUrl()).isEqualTo("https://www.ford.mx/vehiculos/ranger/");
        assertThat(result.getFirst().observedAt()).isEqualTo(Instant.parse("2026-10-16T10:00:00Z"));
    }

    @Test
    void exactVehicleLookupDoesNotSubstituteAnotherTrim() {
        var catalog = new FakeCatalog();
        var tools = new CatalogTools(catalog);

        var result = tools.getVehicle("Ranger", 2027, "Raptor");

        assertThat(catalog.lastLookup).isEqualTo(new VehicleLookup("Ranger", 2027, "Raptor"));
        assertThat(result).isInstanceOf(CatalogTools.UnavailableModelYear.class);
        assertThat(((CatalogTools.UnavailableModelYear) result).options()).isEmpty();
    }

    @Test
    void getVehicleReturnsYearOptionsWhenYearIsOmitted() {
        var catalog = new FakeCatalog();
        catalog.searchResult = List.of(
                new VehicleEvidence("Ranger", 2027, null, null, "MXN", "model-27", null, null, List.of()),
                new VehicleEvidence("Ranger", 2026, null, null, "MXN", "model-26", null, null, List.of()));
        catalog.vehicles.put("2027", catalog.searchResult.getFirst());
        catalog.vehicles.put("2026", catalog.searchResult.get(1));

        var result = new CatalogTools(catalog).getVehicle("Ranger", null, null);

        assertThat(result).isInstanceOf(CatalogTools.ModelYearOptions.class);
        var options = (CatalogTools.ModelYearOptions) result;
        assertThat(options.model()).isEqualTo("Ranger");
        assertThat(options.status()).isEqualTo("available_model_year_options");
        assertThat(options.options()).extracting(VehicleEvidence::year).containsExactly(2027, 2026);
    }

    @Test
    void getVehicleReportsMissingYearAndKeepsAvailableExactOptions() {
        var catalog = new FakeCatalog();
        catalog.searchResult = List.of(
                new VehicleEvidence("Ranger", 2027, null, null, "MXN", "model-27", null, null, List.of()));
        catalog.vehicles.put("2027", catalog.searchResult.getFirst());

        var result = new CatalogTools(catalog).getVehicle("Ranger", 2025, null);

        assertThat(result).isInstanceOf(CatalogTools.UnavailableModelYear.class);
        var unavailable = (CatalogTools.UnavailableModelYear) result;
        assertThat(unavailable.status()).isEqualTo("model_year_not_found");
        assertThat(unavailable.requestedYear()).isEqualTo(2025);
        assertThat(unavailable.availableModelYears()).containsExactly(2027);
    }

    @Test
    void promotionsKeepValidityAndSourceFields() {
        var catalog = new FakeCatalog();
        var benefits = JsonNodeFactory.instance.arrayNode().add("Bono de $25,000 MXN");
        var promotion = new PromotionEvidence("eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee", "Ranger", 2027, "Promoción vigente", "Bono publicado",
                new BigDecimal("25000"), null, null, "MXN", "Sujeto a términos",
                LocalDate.parse("2026-10-01"), LocalDate.parse("2026-10-31"),
                "active", "https://www.ford.mx/promociones/ranger/",
                Instant.parse("2026-10-16T10:00:00Z"), "https://www.ford.mx/vehiculos/ranger/", benefits, null);
        catalog.promotions = new PromotionPage(2, 0, 3, List.of(promotion));
        var tools = new CatalogTools(catalog);

        var result = tools.listPromotions("Ranger", 2027, LocalDate.parse("2026-10-17"), true,
                0, 3, "summary");

        assertThat(catalog.lastPromotionQuery).isEqualTo(new PromotionQuery(
                "Ranger", 2027, LocalDate.parse("2026-10-17"), true, 3, 0));
        assertThat(result.totalCount()).isEqualTo(2);
        assertThat(result.offset()).isZero();
        assertThat(result.hasMore()).isTrue();
        assertThat(result.results()).singleElement().satisfies(compact -> {
            assertThat(compact.id()).isEqualTo("eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee");
            assertThat(compact.validityStatus()).isEqualTo("active");
            assertThat(compact.discountAmount()).isEqualByComparingTo("25000");
            assertThat(compact.conditions()).isEqualTo("Sujeto a términos");
            assertThat(compact.sourceUrl()).isEqualTo("https://www.ford.mx/promociones/ranger/");
            assertThat(compact.modelUrl()).isEqualTo("https://www.ford.mx/vehiculos/ranger/");
            assertThat(compact.benefits()).isEqualTo(benefits);
        });
    }

    @Test
    void promotionsOmitLongConditionsInSummaryAndKeepThemInFullDetail() {
        var catalog = new FakeCatalog();
        var longConditions = "x".repeat(281);
        catalog.promotions = new PromotionPage(1, 0, 3, List.of(new PromotionEvidence(
                "ffffffff-ffff-ffff-ffff-ffffffffffff", "Ranger", 2027, "Bono", "Descripción larga", new BigDecimal("1000"), null, null,
                "MXN", longConditions, null, null, "unknown", "https://ford.test/promo",
                Instant.parse("2026-10-16T10:00:00Z"), "https://ford.test/ranger")));
        var tools = new CatalogTools(catalog);

        var summary = tools.listPromotions(null, null, null, false, 0, 3, "summary");
        var full = tools.listPromotions(null, null, null, false, 0, 3, "full");

        assertThat(summary.results().getFirst().conditions()).isNull();
        assertThat(summary.results().getFirst().conditionsOmitted()).isTrue();
        assertThat(summary.results().getFirst().description()).isNull();
        assertThat(full.results().getFirst().conditions()).isEqualTo(longConditions);
        assertThat(full.results().getFirst().conditionsOmitted()).isNull();
        assertThat(full.results().getFirst().description()).isEqualTo("Descripción larga");
    }

    @Test
    void promotionsRejectUnknownDetailMode() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new CatalogTools(new FakeCatalog())
                .listPromotions(null, null, null, null, 0, 3, "compact"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("detail must be summary or full");
    }

    @Test
    void financingSearchUsesExactVehicleFiltersAndReturnsPublishedTermsOnly() {
        var catalog = new FakeCatalog();
        var benefits = JsonNodeFactory.instance.arrayNode().add("Bono de $130,000 MXN");
        var termConflict = JsonNodeFactory.instance.objectNode().put("labelMonths", 24).put("structuredMonths", 72);
        var offer = new OfferEvidence("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa", "Ranger", 2027, "Plan publicado", "financing", "Ford Credit",
                null, new BigDecimal("12000"), new BigDecimal("180000"), new BigDecimal("20"),
                60, null, null, null, "MXN",
                LocalDate.parse("2026-10-01"), LocalDate.parse("2026-10-31"), "active", true,
                "Sujeto a aprobación", "https://www.ford.mx/promociones/ranger/",
                "https://www.ford.mx/vehiculos/ranger/",
                Instant.parse("2026-10-16T10:00:00Z"), benefits, termConflict,
                new BigDecimal("16"), new BigDecimal("3.5"), new BigDecimal("80"), new BigDecimal("2500"));
        catalog.offers = List.of(offer);

        var result = new CatalogTools(catalog).searchOffers(null, "Ranger", 2027, "financing", null,
                null, null, null, null, 60, LocalDate.parse("2026-10-17"), 2);

        assertThat(catalog.lastOfferQuery).isEqualTo(new OfferQuery("Ranger", 2027, "financing", null,
                null, null, null, null, 60, true, LocalDate.parse("2026-10-17"), 2));
        assertThat(result.query()).isEqualTo("Ranger 2027 financing");
        assertThat(result.resultCount()).isEqualTo(1);
        assertThat(result.totalResultCount()).isEqualTo(1);
        assertThat(result.requiredDisclosures()).isEmpty();
        assertThat(result.results()).hasSize(1);
        assertThat(result.results().getFirst().monthlyPayment()).isEqualByComparingTo("12000");
        assertThat(result.results().getFirst().monthlyPaymentDisplay()).isEqualTo("MXN $12,000");
        assertThat(result.results().getFirst().downPaymentDisplay()).isEqualTo("MXN $180,000");
        assertThat(result.results().getFirst().modelName()).isEqualTo("Ranger");
        assertThat(result.results().getFirst().modelYear()).isEqualTo(2027);
        assertThat(result.results().getFirst().offerId()).isEqualTo("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
        assertThat(result.results().getFirst().modelUrl()).isEqualTo("https://www.ford.mx/vehiculos/ranger/");
        assertThat(result.results().getFirst().benefits()).isEqualTo(benefits);
        assertThat(result.results().getFirst().termConflict()).isEqualTo(termConflict);
        assertThat(result.results().getFirst().taxRatePercent()).isEqualByComparingTo("16");
        assertThat(result.results().getFirst().openingCommissionRatePercent()).isEqualByComparingTo("3.5");
        assertThat(result.results().getFirst().maximumDownPaymentPercent()).isEqualByComparingTo("80");
        assertThat(result.results().getFirst().lifeInsurancePrice()).isEqualByComparingTo("2500");
        assertThat(result.results().getFirst().buyerSummary())
                .isEqualTo("Plan publicado. Mensualidad: MXN $12,000.");
        assertThat(result.results().getFirst().sourceUrl()).isEqualTo("https://www.ford.mx/promociones/ranger/");
        assertThat(result.results().getFirst().modelUrl()).isEqualTo("https://www.ford.mx/vehiculos/ranger/");
    }

    @Test
    void financingSearchDisclosesUnknownValidityAndUsesProvidedQuery() {
        var catalog = new FakeCatalog();
        var offer = new OfferEvidence("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb", "Ranger", 2027, "Plan vigente", "financing", "Ford Credit",
                null, null, null, null, null, null, null, null, "MXN", null, null, "unknown", true,
                null, "https://ford.test/plan", null, Instant.parse("2026-10-16T10:00:00Z"));
        catalog.offers = List.of(offer);

        var result = new CatalogTools(catalog).searchOffers("planes para Ranger", "Ranger", 2027,
                "financing", null, null, null, null, null, null, LocalDate.parse("2026-10-17"), 2);

        assertThat(result.query()).isEqualTo("planes para Ranger");
        assertThat(result.requiredDisclosures()).containsExactly("Plan vigente: La vigencia no está confirmada.");
        assertThat(result.results().getFirst().validityDisclosure()).isEqualTo("La vigencia no está confirmada.");
        assertThat(result.results().getFirst().buyerSummary())
                .isEqualTo("Plan vigente. La vigencia no está confirmada.");
    }

    @Test
    void combinedSearchSelectsOneExactVehicleAndAlignsFinancingAndSummary() {
        var catalog = new FakeCatalog();
        var vehicle = new VehicleEvidence("Ranger", 2027, null, new BigDecimal("899000"), "MXN",
                "https://ford.test/ranger", "https://ford.test/ranger-price",
                Instant.parse("2026-10-16T10:00:00Z"), List.of());
        var offer = new OfferEvidence("cccccccc-cccc-cccc-cccc-cccccccccccc", "Ranger", 2027, "Plan Ranger", "financing", "Ford Credit",
                new BigDecimal("899000"), new BigDecimal("12000"), new BigDecimal("180000"),
                new BigDecimal("20"), 60, null, new BigDecimal("12.5"), new BigDecimal("15.8"),
                "MXN", LocalDate.parse("2026-10-01"), LocalDate.parse("2026-10-31"), "active", true,
                "Sujeto a aprobación", "https://ford.test/finance", "https://ford.test/ranger",
                Instant.parse("2026-10-16T10:00:00Z"));
        catalog.searchResult = List.of(vehicle);
        catalog.offers = List.of(offer);

        var result = new CatalogTools(catalog).searchVehicleAndOffers(
                null, "Ranger", null, 2027, null, 60);

        assertThat(result.query()).isEqualTo("Ranger 2027");
        assertThat(catalog.lastSearch).isEqualTo(new VehicleSearchQuery(
                "Ranger 2027", "Ranger", 2027, null, null, null, null, null, 1));
        assertThat(result.vehicles().results()).containsExactly(vehicle);
        assertThat(result.vehicles().summary()).isEqualTo("Ranger 2027: MXN $899,000");
        assertThat(result.offers().query()).isEqualTo("Ranger 2027 financing");
        assertThat(catalog.lastOfferQuery).isEqualTo(new OfferQuery("Ranger", 2027, "financing", null,
                null, null, null, null, 60, true, LocalDate.now(java.time.ZoneOffset.UTC), 1));
        assertThat(result.answerSummary()).contains("Ranger 2027: MXN $899,000.", "Plan Ranger.");
    }

    @Test
    void combinedSearchStillChecksFinancingWhenNoExactVehicleMatches() {
        var catalog = new FakeCatalog();
        catalog.searchResult = List.of(new VehicleEvidence("Bronco", 2027, null, null, "MXN",
                "https://ford.test/bronco", null, null, List.of()));

        var result = new CatalogTools(catalog).searchVehicleAndOffers(
                "Ranger", "Ranger", 2027, null, null, null);

        assertThat(result.vehicles().results()).isEmpty();
        assertThat(result.vehicles().summary()).isNull();
        assertThat(catalog.lastOfferQuery.model()).isEqualTo("Ranger");
        assertThat(catalog.lastOfferQuery.modelYear()).isEqualTo(2027);
        assertThat(result.answerSummary()).contains("No se encontró el vehículo o variante exactos.");
    }

    @Test
    void combinedSearchDoesNotReturnUnrelatedFinancingWithoutAModelSelector() {
        var catalog = new FakeCatalog();
        catalog.offers = List.of(new OfferEvidence("dddddddd-dddd-dddd-dddd-dddddddddddd", "Explorer", 2027, "Explorer financing", "financing",
                "Ford Credit", null, new BigDecimal("18000"), null, null, 60, null, null, null, "MXN",
                null, null, "unknown", true, null, "https://ford.test/explorer-financing", null,
                Instant.parse("2026-10-16T10:00:00Z")));

        var result = new CatalogTools(catalog).searchVehicleAndOffers(
                "Ranger familiar", null, null, null, null, null);

        assertThat(result.vehicles().results()).isEmpty();
        assertThat(result.offers().results()).isEmpty();
        assertThat(catalog.lastOfferQuery).isNull();
    }

    @Test
    void pageLinksProvenanceAndStatusAreReadOnlyLocalTools() {
        var callbacks = ToolCallbacks.from(new CatalogTools(new FakeCatalog()));
        assertThat(List.of(callbacks).stream().map(callback -> callback.getToolDefinition().name()))
                .contains("get_page_links", "get_vehicle_provenance", "get_catalog_status",
                        "compare_vehicles", "compare_trims", "compare_model_years",
                        "get_variant_comparisons");
    }

    @Test
    void pageLinksReturnNodeShapedEnvelopeAndCompactLinkFields() {
        var catalog = new FakeCatalog();
        catalog.pageLinks = new CatalogPageLinkPage("Ranger 2027 | Camioneta", 6, 5, 2, List.of(new com.ford.service.catalog.api.CatalogPageLinkEvidence(
                "https://ford.test/source", "model", "/ranger", "https://ford.test/ranger", "Ranger",
                "nofollow", true, "internal", Instant.parse("2026-10-16T10:00:00Z"))));

        var result = new CatalogTools(catalog).getPageLinks("https://ford.test/source", 5, 2, true);

        assertThat(result.sourceUrl()).isEqualTo("https://ford.test/source");
        assertThat(result.title()).isEqualTo("Ranger 2027 | Camioneta");
        assertThat(result.totalLinks()).isEqualTo(6);
        assertThat(result.offset()).isEqualTo(5);
        assertThat(result.limit()).isEqualTo(2);
        assertThat(result.links()).containsExactly(new CatalogTools.PageLinkItem(
                "https://ford.test/ranger", "Ranger", "internal", true));
    }

    @Test
    void variantComparisonsRequireAndForwardTheExactModelYear() {
        var catalog = new FakeCatalog();

        new CatalogTools(catalog).getVariantComparisons("Ranger", 2027, "XLT", 4, 3);

        assertThat(catalog.lastVariantComparisonYear).isEqualTo(2027);
        assertThat(catalog.lastVariantComparisonOffset).isEqualTo(4);
    }

    @Test
    void trimComparisonReturnsPairedSourceBackedDifferencesAndMissingTrims() {
        var catalog = new FakeCatalog();
        var left = new VehicleEvidence("Ranger", 2027, "XLT", new BigDecimal("800000"), "MXN",
                "https://ford.test/ranger", "https://ford.test/xlt-price", Instant.parse("2026-10-16T10:00:00Z"),
                List.of(new com.ford.service.catalog.api.VehicleFact("Potencia", "200 hp", "hp",
                        "https://ford.test/xlt-spec", Instant.parse("2026-10-16T10:00:00Z"))), List.of(),
                "https://ford.test/ranger/ficha-tecnica");
        var right = new VehicleEvidence("Ranger", 2027, "Lariat", new BigDecimal("900000"), "MXN",
                "https://ford.test/ranger", "https://ford.test/lariat-price", Instant.parse("2026-10-16T10:00:00Z"),
                List.of(new com.ford.service.catalog.api.VehicleFact("Potencia", "250 hp", "hp",
                        "https://ford.test/lariat-spec", Instant.parse("2026-10-16T10:00:00Z"))), List.of(),
                "https://ford.test/ranger/ficha-tecnica");
        catalog.vehicles.put("XLT", left);
        catalog.vehicles.put("Lariat", right);
        catalog.vehicles.put("Ranger Lariat", right);

        var comparison = new CatalogTools(catalog).compareTrims("Ranger", 2027,
                List.of("XLT", "Ranger Lariat", "XLT", "Raptor"), "summary");

        assertThat(comparison.trims()).extracting(CatalogTools.TrimResult::trim)
                .containsExactly("Lariat", "XLT");
        assertThat(comparison.trims().getFirst().priceSourceUrl()).isEqualTo("https://ford.test/lariat-price");
        assertThat(comparison.modelPageUrl()).isEqualTo("https://ford.test/ranger");
        assertThat(comparison.technicalSheetUrl()).isEqualTo("https://ford.test/ranger/ficha-tecnica");
        assertThat(comparison.missingTrims()).containsExactly("Raptor");
        assertThat(comparison.comparisons()).singleElement().satisfies(pair -> {
            assertThat(pair.priceDifferenceMxn()).isEqualByComparingTo("-100000");
            assertThat(pair.status()).isEqualTo("source_backed_differences_found");
            assertThat(pair.differences()).singleElement().satisfies(difference -> {
                assertThat(difference.normalizedName()).isEqualTo("potencia");
                assertThat(difference.left().sourceUrl()).isEqualTo("https://ford.test/lariat-spec");
                assertThat(difference.right().sourceUrl()).isEqualTo("https://ford.test/xlt-spec");
            });
        });
    }

    @Test
    void trimComparisonUsesNodeSummaryLimitAndFullDetailContract() {
        var catalog = new FakeCatalog();
        var leftFacts = new java.util.ArrayList<com.ford.service.catalog.api.VehicleFact>();
        var rightFacts = new java.util.ArrayList<com.ford.service.catalog.api.VehicleFact>();
        for (int index = 1; index <= 6; index++) {
            leftFacts.add(new com.ford.service.catalog.api.VehicleFact("Fact " + index, "left " + index,
                    null, "https://ford.test/left/" + index, Instant.parse("2026-10-16T10:00:00Z")));
            rightFacts.add(new com.ford.service.catalog.api.VehicleFact("Fact " + index, "right " + index,
                    null, "https://ford.test/right/" + index, Instant.parse("2026-10-16T10:00:00Z")));
        }
        catalog.vehicles.put("XL", new VehicleEvidence("Ranger", 2027, "XL", null, "MXN",
                "https://ford.test/ranger", null, null, leftFacts, List.of(), null,
                "https://ford.test/compare/xl"));
        catalog.vehicles.put("XLT", new VehicleEvidence("Ranger", 2027, "XLT", null, "MXN",
                "https://ford.test/ranger", null, null, rightFacts, List.of(), null,
                "https://ford.test/compare/xlt"));

        var tools = new CatalogTools(catalog);
        var summary = tools.compareTrims("Ranger", 2027, List.of("XL", "XLT"), "summary");
        var full = tools.compareTrims("Ranger", 2027, List.of("XL", "XLT"), "full");

        assertThat(summary.comparisons()).singleElement().satisfies(pair -> {
            assertThat(pair.status()).isEqualTo("source_backed_differences_found");
            assertThat(pair.differences()).hasSize(5);
            assertThat(pair.differencesOmitted()).isEqualTo(1);
        });
        assertThat(full.comparisons()).singleElement().satisfies(pair -> {
            assertThat(pair.differences()).hasSize(6);
            assertThat(pair.differencesOmitted()).isNull();
        });
        assertThat(new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules()
                .valueToTree(summary).toString())
                .contains("\"trims\"", "\"comparisons\"", "\"differencesOmitted\":1",
                        "\"comparisonUrl\":\"https://ford.test/compare/xl\"")
                .doesNotContain("foundTrims", "trimPairs");
    }

    @Test
    void modelYearComparisonPairsOnlySharedChangedModelFacts() {
        var catalog = new FakeCatalog();
        var older = new VehicleEvidence("Ranger", 2026, null, new BigDecimal("700000"), "MXN",
                "https://ford.test/ranger-26", "https://ford.test/ranger-26-price", Instant.parse("2026-10-16T10:00:00Z"),
                List.of(new com.ford.service.catalog.api.VehicleFact("Garantía", "3 años", null,
                        "https://ford.test/spec-26", Instant.parse("2026-10-16T10:00:00Z"))));
        var newer = new VehicleEvidence("Ranger", 2027, null, new BigDecimal("750000"), "MXN",
                "https://ford.test/ranger-27", "https://ford.test/ranger-27-price", Instant.parse("2026-10-16T10:00:00Z"),
                List.of(new com.ford.service.catalog.api.VehicleFact("Garantía", "5 años", null,
                        "https://ford.test/spec-27", Instant.parse("2026-10-16T10:00:00Z"))));
        catalog.vehicles.put("2026", older);
        catalog.vehicles.put("2027", newer);

        var comparison = new CatalogTools(catalog).compareModelYears("Ranger", List.of(2026, 2027, 2026));

        assertThat(comparison.years()).extracting(CatalogTools.ModelYearSnapshot::vehicle)
                .containsExactly(older, newer);
        assertThat(comparison.yearPairs()).singleElement().satisfies(pair -> {
            assertThat(pair.priceDifferenceMxn()).isEqualByComparingTo("50000");
            assertThat(pair.sharedFactDifferences()).singleElement().satisfies(difference -> {
                assertThat(difference.left().value()).isEqualTo("3 años");
                assertThat(difference.right().value()).isEqualTo("5 años");
            });
        });
    }

    @Test
    void crossModelComparisonPreservesImageLinksAndProvenance() {
        var catalog = new FakeCatalog();
        var image = new VehicleImage("https://ford.test/ranger.png", "Ranger exterior", "exterior",
                1200, 800, "https://ford.test/ranger", Instant.parse("2026-10-16T10:00:00Z"));
        catalog.vehicle = Optional.of(new VehicleEvidence("Ranger", 2027, null, null, "MXN",
                "https://ford.test/ranger", null, Instant.parse("2026-10-16T10:00:00Z"), List.of(), List.of(image)));

        var result = new CatalogTools(catalog).compareVehicles(List.of(
                new CatalogTools.VehicleTarget("Ranger", 2027),
                new CatalogTools.VehicleTarget("Explorer", 2026)));

        assertThat(result.getFirst().model()).isEqualTo("Ranger");
        assertThat(result.getFirst().images()).containsExactly(image);
        assertThat(result.getFirst().images().getFirst().sourceUrl()).isEqualTo("https://ford.test/ranger");
    }

    @Test
    void crossModelComparisonFailsWhenARequestedVehicleIsMissing() {
        var catalog = new FakeCatalog();
        catalog.vehicles.put("2027", new VehicleEvidence("Ranger", 2027, null, null, "MXN",
                "https://ford.test/ranger", null, null, List.of()));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new CatalogTools(catalog)
                .compareVehicles(List.of(new CatalogTools.VehicleTarget("Ranger", 2027),
                        new CatalogTools.VehicleTarget("Explorer", 2026))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Explorer 2026 was not found");
    }

    private static final class FakeCatalog implements CatalogPort {
        private final java.util.Map<String, VehicleEvidence> vehicles = new java.util.HashMap<>();
        private VehicleSearchQuery lastSearch;
        private List<VehicleEvidence> searchResult = List.of();
        private VehicleLookup lastLookup;
        private Optional<VehicleEvidence> vehicle = Optional.empty();
        private PromotionQuery lastPromotionQuery;
        private PromotionPage promotions = new PromotionPage(0, 0, 20, List.of());
        private OfferQuery lastOfferQuery;
        private List<OfferEvidence> offers = List.of();
        private CatalogPageLinkPage pageLinks = new CatalogPageLinkPage(null, 0, 0, 20, List.of());
        private Integer lastVariantComparisonYear;
        private int lastVariantComparisonOffset;

        @Override
        public List<VehicleEvidence> search(VehicleSearchQuery query) {
            lastSearch = query;
            return searchResult;
        }

        @Override
        public Optional<VehicleEvidence> find(VehicleLookup lookup) {
            lastLookup = lookup;
            return Optional.ofNullable(vehicles.getOrDefault(lookup.trim() == null
                    ? String.valueOf(lookup.year()) : lookup.trim(), null)).or(() -> vehicle);
        }

        @Override
        public PromotionPage promotions(PromotionQuery query) {
            lastPromotionQuery = query;
            return promotions;
        }

        @Override
        public List<OfferEvidence> offers(OfferQuery query) {
            lastOfferQuery = query;
            return offers;
        }

        @Override
        public CatalogPageLinkPage pageLinks(String sourceUrl, int offset, int limit, boolean includeAssets) {
            return pageLinks;
        }

        @Override
        public Optional<DocumentProvenance> provenance(String documentId) {
            return Optional.empty();
        }

        @Override
        public CatalogStatus status() {
            return new CatalogStatus(0, 0, 0, 0, 0, 0, 0, null, null, null, null, 0, 0, 0);
        }

        @Override
        public List<VariantComparisonEvidence> variantComparisons(String model, Integer year, String trim,
                                                                     int offset, int limit) {
            lastVariantComparisonYear = year;
            lastVariantComparisonOffset = offset;
            return List.of();
        }
    }
}
