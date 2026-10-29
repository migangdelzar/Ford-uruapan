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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;

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
                  promotion_id uuid, model varchar(100), year integer, title varchar(200), description varchar(500),
                  discount_amount numeric(12,2), discount_percent numeric(7,2), offer_type varchar(50),
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
        jdbc.sql("create table ford_read.catalog_pages_v1 (source_url varchar(500), title varchar(200))").update();
        jdbc.sql("""
                create table ford_read.page_links_v1 (
                  source_page_url varchar(500), source_page_type varchar(80), href varchar(500),
                  target_url varchar(500), anchor_text varchar(200), rel varchar(100), is_internal boolean,
                  link_type varchar(80), observed_at timestamptz)
                """).update();
        jdbc.sql("""
                create table ford_read.document_provenance_v1 (
                  document_id uuid, content_hash varchar(128), page_content_hash varchar(128), title varchar(200),
                  document_type varchar(80), source_url varchar(500), model_page_url varchar(500),
                  observed_at timestamptz, model varchar(100), year integer, trim varchar(100))
                """).update();
        jdbc.sql("""
                create table ford_read.comparison_facts_v2 (
                  model varchar(100), year integer, trim varchar(100), competitor_make varchar(100),
                  competitor_model varchar(100), competitor_year integer, competitor_variant varchar(100),
                  category varchar(100), fact_name varchar(100), fact_value varchar(500), fact_unit varchar(30),
                  source_url varchar(500), observed_at timestamptz, comparison_url varchar(500))
                """).update();
        jdbc.sql("""
                insert into ford_read.catalog_pages_v1 values
                  ('https://catalog.synthetic.invalid/ranger/', 'Synthetic Ranger reference page')
                """).update();
        jdbc.sql("""
                insert into ford_read.page_links_v1 values
                  ('https://catalog.synthetic.invalid/ranger/', 'vehicle', '/ranger/specs/',
                   'https://catalog.synthetic.invalid/ranger/specs/', 'Synthetic specifications', null, true,
                   'specification', '2026-10-16T10:00:00Z')
                """).update();
        jdbc.sql("""
                insert into ford_read.document_provenance_v1 values
                ('eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee', 'synthetic-content-hash', 'synthetic-page-hash',
                 'Synthetic Ranger specs', 'specification', 'https://catalog.synthetic.invalid/ranger/specs/',
                 'https://catalog.synthetic.invalid/ranger/', '2026-10-16T10:00:00Z', 'Ranger', 2027,
                 'XLT Gasolina 4x2')
                """).update();
        jdbc.sql("""
                insert into ford_read.comparison_facts_v2 values
                  ('Ranger', 2027, 'XLT Gasolina 4x2', 'Synthetic Make', 'Synthetic Competitor Pickup', 2027,
                   'Synthetic Base', 'Capacity', 'Synthetic cargo capacity', '1200', 'kg',
                   'https://catalog.synthetic.invalid/comparison/', '2026-10-16T10:00:00Z',
                   'https://catalog.synthetic.invalid/ranger/comparison/')
                """).update();

        var modelYearId = java.util.UUID.randomUUID();
        jdbc.sql("""
                insert into ford_read.vehicle_search_v1
                  (model_year_id, model, normalized_name, year, price, currency, model_page_url, observed_at)
                values (:id, 'Ranger', 'ranger', 2027, null, 'MXN',
                        'https://catalog.synthetic.invalid/ranger/', '2026-10-16T10:00:00Z')
                """).param("id", modelYearId).update();
        jdbc.sql("""
                insert into ford_read.vehicle_search_v1
                  (model_year_id, model, normalized_name, year, price, currency, model_page_url, observed_at)
                values ('cccccccc-cccc-cccc-cccc-cccccccccccc', 'Ranger', 'ranger', 2026, 810000, 'MXN',
                        'https://catalog.synthetic.invalid/ranger-2026/', '2026-10-16T10:00:00Z'),
                       ('dddddddd-dddd-dddd-dddd-dddddddddddd', 'Bronco', 'bronco', 2026, 1050000, 'MXN',
                        'https://catalog.synthetic.invalid/bronco-2026/', '2026-10-16T10:00:00Z')
                """).update();
        jdbc.sql("""
                insert into ford_read.vehicle_variants_v1
                  (model_year_id, model, normalized_model, year, trim, normalized_trim, price, currency,
                   model_page_url, price_source_url, observed_at)
                values (:id, 'Ranger', 'ranger', 2027, 'XLT Gasolina 4x2', 'xltgasolina4x2', 899000,
                        'MXN', 'https://catalog.synthetic.invalid/ranger/',
                        'https://catalog.synthetic.invalid/ranger/price/', '2026-10-16T10:00:00Z')
                """).param("id", modelYearId).update();
        jdbc.sql("""
                insert into ford_read.vehicle_variants_v1
                  (model_year_id, model, normalized_model, year, trim, normalized_trim, price, currency,
                   model_page_url, price_source_url, observed_at)
                values (:id, 'Ranger', 'ranger', 2027, 'Lariat Gasolina 4x4', 'lariatgasolina4x4', 1050000,
                        'MXN', 'https://catalog.synthetic.invalid/ranger/',
                        'https://catalog.synthetic.invalid/ranger/price/', '2026-10-16T10:00:00Z')
                """).param("id", modelYearId).update();
        jdbc.sql("""
                insert into ford_read.vehicle_facts_v1 values
                  ('Ranger', 2027, null, 'specification', 'Garantía', '3 años', null,
                   'https://catalog.synthetic.invalid/ranger/specs/', '2026-10-16T10:00:00Z'),
                  ('Ranger', 2027, 'XLT Gasolina 4x2', 'specification', 'Potencia', '270 hp', 'hp',
                   'https://catalog.synthetic.invalid/ranger/specs/', '2026-10-16T10:00:00Z')
                """).update();
        jdbc.sql("""
                insert into ford_read.vehicle_facts_v2 values
                  ('aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa', :modelYearId, 'Ranger', 2027, 'XLT Gasolina 4x2',
                   'Motor', 'specification', 'Potencia', 'potencia', '270 hp', 270, 'hp',
                    'https://catalog.synthetic.invalid/ranger/specs/', '2026-10-16T10:00:00Z',
                    'https://catalog.synthetic.invalid/ranger/'),
                  (NULL, :modelYearId, 'Ranger', 2027, NULL, 'Coverage', 'specification', 'Garantía', 'garantia',
                    '3 años', NULL, NULL, 'https://catalog.synthetic.invalid/ranger/specs/',
                    '2026-10-16T10:00:00Z', 'https://catalog.synthetic.invalid/ranger/'),
                  ('bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb', :modelYearId, 'Ranger', 2027, 'Lariat Gasolina 4x4',
                    'Motor', 'specification', 'Potencia', 'potencia', '310 hp', 310, 'hp',
                    'https://catalog.synthetic.invalid/ranger/specs/', '2026-10-16T10:00:00Z',
                    'https://catalog.synthetic.invalid/ranger/')
                """).param("modelYearId", modelYearId).update();
        jdbc.sql("""
                insert into ford_read.vehicle_images_v1 values
                  ('Ranger', 2027, null, 'https://catalog.synthetic.invalid/assets/ranger.png', 'Ranger', 'hero', 1200, 800,
                   'https://catalog.synthetic.invalid/ranger/', '2026-10-16T10:00:00Z'),
                  ('Ranger', 2027, 'XLT Gasolina 4x2', 'https://catalog.synthetic.invalid/assets/ranger-xlt.png', 'Ranger XLT',
                   'trim', 1200, 800, 'https://catalog.synthetic.invalid/ranger/specs/', '2026-10-16T10:00:00Z')
                """).update();
         jdbc.sql("""
                 insert into ford_read.offers_v1
                   (promotion_id, model, year, title, description, discount_amount, discount_percent, offer_type,
                    provider, cash_price, monthly_payment, down_payment_amount, down_payment_percent, term_months,
                    months_without_interest, annual_interest_rate, cat_percent, currency, valid_from, valid_until,
                    requires_credit_approval, conditions, source_url, model_page_url, observed_at, benefits, term_conflict)
                 values
                  ('aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa', 'Ranger', 2027, 'Synthetic Ford Credit plan', NULL, NULL, NULL,
                   'financing', 'Synthetic Finance', NULL, 12000, 180000, 20, 60, NULL, 12.5, 15.8, 'MXN',
                   DATE '2026-10-01', DATE '2026-10-31', true, 'Synthetic fixture; approval required',
                   'https://catalog.synthetic.invalid/ranger/finance/', 'https://catalog.synthetic.invalid/ranger/',
                   '2026-10-16T10:00:00Z', '["Synthetic benefit"]'::jsonb,
                   '{"labelMonths":24,"structuredMonths":72}'::jsonb),
                  ('bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb', 'Ranger', 2027, 'Synthetic Ranger seasonal benefit',
                   'Synthetic test-only promotion', 25000, 2.5, 'promotion', 'Synthetic Catalog', 874000, NULL,
                   NULL, NULL, NULL, NULL, NULL, NULL, 'MXN', DATE '2026-10-01', DATE '2026-10-31', false,
                   'Synthetic fixture only', 'https://catalog.synthetic.invalid/ranger/promotion/',
                   'https://catalog.synthetic.invalid/ranger/', '2026-10-16T10:00:00Z', '[]'::jsonb, '{}'::jsonb)
                 """).update();
        catalog = new JdbcCatalogAdapter(jdbc, new com.fasterxml.jackson.databind.ObjectMapper());
    }

    @Test
    void preservesModelOnlyAndExactTrimEvidenceWithSources() {
        var model = catalog.find(new VehicleLookup("Ranger", 2027, null)).orElseThrow();
        assertThat(model.facts()).extracting("name").containsExactly("Garantía");
        assertThat(model.modelPageUrl()).isEqualTo("https://catalog.synthetic.invalid/ranger/");
        assertThat(model.images()).extracting("url").containsExactly("https://catalog.synthetic.invalid/assets/ranger.png");

        var trim = catalog.find(new VehicleLookup("Ranger", 2027, "XLT Gasolina 4x2")).orElseThrow();
        assertThat(trim.trim()).isEqualTo("XLT Gasolina 4x2");
        assertThat(trim.facts()).extracting("name").containsExactly("Potencia");
        assertThat(trim.facts().getFirst().sourceUrl())
                .isEqualTo("https://catalog.synthetic.invalid/ranger/specs/");
        assertThat(trim.priceSourceUrl()).isEqualTo("https://catalog.synthetic.invalid/ranger/price/");
        assertThat(trim.modelPageUrl()).isEqualTo("https://catalog.synthetic.invalid/ranger/");
        assertThat(trim.images()).extracting("url").containsExactly("https://catalog.synthetic.invalid/assets/ranger-xlt.png");
    }

    @Test
    void readsOfferBenefitsAndTermConflictAsStructuredJsonFromPostgres() {
        var offer = catalog.offers(new OfferQuery("Ranger", 2027, "financing", null,
                null, null, null, null, null, true, java.time.LocalDate.parse("2026-10-17"), 5)).getFirst();

        assertThat(offer.benefits().isArray()).isTrue();
        assertThat(offer.benefits().get(0).asText()).isEqualTo("Synthetic benefit");
        assertThat(offer.termConflict().path("labelMonths").asInt()).isEqualTo(24);
        assertThat(offer.termConflict().path("structuredMonths").asInt()).isEqualTo(72);
    }

    @Test
    void readsOnlyActiveSyntheticPromotionWithItsSourceAndFreshness() {
        var promotions = catalog.promotions(new PromotionQuery("Ranger", 2027,
                java.time.LocalDate.parse("2026-10-17"), true, 20));

        assertThat(promotions.results()).singleElement().satisfies(promotion -> {
            assertThat(promotion.title()).isEqualTo("Synthetic Ranger seasonal benefit");
            assertThat(promotion.validityStatus()).isEqualTo("active");
            assertThat(promotion.sourceUrl()).contains(".invalid/");
            assertThat(promotion.observedAt()).isNotNull();
        });
    }

    @Test
    void resolvesExactSyntheticYearTrimAndComparesTwoModelsWithoutCrossSourceLeakage() {
        var tools = new CatalogTools(catalog);

        var exact = tools.getVehicle("Ranger", 2027, "XLT Gasolina 4x2");
        var comparison = tools.compareVehicles(List.of(
                new CatalogTools.VehicleTarget("Ranger", 2026),
                new CatalogTools.VehicleTarget("Bronco", 2026)));

        assertThat(exact).isInstanceOf(com.ford.service.catalog.api.VehicleEvidence.class);
        assertThat(((com.ford.service.catalog.api.VehicleEvidence) exact).model()).isEqualTo("Ranger");
        assertThat(((com.ford.service.catalog.api.VehicleEvidence) exact).year()).isEqualTo(2027);
        assertThat(((com.ford.service.catalog.api.VehicleEvidence) exact).trim()).isEqualTo("XLT Gasolina 4x2");
        assertThat(comparison).extracting(com.ford.service.catalog.api.VehicleEvidence::model)
                .containsExactly("Ranger", "Bronco");
        assertThat(comparison).allSatisfy(vehicle ->
                assertThat(vehicle.modelPageUrl()).contains("catalog.synthetic.invalid"));
        var trims = tools.compareTrims("Ranger", 2027,
                List.of("XLT Gasolina 4x2", "Lariat Gasolina 4x4"), "full");
        assertThat(trims.trims()).hasSize(2);
        assertThat(trims.comparisons()).singleElement().satisfies(pair ->
                assertThat(pair.differences()).extracting(CatalogTools.TrimFactDifference::name)
                        .contains("Potencia"));
        var years = tools.compareModelYears("Ranger", List.of(2026, 2027));
        assertThat(years.years()).extracting(snapshot -> snapshot.vehicle().year())
                .containsExactly(2026, 2027);
    }

    @Test
    void returnsSyntheticPageLinksAndDocumentProvenanceOnlyForKnownFixtureIds() {
        var tools = new CatalogTools(catalog);

        var links = tools.getPageLinks("https://catalog.synthetic.invalid/ranger/", 0, 10, false);
        var provenance = tools.getVehicleProvenance("eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee");

        assertThat(links.knownSource()).isTrue();
        assertThat(links.links()).extracting(CatalogTools.PageLinkItem::url)
                .containsExactly("https://catalog.synthetic.invalid/ranger/specs/");
        assertThat(provenance).isPresent().get().satisfies(document -> {
            assertThat(document.model()).isEqualTo("Ranger");
            assertThat(document.year()).isEqualTo(2027);
            assertThat(document.sourceUrl()).contains(".invalid/");
            assertThat(document.observedAt()).isNotNull();
        });
        assertThat(tools.getVehicleProvenance("not-a-fixture-id")).isEmpty();
    }

    @Test
    void returnsOnlySyntheticSourceBackedVariantComparisonFacts() {
        var facts = new CatalogTools(catalog).getVariantComparisons("Ranger", 2027,
                "XLT Gasolina 4x2", 0, 5);

        assertThat(facts).singleElement().satisfies(fact -> {
            assertThat(fact.competitorModel()).isEqualTo("Synthetic Competitor Pickup");
            assertThat(fact.factName()).isEqualTo("Synthetic cargo capacity");
            assertThat(fact.sourceUrl()).contains("catalog.synthetic.invalid");
            assertThat(fact.observedAt()).isNotNull();
        });
    }
}
