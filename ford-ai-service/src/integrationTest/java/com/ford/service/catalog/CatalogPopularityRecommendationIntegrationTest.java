package com.ford.service.catalog;

import com.ford.service.catalog.adapter.out.ClasspathModelPopularityIndex;
import com.ford.service.catalog.api.CatalogPort;
import com.ford.service.catalog.api.VehicleEvidence;
import com.ford.service.catalog.api.VehicleNameIndexEntry;
import com.ford.service.catalog.api.VehicleSearchQuery;
import com.ford.service.catalog.application.CatalogPopularityRecommendationService;
import com.ford.service.database.FordPostgresContainer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Deterministic recommendation acceptance checks over disposable PostgreSQL catalog rows only. */
@Testcontainers
class CatalogPopularityRecommendationIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new FordPostgresContainer();
    private static JdbcClient jdbc;
    private static CatalogPopularityRecommendationService service;
    private static final List<VehicleNameIndexEntry> FIXTURE_NAME_INDEX = List.of(
            new VehicleNameIndexEntry("Territory", 2027, "Titanium"),
            new VehicleNameIndexEntry("Ranger", 2027, "XLT"),
            new VehicleNameIndexEntry("Ranger", 2027, "Wildtrak"),
            new VehicleNameIndexEntry("Ranger Crew Cab", 2027, "XLT"),
            new VehicleNameIndexEntry("Transit", 2027, "Van"),
            new VehicleNameIndexEntry("F-150", 2027, "Lariat"),
            new VehicleNameIndexEntry("Escape", 2027, "Titanium"),
            new VehicleNameIndexEntry("Unmapped catalog model", 2027, "Base"));
    private static final String FIXTURE = "insert into recommendation_catalog values ('Territory',2027,700000,'SUV','SUV','gasoline'),('Ranger',2027,850000,'Camiones','doble cabina','diesel'),('Ranger',2027,950000,'Camiones','doble cabina','diesel'),('Ranger Crew Cab',2027,900000,'Camiones','doble cabina','diesel'),('Transit',2027,1100000,'Van','van','diesel'),('F-150',2027,1200000,'Camiones','doble cabina','gasoline'),('Escape',2027,650000,'SUV','SUV','gasoline')";

    @BeforeAll
    static void setUp() {
        assertThat(POSTGRES.getDatabaseName()).isNotEqualToIgnoringCase("ford_service");
        var dataSource = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        jdbc = JdbcClient.create(dataSource);
        jdbc.sql("create table recommendation_catalog (model varchar(100), model_year integer, price numeric, category varchar(80), body_type varchar(80), fuel_type varchar(80))").update();
        jdbc.sql("insert into recommendation_catalog values ('Territory',2027,700000,'SUV','SUV','gasoline'),('Ranger',2027,850000,'Camiones','doble cabina','diesel'),('Ranger',2027,950000,'Camiones','doble cabina','diesel'),('Ranger Crew Cab',2027,900000,'Camiones','doble cabina','diesel'),('Transit',2027,1100000,'Van','van','diesel'),('F-150',2027,1200000,'Camiones','doble cabina','gasoline'),('Escape',2027,650000,'SUV','SUV','gasoline')").update();
        CatalogPort postgresCatalog = new CatalogPort() {
            @Override public List<VehicleEvidence> search(VehicleSearchQuery query) {
            var sql = new StringBuilder("select model, model_year, price from recommendation_catalog where 1=1");
            var params = new java.util.LinkedHashMap<String, Object>();
            if (query.model() != null) { sql.append(" and lower(model)=lower(:model)"); params.put("model", query.model()); }
            if (query.modelYear() != null) { sql.append(" and model_year=:year"); params.put("year", query.modelYear()); }
            if (query.minPrice() != null) { sql.append(" and price>=:minPrice"); params.put("minPrice", query.minPrice()); }
            if (query.maxPrice() != null) { sql.append(" and price<=:maxPrice"); params.put("maxPrice", query.maxPrice()); }
            if (query.category() != null) { sql.append(" and category=:category"); params.put("category", query.category()); }
            if (query.bodyType() != null) { sql.append(" and body_type=:body"); params.put("body", query.bodyType()); }
            if (query.fuelType() != null) { sql.append(" and fuel_type=:fuel"); params.put("fuel", query.fuelType()); }
            var statement = jdbc.sql(sql.toString()).params(params);
            return statement.query((rs, row) -> {
                var model = rs.getString("model");
                var price = rs.getBigDecimal("price");
                var trim = model.equals("Ranger") ? (price.compareTo(new BigDecimal("900000")) > 0 ? "Wildtrak" : "XLT")
                        : FIXTURE_NAME_INDEX.stream().filter(entry -> entry.model().equals(model)).findFirst()
                                .map(VehicleNameIndexEntry::trim).orElse("catalog trim");
                return new VehicleEvidence(model, rs.getInt("model_year"), trim,
                        price, "MXN", "catalog", "catalog", null, List.of());
            }).list();
            }
            @Override public java.util.Optional<VehicleEvidence> find(com.ford.service.catalog.api.VehicleLookup lookup) { return java.util.Optional.empty(); }
            @Override public List<VehicleNameIndexEntry> nameIndexEntries() { return FIXTURE_NAME_INDEX; }
            @Override public com.ford.service.catalog.api.PromotionPage promotions(com.ford.service.catalog.api.PromotionQuery query) { throw new UnsupportedOperationException(); }
            @Override public List<com.ford.service.catalog.api.OfferEvidence> offers(com.ford.service.catalog.api.OfferQuery query) { throw new UnsupportedOperationException(); }
            @Override public com.ford.service.catalog.api.CatalogPageLinkPage pageLinks(String url, int offset, int limit, boolean assets) { throw new UnsupportedOperationException(); }
            @Override public java.util.Optional<com.ford.service.catalog.api.DocumentProvenance> provenance(String id) { return java.util.Optional.empty(); }
            @Override public com.ford.service.catalog.api.CatalogStatus status() { throw new UnsupportedOperationException(); }
            @Override public List<com.ford.service.catalog.api.VariantComparisonEvidence> variantComparisons(String model, Integer year, String trim, int offset, int limit) { return List.of(); }
        };
        service = new CatalogPopularityRecommendationService(postgresCatalog, new ClasspathModelPopularityIndex());
    }

    @BeforeEach
    void seed() { jdbc.sql("delete from recommendation_catalog").update(); jdbc.sql(FIXTURE).update(); }

    @Test
    void deterministicGoldCasesPreservePopularityAndHardConstraints() {
        var open = service.recommend(query(null, null, null, null, null, null, null), false, 5);
        assertThat(open.results()).extracting(VehicleEvidence::model).containsExactly("Territory", "Ranger", "Ranger", "Ranger Crew Cab", "Transit");
        assertThat(open.mostSoldEligibleModel().sales().units()).isEqualTo(41241L);
        assertThat(open.periodDisclosure()).contains("2024-01-01", "2026-09-30", "INEGI");
        assertThat(open.periodDisclosure()).contains("Ventas del modelo (no de la versión)");
        assertThat(open.results()).allSatisfy(v -> assertThat(v.modelPopularity().modelFamily()).doesNotContain("trim", "XL", "XLT"));

        var pickup = service.recommend(query(null, null, null, "Camiones", "doble cabina", "diesel", null), false, 5);
        assertThat(pickup.results()).extracting(VehicleEvidence::model).containsExactly("Ranger", "Ranger", "Ranger Crew Cab");
        assertThat(pickup.results()).allSatisfy(v -> assertThat(v.modelPopularity()).isNotNull());
        var budget = service.recommend(query(null, null, new BigDecimal("800000"), null, null, null, null), false, 5);
        assertThat(budget.results()).extracting(VehicleEvidence::model).containsExactly("Ranger", "Ranger", "Ranger Crew Cab", "Transit", "F-150");
        assertThat(budget.results()).allSatisfy(v -> assertThat(v.price()).isGreaterThanOrEqualTo(new BigDecimal("800000")));

        var mostSold = service.recommend(query("más vendido", null, null, null, null, null, null), false, 5);
        assertThat(mostSold.mostSoldEligibleModel().eligibleVehicle().model()).isEqualTo("Territory");
        var winningModel = mostSold.mostSoldEligibleModel().eligibleVehicle().model();
        assertThat(winningModel).isEqualTo("Territory");
        assertThat(mostSold.results()).filteredOn(vehicle -> vehicle.model().equals(winningModel))
                .singleElement().extracting(vehicle -> vehicle.modelPopularity().modelFamily()).isEqualTo("Territory");
        assertThat(mostSold.results()).filteredOn(vehicle -> vehicle.model().equals("Ranger")).hasSize(2);
        assertThat(mostSold.results().stream().filter(vehicle -> vehicle.model().equals("Ranger"))
                .map(VehicleEvidence::modelPopularity).distinct()).hasSize(1);
        assertThat(mostSold.mostSoldEligibleModel().sales().units()).isEqualTo(41241L);
        assertThat(mostSold.mostSoldEligibleModel().sales().periodStart()).hasToString("2024-01-01");
        assertThat(mostSold.mostSoldEligibleModel().sales().periodEnd()).hasToString("2026-09-30");
        assertThat(mostSold.mostSoldEligibleModel().sales().status()).isEqualTo("REPORTED");
        assertThat(mostSold.periodDisclosure()).contains("Ventas del modelo (no de la versión)", "INEGI");
        var trend = service.recommend(query("tendencia actual", null, null, null, null, null, null), true, 5);
        assertThat(trend.results()).extracting(VehicleEvidence::model).containsExactly("Territory", "Ranger", "Ranger", "Ranger Crew Cab", "F-150");
        assertThat(trend.mostSoldEligibleModel().sales().units()).isEqualTo(13719L);
        assertThat(trend.mostSoldEligibleModel().sales().periodStart()).hasToString("2026-01-01");
        var exact = service.recommend(query(null, "Ranger Crew Cab", null, null, null, null, 2027), false, 5);
        assertThat(exact.results()).extracting(VehicleEvidence::model).containsExactly("Ranger Crew Cab");
        var unknown = service.recommend(query(null, "Unmapped family", null, null, null, null, null), false, 5);
        assertThat(unknown.results()).isEmpty(); assertThat(unknown.mostSoldEligibleModel()).isNull();
        assertThat(POSTGRES.getJdbcUrl()).contains("/" + POSTGRES.getDatabaseName());
    }

    @Test
    void coverageReportListsCatalogNamesWithoutPopularityMapping() {
        var index = new ClasspathModelPopularityIndex();
        var names = postgresNameIndexEntries();
        var unmapped = names.stream().filter(entry -> index.findForCatalogModel(entry.model()).isEmpty()).toList();
        assertThat(unmapped).extracting(VehicleNameIndexEntry::model).containsExactly("Unmapped catalog model");
        names.stream().filter(entry -> !entry.model().equals("Unmapped catalog model")).forEach(entry -> {
            var vehicle = service.recommend(query(null, entry.model(), null, null, null, null, entry.year()), false, 5)
                    .results().stream().filter(result -> result.model().equals(entry.model()) && result.trim().equals(entry.trim())).findFirst().orElseThrow();
            assertThat(vehicle.modelPopularity()).isNotNull();
            var expectedPopularity = index.findForCatalogModel(entry.model()).orElseThrow();
            assertThat(vehicle.modelPopularity().modelFamily()).isEqualTo(expectedPopularity.modelFamily());
            assertThat(vehicle.modelPopularity().cumulativeUnits()).isEqualTo(expectedPopularity.cumulativeUnits());
            assertThat(vehicle.modelPopularity().geography()).isEqualTo(expectedPopularity.geography());
            assertThat(vehicle.modelPopularity().sourceLabel()).isEqualTo(expectedPopularity.sourceLabel());
            assertThat(vehicle.modelPopularity().sourceUrl()).isEqualTo(expectedPopularity.sourceUrl());
            assertThat(vehicle.modelPopularity().toString()).doesNotContain("trimUnits", "trimRank");
        });
        assertThat(service.recommend(query(null, "Unmapped catalog model", null, null, null, null, 2027), false, 5).results()).isEmpty();
        System.out.println("Disposable PostgreSQL fixture name-index popularity coverage: " + (names.size() - unmapped.size()) + "/" + names.size()
                + "; unmapped catalog model names: " + unmapped.stream().map(VehicleNameIndexEntry::model).collect(java.util.stream.Collectors.joining(", ")));
    }

    private static List<VehicleNameIndexEntry> postgresNameIndexEntries() {
        return FIXTURE_NAME_INDEX;
    }

    private static VehicleSearchQuery query(String text, String model, BigDecimal min, String category, String body, String fuel, Integer year) {
        return new VehicleSearchQuery(text, model, year, min, null, category, body, fuel, 5);
    }
}
