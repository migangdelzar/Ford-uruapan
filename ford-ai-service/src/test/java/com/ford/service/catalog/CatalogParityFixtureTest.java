package com.ford.service.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ford.service.ai.adapter.in.tool.CatalogTools;
import com.ford.service.catalog.adapter.out.JdbcCatalogAdapter;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class CatalogParityFixtureTest {

    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    private static JsonNode fixture;
    private static CatalogTools tools;

    @BeforeAll
    static void loadFixture() throws IOException {
        try (var stream = CatalogParityFixtureTest.class.getResourceAsStream(
                "/catalog/catalog-parity.json")) {
            assertThat(stream).as("archived Node contract fixture").isNotNull();
            fixture = JSON.readTree(stream);
        }

        var dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.h2.Driver");
        dataSource.setUrl("jdbc:h2:mem:catalog_parity_fixture;MODE=PostgreSQL;NON_KEYWORDS=YEAR,TRIM;DB_CLOSE_DELAY=-1");
        dataSource.setUsername("sa");
        var jdbc = JdbcClient.create(dataSource);
        jdbc.sql("CREATE SCHEMA ford_read").update();
        jdbc.sql("""
                CREATE TABLE ford_read.vehicle_search_v1 (
                  model VARCHAR(100), normalized_name VARCHAR(100), year INTEGER,
                  price DECIMAL(12,2), currency VARCHAR(3), category VARCHAR(100),
                  body_type VARCHAR(100), fuel_type VARCHAR(100), model_page_url VARCHAR(500),
                  price_source_url VARCHAR(500), observed_at TIMESTAMP WITH TIME ZONE)
                """).update();
        jdbc.sql("""
                CREATE TABLE ford_read.vehicle_facts_v1 (
                  model VARCHAR(100), year INTEGER, trim VARCHAR(100), fact_type VARCHAR(30),
                  fact_name VARCHAR(100), fact_value VARCHAR(500), fact_unit VARCHAR(30),
                  source_url VARCHAR(500), observed_at TIMESTAMP WITH TIME ZONE)
                """).update();
        jdbc.sql("""
                CREATE TABLE ford_read.vehicle_facts_v2 (
                  variant_id VARCHAR(36), model_year_id VARCHAR(36), model VARCHAR(100), year INTEGER,
                  trim VARCHAR(100), category VARCHAR(100), fact_type VARCHAR(30), fact_name VARCHAR(100),
                  fact_name_normalized VARCHAR(100), fact_value VARCHAR(500), value_numeric DECIMAL(14,2),
                  fact_unit VARCHAR(30), source_url VARCHAR(500), observed_at TIMESTAMP WITH TIME ZONE,
                  model_page_url VARCHAR(500))
                """).update();
        jdbc.sql("""
                CREATE TABLE ford_read.vehicle_images_v1 (
                  model VARCHAR(100), year INTEGER, trim VARCHAR(100), image_url VARCHAR(500),
                  alt_text VARCHAR(200), image_type VARCHAR(50), width INTEGER, height INTEGER,
                  source_url VARCHAR(500), observed_at TIMESTAMP WITH TIME ZONE)
                """).update();
        jdbc.sql("""
                CREATE TABLE ford_read.promotions_v2 (
                  promotion_id UUID,
                  model VARCHAR(100), year INTEGER, title VARCHAR(200), description VARCHAR(500),
                  discount_amount DECIMAL(12,2), discount_percent DECIMAL(7,2), cash_price DECIMAL(12,2),
                  currency VARCHAR(3), conditions VARCHAR(500), valid_from DATE, valid_until DATE,
                  source_url VARCHAR(500), observed_at TIMESTAMP WITH TIME ZONE,
                  model_page_url VARCHAR(500), benefits VARCHAR(1000), term_conflict VARCHAR(1000))
                """).update();
        jdbc.sql("CREATE VIEW ford_read.promotions_v3 AS SELECT * FROM ford_read.promotions_v2").update();
        jdbc.sql("""
                INSERT INTO ford_read.vehicle_search_v1 VALUES
                  ('Ranger', 'ranger', 2027, 899000, 'MXN', 'Pickups', 'Pickup', 'Diesel',
                   'https://www.ford.mx/vehiculos/ranger/',
                   'https://www.ford.mx/vehiculos/ranger/precio/', '2026-10-16T10:00:00Z'),
                  ('Ranger', 'ranger', 2026, 850000, 'MXN', 'Pickups', 'Pickup', 'Diesel',
                   'https://www.ford.mx/vehiculos/ranger/2026/',
                   'https://www.ford.mx/vehiculos/ranger/precio-2026/', '2026-10-15T10:00:00Z'),
                  ('Maverick', 'maverick', 2027, 900000, 'MXN', 'Pickups', 'Pickup', 'Hybrid',
                   NULL, NULL, NULL)
                """).update();
        jdbc.sql("""
                INSERT INTO ford_read.promotions_v2 VALUES
                  ('aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa', 'Ranger', 2027, 'Promoción de prueba', NULL, NULL, NULL, NULL, 'MXN', NULL,
                   DATE '2026-10-01', DATE '2026-10-31',
                   'https://www.ford.mx/promociones/ranger/', '2026-10-16T10:00:00Z',
                   'https://www.ford.mx/vehiculos/ranger/', NULL, NULL)
                """).update();
        jdbc.sql("""
                INSERT INTO ford_read.vehicle_facts_v1 VALUES
                  ('Ranger', 2027, NULL, 'specification', 'Potencia', '270 hp', 'hp',
                   'https://www.ford.mx/vehiculos/ranger/ficha-tecnica/', '2026-10-16T10:00:00Z')
                """).update();
        jdbc.sql("""
                INSERT INTO ford_read.vehicle_facts_v2 VALUES
                  (NULL, NULL, 'Ranger', 2027, NULL, NULL, 'specification', 'Potencia', 'potencia',
                   '270 hp', NULL, 'hp', 'https://www.ford.mx/vehiculos/ranger/ficha-tecnica/',
                   '2026-10-16T10:00:00Z', 'https://www.ford.mx/vehiculos/ranger/')
                """).update();
        tools = new CatalogTools(new JdbcCatalogAdapter(jdbc, new com.fasterxml.jackson.databind.ObjectMapper()));
    }

    @Test
    void searchMatchesArchivedNodeModelYearPriceFreshnessAndExactUrls() {
        var expected = fixture.path("archivedNodeContract").path("search");
        var result = tools.searchVehicles("Ranger", null, null,
                null, null, null, null, null, 5).getFirst();

        assertThat(result.model()).isEqualTo(expected.path("model").asText());
        assertThat(result.year()).isEqualTo(expected.path("year").asInt());
        assertThat(result.price()).isEqualByComparingTo(expected.path("price").asText());
        assertThat(result.currency()).isEqualTo(expected.path("currency").asText());
        assertUtf8BytesEqual(result.modelPageUrl(), expected.path("modelPageUrl").asText());
        assertUtf8BytesEqual(result.priceSourceUrl(), expected.path("priceSourceUrl").asText());
        assertThat(result.observedAt().toString()).isEqualTo(expected.path("observedAt").asText());
        assertThat(JSON.valueToTree(result).has("technicalSheetUrl"))
                .as("search output omits detail-only technicalSheetUrl when unavailable")
                .isFalse();
    }

    @Test
    void exactVehicleMatchesSharedArchivedFieldsAndJavaProjection() {
        var expected = fixture.path("archivedNodeContract").path("exactVehicle");
        var javaOutput = fixture.path("javaDetailProjection");
        var result = (com.ford.service.catalog.api.VehicleEvidence) tools.getVehicle("Ranger", 2027, null);

        assertThat(result.model()).isEqualTo(expected.path("model").asText());
        assertThat(result.year()).isEqualTo(expected.path("year").asInt());
        assertThat(result.trim()).isNull();
        assertThat(result.price()).isEqualByComparingTo(javaOutput.path("price").asText());
        assertUtf8BytesEqual(result.modelPageUrl(), expected.path("modelPageUrl").asText());
        assertUtf8BytesEqual(result.priceSourceUrl(), javaOutput.path("priceSourceUrl").asText());
        assertThat(javaOutput.path("priceSourceUrl").asText())
                .isNotEqualTo(expected.path("technicalSheetUrl").asText());
        assertThat(result.observedAt().toString()).isEqualTo(expected.path("observedAt").asText());
    }

    @Test
    void javaDetailExposesTechnicalSheetUrlFromModelFactSource() {
        var expectedUrl = fixture.path("archivedNodeContract").path("exactVehicle")
                .path("technicalSheetUrl").asText();
        var javaProjectionUrl = fixture.path("javaDetailProjection").path("technicalSheetUrl").asText();
        var result = (com.ford.service.catalog.api.VehicleEvidence) tools.getVehicle("Ranger", 2027, null);
        var javaOutput = JSON.valueToTree(result);

        assertUtf8BytesEqual(javaProjectionUrl, expectedUrl);
        assertUtf8BytesEqual(javaOutput.path("technicalSheetUrl").asText(), expectedUrl);
    }

    @Test
    void promotionsMatchArchivedNodeValidityStateAndExactSourceUrl() {
        var expected = fixture.path("archivedNodeContract").path("promotion");
        var result = tools.listPromotions("Ranger", 2027,
                LocalDate.parse("2026-10-17"), false, 0, 3, "full").results().getFirst();

        assertThat(result.model()).isEqualTo(expected.path("model").asText());
        assertThat(result.year()).isEqualTo(expected.path("year").asInt());
        assertThat(result.validFrom().toString()).isEqualTo(expected.path("validFrom").asText());
        assertThat(result.validUntil().toString()).isEqualTo(expected.path("validUntil").asText());
        assertThat(result.validityStatus()).isEqualTo(expected.path("validityStatus").asText());
        assertUtf8BytesEqual(result.sourceUrl(), expected.path("sourceUrl").asText());
        assertUtf8BytesEqual(result.modelUrl(), "https://www.ford.mx/vehiculos/ranger/");
    }

    @Test
    void javaOnlyMissingEvidenceDoesNotInventSourceOrObservation() {
        var missing = fixture.path("javaOnlyMissingEvidence");
        var result = (com.ford.service.catalog.api.VehicleEvidence) tools.getVehicle(
                missing.path("model").asText(), missing.path("year").asInt(), null);

        assertThat(result.model()).isEqualTo(missing.path("model").asText());
        assertThat(result.year()).isEqualTo(missing.path("year").asInt());
        assertThat(result.price()).isNull();
        assertThat(result.modelPageUrl()).isNull();
        assertThat(result.priceSourceUrl()).isNull();
        assertThat(result.observedAt()).isNull();
        assertThat(result.facts()).isEmpty();
    }

    @Test
    void getVehicleReturnsEveryStoredModelYearAndReportsUnavailableRequestedYear() {
        var options = (CatalogTools.ModelYearOptions) tools.getVehicle("Ranger", null, null);
        assertThat(options.status()).isEqualTo("available_model_year_options");
        assertThat(options.options()).extracting(com.ford.service.catalog.api.VehicleEvidence::year)
                .containsExactly(2027, 2026);

        var missing = (CatalogTools.UnavailableModelYear) tools.getVehicle("Ranger", 2025, null);
        assertThat(missing.status()).isEqualTo("model_year_not_found");
        assertThat(missing.requestedYear()).isEqualTo(2025);
        assertThat(missing.availableModelYears()).containsExactly(2027, 2026);
    }

    private static void assertUtf8BytesEqual(String actual, String expected) {
        assertThat(actual.getBytes(StandardCharsets.UTF_8))
                .containsExactly(expected.getBytes(StandardCharsets.UTF_8));
    }
}
