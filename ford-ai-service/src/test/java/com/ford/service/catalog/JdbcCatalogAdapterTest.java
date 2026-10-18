package com.ford.service.catalog;

import com.ford.service.catalog.adapter.out.JdbcCatalogAdapter;
import com.ford.service.catalog.api.PromotionQuery;
import com.ford.service.catalog.api.OfferQuery;
import com.ford.service.catalog.api.VehicleLookup;
import com.ford.service.catalog.api.VehicleSearchQuery;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class JdbcCatalogAdapterTest {

    private JdbcCatalogAdapter catalog;
    private JdbcClient jdbc;

    @BeforeEach
    void setUp() {
        var dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.h2.Driver");
        dataSource.setUrl("jdbc:h2:mem:ford_catalog;MODE=PostgreSQL;NON_KEYWORDS=YEAR,TRIM;DB_CLOSE_DELAY=-1");
        dataSource.setUsername("sa");
        jdbc = JdbcClient.create(dataSource);
        jdbc.sql("DROP SCHEMA IF EXISTS ford_read CASCADE").update();
        jdbc.sql("CREATE SCHEMA ford_read").update();
        jdbc.sql("""
                CREATE TABLE ford_read.vehicle_search_v1 (
                  model VARCHAR(100), normalized_name VARCHAR(100), year INTEGER,
                  price DECIMAL(12,2), currency VARCHAR(3), category VARCHAR(100),
                  body_type VARCHAR(100), fuel_type VARCHAR(100), model_page_url VARCHAR(500),
                  price_source_url VARCHAR(500), observed_at TIMESTAMP WITH TIME ZONE
                )
                """).update();
        jdbc.sql("""
                CREATE TABLE ford_read.vehicle_variants_v1 (
                  model VARCHAR(100), normalized_model VARCHAR(100), year INTEGER,
                  trim VARCHAR(100), normalized_trim VARCHAR(100), price DECIMAL(12,2),
                  currency VARCHAR(3), model_page_url VARCHAR(500),
                  price_source_url VARCHAR(500), observed_at TIMESTAMP WITH TIME ZONE
                )
                """).update();
        jdbc.sql("""
                CREATE TABLE ford_read.vehicle_facts_v1 (
                  model VARCHAR(100), year INTEGER, trim VARCHAR(100), fact_type VARCHAR(30),
                  fact_name VARCHAR(100), fact_value VARCHAR(500), fact_unit VARCHAR(30),
                  source_url VARCHAR(500), observed_at TIMESTAMP WITH TIME ZONE
                )
                """).update();
        jdbc.sql("""
                CREATE TABLE ford_read.vehicle_facts_v2 (
                  variant_id VARCHAR(36), model_year_id VARCHAR(36), model VARCHAR(100), year INTEGER,
                  trim VARCHAR(100), category VARCHAR(100), fact_type VARCHAR(30), fact_name VARCHAR(100),
                  fact_name_normalized VARCHAR(100), fact_value VARCHAR(500), value_numeric DECIMAL(14,2),
                  fact_unit VARCHAR(30), source_url VARCHAR(500), observed_at TIMESTAMP WITH TIME ZONE,
                  model_page_url VARCHAR(500)
                )
                """).update();
        jdbc.sql("""
                CREATE TABLE ford_read.vehicle_images_v1 (
                  model VARCHAR(100), year INTEGER, trim VARCHAR(100), image_url VARCHAR(500),
                  alt_text VARCHAR(200), image_type VARCHAR(50), width INTEGER, height INTEGER,
                  source_url VARCHAR(500), observed_at TIMESTAMP WITH TIME ZONE
                )
                """).update();
        jdbc.sql("""
                CREATE TABLE ford_read.promotions_v2 (
                  promotion_id UUID,
                  model VARCHAR(100), year INTEGER, title VARCHAR(200), description VARCHAR(500),
                  discount_amount DECIMAL(12,2), discount_percent DECIMAL(7,2), cash_price DECIMAL(12,2),
                  currency VARCHAR(3), conditions VARCHAR(500), valid_from DATE,
                  valid_until DATE, source_url VARCHAR(500), observed_at TIMESTAMP WITH TIME ZONE,
                  model_page_url VARCHAR(500), benefits VARCHAR(1000), term_conflict VARCHAR(1000)
                )
                """).update();
        jdbc.sql("""
                CREATE TABLE ford_read.offers_v1 (
                  promotion_id UUID,
                  model VARCHAR(100), year INTEGER, title VARCHAR(200), offer_type VARCHAR(50),
                  provider VARCHAR(100), cash_price DECIMAL(12,2), monthly_payment DECIMAL(12,2),
                  down_payment_amount DECIMAL(12,2), down_payment_percent DECIMAL(7,2),
                  term_months INTEGER, months_without_interest INTEGER,
                  annual_interest_rate DECIMAL(7,4), cat_percent DECIMAL(7,4), currency VARCHAR(3),
                  valid_from DATE, valid_until DATE, requires_credit_approval BOOLEAN, conditions VARCHAR(500),
                  source_url VARCHAR(500), model_page_url VARCHAR(500), observed_at TIMESTAMP WITH TIME ZONE,
                  benefits VARCHAR(1000), term_conflict VARCHAR(1000), tax_rate_percent DECIMAL(7,4),
                  opening_commission_rate_percent DECIMAL(7,4), maximum_down_payment_percent DECIMAL(7,4),
                  life_insurance_price DECIMAL(14,2)
                )
                """).update();
        jdbc.sql("""
                CREATE TABLE ford_read.page_links_v1 (
                  source_page_url VARCHAR(500), source_page_type VARCHAR(50), observed_at TIMESTAMP WITH TIME ZONE,
                  href VARCHAR(500), target_url VARCHAR(500), anchor_text VARCHAR(200), rel VARCHAR(100),
                  is_internal BOOLEAN, link_type VARCHAR(50)
                )
                """).update();
        jdbc.sql("""
                CREATE TABLE ford_read.provenance_v1 (
                  model VARCHAR(100), year INTEGER, trim VARCHAR(100), source_url VARCHAR(500),
                  page_type VARCHAR(50), title VARCHAR(200), http_status INTEGER,
                  observed_at TIMESTAMP WITH TIME ZONE, last_changed_at TIMESTAMP WITH TIME ZONE,
                  crawl_error VARCHAR(500)
                )
                """).update();
        jdbc.sql("""
                CREATE TABLE ford_read.document_provenance_v1 (
                  document_id UUID, content_hash VARCHAR(200), page_content_hash VARCHAR(200),
                  title VARCHAR(200), document_type VARCHAR(100), source_url VARCHAR(500),
                  model_page_url VARCHAR(500), observed_at TIMESTAMP WITH TIME ZONE,
                  model VARCHAR(100), year INTEGER, trim VARCHAR(100)
                )
                """).update();
        jdbc.sql("""
                CREATE TABLE ford_read.catalog_status_v1 (
                  model_count BIGINT, active_model_year_count BIGINT, variant_count BIGINT, page_count BIGINT,
                  document_count BIGINT, embedded_document_count BIGINT, pending_embedding_count BIGINT,
                  latest_page_crawl_at TIMESTAMP WITH TIME ZONE, latest_sync_status VARCHAR(30),
                  latest_sync_started_at TIMESTAMP WITH TIME ZONE, latest_sync_finished_at TIMESTAMP WITH TIME ZONE
                )
                """).update();
        jdbc.sql("""
                CREATE TABLE ford_read.comparison_facts_v1 (
                  model VARCHAR(100), year INTEGER, trim VARCHAR(100), competitor_make VARCHAR(100),
                  competitor_model VARCHAR(100), competitor_year INTEGER, competitor_variant VARCHAR(100),
                  category VARCHAR(100), fact_name VARCHAR(100), fact_value VARCHAR(300), fact_unit VARCHAR(30),
                  source_url VARCHAR(500), observed_at TIMESTAMP WITH TIME ZONE
                )
                """).update();
        jdbc.sql("CREATE VIEW ford_read.offers_v2 AS SELECT * FROM ford_read.offers_v1").update();
        jdbc.sql("CREATE VIEW ford_read.promotions_v3 AS SELECT * FROM ford_read.promotions_v2").update();
        jdbc.sql("CREATE VIEW ford_read.vehicle_variants_v2 AS SELECT vehicle_variants_v1.*, "
                + "CAST(NULL AS VARCHAR(500)) AS comparison_url FROM ford_read.vehicle_variants_v1").update();
        jdbc.sql("CREATE VIEW ford_read.comparison_facts_v2 AS SELECT comparison_facts_v1.*, "
                + "source_url AS comparison_url FROM ford_read.comparison_facts_v1").update();
        jdbc.sql("CREATE TABLE ford_read.catalog_pages_v1 (source_url VARCHAR(500), title VARCHAR(200))").update();
        jdbc.sql("""
                INSERT INTO ford_read.vehicle_search_v1 VALUES
                  ('Ranger', 'ranger', 2027, NULL, 'MXN', 'Pickups', 'Pickup', 'Diesel',
                   'https://www.ford.mx/vehiculos/ranger/', NULL, '2026-10-16T10:00:00Z'),
                  ('Ranger', 'ranger', 2026, 850000, 'MXN', 'Pickups', 'Pickup', 'Diesel',
                   'https://www.ford.mx/vehiculos/ranger-2026/', 'https://www.ford.mx/precio/',
                   '2026-10-15T10:00:00Z')
                """).update();
        jdbc.sql("""
                INSERT INTO ford_read.vehicle_variants_v1 VALUES
                  ('Ranger', 'ranger', 2027, 'XLT Gasolina 4x2', 'xltgasolina4x2', 899000,
                   'MXN', 'https://www.ford.mx/vehiculos/ranger/',
                   'https://www.ford.mx/vehiculos/ranger/precio/', '2026-10-16T10:00:00Z'),
                  ('Ranger', 'ranger', 2027, 'Raptor', 'raptor', 1500000, 'MXN',
                   'https://www.ford.mx/vehiculos/ranger/', 'https://www.ford.mx/vehiculos/ranger/raptor/',
                   '2026-10-16T10:00:00Z')
                """).update();
        jdbc.sql("""
                INSERT INTO ford_read.vehicle_facts_v1 VALUES
                  ('Ranger', 2027, 'XLT Gasolina 4x2', 'specification', 'Potencia', '270 hp', 'hp',
                   'https://www.ford.mx/vehiculos/ranger/ficha-tecnica/', '2026-10-16T10:00:00Z'),
                  ('Ranger', 2027, NULL, 'specification', 'Garantía', '3 años', null,
                   'https://www.ford.mx/vehiculos/ranger/ficha-tecnica/', '2026-10-16T10:00:00Z'),
                  ('Ranger', 2027, 'Raptor', 'specification', 'Potencia', '400 hp', 'hp',
                   'https://www.ford.mx/vehiculos/ranger/raptor/', '2026-10-16T10:00:00Z'),
                  ('Ranger', 2027, NULL, 'feature', 'Asientos', 'Tela', null,
                   'https://www.ford.mx/vehiculos/ranger/ficha-tecnica/', '2026-10-16T10:00:00Z'),
                  ('Ranger', 2027, 'XLT Gasolina 4x2', 'specification', 'Dato sin fuente', 'dato', null,
                   null, null)
                """).update();
        jdbc.sql("""
                INSERT INTO ford_read.vehicle_facts_v2 VALUES
                  ('aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa', '11111111-1111-1111-1111-111111111111', 'Ranger', 2027,
                   'XLT Gasolina 4x2', 'Motor', 'specification', 'Potencia', 'potencia', '270 hp', 270, 'hp',
                   'https://www.ford.mx/vehiculos/ranger/ficha-tecnica/', '2026-10-16T10:00:00Z',
                   'https://www.ford.mx/vehiculos/ranger/'),
                  (NULL, '11111111-1111-1111-1111-111111111111', 'Ranger', 2027, NULL, 'Coverage',
                   'specification', 'Garantía', 'garantia', '3 años', NULL, NULL,
                   'https://www.ford.mx/vehiculos/ranger/ficha-tecnica/', '2026-10-16T10:00:00Z',
                   'https://www.ford.mx/vehiculos/ranger/'),
                  (NULL, '11111111-1111-1111-1111-111111111111', 'Ranger', 2027, NULL, 'Interior',
                   'feature', 'Asientos', 'asientos', 'Tela', NULL, NULL,
                   'https://www.ford.mx/vehiculos/ranger/ficha-tecnica/', '2026-10-16T10:00:00Z',
                   'https://www.ford.mx/vehiculos/ranger/')
                """).update();
        jdbc.sql("""
                INSERT INTO ford_read.vehicle_images_v1 VALUES
                  ('Ranger', 2027, NULL, 'https://www.ford.mx/assets/ranger-2027.png',
                   'Ranger 2027', 'exterior', 1200, 800,
                   'https://www.ford.mx/vehiculos/ranger/', '2026-10-16T10:00:00Z'),
                  ('Ranger', 2027, 'XLT Gasolina 4x2', 'https://www.ford.mx/assets/ranger-xlt.png',
                   'Ranger XLT', 'trim', 1200, 800,
                   'https://www.ford.mx/vehiculos/ranger/xlt/', '2026-10-16T10:00:00Z')
                """).update();
        jdbc.sql("""
                INSERT INTO ford_read.promotions_v2 VALUES
                  ('eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee', 'Ranger', 2027, 'Bono de temporada', 'Oferta', 25000, null, null, 'MXN', 'Términos',
                   DATE '2026-10-01', DATE '2026-10-31', 'https://www.ford.mx/promociones/ranger/',
                   '2026-10-16T10:00:00Z', 'https://www.ford.mx/vehiculos/ranger/', '["Bono de $25,000 MXN"]', null),
                  ('ffffffff-ffff-ffff-ffff-ffffffffffff', 'Ranger', 2027, 'Promoción vencida', 'Oferta vieja', null, null, null, 'MXN', null,
                   DATE '2026-09-01', DATE '2026-09-30', 'https://www.ford.mx/promociones/ranger/',
                   '2026-09-15T10:00:00Z', 'https://www.ford.mx/vehiculos/ranger/', null, null),
                  ('11111111-1111-1111-1111-111111111111', 'Ranger', 2027, 'Promoción sin inicio', 'Vigencia incompleta', null, null, null, 'MXN', null,
                   NULL, DATE '2026-10-31', 'https://www.ford.mx/promociones/ranger/', '2026-10-16T10:00:00Z', 'https://www.ford.mx/vehiculos/ranger/', null, null),
                  ('22222222-2222-2222-2222-222222222222', 'Ranger', 2027, 'Promotion without source', 'No evidence', null, null, null, 'MXN', null,
                   DATE '2026-10-01', DATE '2026-10-31', null, null, null, null, null)
                """).update();
        jdbc.sql("""
                INSERT INTO ford_read.offers_v1 VALUES
                  ('aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa', 'Ranger', 2027, 'Plan Ford Credit', 'financing', 'Ford Credit', null, 12000,
                   180000, 20, 60, null, 12.5, 15.8, 'MXN', DATE '2026-10-01', DATE '2026-10-31',
                   true, 'Sujeto a aprobación', 'https://www.ford.mx/credito/ranger/',
                   'https://www.ford.mx/vehiculos/ranger/', '2026-10-16T10:00:00Z',
                   '["Bono de $130,000 MXN"]', '{"labelMonths":24,"structuredMonths":72}', 16, 3.5, 80, 2500),
                  ('bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb', 'Ranger', 2027, 'Plan sin inicio', 'financing', 'Ford Credit', null, 9000,
                   100000, 20, 48, null, 12.5, 15.8, 'MXN', NULL, DATE '2026-10-31',
                   true, 'Vigencia incompleta', 'https://www.ford.mx/credito/ranger/',
                   'https://www.ford.mx/vehiculos/ranger/', '2026-10-16T10:00:00Z', null, null, null, null, null, null),
                  ('cccccccc-cccc-cccc-cccc-cccccccccccc', 'Ranger', 2027, 'Plan without source', 'financing', 'Ford Credit', null, 1,
                   null, null, 12, null, null, null, 'MXN', DATE '2026-10-01', DATE '2026-10-31',
                   true, null, null, null, null, null, null, null, null, null, null)
                """).update();
        jdbc.sql("""
                INSERT INTO ford_read.page_links_v1 VALUES
                  ('https://www.ford.mx/vehiculos/ranger/', 'model', '2026-10-16T10:00:00Z',
                   '/gallery', 'https://www.ford.mx/vehiculos/ranger/gallery', 'Gallery', null, true, 'internal'),
                  ('https://www.ford.mx/vehiculos/ranger/', 'model', '2026-10-16T10:00:00Z',
                   '/image.jpg', 'https://www.ford.mx/image.jpg', 'Image', null, true, 'internal')
                """).update();
        jdbc.sql("""
                INSERT INTO ford_read.provenance_v1 VALUES
                  ('Ranger', 2027, NULL, 'https://www.ford.mx/vehiculos/ranger/', 'model',
                   'Ranger 2027 | Camioneta', 200, '2026-10-16T10:00:00Z', NULL, NULL)
                """).update();
        jdbc.sql("INSERT INTO ford_read.catalog_pages_v1 SELECT source_url, title FROM ford_read.provenance_v1")
                .update();
        jdbc.sql("INSERT INTO ford_read.catalog_pages_v1 VALUES ('https://www.ford.mx/known-empty/', 'Empty source page')")
                .update();
        jdbc.sql("""
                INSERT INTO ford_read.document_provenance_v1 VALUES
                  ('11223344-5566-7788-9900-aabbccddeeff', 'document-hash', 'page-hash',
                   'Ranger ficha', 'vehicle_profile',
                   'https://www.ford.mx/vehiculos/ranger/ficha-tecnica/',
                   'https://www.ford.mx/vehiculos/ranger/', '2026-10-16T10:00:00Z', 'Ranger', 2027, 'XLT')
                """).update();
        jdbc.sql("""
                INSERT INTO ford_read.catalog_status_v1 VALUES
                  (12, 18, 60, 120, 90, 80, 10, '2026-10-16T10:00:00Z', 'succeeded',
                   '2026-10-16T09:00:00Z', '2026-10-16T10:00:00Z')
                """).update();
        jdbc.sql("""
                INSERT INTO ford_read.comparison_facts_v1 VALUES
                  ('Ranger', 2027, 'XLT', 'Chevrolet', 'Colorado', 2027, 'LT', 'Motor',
                   'Potencia', '270 hp', 'hp', 'https://www.ford.mx/comparativo/ranger/2027/', '2026-10-16T10:00:00Z'),
                  ('Ranger', 2026, 'XLT', 'Toyota', 'Tacoma', 2026, 'SR', 'Motor',
                   'Potencia', '250 hp', 'hp', 'https://www.ford.mx/comparativo/ranger/2026/', '2026-10-15T10:00:00Z')
                """).update();
        catalog = new JdbcCatalogAdapter(jdbc, new com.fasterxml.jackson.databind.ObjectMapper());
    }

    @Test
    void searchLeavesUnobservedPriceAbsentAndPreservesLinks() {
        var results = catalog.search(new VehicleSearchQuery(
                "Ranger", "Ranger", 2027, null, null, null, null, null, 5));

        assertThat(results).hasSize(1);
        assertThat(results.getFirst().price()).isNull();
        assertThat(results.getFirst().modelPageUrl()).isEqualTo("https://www.ford.mx/vehiculos/ranger/");
        assertThat(results.getFirst().observedAt()).isNotNull();
    }

    @Test
    void provenanceReturnsTheNodeDocumentContextAndPageObservationTime() {
        var provenance = catalog.provenance("11223344-5566-7788-9900-aabbccddeeff");

        assertThat(provenance).get().satisfies(document -> {
            assertThat(document.documentId()).isEqualTo("11223344-5566-7788-9900-aabbccddeeff");
            assertThat(document.contentHash()).isEqualTo("document-hash");
            assertThat(document.pageContentHash()).isEqualTo("page-hash");
            assertThat(document.model()).isEqualTo("Ranger");
            assertThat(document.year()).isEqualTo(2027);
            assertThat(document.trim()).isEqualTo("XLT");
            assertThat(document.observedAt()).isNotNull();
        });
    }

    @Test
    void provenanceReturnsEmptyForInvalidOrUnknownIdentifiers() {
        assertThat(catalog.provenance("not-a-uuid")).isEmpty();
        assertThat(catalog.provenance("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")).isEmpty();
    }

    @Test
    void statusReturnsCatalogCountsAndLatestSyncEvidence() {
        var status = catalog.status();

        assertThat(status.modelCount()).isEqualTo(12);
        assertThat(status.activeModelYearCount()).isEqualTo(18);
        assertThat(status.variantCount()).isEqualTo(60);
        assertThat(status.pageCount()).isEqualTo(120);
        assertThat(status.documentCount()).isEqualTo(90);
        assertThat(status.embeddedDocumentCount()).isEqualTo(80);
        assertThat(status.pendingEmbeddingCount()).isEqualTo(10);
        assertThat(status.latestSyncStatus()).isEqualTo("succeeded");
        assertThat(status.latestSyncStartedAt()).isNotNull();
        assertThat(status.latestSyncFinishedAt()).isNotNull();
    }

    @Test
    void exactVehicleLookupDoesNotSubstituteDifferentTrim() {
        assertThat(catalog.find(new VehicleLookup("Ranger", 2027, "Raptor")))
                .get().extracting("trim").isEqualTo("Raptor");
        assertThat(catalog.find(new VehicleLookup("Ranger", 2027, "Wildtrak"))).isEmpty();
    }

    @Test
    void trimLookupUsesFordCatalogNormalizationAndReturnsItsFacts() {
        var result = catalog.find(new VehicleLookup("Ranger", 2027, " XLT Gasolina 4x2 "));

        assertThat(result).isPresent();
        assertThat(result.orElseThrow().facts()).extracting("name").containsExactly("Potencia");
    }

    @Test
    void modelOnlyLookupDoesNotMixVariantSpecificFacts() {
        var result = catalog.find(new VehicleLookup("Ranger", 2027, null));

        assertThat(result).isPresent();
        assertThat(result.orElseThrow().facts()).extracting("name").containsExactly("Garantía", "Asientos");
        assertThat(result.orElseThrow().facts()).extracting("type")
                .containsExactly("specification", "feature");
    }

    @Test
    void vehicleLookupPreservesImageUrlSourceAndObservationTime() {
        var result = catalog.find(new VehicleLookup("Ranger", 2027, null)).orElseThrow();

        assertThat(result.images()).extracting("url")
                .contains("https://www.ford.mx/assets/ranger-2027.png");
        assertThat(result.images().getFirst().sourceUrl()).isEqualTo("https://www.ford.mx/vehiculos/ranger/");
        assertThat(result.images().getFirst().observedAt()).isNotNull();
    }

    @Test
    void customerEvidenceRequiresSourceAndObservationTime() {
        var vehicle = catalog.find(new VehicleLookup("Ranger", 2027, "XLT Gasolina 4x2")).orElseThrow();
        var promotions = catalog.promotions(new PromotionQuery(
                "Ranger", 2027, LocalDate.parse("2026-10-17"), true, 10));
        var offers = catalog.offers(new OfferQuery("Ranger", 2027, "financing", "Ford Credit",
                null, null, null, null, null, true, LocalDate.parse("2026-10-17"), 10));

        assertThat(vehicle.facts()).extracting("name").containsExactly("Potencia");
        assertThat(vehicle.facts().getFirst().variantId()).isEqualTo("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
        assertThat(vehicle.facts().getFirst().category()).isEqualTo("Motor");
        assertThat(vehicle.facts().getFirst().normalizedName()).isEqualTo("potencia");
        assertThat(vehicle.facts().getFirst().numericValue()).isEqualByComparingTo("270");
        assertThat(promotions.results()).extracting("title").contains("Bono de temporada");
        assertThat(promotions.totalCount()).isEqualTo(2);
        assertThat(offers).extracting("title").contains("Plan Ford Credit", "Plan sin inicio");
        assertThat(offers).filteredOn(offer -> offer.title().equals("Plan Ford Credit")).singleElement().satisfies(offer -> {
            assertThat(offer.benefits().toString()).isEqualTo("[\"Bono de $130,000 MXN\"]");
            assertThat(offer.termConflict().path("labelMonths").asInt()).isEqualTo(24);
            assertThat(offer.termConflict().path("structuredMonths").asInt()).isEqualTo(72);
            assertThat(offer.taxRatePercent()).isEqualByComparingTo("16");
            assertThat(offer.openingCommissionRatePercent()).isEqualByComparingTo("3.5");
            assertThat(offer.maximumDownPaymentPercent()).isEqualByComparingTo("80");
            assertThat(offer.lifeInsurancePrice()).isEqualByComparingTo("2500");
        });
    }

    @Test
    void promotionQueryUsesExplicitAsOfDateAndRetainsSource() {
        var results = catalog.promotions(new PromotionQuery(
                "Ranger", 2027, LocalDate.parse("2026-10-17"), true, 3));

        assertThat(results.results()).hasSize(2);
        assertThat(results.results().getFirst().title()).isEqualTo("Bono de temporada");
        assertThat(results.results().getFirst().id()).isEqualTo("eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee");
        assertThat(results.results().getFirst().sourceUrl()).isEqualTo("https://www.ford.mx/promociones/ranger/");
        assertThat(results.results().getFirst().modelUrl()).isEqualTo("https://www.ford.mx/vehiculos/ranger/");
        assertThat(results.results().getFirst().benefits().toString()).isEqualTo("[\"Bono de $25,000 MXN\"]");
        assertThat(results.results().getFirst().validityStatus()).isEqualTo("active");
        assertThat(results.totalCount()).isEqualTo(2);

        var emptyPage = catalog.promotions(new PromotionQuery(
                "Ranger", 2027, LocalDate.parse("2026-10-17"), true, 3, 2));
        assertThat(emptyPage.results()).isEmpty();
        assertThat(emptyPage.totalCount()).isEqualTo(2);
        assertThat(emptyPage.hasMore()).isFalse();
    }

    @Test
    void promotionValidityMatchesNodeForUpcomingAndIncompleteDates() {
        var upcoming = catalog.promotions(new PromotionQuery(
                "Ranger", 2027, LocalDate.parse("2026-09-15"), false, 10));
        var asOfCurrent = catalog.promotions(new PromotionQuery(
                "Ranger", 2027, LocalDate.parse("2026-10-17"), false, 10));

        assertThat(upcoming.results()).filteredOn(row -> row.title().equals("Bono de temporada"))
                .singleElement().extracting("validityStatus").isEqualTo("upcoming");
        assertThat(asOfCurrent.results()).filteredOn(row -> row.title().equals("Promoción sin inicio"))
                .singleElement().extracting("validityStatus").isEqualTo("unknown");
        var activeOnly = catalog.promotions(new PromotionQuery(
                "Ranger", 2027, LocalDate.parse("2026-10-17"), true, 10));
        assertThat(activeOnly.results()).extracting("title")
                .contains("Bono de temporada", "Promoción sin inicio");
        assertThat(activeOnly.results()).filteredOn(row -> row.title().equals("Promoción sin inicio"))
                .singleElement().extracting("validityStatus").isEqualTo("unknown");
    }

    @Test
    void offerValidityKeepsIncompleteDatesAndLabelsThemUnknown() {
        var all = catalog.offers(new OfferQuery("Ranger", 2027, "financing", "Ford Credit",
                null, null, null, null, null, false, LocalDate.parse("2026-10-17"), 10));
        var activeOnly = catalog.offers(new OfferQuery("Ranger", 2027, "financing", "Ford Credit",
                null, null, null, null, null, true, LocalDate.parse("2026-10-17"), 10));

        assertThat(all).filteredOn(row -> row.title().equals("Plan sin inicio"))
                .singleElement().extracting("validityStatus").isEqualTo("unknown");
        assertThat(activeOnly).extracting("title").containsExactly("Plan Ford Credit", "Plan sin inicio");
        assertThat(activeOnly).filteredOn(row -> row.title().equals("Plan sin inicio"))
                .singleElement().extracting("validityStatus").isEqualTo("unknown");
    }

    @Test
    void offersPreservePublishedFinancingTermsWithoutCalculatingThem() {
        var result = catalog.offers(new OfferQuery("Ranger", 2027, "financing", "Ford Credit",
                null, null, null, null, 60, true, LocalDate.parse("2026-10-17"), 2));

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().monthlyPayment()).isEqualByComparingTo("12000");
        assertThat(result.getFirst().offerId()).isEqualTo("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
        assertThat(result.getFirst().termMonths()).isEqualTo(60);
        assertThat(result.getFirst().sourceUrl()).isEqualTo("https://www.ford.mx/credito/ranger/");
        assertThat(result.getFirst().requiresCreditApproval()).isTrue();
        assertThat(result.getFirst().validityStatus()).isEqualTo("active");
    }

    @Test
    void provenanceRejectsUnknownIdsAndCatalogStatusIsNotInventory() {
        var provenance = catalog.provenance("11223344-5566-7788-9900-aabbccddeeff");
        assertThat(provenance).isPresent();
        assertThat(catalog.provenance("not-a-document-id")).isEmpty();
        assertThat(provenance.orElseThrow().sourceUrl())
                .isEqualTo("https://www.ford.mx/vehiculos/ranger/ficha-tecnica/");

        var status = catalog.status();
        assertThat(status.modelCount()).isEqualTo(12);
        assertThat(status.latestSyncStatus()).isEqualTo("succeeded");
    }

    @Test
    void catalogStatusAllowsNoRecordedSyncAndReturnsStableLegacyCounts() {
        jdbc.sql("UPDATE ford_read.catalog_status_v1 SET latest_sync_status = NULL, latest_sync_started_at = NULL, latest_sync_finished_at = NULL")
                .update();

        var status = catalog.status();

        assertThat(status.pages()).isEqualTo(status.pageCount());
        assertThat(status.vehicles()).isEqualTo(status.activeModelYearCount());
        assertThat(status.embedded()).isEqualTo(status.embeddedDocumentCount());
        assertThat(status.latestSyncStatus()).isNull();
        assertThat(status.latestSyncStartedAt()).isNull();
        assertThat(status.latestSyncFinishedAt()).isNull();
    }

    @Test
    void pageLinksExcludeStaticAssetsByDefaultAndSupportPaging() {
        var result = catalog.pageLinks("https://www.ford.mx/vehiculos/ranger/", 0, 20, false);

        assertThat(result.title()).isEqualTo("Ranger 2027 | Camioneta");
        assertThat(result.knownSource()).isTrue();
        assertThat(result.totalLinks()).isEqualTo(1);
        assertThat(result.links()).hasSize(1);
        assertThat(result.links().getFirst().targetUrl()).isEqualTo("https://www.ford.mx/vehiculos/ranger/gallery");
        var knownEmpty = catalog.pageLinks("https://www.ford.mx/known-empty/", 0, 20, false);
        assertThat(knownEmpty.knownSource()).isTrue();
        assertThat(knownEmpty.title()).isEqualTo("Empty source page");
        assertThat(knownEmpty.totalLinks()).isZero();
        var unknown = catalog.pageLinks("https://www.ford.mx/not-crawled/", 0, 20, false);
        assertThat(unknown.knownSource()).isFalse();
        assertThat(unknown.links()).isEmpty();
    }

    @Test
    void competitorComparisonsRetainOnlySourceBackedRows() {
        var result = catalog.variantComparisons("Ranger", 2027, "XLT", 0, 3);

        assertThat(result).singleElement().satisfies(comparison -> {
            assertThat(comparison.year()).isEqualTo(2027);
            assertThat(comparison.competitorMake()).isEqualTo("Chevrolet");
        });
        assertThat(result.getFirst().competitorModel()).isEqualTo("Colorado");
        assertThat(result.getFirst().sourceUrl()).isEqualTo("https://www.ford.mx/comparativo/ranger/2027/");
        assertThat(result.getFirst().comparisonUrl()).isEqualTo("https://www.ford.mx/comparativo/ranger/2027/");
    }
}
