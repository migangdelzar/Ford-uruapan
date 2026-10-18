package com.ford.service.catalog;

import com.ford.service.catalog.adapter.out.JdbcCatalogAdapter;
import com.ford.service.catalog.api.VehicleLookup;
import com.ford.service.catalog.api.OfferQuery;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class CatalogToolParityIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new com.ford.service.database.FordPostgresContainer();

    private static JdbcCatalogAdapter catalog;

    @BeforeAll
    static void setUp() {
        var dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        var jdbc = JdbcClient.create(dataSource);
        jdbc.sql("create schema ford_read").update();
        jdbc.sql("""
                create table ford_read.vehicle_search_v1 (
                  model_year_id uuid, model varchar(100), normalized_name varchar(100), year integer,
                  price numeric(14,2), currency varchar(3), category varchar(100), body_type varchar(100),
                  fuel_type varchar(100), model_page_url varchar(500), price_source_url varchar(500),
                  observed_at timestamptz)
                """).update();
        jdbc.sql("""
                create table ford_read.vehicle_variants_v1 (
                  model_year_id uuid, model varchar(100), normalized_model varchar(100), year integer,
                  trim varchar(100), normalized_trim varchar(100), price numeric(14,2), currency varchar(3),
                  model_page_url varchar(500), price_source_url varchar(500), observed_at timestamptz)
                """).update();
        jdbc.sql("create view ford_read.vehicle_variants_v2 as select vehicle_variants_v1.*, "
                + "cast(null as varchar(500)) as comparison_url from ford_read.vehicle_variants_v1").update();
        jdbc.sql("""
                create table ford_read.vehicle_facts_v1 (
                  model varchar(100), year integer, trim varchar(100), fact_type varchar(30),
                  fact_name varchar(100), fact_value varchar(500), fact_unit varchar(30), source_url varchar(500),
                  observed_at timestamptz)
                """).update();
        jdbc.sql("""
                create table ford_read.vehicle_facts_v2 (
                  variant_id uuid, model_year_id uuid, model varchar(100), year integer, trim varchar(100),
                  category varchar(100), fact_type varchar(30), fact_name varchar(100),
                  fact_name_normalized varchar(100), fact_value varchar(500), value_numeric numeric(14,2),
                  fact_unit varchar(30), source_url varchar(500), observed_at timestamptz, model_page_url varchar(500)
                )
                """).update();
        jdbc.sql("""
                create table ford_read.vehicle_images_v1 (
                  model varchar(100), year integer, trim varchar(100), image_url varchar(500),
                  alt_text varchar(200), image_type varchar(50), width integer, height integer,
                  source_url varchar(500), observed_at timestamptz)
                """).update();
        jdbc.sql("""
                create table ford_read.offers_v1 (
                  promotion_id uuid, model varchar(100), year integer, title varchar(200), offer_type varchar(50),
                  provider varchar(100), cash_price numeric(12,2), monthly_payment numeric(12,2),
                  down_payment_amount numeric(12,2), down_payment_percent numeric(7,2), term_months integer,
                  months_without_interest integer, annual_interest_rate numeric(7,4), cat_percent numeric(7,4),
                  currency varchar(3), valid_from date, valid_until date, requires_credit_approval boolean,
                  conditions varchar(500), source_url varchar(500), model_page_url varchar(500), observed_at timestamptz,
                  benefits jsonb, term_conflict jsonb
                )
                """).update();
        jdbc.sql("create view ford_read.offers_v2 as select offers_v1.*, "
                + "cast(null as decimal(7,4)) as tax_rate_percent, "
                + "cast(null as decimal(7,4)) as opening_commission_rate_percent, "
                + "cast(null as decimal(7,4)) as maximum_down_payment_percent, "
                + "cast(null as decimal(14,2)) as life_insurance_price from ford_read.offers_v1").update();
        jdbc.sql("create view ford_read.promotions_v3 as select * from ford_read.offers_v1 where offer_type = 'promotion'")
                .update();

        var modelYearId = java.util.UUID.randomUUID();
        jdbc.sql("""
                insert into ford_read.vehicle_search_v1
                  (model_year_id, model, normalized_name, year, price, currency, model_page_url, observed_at)
                values (:id, 'Ranger', 'ranger', 2027, null, 'MXN',
                        'https://www.ford.mx/vehiculos/ranger/', '2026-10-16T10:00:00Z')
                """).param("id", modelYearId).update();
        jdbc.sql("""
                insert into ford_read.vehicle_variants_v1
                  (model_year_id, model, normalized_model, year, trim, normalized_trim, price, currency,
                   model_page_url, price_source_url, observed_at)
                values (:id, 'Ranger', 'ranger', 2027, 'XLT Gasolina 4x2', 'xltgasolina4x2', 899000,
                        'MXN', 'https://www.ford.mx/vehiculos/ranger/',
                        'https://www.ford.mx/vehiculos/ranger/precio/', '2026-10-16T10:00:00Z')
                """).param("id", modelYearId).update();
        jdbc.sql("""
                insert into ford_read.vehicle_facts_v1 values
                  ('Ranger', 2027, null, 'specification', 'Garantía', '3 años', null,
                   'https://www.ford.mx/vehiculos/ranger/ficha-tecnica/', '2026-10-16T10:00:00Z'),
                  ('Ranger', 2027, 'XLT Gasolina 4x2', 'specification', 'Potencia', '270 hp', 'hp',
                   'https://www.ford.mx/vehiculos/ranger/ficha-tecnica/', '2026-10-16T10:00:00Z')
                """).update();
        jdbc.sql("""
                insert into ford_read.vehicle_facts_v2 values
                  ('aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa', :modelYearId, 'Ranger', 2027, 'XLT Gasolina 4x2',
                   'Motor', 'specification', 'Potencia', 'potencia', '270 hp', 270, 'hp',
                   'https://www.ford.mx/vehiculos/ranger/ficha-tecnica/', '2026-10-16T10:00:00Z',
                   'https://www.ford.mx/vehiculos/ranger/'),
                  (NULL, :modelYearId, 'Ranger', 2027, NULL, 'Coverage', 'specification', 'Garantía', 'garantia',
                   '3 años', NULL, NULL, 'https://www.ford.mx/vehiculos/ranger/ficha-tecnica/',
                   '2026-10-16T10:00:00Z', 'https://www.ford.mx/vehiculos/ranger/')
                """).param("modelYearId", modelYearId).update();
        jdbc.sql("""
                insert into ford_read.vehicle_images_v1 values
                  ('Ranger', 2027, null, 'https://www.ford.mx/assets/ranger.png', 'Ranger', 'hero', 1200, 800,
                   'https://www.ford.mx/vehiculos/ranger/', '2026-10-16T10:00:00Z'),
                  ('Ranger', 2027, 'XLT Gasolina 4x2', 'https://www.ford.mx/assets/ranger-xlt.png', 'Ranger XLT',
                   'trim', 1200, 800, 'https://www.ford.mx/vehiculos/ranger/ficha-tecnica/', '2026-10-16T10:00:00Z')
                """).update();
        jdbc.sql("""
                insert into ford_read.offers_v1 values
                  ('aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa', 'Ranger', 2027, 'Plan Ford Credit', 'financing', 'Ford Credit',
                   null, 12000, 180000, 20, 60, null, 12.5, 15.8, 'MXN', DATE '2026-10-01', DATE '2026-10-31',
                   true, 'Sujeto a aprobación', 'https://www.ford.mx/credito/ranger/',
                   'https://www.ford.mx/vehiculos/ranger/', '2026-10-16T10:00:00Z',
                   '["Bono de $130,000 MXN"]'::jsonb, '{"labelMonths":24,"structuredMonths":72}'::jsonb)
                """).update();
        catalog = new JdbcCatalogAdapter(jdbc, new com.fasterxml.jackson.databind.ObjectMapper());
    }

    @Test
    void preservesModelOnlyAndExactTrimEvidenceWithSources() {
        var model = catalog.find(new VehicleLookup("Ranger", 2027, null)).orElseThrow();
        assertThat(model.facts()).extracting("name").containsExactly("Garantía");
        assertThat(model.technicalSheetUrl()).isEqualTo("https://www.ford.mx/vehiculos/ranger/ficha-tecnica/");
        assertThat(model.images()).extracting("url").containsExactly("https://www.ford.mx/assets/ranger.png");

        var trim = catalog.find(new VehicleLookup("Ranger", 2027, "XLT Gasolina 4x2")).orElseThrow();
        assertThat(trim.trim()).isEqualTo("XLT Gasolina 4x2");
        assertThat(trim.facts()).extracting("name").containsExactly("Potencia");
        assertThat(trim.facts().getFirst().sourceUrl())
                .isEqualTo("https://www.ford.mx/vehiculos/ranger/ficha-tecnica/");
        assertThat(trim.priceSourceUrl()).isEqualTo("https://www.ford.mx/vehiculos/ranger/precio/");
        assertThat(trim.technicalSheetUrl()).isEqualTo("https://www.ford.mx/vehiculos/ranger/ficha-tecnica/");
        assertThat(trim.images()).extracting("url").containsExactly("https://www.ford.mx/assets/ranger-xlt.png");
    }

    @Test
    void readsOfferBenefitsAndTermConflictAsStructuredJsonFromPostgres() {
        var offer = catalog.offers(new OfferQuery("Ranger", 2027, "financing", null,
                null, null, null, null, null, true, java.time.LocalDate.parse("2026-10-17"), 5)).getFirst();

        assertThat(offer.benefits().isArray()).isTrue();
        assertThat(offer.benefits().get(0).asText()).isEqualTo("Bono de $130,000 MXN");
        assertThat(offer.termConflict().path("labelMonths").asInt()).isEqualTo(24);
        assertThat(offer.termConflict().path("structuredMonths").asInt()).isEqualTo(72);
    }
}
